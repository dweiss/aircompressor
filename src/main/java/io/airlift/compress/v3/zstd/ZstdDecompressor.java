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

import io.airlift.compress.v3.Decompressor;
import io.airlift.compress.v3.Implementation;

public interface ZstdDecompressor
        extends Decompressor
{
    long getDecompressedSize(byte[] input, int offset, int length);

    /** The native decompressor when the system's libzstd is available and enabled, otherwise the Java one; see {@link ZstdCompressor#create()}. */
    static ZstdDecompressor create()
    {
        return create(Implementation.AUTO);
    }

    /**
     * The given implementation.
     *
     * @throws IllegalStateException for {@link Implementation#NATIVE} when the native library cannot be used
     */
    static ZstdDecompressor create(Implementation implementation)
    {
        return ZstdNative.useNative(implementation) ? new ZstdNativeDecompressor() : new ZstdJavaDecompressor();
    }
}
