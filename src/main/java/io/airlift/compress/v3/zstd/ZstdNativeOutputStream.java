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

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.lang.ref.Cleaner.Cleanable;

import static io.airlift.compress.v3.zstd.Util.checkPositionIndexes;
import static io.airlift.compress.v3.zstd.ZstdNative.COMPRESS_STREAM_OUTPUT_SIZE;
import static io.airlift.compress.v3.zstd.ZstdNative.ZSTD_E_CONTINUE;
import static io.airlift.compress.v3.zstd.ZstdNative.ZSTD_E_END;
import static io.airlift.compress.v3.zstd.ZstdNative.ZSTD_E_FLUSH;
import static java.util.Objects.requireNonNull;

/**
 * Compressing stream on the native library's streaming API; {@link ZstdOutputStream} is the public entry point.
 * <p>
 * Compression works directly from the caller's buffer; the only buffer here is the one the compressed
 * data is produced into before it goes to the underlying stream. The native compression context lives
 * in native memory, freed on {@link #close()} and, as a safety net, by a {@link Cleaner}.
 * <p>
 * Not thread-safe for concurrent use.
 */
final class ZstdNativeOutputStream
        extends OutputStream
{
    private static final Cleaner CLEANER = Cleaner.create();

    private final OutputStream outputStream;
    private final Cleanable cleanable;
    private final MemorySegment compressStream;

    private final byte[] outputBuffer;
    private final MemorySegment outputSegment;

    // positions, passed to the native code as one-element arrays
    private final long[] inputPosition = new long[1];
    private final long[] outputPosition = new long[1];
    private final MemorySegment inputPositionSegment = MemorySegment.ofArray(inputPosition);
    private final MemorySegment outputPositionSegment = MemorySegment.ofArray(outputPosition);

    private boolean closed;

    ZstdNativeOutputStream(OutputStream outputStream, int compressionLevel)
    {
        ZstdNative.verifyEnabled();
        this.outputStream = requireNonNull(outputStream, "outputStream is null");

        NativeResources resources = new NativeResources();
        this.cleanable = CLEANER.register(this, resources);

        this.outputBuffer = new byte[COMPRESS_STREAM_OUTPUT_SIZE];
        this.outputSegment = MemorySegment.ofArray(outputBuffer);

        try {
            MemorySegment stream = ZstdNative.createCompressStream();
            if (stream.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("Failed to create zstd compression stream");
            }
            // freed when the arena closes
            this.compressStream = stream.reinterpret(resources.arena(), ZstdNative::freeCompressStream);
            ZstdNative.initCompressStream(compressStream, compressionLevel);
        }
        catch (Throwable e) {
            cleanable.clean();
            throw e;
        }
    }

    @Override
    public void write(int b)
            throws IOException
    {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] buffer, int offset, int length)
            throws IOException
    {
        if (closed) {
            throw new IOException("Stream is closed");
        }
        requireNonNull(buffer, "buffer is null");
        checkPositionIndexes(offset, offset + length, buffer.length);

        MemorySegment inputSegment = MemorySegment.ofArray(buffer);
        int position = offset;
        int limit = offset + length;
        while (position < limit) {
            inputPosition[0] = position;
            outputPosition[0] = 0;
            ZstdNative.compressStream(compressStream, outputSegment, outputBuffer.length, outputPositionSegment, inputSegment, limit, inputPositionSegment, ZSTD_E_CONTINUE);
            position = (int) inputPosition[0];
            int produced = (int) outputPosition[0];
            if (produced > 0) {
                outputStream.write(outputBuffer, 0, produced);
            }
        }
    }

    @Override
    public void flush()
            throws IOException
    {
        if (closed) {
            return;
        }
        drain(ZSTD_E_FLUSH);
        outputStream.flush();
    }

    private void drain(int endDirective)
            throws IOException
    {
        MemorySegment noInput = MemorySegment.ofArray(new byte[0]);
        long remaining;
        do {
            inputPosition[0] = 0;
            outputPosition[0] = 0;
            remaining = ZstdNative.compressStream(compressStream, outputSegment, outputBuffer.length, outputPositionSegment, noInput, 0, inputPositionSegment, endDirective);
            int produced = (int) outputPosition[0];
            if (produced > 0) {
                outputStream.write(outputBuffer, 0, produced);
            }
        }
        while (remaining > 0);
    }

    @Override
    public void close()
            throws IOException
    {
        if (closed) {
            return;
        }
        try {
            drain(ZSTD_E_END);
        }
        finally {
            closed = true;
            cleanable.clean();
            outputStream.close();
        }
    }

    private record NativeResources(Arena arena)
            implements Runnable
    {
        NativeResources()
        {
            this(Arena.ofShared());
        }

        @Override
        public void run()
        {
            arena.close();
        }
    }
}
