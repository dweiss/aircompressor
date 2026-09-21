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

/*
 * This package is based on the work done by Keiron Liddle, Aftex Software
 * <keiron@aftexsw.com> to whom the Ant project is very grateful for his
 * great code.
 */
package io.airlift.compress.v3.bzip2;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;

import static io.airlift.compress.v3.bzip2.BZip2Constants.BASE_BLOCK_SIZE;
import static io.airlift.compress.v3.bzip2.BZip2Constants.G_SIZE;
import static io.airlift.compress.v3.bzip2.BZip2Constants.MAX_ALPHA_SIZE;
import static io.airlift.compress.v3.bzip2.BZip2Constants.MAX_CODE_LEN;
import static io.airlift.compress.v3.bzip2.BZip2Constants.MAX_SELECTORS;
import static io.airlift.compress.v3.bzip2.BZip2Constants.N_GROUPS;
import static io.airlift.compress.v3.bzip2.BZip2Constants.RUN_B;

/**
 * An input stream that decompresses from the BZip2 format to be read as any
 * other stream. The {@code "BZ"} magic at the start of the data is optional:
 * data that starts right after it (with the {@code 'h'} byte) is accepted, too.
 *
 * <p>
 * The decompression requires large amounts of memory. Thus you should call the
 * {@link #close() close()} method as soon as possible, to force
 * {@code CBZip2InputStream} to release the allocated memory. See
 * {@link CBZip2OutputStream CBZip2OutputStream} for information about memory
 * usage.
 * </p>
 *
 * <p>
 * When decompressing concatenated bzip2 streams they are decompressed one after
 * the other and data following the last stream that is not another bzip2 stream
 * is ignored; the compressed data is then read in chunks of
 * {@value BZip2BitReader#BUFFER_SIZE} bytes, ahead of the decompressed position.
 * Otherwise decompression stops after the first stream and leaves the input
 * positioned at the next byte after it, see
 * {@link #CBZip2InputStream(InputStream, boolean)}.
 * </p>
 *
 * <p>
 * Instances of this class are not thread safe.
 * </p>
 */
