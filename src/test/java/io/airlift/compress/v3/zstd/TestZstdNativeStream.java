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
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The native streaming implementation. Runs where libzstd is installed. */
@EnabledIf("isNativeEnabled")
class TestZstdNativeStream
        extends AbstractTestZstdStream
{
    static boolean isNativeEnabled()
    {
        return ZstdNative.isEnabled();
    }

    @Override
    protected Implementation implementation()
    {
        return Implementation.NATIVE;
    }

    @Override
    protected OutputStream createCompressingOutputStream(OutputStream out)
            throws IOException
    {
        ZstdOutputStream stream = new ZstdOutputStream(out, 3, Implementation.NATIVE);
        assertThat(stream.isNative()).isTrue();
        return stream;
    }

    @Override
    protected InputStream createDecompressingInputStream(InputStream in)
            throws IOException
    {
        ZstdInputStream stream = new ZstdInputStream(in, Implementation.NATIVE);
        assertThat(stream.isNative()).isTrue();
        return stream;
    }

    @Test
    void testCompressionLevels()
            throws Exception
    {
        byte[] original = "levels the Java encoder does not implement work natively. ".repeat(2000).getBytes();
        int previous = Integer.MAX_VALUE;
        for (int level : new int[] {1, 3, 12, 19}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (OutputStream zstdOut = new ZstdOutputStream(out, level, Implementation.NATIVE)) {
                zstdOut.write(original);
            }
            byte[] compressed = out.toByteArray();
            assertThat(decompress(compressed)).isEqualTo(original);
            assertThat(javaDecompress(compressed)).isEqualTo(original);
            assertThat(compressed.length).as("level %s", level).isLessThanOrEqualTo(previous);
            previous = compressed.length;
        }
    }

    @Test
    void testTruncatedStreamFails()
            throws Exception
    {
        byte[] original = new byte[10000];
        new Random(777).nextBytes(original);
        byte[] compressed = compress(original);
        byte[] truncated = Arrays.copyOf(compressed, compressed.length - 1);
        assertThatThrownBy(() -> {
            try (InputStream in = createDecompressingInputStream(new ByteArrayInputStream(truncated))) {
                in.readAllBytes();
            }
        })
                .isInstanceOf(io.airlift.compress.v3.MalformedInputException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void testSmallReadsAcrossFrames()
            throws Exception
    {
        // output buffers smaller than a block make the decoder hold data back between calls; two frames in a row
        byte[] first = new byte[70000];
        new Random(1).nextBytes(first);
        byte[] second = "second frame ".repeat(5000).getBytes();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(compress(first));
        out.write(compress(second));
        byte[] expected = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, expected, first.length, second.length);

        for (int chunk : new int[] {1, 7, 1000, 65536}) {
            try (InputStream in = createDecompressingInputStream(new ByteArrayInputStream(out.toByteArray()))) {
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                byte[] buffer = new byte[chunk];
                int read;
                while ((read = in.read(buffer, 0, buffer.length)) != -1) {
                    result.write(buffer, 0, read);
                }
                assertThat(result.toByteArray()).as("chunk %s", chunk).isEqualTo(expected);
            }
        }
    }
}
