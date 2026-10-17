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
import io.airlift.compress.v3.thirdparty.ZstdJniDecompressor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Compression levels of the Java encoder (the native library accepts every level, see TestZstdNativeStream). */
class TestZstdOutputStreamLevel
{
    @Test
    void testCompressionLevels()
            throws IOException
    {
        byte[] input = compressibleInput(1024 * 1024);

        int[] levels = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        int[] sizes = new int[levels.length];
        for (int i = 0; i < levels.length; i++) {
            byte[] compressed = compress(input, levels[i]);
            sizes[i] = compressed.length;

            byte[] decompressed = new byte[input.length];
            int decompressedSize = new ZstdJniDecompressor().decompress(compressed, 0, compressed.length, decompressed, 0, decompressed.length);
            assertThat(decompressedSize).isEqualTo(input.length);
            assertThat(decompressed).isEqualTo(input);
        }

        // higher levels compress better, with some slack: this is a heuristic, not a guarantee
        for (int i = 1; i < levels.length; i++) {
            assertThat(sizes[i]).as("level %s vs level %s", levels[i], levels[i - 1]).isLessThanOrEqualTo((int) (sizes[i - 1] * 1.02));
        }
        assertThat(sizes[levels.length - 1]).isLessThan(sizes[0]);
    }

    @Test
    void testDefaultLevel()
            throws IOException
    {
        byte[] input = compressibleInput(256 * 1024);

        ByteArrayOutputStream defaultLevel = new ByteArrayOutputStream();
        try (ZstdOutputStream out = new ZstdOutputStream(defaultLevel, CompressionParameters.DEFAULT_COMPRESSION_LEVEL, Implementation.JAVA)) {
            out.write(input);
        }

        assertThat(defaultLevel.toByteArray()).isEqualTo(compress(input, CompressionParameters.DEFAULT_COMPRESSION_LEVEL));
        assertThat(defaultLevel.toByteArray()).isEqualTo(compress(input, 0));
    }

    @Test
    void testUnsupportedStrategyLevel()
    {
        // the fast strategy (levels 1, 2) and the binary tree strategies (levels 13 and up) are not implemented by the Java encoder
        for (int level : new int[] {1, 2, 13, 16, 19, CompressionParameters.MAX_COMPRESSION_LEVEL}) {
            assertThatThrownBy(() -> new ZstdOutputStream(new ByteArrayOutputStream(), level, Implementation.JAVA))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not implemented");
        }
    }

    @Test
    void testInvalidLevel()
    {
        assertThatThrownBy(() -> new ZstdOutputStream(new ByteArrayOutputStream(), -1, Implementation.JAVA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compressionLevel");
        assertThatThrownBy(() -> new ZstdOutputStream(new ByteArrayOutputStream(), CompressionParameters.MAX_COMPRESSION_LEVEL + 1, Implementation.JAVA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compressionLevel");
    }

    private static byte[] compress(byte[] input, int level)
            throws IOException
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZstdOutputStream out = new ZstdOutputStream(output, level, Implementation.JAVA)) {
            for (int offset = 0; offset < input.length; offset += 64 * 1024) {
                out.write(input, offset, Math.min(64 * 1024, input.length - offset));
            }
        }
        return output.toByteArray();
    }

    /** Record-like text: a few kilobyte-sized templates repeated with varying numbers in between. */
    private static byte[] compressibleInput(int size)
    {
        Random random = new Random(0);
        String[] words = new String[500];
        for (int i = 0; i < words.length; i++) {
            StringBuilder word = new StringBuilder();
            for (int j = random.nextInt(8) + 3; j > 0; j--) {
                word.append((char) ('a' + random.nextInt(26)));
            }
            words[i] = word.toString();
        }

        String[] templates = new String[64];
        for (int i = 0; i < templates.length; i++) {
            StringBuilder template = new StringBuilder();
            while (template.length() < 1024 + random.nextInt(3 * 1024)) {
                template.append(words[random.nextInt(words.length)]).append(' ');
            }
            templates[i] = template.toString();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(size);
        while (output.size() < size) {
            output.writeBytes(templates[random.nextInt(templates.length)].getBytes(StandardCharsets.US_ASCII));
            output.writeBytes(Integer.toString(random.nextInt()).getBytes(StandardCharsets.US_ASCII));
            output.write('\n');
        }
        return Arrays.copyOf(output.toByteArray(), size);
    }
}
