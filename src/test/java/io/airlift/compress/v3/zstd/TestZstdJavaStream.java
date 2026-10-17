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
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class TestZstdJavaStream
        extends AbstractTestZstdStream
{
    @Override
    protected Implementation implementation()
    {
        return Implementation.JAVA;
    }

    @Override
    protected OutputStream createCompressingOutputStream(OutputStream out)
            throws IOException
    {
        ZstdOutputStream stream = new ZstdOutputStream(out, 3, Implementation.JAVA);
        assertThat(stream.isNative()).isFalse();
        return stream;
    }

    @Override
    protected InputStream createDecompressingInputStream(InputStream in)
            throws IOException
    {
        ZstdInputStream stream = new ZstdInputStream(in, Implementation.JAVA);
        assertThat(stream.isNative()).isFalse();
        return stream;
    }

    @Test
    void testInteropWithNativeStreams()
            throws Exception
    {
        if (!ZstdNative.isEnabled()) {
            return;
        }
        byte[] original = new byte[50000];
        new Random(111).nextBytes(original);

        byte[] compressed = compress(original);
        assertThat(nativeDecompress(compressed)).isEqualTo(original);

        compressed = nativeCompress(original);
        assertThat(decompress(compressed)).isEqualTo(original);
    }

    private static byte[] nativeCompress(byte[] original)
            throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream zstdOut = new ZstdOutputStream(out, 3, Implementation.NATIVE)) {
            zstdOut.write(original);
        }
        return out.toByteArray();
    }

    private static byte[] nativeDecompress(byte[] compressed)
            throws IOException
    {
        try (InputStream in = new ZstdInputStream(new ByteArrayInputStream(compressed), Implementation.NATIVE)) {
            return in.readAllBytes();
        }
    }
}
