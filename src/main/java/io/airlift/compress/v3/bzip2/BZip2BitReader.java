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
package io.airlift.compress.v3.bzip2;

import java.io.IOException;
import java.io.InputStream;

import static java.util.Objects.requireNonNull;

/**
 * MSB-first bit reader for the bzip2 decoder.
 * <p>
 * The reader keeps up to 64 bits in {@link #bitBuffer}, left aligned: the top
 * {@link #bitCount} bits are valid. The source is read in chunks of
 * {@value #BUFFER_SIZE} bytes into an internal buffer and the bit buffer is
 * refilled eight bytes at a time, so the source is read ahead of the decoder.
 * <p>
 * Hot loops in the decoder copy {@link #bitBuffer} and {@link #bitCount} into
 * locals and write them back before calling {@link #fill()} or any method that
 * may throw.
 */
final class BZip2BitReader
{
    static final int BUFFER_SIZE = 1 << 16;

    private final InputStream in;

    /**
     * Input buffer; {@code buf[pos..limit)} is unread. Eight spare bytes so
     * that a refill can always read a whole word.
     */
    private final byte[] buf = new byte[BUFFER_SIZE + 8];
    private int pos;
    private int limit;
    private boolean sourceEof;

    /** Bytes pulled from the underlying stream, including buffered ones. */
    private long bytesRead;

    /** Left-aligned bit buffer; the top {@link #bitCount} bits are valid. */
    long bitBuffer;

    /** Number of valid bits in {@link #bitBuffer}, 0..64. */
    int bitCount;

    BZip2BitReader(InputStream in)
    {
        this.in = requireNonNull(in, "in is null");
    }

    void close()
            throws IOException
    {
        in.close();
    }

    /**
     * Discards the padding bits up to the next byte boundary (a bzip2 stream
     * ends byte aligned, and a concatenated stream starts at a byte boundary).
     */
    void alignToByte()
    {
        int padding = bitCount & 7;
        bitBuffer <<= padding;
        bitCount -= padding;
    }

    /**
     * Number of bytes of the underlying stream consumed so far,
     * {@code ceil(bits consumed / 8)}, independent of how many bytes are buffered.
     */
    long getBytesRead()
    {
        return bytesRead - (limit - pos) - (bitCount >>> 3);
    }

    /**
     * Greedy refill: reads until more than 56 bits are buffered or the source is
     * exhausted. Never throws for end of input.
     * <p>
     * The fast path ORs a whole 8-byte word into the bit buffer but only consumes
     * as many bytes as fit. The bits of the partially consumed bytes land below
     * the valid region; that is harmless, because nothing reads below
     * {@link #bitCount} and every later refill ORs the very same bits in again
     * from the same, still unread, bytes.
     */
    void fill()
            throws IOException
    {
        byte[] buf = this.buf;
        while (bitCount <= 56) {
            int pos = this.pos;
            int available = limit - pos;
            if (available >= 8) {
                long w = (long) buf[pos] << 56
                        | (long) (buf[pos + 1] & 0xff) << 48
                        | (long) (buf[pos + 2] & 0xff) << 40
                        | (long) (buf[pos + 3] & 0xff) << 32
                        | (long) (buf[pos + 4] & 0xff) << 24
                        | (buf[pos + 5] & 0xff) << 16
                        | (buf[pos + 6] & 0xff) << 8
                        | (buf[pos + 7] & 0xff);
                int k = (64 - bitCount) >>> 3;
                bitBuffer |= w >>> bitCount;
                this.pos = pos + k;
                bitCount += k << 3;
                return;
            }
            if (available > 0) {
                // Tail of a chunk: byte by byte, so that a chunk is only ever replaced once it is empty.
                while (bitCount <= 56 && this.pos < limit) {
                    bitBuffer |= (long) (buf[this.pos++] & 0xff) << (56 - bitCount);
                    bitCount += 8;
                }
            }
            else if (!refillBuffer()) {
                return;
            }
        }
    }

    /**
     * Reads the next chunk of the source into the (empty) buffer.
     *
     * @return false if the source is exhausted.
     */
    private boolean refillBuffer()
            throws IOException
    {
        if (sourceEof) {
            return false;
        }
        pos = 0;
        limit = 0;
        while (limit == 0) {
            int n = in.read(buf, 0, BUFFER_SIZE);
            if (n < 0) {
                sourceEof = true;
                return false;
            }
            limit = n;
        }
        bytesRead += limit;
        return true;
    }

    /**
     * Reads {@code n} bits (1..32), most significant bit first.
     *
     * @throws IOException if the stream ends before {@code n} bits are available
     */
    int readBits(int n)
            throws IOException
    {
        if (bitCount < n) {
            fill();
            if (bitCount < n) {
                throw new IOException("unexpected end of stream");
            }
        }
        int value = (int) (bitBuffer >>> (64 - n));
        bitBuffer <<= n;
        bitCount -= n;
        return value;
    }

    /**
     * Reads a unary code: the number of 1 bits before the next 0 bit (which is
     * consumed as well).
     *
     * @throws IOException if the stream ends before a 0 bit is found
     */
    int readUnary()
            throws IOException
    {
        int ones = 0;
        while (true) {
            if (bitCount < 8) {
                fill();
                if (bitCount == 0) {
                    throw new IOException("unexpected end of stream");
                }
            }
            int leading = Long.numberOfLeadingZeros(~bitBuffer);
            if (leading < bitCount) {
                // The 0 bit is within the valid bits: consume the ones and the terminating zero.
                bitBuffer <<= leading + 1;
                bitCount -= leading + 1;
                return ones + leading;
            }
            // Every valid bit is a 1: consume them all and continue with the next refill.
            ones += bitCount;
            bitBuffer = 0;
            bitCount = 0;
        }
    }

    /**
     * Reads one byte (8 bits at the current bit position).
     *
     * @return the byte, or -1 if the stream ends before 8 bits are available
     */
    int readByteOrEof()
            throws IOException
    {
        if (bitCount < 8) {
            fill();
            if (bitCount < 8) {
                return -1;
            }
        }
        int value = (int) (bitBuffer >>> 56);
        bitBuffer <<= 8;
        bitCount -= 8;
        return value;
    }
}
