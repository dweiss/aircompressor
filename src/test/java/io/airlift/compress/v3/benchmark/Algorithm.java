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
package io.airlift.compress.v3.benchmark;

import io.airlift.compress.v3.Compressor;
import io.airlift.compress.v3.Decompressor;
import io.airlift.compress.v3.deflate.DeflateJavaCompressor;
import io.airlift.compress.v3.deflate.DeflateJavaDecompressor;
import io.airlift.compress.v3.lz4.Lz4FrameJavaCompressor;
import io.airlift.compress.v3.lz4.Lz4FrameJavaDecompressor;
import io.airlift.compress.v3.lz4.Lz4JavaCompressor;
import io.airlift.compress.v3.lz4.Lz4JavaDecompressor;
import io.airlift.compress.v3.lzo.LzoCompressor;
import io.airlift.compress.v3.lzo.LzoDecompressor;
import io.airlift.compress.v3.snappy.SnappyJavaCompressor;
import io.airlift.compress.v3.snappy.SnappyJavaDecompressor;
import io.airlift.compress.v3.thirdparty.JPountzLz4Compressor;
import io.airlift.compress.v3.thirdparty.JPountzLz4Decompressor;
import io.airlift.compress.v3.thirdparty.JdkDeflateCompressor;
import io.airlift.compress.v3.thirdparty.JdkInflateDecompressor;
import io.airlift.compress.v3.thirdparty.XerialSnappyCompressor;
import io.airlift.compress.v3.thirdparty.XerialSnappyDecompressor;
import io.airlift.compress.v3.thirdparty.ZstdJniCompressor;
import io.airlift.compress.v3.thirdparty.ZstdJniDecompressor;
import io.airlift.compress.v3.zstd.ZstdJavaCompressor;
import io.airlift.compress.v3.zstd.ZstdJavaDecompressor;
import net.jpountz.lz4.LZ4Factory;

public enum Algorithm
{
    airlift_lz4(new Lz4JavaDecompressor(), new Lz4JavaCompressor()),
    airlift_lz4_frame(new Lz4FrameJavaDecompressor(), new Lz4FrameJavaCompressor()),
    airlift_snappy(new SnappyJavaDecompressor(), new SnappyJavaCompressor()),
    airlift_lzo(new LzoDecompressor(), new LzoCompressor()),
    airlift_zstd(new ZstdJavaDecompressor(), new ZstdJavaCompressor()),
    airlift_zstd_5(new ZstdJavaDecompressor(), new ZstdJavaCompressor(5)),
    airlift_zstd_7(new ZstdJavaDecompressor(), new ZstdJavaCompressor(7)),
    airlift_zstd_9(new ZstdJavaDecompressor(), new ZstdJavaCompressor(9)),
    airlift_zstd_12(new ZstdJavaDecompressor(), new ZstdJavaCompressor(12)),
    airlift_deflate(new DeflateJavaDecompressor(), new DeflateJavaCompressor()),


    jpountz_lz4_jni(new JPountzLz4Decompressor(LZ4Factory.nativeInstance()), new JPountzLz4Compressor(LZ4Factory.nativeInstance())),
    jpountz_lz4_safe(new JPountzLz4Decompressor(LZ4Factory.safeInstance()), new JPountzLz4Compressor(LZ4Factory.safeInstance())),
    jpountz_lz4_unsafe(new JPountzLz4Decompressor(LZ4Factory.unsafeInstance()), new JPountzLz4Compressor(LZ4Factory.unsafeInstance())),
    xerial_snappy(new XerialSnappyDecompressor(), new XerialSnappyCompressor()),
    zstd_jni(new ZstdJniDecompressor(), new ZstdJniCompressor(3)),
    zstd_jni_5(new ZstdJniDecompressor(), new ZstdJniCompressor(5)),
    zstd_jni_7(new ZstdJniDecompressor(), new ZstdJniCompressor(7)),
    zstd_jni_9(new ZstdJniDecompressor(), new ZstdJniCompressor(9)),
    zstd_jni_12(new ZstdJniDecompressor(), new ZstdJniCompressor(12)),


    java_zip_stream(new JdkInflateDecompressor(), new JdkDeflateCompressor());

    private final Decompressor decompressor;
    private final Compressor compressor;

    Algorithm(Decompressor decompressor, Compressor compressor)
    {
        this.decompressor = decompressor;
        this.compressor = compressor;
    }

    public Compressor getCompressor()
    {
        return compressor;
    }

    public Decompressor getDecompressor()
    {
        return decompressor;
    }
}
