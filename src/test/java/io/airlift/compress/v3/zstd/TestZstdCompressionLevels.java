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

import io.airlift.compress.v3.thirdparty.ZstdJniCompressor;
import io.airlift.compress.v3.thirdparty.ZstdJniDecompressor;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compression levels 5 to 12 (greedy, lazy and lazy2 strategies) compared with the native library at the
 * same level: the output must decode with both decoders and be about as small.
 */
class TestZstdCompressionLevels
{
    /**
     * The entropy stage of the Java encoder differs from zstd 1.5.7, so the output is a bit larger even
     * when the match finder emits the same sequences. Everything beyond this margin is a match finder
     * regression.
     */
    private static final double MAX_SIZE_RATIO_TO_NATIVE = 1.04;

    private static final int[] LEVELS = {5, 6, 7, 8, 9, 10, 11, 12};

    /** A sample from each parameter table: > 256 KB, <= 256 KB, <= 128 KB and <= 16 KB. */
    private static final String[] FILES = {
            "silesia/dickens",
            "silesia/xml",
            "silesia/x-ray",
            "calgary/obj2",
            "canterbury/alice29.txt",
            "calgary/paper1",
            "calgary/geo",
            "calgary/paper5",
            "artificial/aaa.txt",
            "artificial/random.txt",
    };

    @TestFactory
    List<DynamicTest> testOneShot()
            throws IOException
    {
        List<DynamicTest> tests = new ArrayList<>();
        for (String file : FILES) {
            byte[] input = Files.readAllBytes(Path.of("testdata", file));
            for (int level : LEVELS) {
                tests.add(DynamicTest.dynamicTest(file + " level " + level, () -> {
                    byte[] compressed = compressOneShot(input, level);
                    verifyRoundTrip(input, compressed);
                    verifySizeAgainstNative(file, input, level, compressed.length);
                }));
            }
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> testStream()
            throws IOException
    {
        List<DynamicTest> tests = new ArrayList<>();
        for (String file : new String[] {"silesia/dickens", "silesia/x-ray", "canterbury/alice29.txt", "calgary/paper5"}) {
            byte[] input = Files.readAllBytes(Path.of("testdata", file));
            for (int level : LEVELS) {
                tests.add(DynamicTest.dynamicTest(file + " level " + level, () -> {
                    byte[] compressed = compressStream(input, level);
                    verifyRoundTrip(input, compressed);
                    // streaming uses the parameters for an input of unknown size
                    int nativeSize = new ZstdJniCompressor(level).compress(input, 0, input.length, new byte[input.length * 2 + 1024], 0, input.length * 2 + 1024);
                    assertThat(compressed.length).isLessThan((int) (nativeSize * (MAX_SIZE_RATIO_TO_NATIVE + 0.01)));
                }));
            }
        }
        return tests;
    }

    @Test
    void testHigherLevelsCompressBetter()
            throws IOException
    {
        byte[] input = Files.readAllBytes(Path.of("testdata", "silesia/dickens"));
        int previous = compressOneShot(input, 3).length;
        for (int level : new int[] {5, 7, 9, 12}) {
            int size = compressOneShot(input, level).length;
            assertThat(size).as("level %s", level).isLessThan(previous);
            previous = size;
        }
    }

    @Test
    void testUnsupportedLevels()
    {
        for (int level : new int[] {1, 2, 13, 19, 22}) {
            assertThatThrownBy(() -> new ZstdJavaCompressor(level))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not implemented");
        }
        assertThatThrownBy(() -> new ZstdJavaCompressor(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ZstdJavaCompressor(23)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Inputs larger than the stream buffer slide the window. The slide must keep the hash chain table
     * consistent, otherwise matches after the first slide are lost (the chains would point at the wrong
     * positions, which the byte comparison rejects).
     */
    @Test
    void testStreamWindowSlide()
            throws IOException
    {
        // 1 MB "epochs", each a random permutation of the same 64 random 16 KB pieces: nearly every match
        // references the previous epoch, up to 2 MB back, so it needs the chains, not just the hash heads.
        // 24 MB slides the 8 MB stream buffer of levels 5-8 several times and the 16 MB one of levels 9-12 once.
        java.util.Random random = new java.util.Random(0);
        int pieceSize = 16 * 1024;
        byte[][] pieces = new byte[64][pieceSize];
        for (byte[] piece : pieces) {
            random.nextBytes(piece);
        }
        ByteArrayOutputStream builder = new ByteArrayOutputStream();
        List<byte[]> order = new ArrayList<>(List.of(pieces));
        for (int epoch = 0; epoch < 24; epoch++) {
            java.util.Collections.shuffle(order, random);
            for (byte[] piece : order) {
                builder.writeBytes(piece);
            }
        }
        byte[] input = builder.toByteArray();

        for (int level : new int[] {5, 8, 12}) {
            byte[] oneShot = compressOneShot(input, level);
            // everything but the first epoch is a match
            assertThat(oneShot.length).as("level %s", level).isLessThan(input.length / 10);

            byte[] streamed = compressStream(input, level);
            verifyRoundTrip(input, streamed);
            assertThat(streamed.length).as("level %s", level).isLessThan((int) (oneShot.length * 1.15));
        }
    }

    private static void verifyRoundTrip(byte[] input, byte[] compressed)
    {
        byte[] decompressed = new byte[input.length];
        int size = new ZstdJavaDecompressor().decompress(compressed, 0, compressed.length, decompressed, 0, decompressed.length);
        assertThat(size).isEqualTo(input.length);
        assertThat(decompressed).isEqualTo(input);

        java.util.Arrays.fill(decompressed, (byte) 0);
        size = new ZstdJniDecompressor().decompress(compressed, 0, compressed.length, decompressed, 0, decompressed.length);
        assertThat(size).isEqualTo(input.length);
        assertThat(decompressed).isEqualTo(input);
    }

    private static void verifySizeAgainstNative(String file, byte[] input, int level, int compressedSize)
    {
        ZstdJniCompressor nativeCompressor = new ZstdJniCompressor(level);
        byte[] output = new byte[nativeCompressor.maxCompressedLength(input.length)];
        int nativeSize = nativeCompressor.compress(input, 0, input.length, output, 0, output.length);
        String description = String.format(Locale.ROOT, "%s at level %d: java %d vs native %d (%+.2f%%)", file, level, compressedSize, nativeSize, 100.0 * (compressedSize - nativeSize) / nativeSize);
        System.out.println(description);
        assertThat(compressedSize)
                .as(description)
                .isLessThanOrEqualTo((int) (nativeSize * MAX_SIZE_RATIO_TO_NATIVE) + 16);
    }

    static byte[] compressOneShot(byte[] input, int level)
    {
        ZstdJavaCompressor compressor = new ZstdJavaCompressor(level);
        byte[] output = new byte[compressor.maxCompressedLength(input.length)];
        int size = compressor.compress(input, 0, input.length, output, 0, output.length);
        return java.util.Arrays.copyOf(output, size);
    }

    static byte[] compressStream(byte[] input, int level)
            throws IOException
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZstdOutputStream out = new ZstdOutputStream(output, level)) {
            for (int offset = 0; offset < input.length; offset += 64 * 1024) {
                out.write(input, offset, Math.min(64 * 1024, input.length - offset));
            }
        }
        return output.toByteArray();
    }
}
