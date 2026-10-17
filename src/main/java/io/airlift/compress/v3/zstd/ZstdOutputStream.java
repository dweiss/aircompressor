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

import io.airlift.compress.v3.Implementation;

import java.io.IOException;
import java.io.OutputStream;

import static io.airlift.compress.v3.zstd.CompressionParameters.DEFAULT_COMPRESSION_LEVEL;
import static java.util.Objects.requireNonNull;

/**
 * Compresses to the Zstandard frame format, with the Java encoder or the system's native library.
 * <p>
 * Which one is decided per stream, by the {@link Implementation} argument and the
 * {@code io.airlift.compress.v3.zstd.implementation} system property; see {@link ZstdCompressor#create()}.
 * The Java encoder implements levels 3 to 12, the native library every level zstd knows.
 */
public class ZstdOutputStream
        extends OutputStream
{
    private final OutputStream delegate;
    private final boolean nativeImplementation;

    /**
     * Creates a stream compressing at the {@linkplain CompressionParameters#DEFAULT_COMPRESSION_LEVEL default compression level},
     * with the native library if available ({@link Implementation#AUTO}).
     */
    public ZstdOutputStream(OutputStream outputStream)
            throws IOException
    {
        this(outputStream, DEFAULT_COMPRESSION_LEVEL, Implementation.AUTO);
    }

    /**
     * Creates a stream compressing at the given compression level, with the native library if available
     * ({@link Implementation#AUTO}).
     *
     * @param compressionLevel a level between 1 and {@value CompressionParameters#MAX_COMPRESSION_LEVEL}
     * (0 selects the default level, {@value CompressionParameters#DEFAULT_COMPRESSION_LEVEL}). The Java
     * encoder accepts only levels whose match-finding strategy it implements, 3 to 12, and throws
     * {@link IllegalArgumentException} for the others; the native library accepts every level.
     */
    public ZstdOutputStream(OutputStream outputStream, int compressionLevel)
            throws IOException
    {
        this(outputStream, compressionLevel, Implementation.AUTO);
    }

    /**
     * Creates a stream compressing at the given compression level with the given implementation.
     *
     * @throws IllegalStateException for {@link Implementation#NATIVE} when the native library cannot be used
     */
    public ZstdOutputStream(OutputStream outputStream, int compressionLevel, Implementation implementation)
            throws IOException
    {
        requireNonNull(outputStream, "outputStream is null");
        requireNonNull(implementation, "implementation is null");
        this.nativeImplementation = ZstdNative.useNative(implementation);
        this.delegate = nativeImplementation
                ? new ZstdNativeOutputStream(outputStream, compressionLevel)
                : new ZstdJavaOutputStream(outputStream, compressionLevel);
    }

    /** Whether this stream compresses with the native library. */
    public boolean isNative()
    {
        return nativeImplementation;
    }

    @Override
    public void write(int b)
            throws IOException
    {
        delegate.write(b);
    }

    @Override
    public void write(byte[] buffer, int offset, int length)
            throws IOException
    {
        delegate.write(buffer, offset, length);
    }

    @Override
    public void flush()
            throws IOException
    {
        delegate.flush();
    }

    @Override
    public void close()
            throws IOException
    {
        delegate.close();
    }
}
