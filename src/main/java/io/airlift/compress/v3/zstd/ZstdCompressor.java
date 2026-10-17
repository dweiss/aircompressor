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

import io.airlift.compress.v3.Compressor;
import io.airlift.compress.v3.Implementation;

import java.lang.foreign.MemorySegment;

public interface ZstdCompressor
        extends Compressor
{
    int compress(MemorySegment input, MemorySegment output);

    /**
     * The native compressor (the system's libzstd through the foreign function API) when it is available
     * and enabled, otherwise the Java one; see {@code io.airlift.compress.v3.zstd.implementation} in the README.
     */
    static ZstdCompressor create()
    {
        return create(Implementation.AUTO);
    }

    /**
     * Like {@link #create()}, at the given compression level. The Java compressor accepts levels 3 to 12
     * only, the native one every level zstd knows.
     */
    static ZstdCompressor create(int compressionLevel)
    {
        return create(compressionLevel, Implementation.AUTO);
    }

    /**
     * The given implementation at the default compression level.
     *
     * @throws IllegalStateException for {@link Implementation#NATIVE} when the native library cannot be used
     */
    static ZstdCompressor create(Implementation implementation)
    {
        return ZstdNative.useNative(implementation) ? new ZstdNativeCompressor() : new ZstdJavaCompressor();
    }

    /**
     * The given implementation at the given compression level.
     *
     * @throws IllegalStateException for {@link Implementation#NATIVE} when the native library cannot be used
     */
    static ZstdCompressor create(int compressionLevel, Implementation implementation)
    {
        return ZstdNative.useNative(implementation) ? new ZstdNativeCompressor(compressionLevel) : new ZstdJavaCompressor(compressionLevel);
    }
}
