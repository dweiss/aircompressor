/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.compress.v3.zstd;

import static io.airlift.compress.v3.zstd.Constants.REPEATED_OFFSET_COUNT;
import static io.airlift.compress.v3.zstd.Constants.SIZE_OF_INT;
import static io.airlift.compress.v3.zstd.Constants.SIZE_OF_LONG;
import static io.airlift.compress.v3.zstd.DoubleFastBlockCompressor.count;
import static io.airlift.compress.v3.zstd.Util.highestBit;
import static java.lang.Math.clamp;
import static java.lang.Math.min;

/**
 * The greedy, lazy and lazy2 strategies (zstd levels 5 to 12): a port of ZSTD_compressBlock_lazy_generic
 * from zstd 1.5.7 with its two match finders, without dictionary support. Depending on {@code depth}, the
 * parser searches at the current position only, or also at the next one or two positions, and keeps the
 * match that gives the largest estimated gain.
 * <p>
 * The <b>hash chain</b> finder (ZSTD_HcFindBestMatch) inserts every position into a chain:
 * {@code hashTable[hash]} holds the most recent position with that hash and {@code chainTable[position &
 * chainMask]} the previous one. A search walks the chain of the current position for up to
 * {@code 1 << searchLog} candidates and keeps the longest match.
 * <p>
 * The <b>row hash</b> finder (ZSTD_RowFindBestMatch) splits the hash table into rows of 16, 32 or 64
 * positions. The upper bits of a hash select the row and its lowest 8 bits are a tag kept in a parallel
 * byte table, whose first byte per row is the row's head: each row is a ring buffer of the most recent
 * positions hashing to it. A search compares the tag against the whole row at once (eight bytes at a time)
 * and only verifies the positions whose tag agrees, so most candidates cost no cache miss. zstd uses it for
 * all but the smallest windows; the C version also issues prefetches, which have no Java equivalent.
 * <p>
 * Offsets are kept in zstd's "offBase" convention inside this class: 1 means the first repeated offset
 * and {@code offset + 3} an explicit offset. {@link SequenceStore#storeSequence} takes {@code offBase - 1}.
 */
