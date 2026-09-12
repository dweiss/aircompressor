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

import java.lang.foreign.MemorySegment;
import java.util.Locale;

import static java.lang.Math.addExact;
import static java.lang.Math.toIntExact;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.String.format;
import static java.lang.ref.Reference.reachabilityFence;
import static java.util.Objects.requireNonNull;

public final class Lz4JavaDecompressor
        implements Lz4Decompressor
{
    @Override
    public int decompress(byte[] input, int inputOffset, int inputLength, byte[] output, int outputOffset, int maxOutputLength)
            throws MalformedInputException
    {
        verifyRange(input, inputOffset, inputLength);
        verifyRange(output, outputOffset, maxOutputLength);

        long inputAddress = inputOffset;
        long inputLimit = inputAddress + inputLength;
        long outputAddress = outputOffset;
        long outputLimit = outputAddress + maxOutputLength;

        return Lz4RawDecompressor.decompress(input, inputAddress, inputLimit, output, outputAddress, outputLimit);
    }

    @Override
    public int decompress(MemorySegment input, MemorySegment output)
    {
        try {
            byte[] inputBase = Mem.heapArray(input);
            long inputAddress = 0L;
            if (inputBase != null) {
                inputAddress = input.address();
            }
            else {
                inputBase = input.toArray(JAVA_BYTE);
            }
            long inputLimit = addExact(inputAddress, input.byteSize());

            byte[] outputBase = Mem.heapArray(output);
            long outputAddress = 0L;
            boolean copyOutput = outputBase == null;
            if (copyOutput) {
                outputBase = new byte[toIntExact(output.byteSize())];
            }
            else {
                outputAddress = output.address();
            }
            long outputLimit = addExact(outputAddress, output.byteSize());

            int written = Lz4RawDecompressor.decompress(
                    inputBase,
                    inputAddress,
                    inputLimit,
                    outputBase,
                    outputAddress,
                    outputLimit);
            if (copyOutput) {
                MemorySegment.copy(MemorySegment.ofArray(outputBase), 0, output, 0, written);
            }
            return written;
        }
        finally {
            reachabilityFence(input);
            reachabilityFence(output);
        }
    }

    private static void verifyRange(byte[] data, int offset, int length)
    {
        requireNonNull(data, "data is null");
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException(format(Locale.ROOT, "Invalid offset or length (%s, %s) in array of length %s", offset, length, data.length));
        }
    }
}
