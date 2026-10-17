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
 * with the hash chain match finder (ZSTD_HcFindBestMatch) from zstd 1.5.7, without dictionary support.
 * <p>
 * Every position is inserted into a hash chain: {@code hashTable[hash]} holds the most recent position with
 * that hash and {@code chainTable[position & chainMask]} the previous one. A search walks the chain of the
 * current position for up to {@code 1 << searchLog} candidates and keeps the longest match. Depending on
 * {@code depth}, the parser then retries at the next one or two positions and keeps the match that gives
 * the largest estimated gain.
 * <p>
 * Offsets are kept in zstd's "offBase" convention inside this class: 1 means the first repeated offset
 * and {@code offset + 3} an explicit offset. {@link SequenceStore#storeSequence} takes {@code offBase - 1}.
 */
class LazyBlockCompressor
        implements BlockCompressor
{
    private static final int MIN_MATCH = 3;
    private static final int SEARCH_STRENGTH = 8;
    private static final int LAZY_SKIPPING_STEP = 8;

    private static final int REPCODE_1 = 1;
    private static final int OFFSET_TO_OFFBASE = REPEATED_OFFSET_COUNT;
    private static final int NO_MATCH = 999999999;

    /** Searches at the current position only (greedy), one position ahead (lazy) or two (lazy2). */
    private final int depth;

    LazyBlockCompressor(int depth)
    {
        this.depth = depth;
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
        final int hashBits = parameters.getHashLog();
        final int[] chainTable = state.chainTable;
        final int chainSize = 1 << parameters.getChainLog();
        final int maxAttempts = 1 << parameters.getSearchLog();

        final int inputEnd = inputAddress + inputSize;
        final int inputLimit = inputEnd - SIZE_OF_LONG; // hashes read up to 8 bytes

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
                long found = search(inputBase, ip, inputEnd, base, windowSize, hashTable, hashBits, chainTable, chainSize, maxAttempts, mls, state, lazySkipping);
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
                        found = search(inputBase, ip, inputEnd, base, windowSize, hashTable, hashBits, chainTable, chainSize, maxAttempts, mls, state, lazySkipping);
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
                            found = search(inputBase, ip, inputEnd, base, windowSize, hashTable, hashBits, chainTable, chainSize, maxAttempts, mls, state, lazySkipping);
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
            lazySkipping = false;

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
     * Inserts the positions up to {@code ip} into the hash chain and finds the longest match at {@code ip}.
     *
     * @return the match length in the upper 32 bits and its offBase in the lower 32 bits. The length is 3
     * (shorter than any acceptable match) when nothing was found.
     */
    private static long search(byte[] inputBase, int ip, int inputEnd, int base, int windowSize, int[] hashTable, int hashBits, int[] chainTable, int chainSize, int maxAttempts, int mls, BlockCompressionState state, boolean lazySkipping)
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
}
