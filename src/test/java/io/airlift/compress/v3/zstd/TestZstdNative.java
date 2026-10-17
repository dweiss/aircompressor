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
import io.airlift.compress.v3.Decompressor;
import io.airlift.compress.v3.thirdparty.ZstdJniCompressor;
import io.airlift.compress.v3.thirdparty.ZstdJniDecompressor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;

/** The system libzstd through the foreign function API, against zstd-jni. Runs where libzstd is installed. */
@EnabledIf("isNativeEnabled")
public class TestZstdNative
        extends AbstractTestZstd
{
    static boolean isNativeEnabled()
    {
        return ZstdNativeCompressor.isEnabled();
    }

    @Override
    protected ZstdCompressor getCompressor()
    {
        return new ZstdNativeCompressor();
    }

    @Override
    protected ZstdDecompressor getDecompressor()
    {
        return new ZstdNativeDecompressor();
    }

    @Override
    protected Compressor getVerifyCompressor()
    {
        return new ZstdJniCompressor(3);
    }

    @Override
    protected Decompressor getVerifyDecompressor()
    {
        return new ZstdJniDecompressor();
    }

    @Test
    void testFactoriesPickNative()
    {
        assertThat(ZstdCompressor.create()).isInstanceOf(ZstdNativeCompressor.class);
        assertThat(ZstdCompressor.create(19)).isInstanceOf(ZstdNativeCompressor.class);
        assertThat(ZstdDecompressor.create()).isInstanceOf(ZstdNativeDecompressor.class);
        assertThat(ZstdNative.version()).isPresent().get().asString().startsWith("1.");
    }

    @Test
    void testAllLevelsRoundTrip()
    {
        byte[] input = "the native library accepts every level zstd knows, including the negative ones. ".repeat(2000).getBytes();
        ZstdJavaDecompressor javaDecompressor = new ZstdJavaDecompressor();
        for (int level : new int[] {-5, 1, 3, 9, 19, 22}) {
            ZstdCompressor compressor = new ZstdNativeCompressor(level);
            byte[] compressed = new byte[compressor.maxCompressedLength(input.length)];
            int compressedSize = compressor.compress(input, 0, input.length, compressed, 0, compressed.length);
            byte[] decompressed = new byte[input.length];
            assertThat(javaDecompressor.decompress(compressed, 0, compressedSize, decompressed, 0, decompressed.length)).isEqualTo(input.length);
            assertThat(decompressed).isEqualTo(input);
        }
    }
}
