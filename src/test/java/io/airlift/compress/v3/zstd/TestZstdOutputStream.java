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

import io.airlift.compress.v3.thirdparty.ZstdJniDecompressor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class TestZstdOutputStream
{
    /**
     * Inputs larger than the stream's internal buffer (four times the window size) are compressed
     * in chunks, sliding the window between them. The match window must be rebased together with
     * the hash tables, otherwise everything after the first chunk degrades to literals only.
     */
    @Test
    void testLargeInputKeepsCompressionRatio()
            throws IOException
    {
        byte[] input = compressibleInput(32 * 1024 * 1024);

        ByteArrayOutputStream streamed = new ByteArrayOutputStream();
        try (ZstdOutputStream out = new ZstdOutputStream(streamed)) {
            // write in small pieces, like a buffered writer would
            for (int offset = 0; offset < input.length; offset += 64 * 1024) {
                out.write(input, offset, Math.min(64 * 1024, input.length - offset));
            }
        }
        byte[] compressed = streamed.toByteArray();

        // the output must decode with an independent decoder
        byte[] decompressed = new byte[input.length];
        int decompressedSize = new ZstdJniDecompressor().decompress(compressed, 0, compressed.length, decompressed, 0, decompressed.length);
        assertThat(decompressedSize).isEqualTo(input.length);
        assertThat(decompressed).isEqualTo(input);

        // and be about as small as the one-shot compressor's output at the same level
        ZstdJavaCompressor compressor = new ZstdJavaCompressor();
        byte[] oneShot = new byte[compressor.maxCompressedLength(input.length)];
        int oneShotSize = compressor.compress(input, 0, input.length, oneShot, 0, oneShot.length);

        assertThat((double) input.length / oneShotSize).isGreaterThan(5.0);
        assertThat(compressed.length).isLessThan((int) (oneShotSize * 1.1));
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
        byte[] result = output.toByteArray();
        return Arrays.copyOf(result, size);
    }
}
