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
package io.airlift.compress.v3.thirdparty;

import io.airlift.compress.v3.Compressor;
import org.anarres.lzo.LzoAlgorithm;
import org.anarres.lzo.LzoLibrary;
import org.anarres.lzo.LzoTransformer;
import org.anarres.lzo.lzo_uintp;

import java.lang.foreign.MemorySegment;

/** Raw LZO1X blocks from the independent lzo-core implementation, as a reference for the LZO tests. */
public class AnarresLzoCompressor
        implements Compressor
{
    private final org.anarres.lzo.LzoCompressor compressor = LzoLibrary.getInstance().newCompressor(LzoAlgorithm.LZO1X, null);

    @Override
    public int maxCompressedLength(int uncompressedSize)
    {
        return uncompressedSize + (uncompressedSize / 16) + 64 + 3;
    }

    @Override
    public int compress(byte[] input, int inputOffset, int inputLength, byte[] output, int outputOffset, int maxOutputLength)
    {
        if (inputLength == 0) {
            return 0; // airlift's LZO (like Hadoop's) has no end marker for empty input; lzo-core emits one
        }
        lzo_uintp outputLength = new lzo_uintp(maxOutputLength);
        int code = compressor.compress(input, inputOffset, inputLength, output, outputOffset, outputLength);
        if (code != LzoTransformer.LZO_E_OK) {
            throw new RuntimeException("LZO compression failed: " + compressor.toErrorString(code));
        }
        return outputLength.value;
    }

    @Override
    public int compress(MemorySegment input, MemorySegment output)
    {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
