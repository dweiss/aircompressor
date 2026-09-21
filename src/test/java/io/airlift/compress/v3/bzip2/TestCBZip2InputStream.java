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
package io.airlift.compress.v3.bzip2;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCBZip2InputStream
{
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "bzip2-test");
        thread.setDaemon(true);
        return thread;
    });

    private static final int[] CHUNK_SIZES = {1, 3, 64, 4093, 65536, 1 << 20};

    @Test
    void testEmptyInput()
            throws IOException
    {
        for (int blockSize = 1; blockSize <= 9; blockSize += 4) {
            byte[] compressed = compress(new byte[0], blockSize);
            assertRoundTrip(new byte[0], compressed);
        }
    }

    @Test
    void testTinyInputs()
            throws IOException
    {
        for (int length = 1; length <= 17; length++) {
            byte[] data = new byte[length];
            Arrays.fill(data, (byte) 'x');
            assertRoundTrip(data, compress(data, 9));
            data = randomBytes(new Random(length), length);
            assertRoundTrip(data, compress(data, 9));
        }
    }

    @Test
    void testRandomData()
            throws IOException
    {
        // incompressible data: several blocks at block size 1, one at block size 9
        byte[] data = randomBytes(new Random(1), 350_000);
        assertRoundTrip(data, compress(data, 1));
        assertRoundTrip(data, compress(data, 9));
    }

    @Test
    void testTextLikeData()
            throws IOException
    {
        byte[] data = textLike(new Random(2), 2_500_000);
        assertRoundTrip(data, compress(data, 1));
        assertRoundTrip(data, compress(data, 5));
        assertRoundTrip(data, compress(data, 9));
    }

    @Test
    void testRuns()
            throws IOException
    {
        // Exercises the RLE1 stage: runs of every length around the 4-byte threshold and up to
        // and beyond the 255 repeat limit, with random block-boundary placement.
        Random random = new Random(3);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 20_000; i++) {
            int runLength = switch (random.nextInt(4)) {
                case 0 -> 1 + random.nextInt(4);
                case 1 -> 4 + random.nextInt(6);
                case 2 -> 250 + random.nextInt(20);
                default -> random.nextInt(2000);
            };
            byte value = (byte) random.nextInt(3);
            for (int j = 0; j < runLength; j++) {
                out.write(value);
            }
        }
        byte[] data = out.toByteArray();
        assertRoundTrip(data, compress(data, 1));
        assertRoundTrip(data, compress(data, 9));
    }

    @Test
    void testLongRunOfSingleByte()
            throws IOException
    {
        byte[] data = new byte[3_000_000];
        Arrays.fill(data, (byte) 0);
        assertRoundTrip(data, compress(data, 1));
        assertRoundTrip(data, compress(data, 9));
        Arrays.fill(data, (byte) 0xff);
        assertRoundTrip(data, compress(data, 9));
    }

    @Test
    void testBlockSizes()
            throws IOException
    {
        byte[] data = textLike(new Random(4), 1_000_000);
        for (int blockSize = 1; blockSize <= 9; blockSize++) {
            assertRoundTrip(data, compress(data, blockSize));
        }
    }

    @Test
    void testConcatenatedStreams()
            throws IOException
    {
        byte[] first = textLike(new Random(5), 300_000);
        byte[] second = randomBytes(new Random(6), 150_000);
        byte[] third = new byte[0];
        byte[] fourth = textLike(new Random(7), 10);

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        compressed.write(compress(first, 2));
        compressed.write(compress(second, 9));
        compressed.write(compress(third, 1));
        compressed.write(compress(fourth, 3));

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(first);
        expected.write(second);
        expected.write(third);
        expected.write(fourth);

        assertRoundTrip(expected.toByteArray(), compressed.toByteArray());
    }

    @Test
    void testTrailingGarbageIsIgnored()
            throws IOException
    {
        byte[] data = textLike(new Random(8), 100_000);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        compressed.write(compress(data, 9));
        compressed.write(new byte[] {'B', 'Z', 'x', 'y', 'z'});
        assertRoundTrip(data, compressed.toByteArray());

        compressed = new ByteArrayOutputStream();
        compressed.write(compress(data, 9));
        compressed.write(new byte[] {'B', 'Z', 'h', 'a'});
        assertRoundTrip(data, compressed.toByteArray());
    }

    @Test
    void testTruncatedInput()
            throws IOException
    {
        byte[] data = textLike(new Random(9), 400_000);
        byte[] compressed = compress(data, 1);
        for (int length : new int[] {0, 1, 2, 3, 4, 5, 10, 100, compressed.length / 2, compressed.length - 5, compressed.length - 1}) {
            byte[] truncated = Arrays.copyOf(compressed, length);
            assertThatThrownBy(() -> decompress(truncated, 65536))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void testCorruptedInput()
            throws IOException
    {
        byte[] data = textLike(new Random(10), 400_000);
        byte[] compressed = compress(data, 1);
        Random random = new Random(11);
        for (int i = 0; i < 20; i++) {
            byte[] corrupted = compressed.clone();
            int position = 4 + random.nextInt(corrupted.length - 4);
            corrupted[position] ^= (byte) (1 << random.nextInt(8));
            assertThatThrownBy(() -> decompress(corrupted, 65536))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void testReadAfterEndOfStream()
            throws IOException
    {
        byte[] data = textLike(new Random(12), 1000);
        try (InputStream in = open(compress(data, 9))) {
            assertThat(in.readAllBytes()).isEqualTo(data);
            assertThat(in.read()).isEqualTo(-1);
            assertThat(in.read(new byte[10], 0, 10)).isEqualTo(-1);
            assertThat(in.read(new byte[10], 0, 0)).isEqualTo(0);
        }
    }

    @Test
    void testHadoopStreamsRoundTrip()
            throws IOException
    {
        byte[] data = textLike(new Random(13), 700_000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var compressor = new BZip2HadoopStreams().createOutputStream(out)) {
            compressor.write(data, 0, 300_000);
            compressor.write(data, 300_000, data.length - 300_000);
        }
        byte[] compressed = out.toByteArray();
        assertThat(compressed[0]).isEqualTo((byte) 'B');
        assertThat(compressed[1]).isEqualTo((byte) 'Z');

        for (int chunkSize : CHUNK_SIZES) {
            try (InputStream in = new BZip2HadoopStreams().createInputStream(new ByteArrayInputStream(compressed))) {
                assertThat(readAll(in, chunkSize)).isEqualTo(data);
            }
        }
        try (InputStream in = new BZip2HadoopStreams().createInputStream(new ByteArrayInputStream(compressed))) {
            assertThat(readByteAtATime(in)).isEqualTo(data);
        }
    }

    private static void assertRoundTrip(byte[] expected, byte[] compressed)
            throws IOException
    {
        for (int chunkSize : CHUNK_SIZES) {
            assertThat(decompress(compressed, chunkSize)).isEqualTo(expected);
        }
        try (InputStream in = open(compressed)) {
            assertThat(readByteAtATime(in)).isEqualTo(expected);
        }
    }

    private static byte[] compress(byte[] data, int blockSize)
            throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('B');
        out.write('Z');
        try (CBZip2OutputStream compressor = new CBZip2OutputStream(out, blockSize)) {
            compressor.write(data);
        }
        return out.toByteArray();
    }

    private static InputStream open(byte[] compressed)
    {
        // the "BZ" magic is optional: the sequential decoder gets the stream without it, the parallel one with it
        int offset = compressed.length >= 2 && compressed[0] == 'B' && compressed[1] == 'Z' ? 2 : 0;
        return new CBZip2InputStream(new ByteArrayInputStream(compressed, offset, compressed.length - offset));
    }

    private static byte[] decompress(byte[] compressed, int chunkSize)
            throws IOException
    {
        byte[] decompressed;
        try (InputStream in = open(compressed)) {
            decompressed = readAll(in, chunkSize);
        }
        catch (IOException e) {
            assertThatThrownBy(() -> decompressParallel(compressed, chunkSize))
                    .isInstanceOf(IOException.class);
            throw e;
        }
        // the parallel decoder must produce the same bytes
        assertThat(decompressParallel(compressed, chunkSize)).isEqualTo(decompressed);
        return decompressed;
    }

    private static byte[] decompressParallel(byte[] compressed, int chunkSize)
            throws IOException
    {
        try (InputStream in = new CBZip2InputStream(new ByteArrayInputStream(compressed), true, EXECUTOR, 4)) {
            return readAll(in, chunkSize);
        }
    }

    private static byte[] readAll(InputStream in, int chunkSize)
            throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[chunkSize + 7];
        int n;
        while ((n = in.read(buffer, 7, chunkSize)) >= 0) {
            assertThat(n).isGreaterThan(0);
            out.write(buffer, 7, n);
        }
        assertThat(in.read(buffer, 0, chunkSize)).isEqualTo(-1);
        return out.toByteArray();
    }

    private static byte[] readByteAtATime(InputStream in)
            throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            out.write(b);
        }
        assertThat(in.read()).isEqualTo(-1);
        return out.toByteArray();
    }

    private static byte[] randomBytes(Random random, int length)
    {
        byte[] data = new byte[length];
        random.nextBytes(data);
        return data;
    }

    private static byte[] textLike(Random random, int length)
    {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            StringBuilder word = new StringBuilder();
            int wordLength = 1 + random.nextInt(12);
            for (int j = 0; j < wordLength; j++) {
                word.append((char) ('a' + random.nextInt(26)));
            }
            words.add(word.toString());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(length + 16);
        while (out.size() < length) {
            String word = words.get(Math.min(words.size() - 1, (int) Math.abs(random.nextGaussian() * 60)));
            out.writeBytes(word.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.write(random.nextInt(20) == 0 ? '\n' : ' ');
        }
        return Arrays.copyOf(out.toByteArray(), length);
    }
}
