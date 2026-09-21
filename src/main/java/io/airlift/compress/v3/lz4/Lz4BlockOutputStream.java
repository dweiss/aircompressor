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
package io.airlift.compress.v3.lz4;

import io.airlift.compress.v3.xxhash.XxHash32Hasher;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * A block-compressed output stream in the format of lz4-java's {@code LZ4BlockOutputStream} (magic,
 * token, lengths, xxHash32 checksum, then the block in raw or LZ4 block format), with its default
 * checksum seed. Streams written by lz4-java can be read by {@link Lz4BlockInputStream} and vice
 * versa.
 *
 * <p>The underlying stream is only written to with bulk {@code write(byte[], int, int)} calls.
 * This class is not thread-safe.
 */
public final class Lz4BlockOutputStream
        extends OutputStream
{
    public static final int DEFAULT_BLOCK_SIZE = 64 * 1024;

    static final byte[] MAGIC = {'L', 'Z', '4', 'B', 'l', 'o', 'c', 'k'};
    static final int MAGIC_LENGTH = MAGIC.length;
    static final int HEADER_LENGTH = MAGIC_LENGTH + 1 + 4 + 4 + 4;
    static final int COMPRESSION_LEVEL_BASE = 10;
    static final int MIN_BLOCK_SIZE = 64;
    static final int MAX_BLOCK_SIZE = 1 << (COMPRESSION_LEVEL_BASE + 0x0F);
    static final int COMPRESSION_METHOD_RAW = 0x10;
    static final int COMPRESSION_METHOD_LZ4 = 0x20;
    static final int CHECKSUM_SEED = 0x9747b28c;

    /** lz4-java stores the xxHash32 checksum masked to 28 bits (Checksum.getValue() quirk). */
    static final int CHECKSUM_MASK = 0x0FFFFFFF;

    private final OutputStream out;
    private final int compressionLevel;
    private final byte[] buffer;
    private int count;

    /** Header followed by the compressed (or raw) block. */
    private byte[] compressedBuffer;

    private final Lz4JavaCompressor compressor = new Lz4JavaCompressor();
    private boolean finished;

    public Lz4BlockOutputStream(OutputStream out)
    {
        this(out, DEFAULT_BLOCK_SIZE);
    }

    public Lz4BlockOutputStream(OutputStream out, int blockSize)
    {
        this.out = requireNonNull(out, "out is null");
        this.compressionLevel = compressionLevel(blockSize);
        this.buffer = new byte[blockSize];
        // LZ4 worst case is slightly larger than the input; grown on demand.
        this.compressedBuffer = new byte[HEADER_LENGTH + compressor.maxCompressedLength(blockSize)];
        System.arraycopy(MAGIC, 0, compressedBuffer, 0, MAGIC_LENGTH);
    }

    static int compressionLevel(int blockSize)
    {
        if (blockSize < MIN_BLOCK_SIZE || blockSize > MAX_BLOCK_SIZE) {
            throw new IllegalArgumentException("Block size must be in [" + MIN_BLOCK_SIZE + ", " + MAX_BLOCK_SIZE + "]: " + blockSize);
        }
        int level = 32 - Integer.numberOfLeadingZeros(blockSize - 1); // ceil of log2
        return Math.max(0, level - COMPRESSION_LEVEL_BASE);
    }

    @Override
    public void write(int b)
            throws IOException
    {
        ensureOpen();
        if (count == buffer.length) {
            flushBlock();
        }
        buffer[count++] = (byte) b;
    }

    @Override
    public void write(byte[] b, int off, int len)
            throws IOException
    {
        ensureOpen();
        Objects.checkFromIndexSize(off, len, b.length);
        while (len > 0) {
            if (count == buffer.length) {
                flushBlock();
            }
            int chunk = Math.min(len, buffer.length - count);
            System.arraycopy(b, off, buffer, count, chunk);
            count += chunk;
            off += chunk;
            len -= chunk;
        }
    }

    private void flushBlock()
            throws IOException
    {
        if (count == 0) {
            return;
        }
        int originalLength = count;
        int check = XxHash32Hasher.hash(buffer, 0, originalLength, CHECKSUM_SEED) & CHECKSUM_MASK;

        int compressedLength = compressor.compress(
                        buffer, 0, originalLength, compressedBuffer, HEADER_LENGTH, compressedBuffer.length - HEADER_LENGTH);
        int compressionMethod = COMPRESSION_METHOD_LZ4;
        if (compressedLength >= originalLength) {
            // Incompressible: store the block raw, as lz4-java does.
            compressedLength = originalLength;
            compressionMethod = COMPRESSION_METHOD_RAW;
        }
        if (compressionMethod == COMPRESSION_METHOD_RAW) {
            System.arraycopy(buffer, 0, compressedBuffer, HEADER_LENGTH, originalLength);
        }

        compressedBuffer[MAGIC_LENGTH] = (byte) (compressionMethod | compressionLevel);
        writeIntLE(compressedLength, compressedBuffer, MAGIC_LENGTH + 1);
        writeIntLE(originalLength, compressedBuffer, MAGIC_LENGTH + 5);
        writeIntLE(check, compressedBuffer, MAGIC_LENGTH + 9);
        out.write(compressedBuffer, 0, HEADER_LENGTH + compressedLength);
        count = 0;
    }

    /**
     * Writes any buffered data and the end-of-stream marker (an empty block), then flushes the
     * underlying stream. The underlying stream is left open and may be written to afterwards.
     */
    public void finish()
            throws IOException
    {
        if (!finished) {
            flushBlock();
            compressedBuffer[MAGIC_LENGTH] = (byte) (COMPRESSION_METHOD_RAW | compressionLevel);
            writeIntLE(0, compressedBuffer, MAGIC_LENGTH + 1);
            writeIntLE(0, compressedBuffer, MAGIC_LENGTH + 5);
            writeIntLE(0, compressedBuffer, MAGIC_LENGTH + 9);
            out.write(compressedBuffer, 0, HEADER_LENGTH);
            finished = true;
            out.flush();
        }
    }

    @Override
    public void flush()
            throws IOException
    {
        ensureOpen();
        flushBlock();
        out.flush();
    }

    /** Finishes the compressed stream and closes the underlying stream. */
    @Override
    public void close()
            throws IOException
    {
        try {
            finish();
        }
        finally
        {
            out.close();
        }
    }

    private void ensureOpen()
            throws IOException
    {
        if (finished) {
            throw new IOException("Stream already finished.");
        }
    }

    static void writeIntLE(int value, byte[] buf, int off)
    {
        buf[off] = (byte) value;
        buf[off + 1] = (byte) (value >>> 8);
        buf[off + 2] = (byte) (value >>> 16);
        buf[off + 3] = (byte) (value >>> 24);
    }

    static int readIntLE(byte[] buf, int off)
    {
        return (buf[off] & 0xFF)
                | ((buf[off + 1] & 0xFF) << 8)
                | ((buf[off + 2] & 0xFF) << 16)
                | ((buf[off + 3] & 0xFF) << 24);
    }
}
