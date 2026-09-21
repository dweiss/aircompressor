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

import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestLz4BlockStreams
{
    private static final int[] LENGTHS = {0, 1, 63, 64, 65, 1000, 65535, 65536, 65537, 300_000};
    private static final int[] BLOCK_SIZES = {64, 100, 4096, Lz4BlockOutputStream.DEFAULT_BLOCK_SIZE, 1 << 20};

    @Test
    void testRoundTrip()
            throws IOException
    {
        Random random = new Random(1);
        for (int length : LENGTHS) {
            for (byte[] data : new byte[][] {compressible(random, length), incompressible(random, length)}) {
                for (int blockSize : BLOCK_SIZES) {
                    byte[] compressed = compress(data, blockSize);
                    assertThat(decompress(compressed)).isEqualTo(data);
                    assertThat(readByteAtATime(compressed)).isEqualTo(data);
                }
            }
        }
    }

    @Test
    void testIncompressibleBlocksAreStored()
            throws IOException
    {
        byte[] data = incompressible(new Random(2), 100_000);
        // two block headers and the end mark
        assertThat(compress(data, Lz4BlockOutputStream.DEFAULT_BLOCK_SIZE)).hasSize(data.length + 3 * Lz4BlockOutputStream.HEADER_LENGTH);
    }

    @Test
    void testLz4JavaReadsOurStreams()
            throws IOException
    {
        Random random = new Random(3);
        for (int length : LENGTHS) {
            for (byte[] data : new byte[][] {compressible(random, length), incompressible(random, length)}) {
                byte[] compressed = compress(data, Lz4BlockOutputStream.DEFAULT_BLOCK_SIZE);
                try (InputStream in = new LZ4BlockInputStream(new ByteArrayInputStream(compressed))) {
                    assertThat(in.readAllBytes()).isEqualTo(data);
                }
            }
        }
    }

    @Test
    void testReadsLz4JavaStreams()
            throws IOException
    {
        Random random = new Random(4);
        for (int length : LENGTHS) {
            for (byte[] data : new byte[][] {compressible(random, length), incompressible(random, length)}) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (OutputStream lz4 = new LZ4BlockOutputStream(out)) {
                    lz4.write(data);
                }
                assertThat(decompress(out.toByteArray())).isEqualTo(data);
            }
        }
    }

    @Test
    void testStopsAtEndOfStreamMarker()
            throws IOException
    {
        byte[] data = compressible(new Random(5), 100_000);
        byte[] tail = {1, 2, 3, 4, 5};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Lz4BlockOutputStream lz4 = new Lz4BlockOutputStream(out);
        lz4.write(data);
        // finish() leaves the underlying stream open
        lz4.finish();
        out.write(tail);

        ByteArrayInputStream source = new ByteArrayInputStream(out.toByteArray());
        Lz4BlockInputStream in = new Lz4BlockInputStream(source);
        assertThat(in.readAllBytes()).isEqualTo(data);
        assertThat(in.read()).isEqualTo(-1);
        assertThat(source.readAllBytes()).isEqualTo(tail);

        assertThatThrownBy(() -> lz4.write(1)).isInstanceOf(IOException.class);
    }

    @Test
    void testTruncatedInput()
            throws IOException
    {
        byte[] compressed = compress(compressible(new Random(6), 100_000), 4096);
        for (int length : new int[] {0, 1, Lz4BlockOutputStream.HEADER_LENGTH - 1, Lz4BlockOutputStream.HEADER_LENGTH, compressed.length / 2, compressed.length - 1}) {
            byte[] truncated = Arrays.copyOf(compressed, length);
            assertThatThrownBy(() -> decompress(truncated)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void testCorruptedInput()
            throws IOException
    {
        byte[] data = compressible(new Random(7), 100_000);
        byte[] compressed = compress(data, 4096);
        Random random = new Random(8);
        int detected = 0;
        for (int i = 0; i < 500; i++) {
            byte[] corrupted = compressed.clone();
            int position = random.nextInt(corrupted.length);
            corrupted[position] ^= (byte) (1 << random.nextInt(8));
            try {
                // The block size hint in the token of a block doesn't affect decoding: corruption is either
                // detected or the data is intact.
                assertThat(decompress(corrupted))
                        .describedAs("bit flipped at byte %s", position)
                        .isEqualTo(data);
            }
            catch (IOException e) {
                detected++;
            }
        }
        assertThat(detected).isGreaterThan(450);
    }

    @Test
    void testInvalidBlockSize()
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThatThrownBy(() -> new Lz4BlockOutputStream(out, 63)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Lz4BlockOutputStream(out, (1 << 25) + 1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] compress(byte[] data, int blockSize)
            throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Lz4BlockOutputStream lz4 = new Lz4BlockOutputStream(out, blockSize)) {
            // a mix of bulk and single byte writes
            int half = data.length / 2;
            lz4.write(data, 0, half);
            for (int i = half; i < data.length; i++) {
                lz4.write(data[i]);
            }
        }
        return out.toByteArray();
    }

    private static byte[] decompress(byte[] compressed)
            throws IOException
    {
        try (InputStream in = new Lz4BlockInputStream(new ByteArrayInputStream(compressed))) {
            return in.readAllBytes();
        }
    }

    private static byte[] readByteAtATime(byte[] compressed)
            throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new Lz4BlockInputStream(new ByteArrayInputStream(compressed))) {
            int value;
            while ((value = in.read()) >= 0) {
                out.write(value);
            }
        }
        return out.toByteArray();
    }

    private static byte[] compressible(Random random, int length)
    {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) ("abcdefgh".charAt(random.nextInt(8)) + (random.nextInt(30) == 0 ? random.nextInt(4) : 0));
        }
        return data;
    }

    private static byte[] incompressible(Random random, int length)
    {
        byte[] data = new byte[length];
        random.nextBytes(data);
        return data;
    }
}
