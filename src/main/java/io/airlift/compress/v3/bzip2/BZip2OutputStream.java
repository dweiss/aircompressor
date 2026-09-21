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
import java.io.OutputStream;

import static java.util.Objects.requireNonNull;

/**
 * An output stream that compresses into the BZip2 format, including the {@code "BZ"} magic at the
 * start of the data, so that the result is a complete {@code .bz2} stream.
 *
 * <p>The compression requires large amounts of memory (about 8 bytes per byte of the block size),
 * so {@link #close()} or {@link #finish()} the stream as soon as possible. This class is not thread
 * safe.
 */
public final class BZip2OutputStream
        extends OutputStream
{
    /** The smallest block size, in units of 100 kB. */
    public static final int MIN_BLOCK_SIZE = 1;

    /** The largest (and default) block size, in units of 100 kB. */
    public static final int MAX_BLOCK_SIZE = 9;

    private final OutputStream out;
    private final CBZip2OutputStream compressor;

    /**
     * Creates a stream that compresses with the largest block size (900 kB).
     *
     * @throws IOException if writing the stream header to {@code out} fails
     */
    public BZip2OutputStream(OutputStream out)
            throws IOException
    {
        this(out, MAX_BLOCK_SIZE);
    }

    /**
     * @param blockSize the block size in units of 100 kB, from {@link #MIN_BLOCK_SIZE} to
     * {@link #MAX_BLOCK_SIZE}; larger blocks compress better and need more memory, for compression
     * and for decompression
     * @throws IOException if writing the stream header to {@code out} fails
     * @throws IllegalArgumentException if the block size is out of range
     */
    public BZip2OutputStream(OutputStream out, int blockSize)
            throws IOException
    {
        this.out = requireNonNull(out, "out is null");
        if (blockSize < MIN_BLOCK_SIZE || blockSize > MAX_BLOCK_SIZE) {
            throw new IllegalArgumentException("blockSize must be in [" + MIN_BLOCK_SIZE + ", " + MAX_BLOCK_SIZE + "]: " + blockSize);
        }
        out.write(new byte[] {'B', 'Z'});
        this.compressor = new CBZip2OutputStream(out, blockSize);
    }

    @Override
    public void write(int b)
            throws IOException
    {
        compressor.write(b);
    }

    @Override
    public void write(byte[] buffer, int offset, int length)
            throws IOException
    {
        compressor.write(buffer, offset, length);
    }

    /**
     * Flushes the underlying stream. Data of the current block stays buffered until the block is
     * full or the stream is finished.
     */
    @Override
    public void flush()
            throws IOException
    {
        out.flush();
    }

    /**
     * Writes the buffered data and the end of the bzip2 stream; the underlying stream is left open.
     */
    public void finish()
            throws IOException
    {
        compressor.finish();
    }

    /** Finishes the bzip2 stream and closes the underlying stream. */
    @Override
    public void close()
            throws IOException
    {
        try {
            compressor.finish();
        }
        finally {
            out.close();
        }
    }
}
