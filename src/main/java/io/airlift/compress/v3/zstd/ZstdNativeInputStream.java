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

import io.airlift.compress.v3.MalformedInputException;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.lang.ref.Cleaner.Cleanable;

import static io.airlift.compress.v3.zstd.Util.checkPositionIndexes;
import static io.airlift.compress.v3.zstd.ZstdNative.DECOMPRESS_STREAM_INPUT_SIZE;
import static java.util.Objects.requireNonNull;

/**
 * Decompressing stream on the native library's streaming API; {@link ZstdInputStream} is the public entry point.
 * <p>
 * Decompression works directly into the caller's buffer; the only buffer here is the one the compressed
 * data is read into from the underlying stream. Concatenated frames are decompressed one after the other.
 * The native decompression context lives in native memory, freed on {@link #close()} and, as a safety net,
 * by a {@link Cleaner}.
 * <p>
 * Not thread-safe for concurrent use.
 */
final class ZstdNativeInputStream
        extends InputStream
{
    private static final Cleaner CLEANER = Cleaner.create();

    private final InputStream inputStream;
    private final Cleanable cleanable;
    private final MemorySegment decompressStream;

    private final byte[] inputBuffer;
    private final MemorySegment inputSegment;
    private int inputOffset;
    private int inputLimit;

    // positions, passed to the native code as one-element arrays
    private final long[] inputPosition = new long[1];
    private final long[] outputPosition = new long[1];
    private final MemorySegment inputPositionSegment = MemorySegment.ofArray(inputPosition);
    private final MemorySegment outputPositionSegment = MemorySegment.ofArray(outputPosition);

    private boolean closed;
    private boolean endOfInput;
    // false while in the middle of a frame: hitting the end of input then means the stream is truncated
    private boolean frameComplete = true;
    // true when the decoder has consumed its input and holds no output back, i.e. only new input can make progress
    private boolean needsInput = true;

    ZstdNativeInputStream(InputStream inputStream)
    {
        ZstdNative.verifyEnabled();
        this.inputStream = requireNonNull(inputStream, "inputStream is null");

        NativeResources resources = new NativeResources();
        this.cleanable = CLEANER.register(this, resources);

        this.inputBuffer = new byte[DECOMPRESS_STREAM_INPUT_SIZE];
        this.inputSegment = MemorySegment.ofArray(inputBuffer);

        try {
            MemorySegment stream = ZstdNative.createDecompressStream();
            if (stream.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("Failed to create zstd decompression stream");
            }
            // freed when the arena closes
            this.decompressStream = stream.reinterpret(resources.arena(), ZstdNative::freeDecompressStream);
            ZstdNative.initDecompressStream(decompressStream);
        }
        catch (Throwable e) {
            cleanable.clean();
            throw e;
        }
    }

    @Override
    public int read()
            throws IOException
    {
        byte[] single = new byte[1];
        int read = read(single, 0, 1);
        return read == -1 ? -1 : single[0] & 0xFF;
    }

    @Override
    public int read(byte[] output, int outputOffset, int outputLength)
            throws IOException
    {
        if (closed) {
            throw new IOException("Stream is closed");
        }
        requireNonNull(output, "output is null");
        checkPositionIndexes(outputOffset, outputOffset + outputLength, output.length);
        if (outputLength == 0) {
            return 0;
        }

        int total = 0;
        while (total < outputLength) {
            if (needsInput && inputOffset >= inputLimit && !fill()) {
                if (!frameComplete) {
                    throw new MalformedInputException(0, "Not enough input bytes: the zstd stream is truncated");
                }
                return total > 0 ? total : -1;
            }

            // decode with the input at hand, which may be none: that flushes output the decoder held back
            // because the previous output buffer was full
            int space = outputLength - total;
            int inputOffsetBefore = inputOffset;
            inputPosition[0] = inputOffset;
            outputPosition[0] = 0;
            MemorySegment outputSegment = MemorySegment.ofArray(output).asSlice(outputOffset + total, space);
            long hint = ZstdNative.decompressStream(decompressStream, outputSegment, space, outputPositionSegment, inputSegment, inputLimit, inputPositionSegment);
            inputOffset = (int) inputPosition[0];
            int produced = (int) outputPosition[0];
            total += produced;

            if (hint == 0) {
                // frame complete; a following frame starts a new decompression and, like the Java stream,
                // continues to fill the caller's buffer
                frameComplete = true;
                needsInput = true;
                if (inputOffset >= inputLimit && !fill()) {
                    return total > 0 ? total : -1;
                }
                ZstdNative.initDecompressStream(decompressStream);
                continue;
            }

            frameComplete = false;
            // output not filled: the decoder wants more input. Output filled: call again before reading input
            needsInput = produced < space;
            if (produced == 0 && inputOffset == inputOffsetBefore && inputOffset < inputLimit) {
                throw new IOException("Decompression made no progress");
            }
        }
        return total;
    }

    /** Reads more compressed data; false at the end of the underlying stream. */
    private boolean fill()
            throws IOException
    {
        if (endOfInput) {
            return false;
        }
        int read = inputStream.read(inputBuffer);
        if (read == -1) {
            endOfInput = true;
            return false;
        }
        inputOffset = 0;
        inputLimit = read;
        return true;
    }

    @Override
    public int available()
            throws IOException
    {
        if (closed) {
            throw new IOException("Stream is closed");
        }
        return 0;
    }

    @Override
    public void close()
            throws IOException
    {
        if (!closed) {
            closed = true;
            cleanable.clean();
            inputStream.close();
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
