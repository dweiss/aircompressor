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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of the single-stream mode (which leaves the source positioned right after the bzip2 stream)
 * and of the parallel block decoder of {@link BZip2InputStream}, and of {@link BZip2OutputStream}.
 */
class TestBZip2DecoderModes
{
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);

    private static final byte[] FIRST = textLike(new Random(1), 700_000);
    private static final byte[] SECOND = textLike(new Random(2), 50_000);
    private static final byte[] TAIL = "data after the bzip2 streams".getBytes(UTF_8);

    private static final byte[] FIRST_COMPRESSED = compress(FIRST, 1);

    /** Two bzip2 streams (the first one with several blocks) followed by other data. */
    private static final byte[] INPUT = concat(FIRST_COMPRESSED, compress(SECOND, 9), TAIL);

    @AfterAll
    static void shutdown()
    {
        EXECUTOR.shutdownNow();
    }

    @Test
    void testSingleStreamLeavesSourceWithMarkAfterStream()
            throws IOException
    {
        assertSingleStreams(new ByteArrayInputStream(INPUT));
    }

    @Test
    void testSingleStreamLeavesSourceWithoutMarkAfterStream()
            throws IOException
    {
        assertSingleStreams(withoutMark(new ByteArrayInputStream(INPUT)));
    }

    @Test
    void testSingleStreamThroughSmallBufferedInputStream()
            throws IOException
    {
        // the mark limit of the decoder (64 KiB) is much larger than the buffer of the source
        assertSingleStreams(new BufferedInputStream(new ByteArrayInputStream(INPUT), 512));
    }

    @Test
    void testSingleStreamReadByteAtATime()
            throws IOException
    {
        InputStream source = new ByteArrayInputStream(INPUT);
        BZip2InputStream in = new BZip2InputStream(source, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int value;
        while ((value = in.read()) >= 0) {
            out.write(value);
        }
        assertThat(out.toByteArray()).isEqualTo(FIRST);
        assertThat(in.getProcessedByteCount()).isEqualTo(FIRST_COMPRESSED.length);
        assertThat(source.available()).isEqualTo(INPUT.length - FIRST_COMPRESSED.length);
    }

    @Test
    void testErrorLeavesSourceAtFirstByteNotConsumed()
            throws IOException
    {
        byte[] corrupted = INPUT.clone();
        corrupted[FIRST_COMPRESSED.length / 2] ^= 0x10;

        InputStream withMark = new ByteArrayInputStream(corrupted);
        assertThatThrownBy(() -> new BZip2InputStream(withMark, false).readAllBytes())
                .isInstanceOf(IOException.class);

        InputStream withoutMark = withoutMark(new ByteArrayInputStream(corrupted));
        assertThatThrownBy(() -> new BZip2InputStream(withoutMark, false).readAllBytes())
                .isInstanceOf(IOException.class);

        // Not at the end of the 64 KiB chunk that was read ahead, but at the first byte not consumed. A source
        // without mark/reset cannot take back the lookahead of the bit buffer (less than eight bytes).
        assertThat(withMark.available())
                .isGreaterThan(TAIL.length)
                .isBetween(withoutMark.available(), withoutMark.available() + 7);
    }

    @Test
    void testParallelSingleStream()
            throws IOException
    {
        try (InputStream in = new BZip2InputStream(new ByteArrayInputStream(INPUT), false, EXECUTOR, 3)) {
            assertThat(in.readAllBytes()).isEqualTo(FIRST);
            assertThat(in.read()).isEqualTo(-1);
        }
    }

    @Test
    void testParallelConcatenatedStreams()
            throws IOException
    {
        for (int maxConcurrentInFlight : new int[] {1, 2, 16}) {
            try (InputStream in = new BZip2InputStream(new ByteArrayInputStream(INPUT), true, EXECUTOR, maxConcurrentInFlight)) {
                assertThat(in.readAllBytes()).isEqualTo(concat(FIRST, SECOND));
            }
        }
    }

    @Test
    void testParallelWithoutStreamMagic()
            throws IOException
    {
        // the "BZ" magic of the first stream is optional
        try (InputStream in = new BZip2InputStream(new ByteArrayInputStream(INPUT, 2, INPUT.length - 2), true, EXECUTOR, 3)) {
            assertThat(in.readAllBytes()).isEqualTo(concat(FIRST, SECOND));
        }
    }

    @Test
    void testParallelRescansAfterFalseDelimiters()
            throws IOException
    {
        // larger than the input buffer of the decoder, so that the buffer is compacted while blocks are in flight
        byte[] data = textLike(new Random(3), 6_000_000);
        byte[] compressed = compress(data, 1);

        try (ParallelBZip2Decoder decoder = new ParallelBZip2Decoder(new ByteArrayInputStream(compressed), true, EXECUTOR, 4)) {
            // pretend that block delimiters were found inside the payload of blocks
            for (long bit = 5_000; bit < compressed.length * 8L; bit += 1_234_567) {
                decoder.spuriousDelimitersForTesting.add(bit);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[65536];
            int n;
            while ((n = decoder.read(buffer, 0, buffer.length)) >= 0) {
                out.write(buffer, 0, n);
            }
            assertThat(out.toByteArray()).isEqualTo(data);
        }
    }

    @Test
    void testParallelRejectsInvalidArguments()
    {
        assertThatThrownBy(() -> new BZip2InputStream(new ByteArrayInputStream(INPUT), true, EXECUTOR, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BZip2InputStream(new ByteArrayInputStream(INPUT), true, null, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BZip2HadoopStreams(EXECUTOR, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testParallelHadoopStreams()
            throws IOException
    {
        try (InputStream in = new BZip2HadoopStreams(EXECUTOR, 4).createInputStream(new ByteArrayInputStream(INPUT))) {
            assertThat(in.readAllBytes()).isEqualTo(concat(FIRST, SECOND));
        }
    }

    @Test
    void testOutputStreamWritesCompleteStream()
            throws IOException
    {
        for (int blockSize : new int[] {BZip2OutputStream.MIN_BLOCK_SIZE, 5, BZip2OutputStream.MAX_BLOCK_SIZE}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (BZip2OutputStream compressor = new BZip2OutputStream(out, blockSize)) {
                compressor.write(FIRST, 0, 1000);
                for (int i = 1000; i < 2000; i++) {
                    compressor.write(FIRST[i]);
                }
                compressor.write(FIRST, 2000, FIRST.length - 2000);
            }
            byte[] compressed = out.toByteArray();
            assertThat(Arrays.copyOf(compressed, 4)).isEqualTo(new byte[] {'B', 'Z', 'h', (byte) ('0' + blockSize)});
            assertThat(new BZip2InputStream(new ByteArrayInputStream(compressed)).readAllBytes()).isEqualTo(FIRST);
        }

        assertThatThrownBy(() -> new BZip2OutputStream(new ByteArrayOutputStream(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BZip2OutputStream(new ByteArrayOutputStream(), 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testOutputStreamFinishLeavesUnderlyingStreamOpen()
            throws IOException
    {
        int[] closed = new int[1];
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        {
            @Override
            public void close()
            {
                closed[0]++;
            }
        };

        BZip2OutputStream compressor = new BZip2OutputStream(out);
        compressor.write(SECOND);
        compressor.finish();
        assertThat(closed[0]).isEqualTo(0);
        out.writeBytes(TAIL);

        // closing after finish() still closes the underlying stream
        compressor.close();
        assertThat(closed[0]).isEqualTo(1);

        InputStream source = new ByteArrayInputStream(out.toByteArray());
        assertThat(new BZip2InputStream(source, false).readAllBytes()).isEqualTo(SECOND);
        assertThat(source.readAllBytes()).isEqualTo(TAIL);
    }

    @Test
    @SuppressWarnings("deprecation")
    void testDeprecatedClassName()
            throws IOException
    {
        assertThat(new CBZip2InputStream(new ByteArrayInputStream(INPUT)).readAllBytes()).isEqualTo(concat(FIRST, SECOND));
        assertThat(new CBZip2InputStream(new ByteArrayInputStream(INPUT), false).readAllBytes()).isEqualTo(FIRST);
        assertThat(new CBZip2InputStream(new ByteArrayInputStream(INPUT), false, EXECUTOR, 2).readAllBytes()).isEqualTo(FIRST);
    }

    /**
     * Decompresses the two streams of {@link #INPUT} one at a time; each decoder must leave the source
     * at the first byte after its stream.
     */
    private static void assertSingleStreams(InputStream source)
            throws IOException
    {
        BZip2InputStream first = new BZip2InputStream(source, false);
        assertThat(first.readAllBytes()).isEqualTo(FIRST);
        assertThat(first.getProcessedByteCount()).isEqualTo(FIRST_COMPRESSED.length);

        assertThat(new BZip2InputStream(source, false).readAllBytes()).isEqualTo(SECOND);
        assertThat(source.readAllBytes()).isEqualTo(TAIL);
    }

    private static InputStream withoutMark(InputStream in)
    {
        return new FilterInputStream(in)
        {
            @Override
            public boolean markSupported()
            {
                return false;
            }

            @Override
            public void mark(int readLimit)
            {
                throw new UnsupportedOperationException();
            }

            @Override
            public void reset()
            {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static byte[] compress(byte[] data, int blockSize)
    {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write('B');
            out.write('Z');
            try (CBZip2OutputStream compressor = new CBZip2OutputStream(out, blockSize)) {
                compressor.write(data);
            }
            return out.toByteArray();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] concat(byte[]... parts)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] textLike(Random random, int length)
    {
        String[] words = {"the", "quick", "brown", "fox", "jumps", "over", "lazy", "dog", "bzip2", "\n"};
        ByteArrayOutputStream out = new ByteArrayOutputStream(length + 16);
        while (out.size() < length) {
            out.writeBytes(words[random.nextInt(words.length)].getBytes(UTF_8));
            out.write(random.nextInt(40) == 0 ? random.nextInt(256) : ' ');
        }
        return Arrays.copyOf(out.toByteArray(), length);
    }
}