public class CBZip2InputStream
        extends InputStream
{
    /**
     * Number of input bits used to index the Huffman lookup tables. Codes of at
     * most this length are decoded with one table lookup, longer ones fall back
     * to the canonical bit-by-bit decoder.
     */
    static final int FAST_BITS = 10;

    /**
     * The Huffman decoding loop refills its bit buffer when fewer than this many
     * bits are buffered: at least {@link #FAST_BITS} + 1 so that a lookup table
     * hit never lacks bits (longer codes take the slow path, which refills on
     * its own).
     */
    private static final int REFILL_THRESHOLD = FAST_BITS + 1;

    private static final int EOF = 0;
    private static final int START_BLOCK_STATE = 1;
    private static final int RAND_PART_A_STATE = 2;
    private static final int RAND_PART_B_STATE = 3;
    private static final int RAND_PART_C_STATE = 4;
    private static final int NO_RAND_PART_A_STATE = 5;
    private static final int NO_RAND_PART_B_STATE = 6;
    private static final int NO_RAND_PART_C_STATE = 7;

    private final byte[] oneByte = new byte[1];
    private final Crc32 crc32 = new Crc32();

    private final boolean decompressConcatenated;
    private ParallelBZip2Decoder parallel;
    private BZip2BitReader bin;
    private boolean initialized;

    /**
     * Index of the last char in the block, so the block size == last + 1.
     */
    private int last;

    /**
     * Index in zptr[] of original string after sorting.
     */
    private int origPtr;

    /**
     * always: in the range 0 .. 9. The current block size is 100000 * this
     * number.
     */
    private int blockSize100k;

    private boolean blockRandomised;

    private int currentState = START_BLOCK_STATE;

    private int storedBlockCRC;
    private int storedCombinedCRC;
    private int computedCombinedCRC;

    // Variables used by setup* methods exclusively

    private int suCount;
    private int suCh2;
    private int suChPrev;
    private int suI2;
    private int suJ2;
    private int suRNToGo;
    private int suRTPos;
    private int suTPos;
    private int suZ;

    /**
     * All memory intensive stuff. This field is initialized by initBlock().
     */
    private Data data;

    /**
     * Constructs a new CBZip2InputStream which decompresses all the concatenated
     * bzip2 streams read from the specified stream.
     *
     * @throws NullPointerException if {@code in == null}
     */
    public CBZip2InputStream(InputStream in)
    {
        this(in, true);
    }

    /**
     * Constructs a new CBZip2InputStream which decompresses bytes read from the
     * specified stream.
     *
     * @param decompressConcatenated if true, decompress until the end of the
     * input; if false, stop after the first bzip2 stream and leave the input
     * positioned at the next byte after it. The input is read in chunks of up to
     * 64 KiB when it supports {@link InputStream#mark(int)} (for example a
     * {@link java.io.BufferedInputStream}) or when decompressing concatenated
     * streams, and a few bytes at a time otherwise, so wrap unbuffered sources
     * in a {@link java.io.BufferedInputStream}. Note that the input's mark is
     * used internally in the former case: a mark set by the caller before
     * constructing this stream is not preserved.
     * @throws NullPointerException if {@code in == null}
     */
    public CBZip2InputStream(InputStream in, boolean decompressConcatenated)
    {
        this.bin = new BZip2BitReader(in, decompressConcatenated);
        this.decompressConcatenated = decompressConcatenated;
    }

    /**
     * Constructs a new CBZip2InputStream that decompresses the blocks of the
     * stream concurrently on the given {@link ExecutorService}.
     *
     * <p>
     * Block boundaries are found by scanning the compressed data for the block
     * magic numbers, the blocks are decompressed in parallel and delivered in
     * order, and all CRCs are verified as usual, so valid streams produce exactly
     * the bytes the single-threaded constructors produce. Corrupt or truncated
     * input still fails with an {@link IOException}, though not necessarily with
     * the same message or at the same output position as the single-threaded
     * decoder. The input is read ahead of the decompressed position, so this mode
     * does not leave the input positioned right after the bzip2 stream; use a
     * single-threaded constructor when that matters.
     * </p>
     *
     * @param decompressConcatenated if true, decompress until the end of the
     * input; if false, stop after the first bzip2 stream
     * @param executor runs the block decompressions; not shut down or otherwise
     * owned by this stream
     * @param maxConcurrentInFlight the maximum number of blocks read ahead and
     * decompressed concurrently; the executor supplies the threads, this argument
     * bounds the concurrency and the memory held by this stream (each in-flight
     * block needs its compressed form plus its decompressed output, typically
     * under 1 MiB but up to tens of MiB for highly repetitive data)
     * @throws NullPointerException if {@code in == null} or {@code executor == null}
     * @throws IllegalArgumentException if {@code maxConcurrentInFlight < 1}
     */
    public CBZip2InputStream(InputStream in, boolean decompressConcatenated, ExecutorService executor, int maxConcurrentInFlight)
    {
        this.parallel = new ParallelBZip2Decoder(in, decompressConcatenated, executor, maxConcurrentInFlight);
        this.decompressConcatenated = decompressConcatenated;
    }

    /**
     * Number of bytes of the compressed stream consumed so far (in the parallel
     * mode: read so far, which is ahead of the decompressed position).
     */
    public long getProcessedByteCount()
    {
        if (parallel != null) {
            return parallel.getBytesRead();
        }
        return bin == null ? 0 : bin.getBytesRead();
    }

    /**
     * After an error, puts a source with mark/reset back at the first byte not
     * consumed (a no-op for other sources).
     */
    private void repositionSourceAfter(Exception cause)
    {
        try {
            bin.repositionSource();
        }
        catch (IOException | RuntimeException e) {
            cause.addSuppressed(e);
        }
    }

    @Override
    public int read()
            throws IOException
    {
        if (bin == null && parallel == null) {
            throw new IOException("stream closed");
        }
        return read(oneByte, 0, 1) < 0 ? -1 : (oneByte[0] & 0xff);
    }

    @Override
    public int read(byte[] dest, int offs, int len)
            throws IOException
    {
        if (offs < 0) {
            throw new IndexOutOfBoundsException("offs(" + offs + ") < 0.");
        }
        if (len < 0) {
            throw new IndexOutOfBoundsException("len(" + len + ") < 0.");
        }
        if (offs + len > dest.length) {
            throw new IndexOutOfBoundsException("offs(" + offs + ") + len("
                    + len + ") > dest.length(" + dest.length + ").");
        }
        if (parallel != null) {
            return len == 0 ? 0 : parallel.read(dest, offs, len);
        }
        if (bin == null) {
            throw new IOException("stream closed");
        }
        if (len == 0) {
            return 0;
        }

        try {
            return readSequential(dest, offs, len);
        }
        catch (IOException | RuntimeException e) {
            repositionSourceAfter(e);
            throw e;
        }
    }

    private int readSequential(byte[] dest, int offs, int len)
            throws IOException
    {
        if (!initialized) {
            initialized = true;
            init(true);
            initBlock();
        }

        int n = 0;
        while (n < len) {
            int state = currentState;
            if (state == NO_RAND_PART_B_STATE || state == NO_RAND_PART_C_STATE) {
                // Steady state of a non-randomised block: bulk path.
                n = readNoRand(dest, offs, len, n);
                if (currentState == NO_RAND_PART_A_STATE) {
                    // Block exhausted while more output was wanted (same sequence as setupNoRandPartA()).
                    endBlock();
                    initBlock();
                }
            }
            else {
                // Block boundaries, randomised blocks and EOF go through the byte-at-a-time state machine.
                int b = read0();
                if (b < 0) {
                    break;
                }
                dest[offs + n++] = (byte) b;
            }
        }
        return n == 0 ? -1 : n;
    }

    /**
     * Inverse BWT traversal and RLE1 expansion of a non-randomised block into
     * {@code dest}: the {@link #setupNoRandPartA()}/{@link #setupNoRandPartB()}/
     * {@link #setupNoRandPartC()} state machine with its state in locals, reading
     * the block from {@code data.raw} and computing the CRC over the output slice.
     *
     * @return the new count of bytes written into {@code dest}. Leaves
     * {@link #currentState} at {@link #NO_RAND_PART_A_STATE} if the block was
     * exhausted before {@code len} bytes were produced.
     */
    private int readNoRand(byte[] dest, int offs, int len, int n)
    {
        byte[] raw = data.raw;
        int lastShadow = this.last;
        int i2 = suI2;
        int count = suCount;
        int ch2 = suCh2;
        int chPrev = suChPrev;
        int z = suZ;
        int j2 = suJ2;
        int state = currentState;
        int start = offs + n;
        int end = offs + len;
        int o = start;
        while (o < end) {
            if (state == NO_RAND_PART_C_STATE) {
                if (j2 < z) {
                    int k = Math.min(z - j2, end - o);
                    byte b = (byte) ch2;
                    for (int i = 0; i < k; i++) {
                        dest[o + i] = b;
                    }
                    o += k;
                    j2 += k;
                    continue;
                }
                i2++;
                count = 0;
            }
            else if (ch2 != chPrev) {
                count = 1;
            }
            else if (++count >= 4) {
                // Repeat count; i2 may be last + 1 here, raw[last + 1] holds the byte the cycle continues with.
                z = raw[i2] & 0xff;
                j2 = 0;
                state = NO_RAND_PART_C_STATE;
                continue;
            }
            // setupNoRandPartA()
            if (i2 > lastShadow) {
                state = NO_RAND_PART_A_STATE;
                break;
            }
            chPrev = ch2;
            ch2 = raw[i2++] & 0xff;
            dest[o++] = (byte) ch2;
            state = NO_RAND_PART_B_STATE;
        }
        suI2 = i2;
        suCount = count;
        suCh2 = ch2;
        suChPrev = chPrev;
        suZ = z;
        suJ2 = j2;
        currentState = state;
        crc32.updateCRC(dest, start, o - start);
        return o - offs;
    }

    private int read0()
            throws IOException
    {
        return switch (currentState) {
            case EOF -> -1;
            case START_BLOCK_STATE -> setupBlock();
            case RAND_PART_B_STATE -> setupRandPartB();
            case RAND_PART_C_STATE -> setupRandPartC();
            case NO_RAND_PART_B_STATE -> setupNoRandPartB();
            case NO_RAND_PART_C_STATE -> setupNoRandPartC();
            default -> throw new IllegalStateException("unexpected state " + currentState);
        };
    }

    /**
     * Reads a stream header.
     *
     * @param firstStream whether this is the first stream, whose {@code "BZ"}
     * magic is optional
     * @return false if there is no further stream
     */
    private boolean init(boolean firstStream)
            throws IOException
    {
        if (firstStream) {
            int magic2 = bin.readByteOrEof();
            if (magic2 == 'B') {
                if (bin.readByteOrEof() != 'Z') {
                    throw new IOException("Stream is not BZip2 formatted");
                }
                magic2 = bin.readByteOrEof();
            }
            if (magic2 != 'h') {
                throw new IOException("Stream is not BZip2 formatted: expected 'h'"
                        + " as first byte but got '" + (char) magic2 + "'");
            }
        }
        else {
            // Continue with a concatenated stream if one follows; anything else ends the data.
            bin.alignToByte();
            int magic0 = bin.readByteOrEof();
            if (magic0 != 'B' || bin.readByteOrEof() != 'Z' || bin.readByteOrEof() != 'h') {
                return false;
            }
        }

        int blockSize = bin.readByteOrEof();
        if ((blockSize < '1') || (blockSize > '9')) {
            if (!firstStream) {
                return false;
            }
            throw new IOException("Stream is not BZip2 formatted: illegal "
                    + "blocksize " + (char) blockSize);
        }

        this.blockSize100k = blockSize - '0';
        this.computedCombinedCRC = 0;
        return true;
    }

    private void initBlock()
            throws IOException
    {
        while (true) {
            int magic0 = bin.readBits(8);
            int magic1 = bin.readBits(8);
            int magic2 = bin.readBits(8);
            int magic3 = bin.readBits(8);
            int magic4 = bin.readBits(8);
            int magic5 = bin.readBits(8);

            if (magic0 == 0x17 && magic1 == 0x72 && magic2 == 0x45
                    && magic3 == 0x38 && magic4 == 0x50 && magic5 == 0x90) {
                // End of stream: check the combined CRC and advance to the next stream, if any.
                if (complete()) {
                    return;
                }
                continue;
            }

            if (magic0 != 0x31 || // '1'
                    magic1 != 0x41 || // ')'
                    magic2 != 0x59 || // 'Y'
                    magic3 != 0x26 || // '&'
                    magic4 != 0x53 || // 'S'
                    magic5 != 0x59 /* 'Y' */) {
                this.currentState = EOF;
                throw new IOException("bad block header");
            }
            break;
        }

        this.storedBlockCRC = bin.readBits(32);
        this.blockRandomised = bin.readBits(1) == 1;

        // Allocate data here instead in constructor, so we do not allocate
        // it if the input file is empty.
        if (this.data == null) {
            this.data = new Data(this.blockSize100k);
        }

        getAndMoveToFrontDecode();

        this.crc32.initialiseCRC();
        this.currentState = START_BLOCK_STATE;
    }

    private void endBlock()
            throws IOException
    {
        int computedBlockCRC = this.crc32.getFinalCRC();

        // A bad CRC is considered a fatal error.
        if (this.storedBlockCRC != computedBlockCRC) {
            // make next blocks readable without error
            // (repair feature, not yet documented, not tested)
            this.computedCombinedCRC = (this.storedCombinedCRC << 1)
                    | (this.storedCombinedCRC >>> 31);
            this.computedCombinedCRC ^= this.storedBlockCRC;

            throw new IOException("crc error");
        }

        this.computedCombinedCRC = (this.computedCombinedCRC << 1)
                | (this.computedCombinedCRC >>> 31);
        this.computedCombinedCRC ^= computedBlockCRC;
    }

    /**
     * Finishes a stream.
     *
     * @return true if there is no further stream
     */
    private boolean complete()
            throws IOException
    {
        this.storedCombinedCRC = bin.readBits(32);
        this.currentState = EOF;
        this.data = null;

        if (!decompressConcatenated) {
            bin.repositionSource();
        }
        if (this.storedCombinedCRC != this.computedCombinedCRC) {
            throw new IOException("crc error");
        }
        return !decompressConcatenated || !init(false);
    }

    @Override
    public void close()
            throws IOException
    {
        if (parallel != null) {
            ParallelBZip2Decoder parallelShadow = this.parallel;
            this.parallel = null;
            parallelShadow.close();
            return;
        }
        BZip2BitReader binShadow = this.bin;
        if (binShadow != null) {
            try {
                binShadow.close();
            }
            finally {
                this.data = null;
                this.bin = null;
            }
        }
    }

    private static void checkBounds(int checkVal, int limitExclusive, String name)
            throws IOException
    {
        if (checkVal < 0) {
            throw new IOException("stream corrupted: '" + name + "' value negative");
        }
        if (checkVal >= limitExclusive) {
            throw new IOException("stream corrupted: '" + name + "' value too big");
        }
    }

    /**
     * Builds the decoding tables of one Huffman group from
     * {@code data.codeLengths[0..alphaSize)}: code lengths must be in
     * {@code [1, MAX_CODE_LEN]} and must not over-subscribe the code space
     * (Kraft's inequality). Incomplete codes are accepted.
     */
    private static void buildHuffmanTables(Data data, int group, int alphaSize)
            throws IOException
    {
        int[] codeLengths = data.codeLengths;
        // 1) Validate and find min/max lengths, in symbol order.
        int min = MAX_CODE_LEN;
        int max = 0;
        for (int i = 0; i < alphaSize; i++) {
            int len = codeLengths[i];
            if (len < 1 || len > MAX_CODE_LEN) {
                throw new IOException("stream corrupted: invalid code length at symbol " + i + ": " + len);
            }
            if (len < min) {
                min = len;
            }
            if (len > max) {
                max = len;
            }
        }
        if (max == 0) {
            throw new IOException("stream corrupted: all code lengths are zero");
        }
        // 2) Histogram of code lengths and Kraft's inequality.
        int[] count = data.lengthCount;
        Arrays.fill(count, 0);
        for (int i = 0; i < alphaSize; i++) {
            count[codeLengths[i]]++;
        }
        int availableNodes = 1;
        for (int len = 1; len <= max; len++) {
            availableNodes <<= 1;
            if (count[len] > availableNodes) {
                throw new IOException("stream corrupted: too many codes of length " + len);
            }
            availableNodes -= count[len];
        }
        // 3) Symbols sorted by (length, symbol); offset[len] ends up pointing at the last symbol of each length.
        int[] offset = data.lengthOffset;
        offset[0] = -1;
        for (int len = 1; len <= max; len++) {
            offset[len] = offset[len - 1] + count[len - 1];
        }
        int[] perm = data.perm[group];
        for (int i = 0; i < alphaSize; i++) {
            perm[++offset[codeLengths[i]]] = i;
        }
        // 4) Largest code of each length and the bias between codes and indices into perm.
        int[] limit = data.limit[group];
        int[] bias = data.bias[group];
        int firstCode = 0;
        for (int len = min; len <= max; len++) {
            firstCode += count[len];
            limit[len] = firstCode - 1;
            bias[len] = limit[len] - offset[len];
            firstCode <<= 1;
        }
        data.minLens[group] = min;
        data.maxLens[group] = max;
        // 5) Lookup table for codes of at most FAST_BITS bits: canonical codes are consecutive within a
        // length, in symbol order.
        int[] fast = data.fastTable;
        int base = group << FAST_BITS;
        Arrays.fill(fast, base, base + (1 << FAST_BITS), 0);
        int code = 0;
        int symbolIndex = 0;
        for (int len = 1; len <= max && len <= FAST_BITS; len++) {
            int n = count[len];
            for (int k = 0; k < n; k++, code++) {
                int entry = (perm[symbolIndex++] << 8) | len;
                int from = base + (code << (FAST_BITS - len));
                Arrays.fill(fast, from, from + (1 << (FAST_BITS - len)), entry);
            }
            code <<= 1;
        }
    }

    private static void makeMaps(Data data)
    {
        boolean[] inUse = data.inUse;
        byte[] seqToUnseq = data.seqToUnseq;

        int nInUseShadow = 0;

        for (int i = 0; i < 256; i++) {
            if (inUse[i]) {
                seqToUnseq[nInUseShadow++] = (byte) i;
            }
        }

        data.inUseCount = nInUseShadow;
    }

    private static void recvDecodingTables(BZip2BitReader bin, Data dataShadow)
            throws IOException
    {
        boolean[] inUse = dataShadow.inUse;
        byte[] pos = dataShadow.recvDecodingTablesPos;
        byte[] selector = dataShadow.selector;
        byte[] selectorMtf = dataShadow.selectorMtf;

        int inUse16 = 0;

        /* Receive the mapping table */
        for (int i = 0; i < 16; i++) {
            if (bin.readBitsInBlock(1) != 0) {
                inUse16 |= 1 << i;
            }
        }

        Arrays.fill(inUse, false);
        for (int i = 0; i < 16; i++) {
            if ((inUse16 & (1 << i)) != 0) {
                int i16 = i << 4;
                for (int j = 0; j < 16; j++) {
                    if (bin.readBitsInBlock(1) != 0) {
                        inUse[i16 + j] = true;
                    }
                }
            }
        }

        makeMaps(dataShadow);
        int alphaSize = dataShadow.inUseCount + 2;

        /* Now the selectors */
        int nGroups = bin.readBitsInBlock(3);
        int selectors = bin.readBitsInBlock(15);
        checkBounds(alphaSize, MAX_ALPHA_SIZE + 1, "alphaSize");
        checkBounds(nGroups, N_GROUPS + 1, "nGroups");

        // Don't fail on nSelectors overflowing boundaries but discard the values in overflow
        // See https://gnu.wildebeest.org/blog/mjw/2019/08/02/bzip2-and-the-cve-that-wasnt/
        // and https://sourceware.org/ml/bzip2-devel/2019-q3/msg00007.html
        for (int i = 0; i < selectors; i++) {
            int j = bin.readUnaryInBlock();
            if (i < MAX_SELECTORS) {
                selectorMtf[i] = (byte) j;
            }
        }
        int nSelectors = Math.min(selectors, MAX_SELECTORS);

        /* Undo the MTF values for the selectors. */
        for (int v = nGroups; --v >= 0; ) {
            pos[v] = (byte) v;
        }

        for (int i = 0; i < nSelectors; i++) {
            int v = selectorMtf[i] & 0xff;
            checkBounds(v, N_GROUPS, "selectorMtf");
            byte tmp = pos[v];
            while (v > 0) {
                // nearly all times v is zero, 4 in most other cases
                pos[v] = pos[v - 1];
                v--;
            }
            pos[0] = tmp;
            selector[i] = tmp;
        }

        /* Now the Huffman coding tables */
        int[] codeLengths = dataShadow.codeLengths;
        for (int t = 0; t < nGroups; t++) {
            int curr = bin.readBitsInBlock(5);
            for (int i = 0; i < alphaSize; i++) {
                while (bin.readBitsInBlock(1) != 0) {
                    curr += bin.readBitsInBlock(1) != 0 ? -1 : 1;
                }
                codeLengths[i] = curr;
            }
            buildHuffmanTables(dataShadow, t, alphaSize);
        }
        dataShadow.nGroups = nGroups;
    }

    /**
     * Decodes one symbol bit by bit (canonical Huffman decoding): used for codes
     * longer than {@link #FAST_BITS} bits, for prefixes no code claims (the
     * stream is then corrupt) and near the end of the input. The bit buffer
     * state must have been written back to {@code bin} by the caller.
     */
    private int decodeSymbolSlow(int group)
            throws IOException
    {
        BZip2BitReader bin = this.bin;
        Data dataShadow = this.data;
        int[] limit = dataShadow.limit[group];
        int maxLen = dataShadow.maxLens[group];
        int len = dataShadow.minLens[group];
        int code = bin.readBitsInBlock(len);
        while (len <= maxLen && code > limit[len]) {
            code = (code << 1) | bin.readBitsInBlock(1);
            len++;
        }
        if (len > maxLen) {
            throw new IOException("stream corrupted: invalid Huffman code " + code);
        }
        return dataShadow.perm[group][code - dataShadow.bias[group][len]];
    }

    private void getAndMoveToFrontDecode()
            throws IOException
    {
        BZip2BitReader bin = this.bin;
        this.origPtr = bin.readBits(24);
        Data dataShadow = this.data;
        recvDecodingTables(bin, dataShadow);

        int[] tt = dataShadow.tt;
        int[] unzftab = dataShadow.unzftab;
        byte[] selector = dataShadow.selector;
        byte[] seqToUnseq = dataShadow.seqToUnseq;
        int[] yy = dataShadow.yy;
        int[] fast = dataShadow.fastTable;
        int nGroups = dataShadow.nGroups;
        int limitLast = this.blockSize100k * BASE_BLOCK_SIZE;

        /*
         * Setting up the unzftab entries here is not strictly necessary, but it
         * does save having to do it later in a separate pass, and so saves a
         * block's worth of cache misses.
         */
        for (int i = 256; --i >= 0; ) {
            yy[i] = i;
            unzftab[i] = 0;
        }

        int groupPos = G_SIZE - 1;
        int eob = dataShadow.inUseCount + 1;
        int lastShadow = -1;
        int groupNo = 0;
        int zt = selector[groupNo] & 0xff;
        checkBounds(zt, nGroups, "zt");
        int fastBase = zt << FAST_BITS;
        // RUNA/RUNB accumulation state
        boolean inRun = false;
        int runLength = -1;
        int runWeight = 1;
        boolean first = true;
        long bitBuffer = bin.bitBuffer;
        int bitCount = bin.bitCount;
        // The locals hold the reader state except while the reader itself is working (refill, slow
        // path); on an exception thrown by the reader the locals are stale and must not be written back.
        boolean localsAhead = true;
        try {
            while (true) {
                // Every symbol but the first is preceded by the group bookkeeping.
                if (first) {
                    first = false;
                }
                else if (groupPos == 0) {
                    groupPos = G_SIZE - 1;
                    checkBounds(++groupNo, selector.length, "groupNo");
                    zt = selector[groupNo] & 0xff;
                    checkBounds(zt, nGroups, "zt");
                    fastBase = zt << FAST_BITS;
                }
                else {
                    groupPos--;
                }

                // Decode one symbol.
                if (bitCount < REFILL_THRESHOLD) {
                    bin.bitBuffer = bitBuffer;
                    bin.bitCount = bitCount;
                    localsAhead = false;
                    bin.fill();
                    bitBuffer = bin.bitBuffer;
                    bitCount = bin.bitCount;
                    localsAhead = true;
                }
                int nextSym;
                int entry = fast[fastBase + (int) (bitBuffer >>> (64 - FAST_BITS))];
                int codeLen = entry & 0xff;
                if (entry != 0 && codeLen <= bitCount) {
                    nextSym = entry >>> 8;
                    bitBuffer <<= codeLen;
                    bitCount -= codeLen;
                }
                else {
                    bin.bitBuffer = bitBuffer;
                    bin.bitCount = bitCount;
                    localsAhead = false;
                    nextSym = decodeSymbolSlow(zt);
                    bitBuffer = bin.bitBuffer;
                    bitCount = bin.bitCount;
                    localsAhead = true;
                }

                if (nextSym <= RUN_B) {
                    // RUN_A (0) adds the weight, RUN_B (1) twice the weight.
                    if (!inRun) {
                        inRun = true;
                        runLength = -1;
                        runWeight = 1;
                    }
                    runLength += runWeight << nextSym;
                    runWeight <<= 1;
                    continue;
                }

                if (inRun) {
                    inRun = false;
                    checkBounds(runLength, tt.length, "s");
                    // yy is a permutation of 0..inUseCount-1 at positions below inUseCount, so yy[0] always
                    // indexes seqToUnseq.
                    int ch = seqToUnseq[yy[0]] & 0xff;
                    unzftab[ch] += runLength + 1;
                    int from = ++lastShadow;
                    lastShadow += runLength;
                    checkBounds(lastShadow, tt.length, "lastShadow");
                    if (runLength < 32) {
                        // Most runs are short; avoid the call overhead of Arrays.fill.
                        for (int i = from; i <= lastShadow; i++) {
                            tt[i] = ch;
                        }
                    }
                    else {
                        Arrays.fill(tt, from, lastShadow + 1, ch);
                    }
                    if (lastShadow >= limitLast) {
                        throw new IOException("block overrun");
                    }
                }

                if (nextSym == eob) {
                    break;
                }

                if (++lastShadow >= limitLast) {
                    throw new IOException("block overrun");
                }

                // nextSym < alphaSize == inUseCount + 2 and nextSym != eob, so nextSym - 1 < inUseCount <=
                // 256 and yy[nextSym - 1] < inUseCount.
                int tmp = yy[nextSym - 1];
                int ch = seqToUnseq[tmp] & 0xff;
                unzftab[ch]++;
                tt[lastShadow] = ch;

                /*
                 * This loop is hammered during decompression, hence avoid
                 * native method call overhead of System.arraycopy for very
                 * small ranges to copy.
                 */
                if (nextSym <= 16) {
                    for (int j = nextSym - 1; j > 0; ) {
                        yy[j] = yy[--j];
                    }
                }
                else {
                    System.arraycopy(yy, 0, yy, 1, nextSym - 1);
                }

                yy[0] = tmp;
            }
        }
        finally {
            if (localsAhead) {
                bin.bitBuffer = bitBuffer;
                bin.bitCount = bitCount;
            }
        }

        this.last = lastShadow;
    }

    private int setupBlock()
            throws IOException
    {
        if (currentState == EOF || this.data == null) {
            return -1;
        }

        int[] cftab = this.data.cftab;
        int[] tt = this.data.tt;
        int lastShadow = this.last;

        // Checked before the in-place transform below, which must run exactly once per block.
        if (this.origPtr < 0 || this.origPtr > lastShadow) {
            throw new IOException("stream corrupted");
        }

        cftab[0] = 0;
        System.arraycopy(this.data.unzftab, 0, cftab, 1, 256);

        for (int i = 1, c = cftab[0]; i <= 256; i++) {
            c += cftab[i];
            cftab[i] = c;
        }

        // tt[i] holds byte i of the transformed block in its low 8 bits; link every byte to its
        // successor in the original data. unzftab counted exactly the bytes written, so
        // cftab[256] == lastShadow + 1 and every index below is written exactly once.
        for (int i = 0; i <= lastShadow; i++) {
            tt[cftab[tt[i] & 0xff]++] |= i << 8;
        }

        this.suCount = 0;
        this.suI2 = 0;
        this.suCh2 = 256; /* not a char and not EOF */

        if (this.blockRandomised) {
            this.suTPos = tt[this.origPtr] >>> 8;
            this.suRNToGo = 0;
            this.suRTPos = 0;
            return setupRandPartA();
        }
        this.data.inverseBwt.unwind(tt, this.origPtr, lastShadow + 1, this.data.raw);
        return setupNoRandPartA();
    }

    private int setupRandPartA()
            throws IOException
    {
        if (this.suI2 <= this.last) {
            this.suChPrev = this.suCh2;
            int v = this.data.tt[this.suTPos];
            int suCh2Shadow = v & 0xff;
            this.suTPos = v >>> 8;
            if (this.suRNToGo == 0) {
                this.suRNToGo = R_NUMS[this.suRTPos] - 1;
                if (++this.suRTPos == 512) {
                    this.suRTPos = 0;
                }
            }
            else {
                this.suRNToGo--;
            }
            suCh2Shadow ^= (this.suRNToGo == 1) ? 1 : 0;
            this.suCh2 = suCh2Shadow;
            this.suI2++;
            this.currentState = RAND_PART_B_STATE;
            this.crc32.updateCRC(suCh2Shadow);
            return suCh2Shadow;
        }
        endBlock();
        initBlock();
        return setupBlock();
    }

    private int setupNoRandPartA()
            throws IOException
    {
        if (this.suI2 <= this.last) {
            this.suChPrev = this.suCh2;
            int suCh2Shadow = this.data.raw[this.suI2] & 0xff;
            this.suCh2 = suCh2Shadow;
            this.suI2++;
            this.currentState = NO_RAND_PART_B_STATE;
            this.crc32.updateCRC(suCh2Shadow);
            return suCh2Shadow;
        }
        this.currentState = NO_RAND_PART_A_STATE;
        endBlock();
        initBlock();
        return setupBlock();
    }

    private int setupRandPartB()
            throws IOException
    {
        if (this.suCh2 != this.suChPrev) {
            this.currentState = RAND_PART_A_STATE;
            this.suCount = 1;
            return setupRandPartA();
        }
        if (++this.suCount < 4) {
            this.currentState = RAND_PART_A_STATE;
            return setupRandPartA();
        }
        int v = this.data.tt[this.suTPos];
        this.suZ = v & 0xff;
        this.suTPos = v >>> 8;
        if (this.suRNToGo == 0) {
            this.suRNToGo = R_NUMS[this.suRTPos] - 1;
            if (++this.suRTPos == 512) {
                this.suRTPos = 0;
            }
        }
        else {
            this.suRNToGo--;
        }
        this.suJ2 = 0;
        this.currentState = RAND_PART_C_STATE;
        if (this.suRNToGo == 1) {
            this.suZ ^= 1;
        }
        return setupRandPartC();
    }

    private int setupRandPartC()
            throws IOException
    {
        if (this.suJ2 < this.suZ) {
            this.crc32.updateCRC(this.suCh2);
            this.suJ2++;
            return this.suCh2;
        }
        this.currentState = RAND_PART_A_STATE;
        this.suI2++;
        this.suCount = 0;
        return setupRandPartA();
    }

    private int setupNoRandPartB()
            throws IOException
    {
        if (this.suCh2 != this.suChPrev) {
            this.suCount = 1;
            return setupNoRandPartA();
        }
        if (++this.suCount >= 4) {
            this.suZ = this.data.raw[this.suI2] & 0xff;
            this.suJ2 = 0;
            return setupNoRandPartC();
        }
        return setupNoRandPartA();
    }

    private int setupNoRandPartC()
            throws IOException
    {
        if (this.suJ2 < this.suZ) {
            int suCh2Shadow = this.suCh2;
            this.crc32.updateCRC(suCh2Shadow);
            this.suJ2++;
            this.currentState = NO_RAND_PART_C_STATE;
            return suCh2Shadow;
        }
        this.suI2++;
        this.suCount = 0;
        return setupNoRandPartA();
    }

    private static final class Data
    {
        // (with blockSize 900k)
        final boolean[] inUse = new boolean[256]; // 256 byte
        // Always equal to the number of true values in inUse[].
        int inUseCount;

        final byte[] seqToUnseq = new byte[256]; // 256 byte
        final byte[] selector = new byte[MAX_SELECTORS]; // 18002 byte
        final byte[] selectorMtf = new byte[MAX_SELECTORS]; // 18002 byte

        /**
         * Freq table collected to save a pass over the data during
         * decompression.
         */
        final int[] unzftab = new int[256]; // 1024 byte

        /**
         * Huffman decoding tables, one group per {@link BZip2Constants#N_GROUPS}:
         * a lookup table indexed by the next {@link #FAST_BITS} bits of input
         * (entry = symbol {@code << 8 | code length}, 0 = no code of at most
         * {@link #FAST_BITS} bits matches), and the canonical-code tables for
         * longer codes.
         */
        final int[] fastTable = new int[N_GROUPS << FAST_BITS]; // 24576 byte

        final int[][] limit = new int[N_GROUPS][MAX_CODE_LEN + 2];
        final int[][] bias = new int[N_GROUPS][MAX_CODE_LEN + 2];
        final int[][] perm = new int[N_GROUPS][MAX_ALPHA_SIZE]; // 6192 byte
        final int[] minLens = new int[N_GROUPS]; // 24 byte
        final int[] maxLens = new int[N_GROUPS]; // 24 byte

        /** Number of Huffman groups in the current block. */
        int nGroups;

        /** Scratch space for building the Huffman tables. */
        final int[] codeLengths = new int[MAX_ALPHA_SIZE];
        final int[] lengthCount = new int[MAX_CODE_LEN + 2];
        final int[] lengthOffset = new int[MAX_CODE_LEN + 2];

        final int[] cftab = new int[257]; // 1028 byte
        final int[] yy = new int[256]; // 1024 byte
        final byte[] recvDecodingTablesPos = new byte[N_GROUPS]; // 6 byte

        /**
         * The block: while decoding the MTF/RLE2 stage the low 8 bits of
         * {@code tt[i]} hold byte {@code i} of the BWT-transformed block, after
         * the inverse BWT setup the upper 24 bits of {@code tt[i]} hold the index
         * of the byte following byte {@code i} in the original data (block sizes
         * are at most 900,000 &lt; 2^24).
         */
        final int[] tt; // 3600000 byte

        /**
         * The block in original order, {@code raw[0..last]}, produced by the
         * inverse BWT for non-randomised blocks; {@code raw[last + 1]} holds the
         * byte the cycle continues with (read as an RLE1 repeat count if a
         * corrupt block ends inside a run).
         */
        final byte[] raw; // 900001 byte

        final BZip2InverseBwt inverseBwt; // about 1.4 MB

        Data(int blockSize100k)
        {
            int n = blockSize100k * BASE_BLOCK_SIZE;
            this.tt = new int[n];
            this.raw = new byte[n + 1];
            this.inverseBwt = new BZip2InverseBwt(n);
        }
    }

    private static final int[] R_NUMS = {
            619, 720, 127, 481, 931, 816, 813, 233, 566, 247,
            985, 724, 205, 454, 863, 491, 741, 242, 949, 214, 733, 859, 335,
            708, 621, 574, 73, 654, 730, 472, 419, 436, 278, 496, 867, 210,
            399, 680, 480, 51, 878, 465, 811, 169, 869, 675, 611, 697, 867,
            561, 862, 687, 507, 283, 482, 129, 807, 591, 733, 623, 150, 238,
            59, 379, 684, 877, 625, 169, 643, 105, 170, 607, 520, 932, 727,
            476, 693, 425, 174, 647, 73, 122, 335, 530, 442, 853, 695, 249,
            445, 515, 909, 545, 703, 919, 874, 474, 882, 500, 594, 612, 641,
            801, 220, 162, 819, 984, 589, 513, 495, 799, 161, 604, 958, 533,
            221, 400, 386, 867, 600, 782, 382, 596, 414, 171, 516, 375, 682,
            485, 911, 276, 98, 553, 163, 354, 666, 933, 424, 341, 533, 870,
            227, 730, 475, 186, 263, 647, 537, 686, 600, 224, 469, 68, 770,
            919, 190, 373, 294, 822, 808, 206, 184, 943, 795, 384, 383, 461,
            404, 758, 839, 887, 715, 67, 618, 276, 204, 918, 873, 777, 604,
            560, 951, 160, 578, 722, 79, 804, 96, 409, 713, 940, 652, 934, 970,
            447, 318, 353, 859, 672, 112, 785, 645, 863, 803, 350, 139, 93,
            354, 99, 820, 908, 609, 772, 154, 274, 580, 184, 79, 626, 630, 742,
            653, 282, 762, 623, 680, 81, 927, 626, 789, 125, 411, 521, 938,
            300, 821, 78, 343, 175, 128, 250, 170, 774, 972, 275, 999, 639,
            495, 78, 352, 126, 857, 956, 358, 619, 580, 124, 737, 594, 701,
            612, 669, 112, 134, 694, 363, 992, 809, 743, 168, 974, 944, 375,
            748, 52, 600, 747, 642, 182, 862, 81, 344, 805, 988, 739, 511, 655,
            814, 334, 249, 515, 897, 955, 664, 981, 649, 113, 974, 459, 893,
            228, 433, 837, 553, 268, 926, 240, 102, 654, 459, 51, 686, 754,
            806, 760, 493, 403, 415, 394, 687, 700, 946, 670, 656, 610, 738,
            392, 760, 799, 887, 653, 978, 321, 576, 617, 626, 502, 894, 679,
            243, 440, 680, 879, 194, 572, 640, 724, 926, 56, 204, 700, 707,
            151, 457, 449, 797, 195, 791, 558, 945, 679, 297, 59, 87, 824, 713,
            663, 412, 693, 342, 606, 134, 108, 571, 364, 631, 212, 174, 643,
            304, 329, 343, 97, 430, 751, 497, 314, 983, 374, 822, 928, 140,
            206, 73, 263, 980, 736, 876, 478, 430, 305, 170, 514, 364, 692,
            829, 82, 855, 953, 676, 246, 369, 970, 294, 750, 807, 827, 150,
            790, 288, 923, 804, 378, 215, 828, 592, 281, 565, 555, 710, 82,
            896, 831, 547, 261, 524, 462, 293, 465, 502, 56, 661, 821, 976,
            991, 658, 869, 905, 758, 745, 193, 768, 550, 608, 933, 378, 286,
            215, 979, 792, 961, 61, 688, 793, 644, 986, 403, 106, 366, 905,
            644, 372, 567, 466, 434, 645, 210, 389, 550, 919, 135, 780, 773,
            635, 389, 707, 100, 626, 958, 165, 504, 920, 176, 193, 713, 857,
            265, 203, 50, 668, 108, 645, 990, 626, 197, 510, 357, 358, 850,
            858, 364, 936, 638};
}
