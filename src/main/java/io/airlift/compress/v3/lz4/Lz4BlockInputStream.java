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

import io.airlift.compress.v3.MalformedInputException;
import io.airlift.compress.v3.xxhash.XxHash32Hasher;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

import static io.airlift.compress.v3.lz4.Lz4BlockOutputStream.CHECKSUM_MASK;
import static io.airlift.compress.v3.lz4.Lz4BlockOutputStream.CHECKSUM_SEED;
import static io.airlift.compress.v3.lz4.Lz4BlockOutputStream.COMPRESSION_LEVEL_BASE;
import static io.airlift.compress.v3.lz4.Lz4BlockOutputStream.COMPRESSION_METHOD_LZ4;
import static io.airlift.compress.v3.lz4.Lz4BlockOutputStream.COMPRESSION_METHOD_RAW;
import static io.airlift.compress.v3.lz4.Lz4BlockOutputStream.HEADER_LENGTH;
import static io.airlift.compress.v3.lz4.Lz4BlockOutputStream.MAGIC;
import static io.airlift.compress.v3.lz4.Lz4BlockOutputStream.MAGIC_LENGTH;
import static io.airlift.compress.v3.lz4.Lz4BlockOutputStream.readIntLE;
import static java.util.Objects.requireNonNull;

/**
 * Reads block streams written by {@link Lz4BlockOutputStream} (or lz4-java's {@code
 * LZ4BlockOutputStream} with its default checksum). Reading stops at the end-of-stream marker (an
 * empty block); the underlying stream is not read past it and is only accessed with bulk reads.
 * This class is not thread-safe.
 */
public final class Lz4BlockInputStream
        extends InputStream
{
    private final InputStream in;
    private final Lz4JavaDecompressor decompressor = new Lz4JavaDecompressor();

    private byte[] compressedBuffer = new byte[HEADER_LENGTH];
    private byte[] buffer = new byte[0];
    private int position;
    private int originalLength;
    private boolean finished;

    public Lz4BlockInputStream(InputStream in)
    {
        this.in = requireNonNull(in, "in is null");
    }

    @Override
    public int read()
            throws IOException
    {
        if (position == originalLength && !refill()) {
            return -1;
        }
        return buffer[position++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len)
            throws IOException
    {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }
        if (position == originalLength && !refill()) {
            return -1;
        }
        int chunk = Math.min(len, originalLength - position);
        System.arraycopy(buffer, position, b, off, chunk);
        position += chunk;
        return chunk;
    }

    @Override
    public int available()
    {
        return originalLength - position;
    }

    /** Reads the next block into {@link #buffer}; returns false at the end-of-stream marker. */
    private boolean refill()
            throws IOException
    {
        if (finished) {
            return false;
        }
        readFully(compressedBuffer, HEADER_LENGTH);
        if (!Arrays.equals(compressedBuffer, 0, MAGIC_LENGTH, MAGIC, 0, MAGIC_LENGTH)) {
            throw new IOException("LZ4 block stream is corrupted (bad magic).");
        }
        int token = compressedBuffer[MAGIC_LENGTH] & 0xFF;
        int compressionMethod = token & 0xF0;
        int compressionLevel = COMPRESSION_LEVEL_BASE + (token & 0x0F);
        if (compressionMethod != COMPRESSION_METHOD_RAW
                && compressionMethod != COMPRESSION_METHOD_LZ4) {
            throw new IOException("LZ4 block stream is corrupted (bad compression method).");
        }
        int compressedLength = readIntLE(compressedBuffer, MAGIC_LENGTH + 1);
        originalLength = readIntLE(compressedBuffer, MAGIC_LENGTH + 5);
        int check = readIntLE(compressedBuffer, MAGIC_LENGTH + 9);
        if (originalLength > 1 << compressionLevel
                || originalLength < 0
                || compressedLength < 0
                || (originalLength == 0 && compressedLength != 0)
                || (originalLength != 0 && compressedLength == 0)
                || (compressionMethod == COMPRESSION_METHOD_RAW && originalLength != compressedLength)
                || (compressionMethod == COMPRESSION_METHOD_LZ4 && compressedLength >= originalLength)) {
            throw new IOException("LZ4 block stream is corrupted (bad block lengths).");
        }

        if (originalLength == 0) {
            if (check != 0) {
                throw new IOException("LZ4 block stream is corrupted (bad end-of-stream marker).");
            }
            finished = true;
            position = 0;
            return false;
        }

        buffer = grow(buffer, originalLength);
        if (compressionMethod == COMPRESSION_METHOD_RAW) {
            readFully(buffer, originalLength);
        }
        else {
            compressedBuffer = grow(compressedBuffer, compressedLength);
            readFully(compressedBuffer, compressedLength);
            int decompressed;
            try {
                decompressed = decompressor.decompress(
                                compressedBuffer, 0, compressedLength, buffer, 0, originalLength);
            }
            catch (MalformedInputException e) {
                throw new IOException("LZ4 block stream is corrupted (bad block contents).", e);
            }
            if (decompressed != originalLength) {
                throw new IOException("LZ4 block stream is corrupted (bad block contents).");
            }
        }

        if ((XxHash32Hasher.hash(buffer, 0, originalLength, CHECKSUM_SEED) & CHECKSUM_MASK) != check) {
            throw new IOException("LZ4 block stream is corrupted (checksum mismatch).");
        }
        position = 0;
        return true;
    }

    private void readFully(byte[] b, int len)
            throws IOException
    {
        int read = 0;
        while (read < len) {
            int r = in.read(b, read, len - read);
            if (r < 0) {
                throw new EOFException("LZ4 block stream ended prematurely.");
            }
            read += r;
        }
    }

    @Override
    public void close()
            throws IOException
    {
        in.close();
    }

    /** Returns an array of at least the given size; contents are not preserved. */
    private static byte[] grow(byte[] array, int minSize)
    {
        return array.length >= minSize ? array : new byte[minSize];
    }
}
