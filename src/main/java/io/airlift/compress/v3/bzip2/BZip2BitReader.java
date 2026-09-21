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
 * {@link #bitCount} bits are valid. Two refill policies exist:
 * <ul>
 * <li>{@link #readBits(int)} and {@link #readByteOrEof()} refill <em>lazily</em>:
 * they pull exactly the bytes needed for the request, so at the end of a bzip2
 * stream the reader has consumed nothing past the stream's last byte.
 * <li>{@link #fill()} refills <em>greedily</em> up to the capacity of the bit
 * buffer. It may only be used while decoding a block body: after the last
 * Huffman symbol of a block the stream always continues with at least 80 bits
 * (end-of-stream magic and combined CRC), so a lookahead of at most 64 bits
 * never crosses the end of the stream.
 * </ul>
 * <p>
 * In <em>bulk</em> mode the source is read in chunks of {@value #BUFFER_SIZE}
 * bytes into an internal buffer and the bit buffer is refilled eight bytes at a
 * time. The source is then read ahead of the decoder, so bulk mode is used when
 * that cannot matter (the decoder consumes the input to its end, i.e.
 * decompresses concatenated streams) or when the source supports
 * {@link InputStream#mark(int)}: it is then repositioned to the first byte not
 * consumed at every chunk boundary, at the end of the bzip2 stream and after an
 * error, so that at the end of the bzip2 stream it ends up exactly where the
 * exact (byte by byte) mode leaves it (after an error inside a block the exact
 * mode may have pulled up to seven more bytes into the bit buffer). Each chunk
 * is bracketed by its own {@code mark(BUFFER_SIZE)}, and never more than
 * {@value #BUFFER_SIZE} bytes are read before the mark is reset to or replaced,
 * so the mark limit is honoured; a source that invalidates its mark anyway
 * makes {@code reset()} throw an {@link IOException} (no data is decoded
 * wrongly, only the source position is lost). Because the source's single mark
 * is used, a mark set by the caller beforehand is not preserved.
 * <p>
 * Hot loops in the decoder copy {@link #bitBuffer} and {@link #bitCount} into
 * locals and write them back before calling {@link #fill()} or any method that
 * may throw.
 */
final class BZip2BitReader
{
    /** Size of the input buffer in bulk mode. */
    static final int BUFFER_SIZE = 1 << 16;

    private final InputStream in;

    /** Bulk mode: read the source in chunks (may read ahead of the bzip2 stream). */
    private final boolean bulk;

    /**
     * Bulk mode over a source with mark/reset: the source is repositioned to the
     * consumed byte at chunk boundaries, at the end and after errors.
     */
    private final boolean repositionable;

    /**
     * Input buffer (bulk mode only); {@code buf[pos..limit)} is unread. Eight
     * spare bytes so that a refill can always read a whole word.
     */
    private final byte[] buf;
    private int pos;
    private int limit;
    private boolean sourceEof;
    private boolean repositioned;

    private final byte[] scratch = new byte[8];

    /** Bytes pulled from the underlying stream, including buffered ones (in both buffers). */
    private long bytesRead;

    /**
     * Set once a request for bits could not be satisfied: the source is then at
     * its end and is not repositioned any more.
     */
    private boolean exhausted;

    /** Left-aligned bit buffer; the top {@link #bitCount} bits are valid. */
    long bitBuffer;

    /** Number of valid bits in {@link #bitBuffer}, 0..64. */
    int bitCount;

    /**
     * @param consumeToEnd whether the decoder consumes the source to its end
     * (concatenated streams), which allows reading ahead freely
     */
    BZip2BitReader(InputStream in, boolean consumeToEnd)
    {
        this.in = requireNonNull(in, "in is null");
        this.repositionable = !consumeToEnd && in.markSupported();
        this.bulk = consumeToEnd || repositionable;
        this.buf = bulk ? new byte[BUFFER_SIZE + 8] : null;
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
        return exhausted ? bytesRead : bytesRead - (limit - pos) - (bitCount >>> 3);
    }

    /**
     * Greedy refill: reads until more than 56 bits are buffered or the source is
     * exhausted. Never throws for end of input.
     */
    void fill()
            throws IOException
    {
        if (bulk) {
            fillBulk();
            return;
        }
        int count = bitCount;
        long buffer = bitBuffer;
        while (count <= 56) {
            int n = in.read(scratch, 0, (64 - count) >>> 3);
            if (n <= 0) {
                break;
            }
            bytesRead += n;
            for (int i = 0; i < n; i++) {
                buffer |= (long) (scratch[i] & 0xff) << (56 - count);
                count += 8;
            }
        }
        bitCount = count;
        bitBuffer = buffer;
    }

    /**
     * Greedy refill in bulk mode: ORs a whole 8-byte word into the bit buffer but
     * only consumes as many bytes as fit. The bits of the partially consumed
     * bytes land below the valid region; that is harmless, because nothing reads
     * below {@link #bitCount} and every later refill ORs the very same bits in
     * again from the same, still unread, bytes.
     */
    private void fillBulk()
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
     * Lazily reads exactly the bytes needed to have {@code n} bits buffered,
     * stopping silently at end of input.
     */
    private void fillLazy(int n)
            throws IOException
    {
        if (bulk) {
            while (bitCount < n) {
                if (pos >= limit && !refillBuffer()) {
                    return;
                }
                bitBuffer |= (long) (buf[pos++] & 0xff) << (56 - bitCount);
                bitCount += 8;
            }
            return;
        }
        while (bitCount < n) {
            int b = in.read();
            if (b < 0) {
                return;
            }
            bytesRead++;
            bitBuffer |= (long) b << (56 - bitCount);
            bitCount += 8;
        }
    }

    /**
     * Bulk mode: reads the next chunk of the source into the (empty) buffer.
     *
     * @return false if the source is exhausted
     */
    private boolean refillBuffer()
            throws IOException
    {
        if (sourceEof) {
            return false;
        }
        // Bytes the new chunk must exceed to make progress (the lookahead bytes read again below).
        int reread = 0;
        if (repositionable) {
            // Put the source at the first byte not consumed yet (the whole bytes of lookahead in the
            // bit buffer are dropped and read again), then mark it so that it can be brought back
            // there when the bzip2 stream ends.
            reread = bitCount >>> 3;
            if (limit > 0) {
                in.reset();
                in.skipNBytes(limit - reread);
            }
            dropLookaheadBytes();
            in.mark(BUFFER_SIZE);
        }
        pos = 0;
        limit = 0;
        while (limit <= reread) {
            int n = in.read(buf, limit, BUFFER_SIZE - limit);
            if (n <= 0) {
                sourceEof = true;
                break;
            }
            bytesRead += n;
            limit += n;
        }
        return limit > 0;
    }

    /**
     * Drops the whole bytes of lookahead from the bit buffer, keeping only the
     * bits of the partially consumed byte; adjusts {@link #bytesRead} accordingly.
     */
    private void dropLookaheadBytes()
    {
        int lookaheadBytes = bitCount >>> 3;
        bytesRead -= lookaheadBytes;
        bitCount -= lookaheadBytes << 3;
        bitBuffer = bitCount == 0 ? 0 : bitBuffer & (-1L << (64 - bitCount));
    }

    /**
     * Repositions a source with mark/reset to the first byte not consumed (see
     * the class comment). Called at the end of a bzip2 stream and after an error;
     * a no-op for other sources, after a failed read (the source is then at its
     * end) and when called again.
     */
    void repositionSource()
            throws IOException
    {
        if (!repositionable || exhausted || repositioned || limit == 0) {
            return;
        }
        repositioned = true;
        in.reset();
        in.skipNBytes(pos - (bitCount >>> 3));
        bytesRead -= limit - pos;
        limit = 0;
        pos = 0;
        dropLookaheadBytes();
    }

    /**
     * Reads {@code n} bits (1..32), most significant bit first, refilling lazily.
     *
     * @throws IOException if the stream ends before {@code n} bits are available
     */
    int readBits(int n)
            throws IOException
    {
        if (bitCount < n) {
            fillLazy(n);
            if (bitCount < n) {
                exhausted = true;
                throw new IOException("unexpected end of stream");
            }
        }
        int value = (int) (bitBuffer >>> (64 - n));
        bitBuffer <<= n;
        bitCount -= n;
        return value;
    }

    /**
     * Reads {@code n} bits (1..32) inside a block body, refilling greedily.
     *
     * @throws IOException if the stream ends before {@code n} bits are available
     */
    int readBitsInBlock(int n)
            throws IOException
    {
        if (bitCount < n) {
            fill();
            if (bitCount < n) {
                exhausted = true;
                throw new IOException("unexpected end of stream");
            }
        }
        int value = (int) (bitBuffer >>> (64 - n));
        bitBuffer <<= n;
        bitCount -= n;
        return value;
    }

    /**
     * Reads a unary code inside a block body: the number of 1 bits before the
     * next 0 bit (which is consumed as well).
     *
     * @throws IOException if the stream ends before a 0 bit is found
     */
    int readUnaryInBlock()
            throws IOException
    {
        int ones = 0;
        while (true) {
            if (bitCount < 8) {
                fill();
                if (bitCount == 0) {
                    exhausted = true;
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
     * Reads one byte (8 bits at the current bit position), refilling lazily.
     *
     * @return the byte, or -1 if the stream ends before 8 bits are available
     */
    int readByteOrEof()
            throws IOException
    {
        if (bitCount < 8) {
            fillLazy(8);
            if (bitCount < 8) {
                exhausted = true;
                return -1;
            }
        }
        int value = (int) (bitBuffer >>> 56);
        bitBuffer <<= 8;
        bitCount -= 8;
        return value;
    }
}