class LazyBlockCompressor
        implements BlockCompressor
{
    static final int ROW_HASH_TAG_BITS = 8;
    static final int MIN_ROW_LOG = 4;
    static final int MAX_ROW_LOG = 6;
    static final int ROW_HASH_CACHE_SIZE = 8;

    private static final int MIN_MATCH = 3;
    private static final int SEARCH_STRENGTH = 8;
    private static final int LAZY_SKIPPING_STEP = 8;

    private static final int REPCODE_1 = 1;
    private static final int OFFSET_TO_OFFBASE = REPEATED_OFFSET_COUNT;
    private static final int NO_MATCH = 999999999;

    private static final int ROW_HASH_TAG_MASK = (1 << ROW_HASH_TAG_BITS) - 1;
    private static final int ROW_HASH_CACHE_MASK = ROW_HASH_CACHE_SIZE - 1;
    // row update after a long gap: insert this many positions at the start and the end of the gap only
    private static final int ROW_SKIP_THRESHOLD = 384;
    private static final int ROW_MAX_START_POSITIONS_TO_UPDATE = 96;
    private static final int ROW_MAX_END_POSITIONS_TO_UPDATE = 32;

    private static final long SWAR_LOW_BITS = 0x0101010101010101L;
    private static final long SWAR_HIGH_BITS = 0x8080808080808080L;
    // multiplying the high bits of 8 bytes by this gathers them into the top byte
    private static final long SWAR_EXTRACT_MAGIC = Long.divideUnsigned(-1L, 0x7F) >>> 8;

    private static final LazyBlockCompressor[] HASH_CHAIN = {new LazyBlockCompressor(0, false), new LazyBlockCompressor(1, false), new LazyBlockCompressor(2, false)};
    private static final LazyBlockCompressor[] ROW_HASH = {new LazyBlockCompressor(0, true), new LazyBlockCompressor(1, true), new LazyBlockCompressor(2, true)};

    /** Searches at the current position only (greedy), one position ahead (lazy) or two (lazy2). */
    private final int depth;
    private final boolean rowHash;

    private LazyBlockCompressor(int depth, boolean rowHash)
    {
        this.depth = depth;
        this.rowHash = rowHash;
    }

    /** The parser of the given depth with the hash chain match finder. */
    static LazyBlockCompressor hashChain(int depth)
    {
        return HASH_CHAIN[depth];
    }

    /** The parser of the given strategy with the row hash match finder. */
    static LazyBlockCompressor rowHash(CompressionParameters.Strategy strategy)
    {
        return switch (strategy) {
            case GREEDY -> ROW_HASH[0];
            case LAZY -> ROW_HASH[1];
            case LAZY2 -> ROW_HASH[2];
            default -> throw new IllegalArgumentException("not a greedy or lazy strategy: " + strategy);
        };
    }

    @Override
    public int compressBlock(byte[] inputBase, final int inputAddress, int inputSize, SequenceStore output, BlockCompressionState state, RepeatedOffsets offsets, CompressionParameters parameters)
    {
        // Table entries are positions relative to base. Unlike the double-fast strategy, which filters
        // candidates against the window computed at the block end, matches are limited per position to
        // the last windowSize bytes (ZSTD_HcFindBestMatch), all of which are present in inputBase: the
        // streaming encoder always retains a full window ahead of the data it compresses.
        final int base = state.getBaseAddress();
        final int windowSize = parameters.getWindowSize();

        final int mls = clamp(parameters.getSearchLength(), 4, 6);
        final int[] hashTable = state.hashTable;
        // hash chain finder
        final int[] chainTable = state.chainTable;
        final int chainSize = 1 << parameters.getChainLog();
        // row hash finder: searchLog selects the row size, and the number of attempts is capped by it
        final byte[] tagTable = state.tagTable;
        final int rowLog = clamp(parameters.getSearchLog(), MIN_ROW_LOG, MAX_ROW_LOG);
        final int maxAttempts = 1 << (rowHash ? min(parameters.getSearchLog(), rowLog) : parameters.getSearchLog());
        // hash chain: the hash table index; row hash: the row index plus the tag bits
        final int hashBits = rowHash ? parameters.getHashLog() - rowLog + ROW_HASH_TAG_BITS : parameters.getHashLog();

        final int inputEnd = inputAddress + inputSize;
        // hashes read up to 8 bytes, and the row finder hashes 8 positions ahead
        final int inputLimit = inputEnd - SIZE_OF_LONG - (rowHash ? ROW_HASH_CACHE_SIZE : 0);

        int ip = inputAddress;
        int anchor = inputAddress;

        int offset1 = offsets.getOffset0();
        int offset2 = offsets.getOffset1();
        int offsetSaved1 = 0;
        int offsetSaved2 = 0;

        if (ip == base) {
            ip++;
        }
        int maxRep = min(ip - base, windowSize);
        if (offset2 > maxRep) {
            offsetSaved2 = offset2;
            offset2 = 0;
        }
        if (offset1 > maxRep) {
            offsetSaved1 = offset1;
            offset1 = 0;
        }

        // limited update after a very long match (ZSTD_buildSeqStore)
        int current = inputAddress - base;
        if (current > state.nextToUpdate + 384) {
            state.nextToUpdate = current - min(192, current - state.nextToUpdate - 384);
        }

        boolean lazySkipping = false;

        if (rowHash) {
            rowFillHashCache(inputBase, base, state.nextToUpdate, inputLimit, hashTable, tagTable, hashBits, rowLog, mls, state);
        }

        while (ip < inputLimit) {
            int matchLength = 0;
            int offBase = REPCODE_1;
            int start = ip + 1;

            // check repcode at ip + 1
            if (offset1 > 0 && (int) Mem.INT_LE.get(inputBase, ip + 1 - offset1) == (int) Mem.INT_LE.get(inputBase, ip + 1)) {
                matchLength = count(inputBase, ip + 1 + SIZE_OF_INT, inputEnd, ip + 1 + SIZE_OF_INT - offset1) + SIZE_OF_INT;
            }

            if (matchLength == 0 || depth > 0) {
                // first search (depth 0)
                long found = rowHash
                        ? rowSearch(inputBase, ip, inputEnd, inputLimit, base, windowSize, hashTable, tagTable, hashBits, rowLog, maxAttempts, mls, state, lazySkipping)
                        : chainSearch(inputBase, ip, inputEnd, base, windowSize, hashTable, hashBits, chainTable, chainSize, maxAttempts, mls, state, lazySkipping);
                int foundLength = (int) (found >>> 32);
                if (foundLength > matchLength) {
                    matchLength = foundLength;
                    start = ip;
                    offBase = (int) found;
                }

                if (matchLength < 4) {
                    int step = ((ip - anchor) >> SEARCH_STRENGTH) + 1; // jump faster over incompressible sections
                    ip += step;
                    // Once we skip more than 8 bytes at a time, stop inserting every position into the tables
                    // and only insert the positions we search (2KB without a match triggers this).
                    lazySkipping = step > LAZY_SKIPPING_STEP;
                    continue;
                }

                // let's try to find a better solution
                if (depth >= 1) {
                    while (ip < inputLimit) {
                        ip++;
                        if (offset1 > 0 && (int) Mem.INT_LE.get(inputBase, ip) == (int) Mem.INT_LE.get(inputBase, ip - offset1)) {
                            int repLength = count(inputBase, ip + SIZE_OF_INT, inputEnd, ip + SIZE_OF_INT - offset1) + SIZE_OF_INT;
                            int gain2 = repLength * 3;
                            int gain1 = matchLength * 3 - highestBit(offBase) + 1;
                            if (repLength >= 4 && gain2 > gain1) {
                                matchLength = repLength;
                                offBase = REPCODE_1;
                                start = ip;
                            }
                        }
                        found = rowHash
                        ? rowSearch(inputBase, ip, inputEnd, inputLimit, base, windowSize, hashTable, tagTable, hashBits, rowLog, maxAttempts, mls, state, lazySkipping)
                        : chainSearch(inputBase, ip, inputEnd, base, windowSize, hashTable, hashBits, chainTable, chainSize, maxAttempts, mls, state, lazySkipping);
                        foundLength = (int) (found >>> 32);
                        int foundOffBase = (int) found;
                        int gain2 = foundLength * 4 - highestBit(foundOffBase); // raw approximation
                        int gain1 = matchLength * 4 - highestBit(offBase) + 4;
                        if (foundLength >= 4 && gain2 > gain1) {
                            matchLength = foundLength;
                            offBase = foundOffBase;
                            start = ip;
                            continue; // search a better one
                        }

                        // let's find an even better one
                        if (depth == 2 && ip < inputLimit) {
                            ip++;
                            if (offset1 > 0 && (int) Mem.INT_LE.get(inputBase, ip) == (int) Mem.INT_LE.get(inputBase, ip - offset1)) {
                                int repLength = count(inputBase, ip + SIZE_OF_INT, inputEnd, ip + SIZE_OF_INT - offset1) + SIZE_OF_INT;
                                gain2 = repLength * 4;
                                gain1 = matchLength * 4 - highestBit(offBase) + 1;
                                if (repLength >= 4 && gain2 > gain1) {
                                    matchLength = repLength;
                                    offBase = REPCODE_1;
                                    start = ip;
                                }
                            }
                            found = rowHash
                        ? rowSearch(inputBase, ip, inputEnd, inputLimit, base, windowSize, hashTable, tagTable, hashBits, rowLog, maxAttempts, mls, state, lazySkipping)
                        : chainSearch(inputBase, ip, inputEnd, base, windowSize, hashTable, hashBits, chainTable, chainSize, maxAttempts, mls, state, lazySkipping);
                            foundLength = (int) (found >>> 32);
                            foundOffBase = (int) found;
                            gain2 = foundLength * 4 - highestBit(foundOffBase);
                            gain1 = matchLength * 4 - highestBit(offBase) + 7;
                            if (foundLength >= 4 && gain2 > gain1) {
                                matchLength = foundLength;
                                offBase = foundOffBase;
                                start = ip;
                                continue;
                            }
                        }
                        break; // nothing found : store previous solution
                    }
                }

                // catch up: extend an explicit-offset match backwards
                if (offBase > OFFSET_TO_OFFBASE) {
                    int offset = offBase - OFFSET_TO_OFFBASE;
                    while (start > anchor && start - offset > base && inputBase[start - 1] == inputBase[start - 1 - offset]) {
                        start--;
                        matchLength++;
                    }
                    offset2 = offset1;
                    offset1 = offset;
                }
            }

            // store sequence
            output.storeSequence(inputBase, anchor, start - anchor, offBase - 1, matchLength - MIN_MATCH);
            ip = start + matchLength;
            anchor = ip;
            if (lazySkipping) {
                // found a match: leave the lazy skipping mode, which did not maintain the hash cache
                if (rowHash) {
                    rowFillHashCache(inputBase, base, state.nextToUpdate, inputLimit, hashTable, tagTable, hashBits, rowLog, mls, state);
                }
                lazySkipping = false;
            }

            // check immediate repcode
            while (ip <= inputLimit && offset2 > 0 && (int) Mem.INT_LE.get(inputBase, ip) == (int) Mem.INT_LE.get(inputBase, ip - offset2)) {
                matchLength = count(inputBase, ip + SIZE_OF_INT, inputEnd, ip + SIZE_OF_INT - offset2) + SIZE_OF_INT;
                // swap offset2 <=> offset1
                int temp = offset2;
                offset2 = offset1;
                offset1 = temp;
                output.storeSequence(inputBase, anchor, 0, REPCODE_1 - 1, matchLength - MIN_MATCH);
                ip += matchLength;
                anchor = ip;
            }
        }

        // If offset1 started invalid (offsetSaved1 != 0) and became valid (offset1 != 0), rotate saved offsets.
        if (offsetSaved1 != 0 && offset1 != 0) {
            offsetSaved2 = offsetSaved1;
        }

        // save reps for next block
        offsets.saveOffset0(offset1 != 0 ? offset1 : offsetSaved1);
        offsets.saveOffset1(offset2 != 0 ? offset2 : offsetSaved2);

        // return the last literals size
        return inputEnd - anchor;
    }

    /**
     * Hash chain finder: inserts the positions up to {@code ip} into the chains and finds the longest match
     * at {@code ip}.
     *
     * @return the match length in the upper 32 bits and its offBase in the lower 32 bits. The length is 3
     * (shorter than any acceptable match) when nothing was found.
     */
    private static long chainSearch(byte[] inputBase, int ip, int inputEnd, int base, int windowSize, int[] hashTable, int hashBits, int[] chainTable, int chainSize, int maxAttempts, int mls, BlockCompressionState state, boolean lazySkipping)
    {
        int chainMask = chainSize - 1;
        int current = ip - base;
        // position 0 doubles as the empty table entry, so it never matches (zstd: ZSTD_WINDOW_START_INDEX)
        int lowLimit = Math.max(1, current - windowSize);

        // catch up: insert positions not yet in the tables. In lazy skipping mode only one is inserted.
        int index = state.nextToUpdate;
        while (index < current) {
            int hash = ZstdHash.hash(inputBase, base + index, hashBits, mls);
            chainTable[index & chainMask] = hashTable[hash];
            hashTable[hash] = index;
            index++;
            if (lazySkipping) {
                break;
            }
        }
        state.nextToUpdate = current;

        int matchIndex = hashTable[ZstdHash.hash(inputBase, ip, hashBits, mls)];
        int minChain = current > chainSize ? current - chainSize : 0;

        int matchLength = 3;
        int offBase = NO_MATCH;
        for (int attempts = maxAttempts; matchIndex >= lowLimit && attempts > 0; attempts--) {
            int match = base + matchIndex;
            // a longer match must agree on the 4 bytes ending just past the current best length
            if ((int) Mem.INT_LE.get(inputBase, match + matchLength - 3) == (int) Mem.INT_LE.get(inputBase, ip + matchLength - 3)) {
                int currentLength = count(inputBase, ip, inputEnd, match);
                if (currentLength > matchLength) {
                    matchLength = currentLength;
                    offBase = current - matchIndex + OFFSET_TO_OFFBASE;
                    if (ip + currentLength == inputEnd) {
                        break; // best possible, avoids read overflow on next attempt
                    }
                }
            }
            if (matchIndex <= minChain) {
                break;
            }
            matchIndex = chainTable[matchIndex & chainMask];
        }
        return ((long) matchLength << 32) | offBase;
    }

    /**
     * Row hash finder: inserts the positions up to {@code ip} into their rows and finds the longest match at
     * {@code ip}. Returns the same packed value as {@link #chainSearch}.
     */
    private static long rowSearch(byte[] inputBase, int ip, int inputEnd, int inputLimit, int base, int windowSize, int[] hashTable, byte[] tagTable, int hashBits, int rowLog, int maxAttempts, int mls, BlockCompressionState state, boolean lazySkipping)
    {
        int current = ip - base;
        int rowEntries = 1 << rowLog;
        int rowMask = rowEntries - 1;
        // position 0 doubles as the empty table entry, so it never matches (zstd: ZSTD_WINDOW_START_INDEX)
        int lowLimit = Math.max(1, current - windowSize);

        // update the rows up to (but not including) the current position. In lazy skipping mode only the
        // positions that are searched are inserted, and the hash cache is not maintained.
        int hash;
        if (!lazySkipping) {
            rowUpdate(inputBase, base, current, inputLimit, hashTable, tagTable, hashBits, rowLog, mls, state);
            hash = rowNextCachedHash(inputBase, base, current, hashTable, tagTable, hashBits, rowLog, mls, state);
        }
        else {
            hash = ZstdHash.hash(inputBase, ip, hashBits, mls);
            state.nextToUpdate = current;
        }
        int row = (hash >>> ROW_HASH_TAG_BITS) << rowLog;
        int tag = hash & ROW_HASH_TAG_MASK;
        int head = tagTable[row] & rowMask;

        int matchLength = 3;
        int offBase = NO_MATCH;
        // bit i of the mask is set when the entry i places after the head carries our tag
        long matches = rowMatchMask(tagTable, row, tag, head, rowEntries);
        for (int attempts = maxAttempts; matches != 0 && attempts > 0; matches &= matches - 1) {
            int matchPosition = (head + Long.numberOfTrailingZeros(matches)) & rowMask;
            if (matchPosition == 0) {
                continue; // the head byte, not a tag
            }
            int matchIndex = hashTable[row + matchPosition];
            if (matchIndex < lowLimit) {
                break; // entries only get older from here on
            }
            attempts--;
            int match = base + matchIndex;
            // a longer match must agree on the 4 bytes ending just past the current best length
            if ((int) Mem.INT_LE.get(inputBase, match + matchLength - 3) == (int) Mem.INT_LE.get(inputBase, ip + matchLength - 3)) {
                int currentLength = count(inputBase, ip, inputEnd, match);
                if (currentLength > matchLength) {
                    matchLength = currentLength;
                    offBase = current - matchIndex + OFFSET_TO_OFFBASE;
                    if (ip + currentLength == inputEnd) {
                        break; // best possible, avoids read overflow on next attempt
                    }
                }
            }
        }

        // insert the current position too, which saves an update at the next search
        int position = rowNextIndex(tagTable, row, rowMask);
        tagTable[row + position] = (byte) tag;
        hashTable[row + position] = current;
        state.nextToUpdate = current + 1;

        return ((long) matchLength << 32) | offBase;
    }

    /**
     * Inserts the positions from {@code state.nextToUpdate} up to {@code target} into their rows. After a
     * long match only the positions at the beginning and at the end of the gap are inserted (ZSTD_row_update_internal).
     */
    private static void rowUpdate(byte[] inputBase, int base, int target, int inputLimit, int[] hashTable, byte[] tagTable, int hashBits, int rowLog, int mls, BlockCompressionState state)
    {
        int index = state.nextToUpdate;
        if (target - index > ROW_SKIP_THRESHOLD) {
            rowInsert(inputBase, base, index, index + ROW_MAX_START_POSITIONS_TO_UPDATE, hashTable, tagTable, hashBits, rowLog, mls, state);
            index = target - ROW_MAX_END_POSITIONS_TO_UPDATE;
            rowFillHashCache(inputBase, base, index, inputLimit, hashTable, tagTable, hashBits, rowLog, mls, state);
        }
        rowInsert(inputBase, base, index, target, hashTable, tagTable, hashBits, rowLog, mls, state);
        state.nextToUpdate = target;
    }

    private static void rowInsert(byte[] inputBase, int base, int index, int end, int[] hashTable, byte[] tagTable, int hashBits, int rowLog, int mls, BlockCompressionState state)
    {
        int rowMask = (1 << rowLog) - 1;
        for (; index < end; index++) {
            int hash = rowNextCachedHash(inputBase, base, index, hashTable, tagTable, hashBits, rowLog, mls, state);
            int row = (hash >>> ROW_HASH_TAG_BITS) << rowLog;
            int position = rowNextIndex(tagTable, row, rowMask);
            tagTable[row + position] = (byte) hash;
            hashTable[row + position] = index;
        }
    }

    /**
     * Computes the hashes of the positions {@code index} to {@code index + 7} (but not beyond {@code inputLimit})
     * into the hash cache and touches their rows (ZSTD_row_fillHashCache).
     */
    private static void rowFillHashCache(byte[] inputBase, int base, int index, int inputLimit, int[] hashTable, byte[] tagTable, int hashBits, int rowLog, int mls, BlockCompressionState state)
    {
        int[] hashCache = state.hashCache;
        int maxElements = base + index > inputLimit ? 0 : inputLimit - (base + index) + 1;
        int limit = index + min(ROW_HASH_CACHE_SIZE, maxElements);
        int touched = 0;
        for (; index < limit; index++) {
            int hash = ZstdHash.hash(inputBase, base + index, hashBits, mls);
            int row = (hash >>> ROW_HASH_TAG_BITS) << rowLog;
            touched += hashTable[row] + tagTable[row];
            hashCache[index & ROW_HASH_CACHE_MASK] = hash;
        }
        state.prefetchSink += touched;
    }

    /**
     * Returns the cached hash of {@code index} and replaces it with the hash of {@code index + 8}, touching
     * that position's row so that it is in the cache when its turn comes (ZSTD_row_nextCachedHash).
     */
    private static int rowNextCachedHash(byte[] inputBase, int base, int index, int[] hashTable, byte[] tagTable, int hashBits, int rowLog, int mls, BlockCompressionState state)
    {
        int[] hashCache = state.hashCache;
        int newHash = ZstdHash.hash(inputBase, base + index + ROW_HASH_CACHE_SIZE, hashBits, mls);
        int row = (newHash >>> ROW_HASH_TAG_BITS) << rowLog;
        state.prefetchSink += hashTable[row] + tagTable[row];
        int hash = hashCache[index & ROW_HASH_CACHE_MASK];
        hashCache[index & ROW_HASH_CACHE_MASK] = newHash;
        return hash;
    }

    /**
     * Returns the next entry of a row to insert at, moving the row's head backwards through
     * {@code [1, rowEntries)}: the entry after the head is always the most recent one.
     */
    private static int rowNextIndex(byte[] tagTable, int row, int rowMask)
    {
        int next = (tagTable[row] - 1) & rowMask;
        if (next == 0) {
            next = rowMask; // skip the head byte
        }
        tagTable[row] = (byte) next;
        return next;
    }

    /**
     * Compares {@code tag} with every tag of a row, 8 bytes at a time, and returns a mask with bit i set
     * when the entry at {@code (head + i) & rowMask} agrees (ZSTD_row_getMatchMask, portable variant).
     */
    static long rowMatchMask(byte[] tagTable, int row, int tag, int head, int rowEntries)
    {
        long splat = tag * SWAR_LOW_BITS;
        long matches = 0;
        for (int i = rowEntries - SIZE_OF_LONG; i >= 0; i -= SIZE_OF_LONG) {
            long chunk = (long) Mem.LONG_LE.get(tagTable, row + i) ^ splat;
            // high bit of every byte that is not zero, i.e. does not carry the tag
            chunk = (((chunk | SWAR_HIGH_BITS) - SWAR_LOW_BITS) | chunk) & SWAR_HIGH_BITS;
            matches = (matches << SIZE_OF_LONG) | ((chunk * SWAR_EXTRACT_MAGIC) >>> (Long.SIZE - SIZE_OF_LONG));
        }
        matches = ~matches;
        return switch (rowEntries) {
            case 16 -> {
                int value = (int) matches & 0xFFFF;
                yield ((value >>> head) | (value << (16 - head))) & 0xFFFF;
            }
            case 32 -> Integer.rotateRight((int) matches, head) & 0xFFFFFFFFL;
            default -> Long.rotateRight(matches, head);
        };
    }
}
