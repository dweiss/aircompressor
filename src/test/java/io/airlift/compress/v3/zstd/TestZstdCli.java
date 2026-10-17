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

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the Java encoder against the {@code zstd} command line tool: its output must decompress with the
 * tool, and be about as small as the tool's output at the same level with the same (hash chain) match finder.
 * Runs only when the tool is installed; {@code -Dzstd.cli=/path/to/zstd} points at a specific binary.
 */
@EnabledIf("isZstdAvailable")
class TestZstdCli
{
    /** See {@link TestZstdCompressionLevels#MAX_SIZE_RATIO_TO_NATIVE}. */
    private static final double MAX_SIZE_RATIO_TO_CLI = 1.04;

    private static final String[] FILES = {"silesia/dickens", "silesia/xml", "silesia/x-ray", "canterbury/alice29.txt", "calgary/paper5"};
    private static final int[] LEVELS = {3, 5, 6, 7, 9, 12};

    @TempDir
    Path tempDir;

    static boolean isZstdAvailable()
    {
        return zstdBinary() != null;
    }

    private static String zstdBinary()
    {
        String property = System.getProperty("zstd.cli");
        if (property != null) {
            return property;
        }
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(dir, "zstd");
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    @TestFactory
    List<DynamicTest> testAgainstCli()
    {
        List<DynamicTest> tests = new ArrayList<>();
        for (String file : FILES) {
            for (int level : LEVELS) {
                tests.add(DynamicTest.dynamicTest(file + " level " + level, () -> compareWithCli(file, level)));
            }
        }
        return tests;
    }

    private void compareWithCli(String file, int level)
            throws Exception
    {
        Path inputFile = Path.of("testdata", file);
        byte[] input = Files.readAllBytes(inputFile);

        byte[] oneShot = TestZstdCompressionLevels.compressOneShot(input, level);
        byte[] streamed = TestZstdCompressionLevels.compressStream(input, level);

        String name = file.replace('/', '_') + "." + level;
        Path oneShotFile = tempDir.resolve(name + ".oneshot.zst");
        Path streamedFile = tempDir.resolve(name + ".stream.zst");
        Files.write(oneShotFile, oneShot);
        Files.write(streamedFile, streamed);

        // the CLI must decompress both outputs to the original
        for (Path compressed : List.of(oneShotFile, streamedFile)) {
            Path decompressed = tempDir.resolve(compressed.getFileName() + ".out");
            run(zstdBinary(), "-d", "-q", "-f", "-o", decompressed.toString(), compressed.toString());
            assertThat(Files.readAllBytes(decompressed)).as("%s", compressed).isEqualTo(input);
            Files.delete(decompressed);
        }

        // and the size must be about the same as the CLI's, using the same match finder as the Java port
        Path cliFile = tempDir.resolve(name + ".cli.zst");
        run(zstdBinary(), "-" + level, "--no-row-match-finder", "--single-thread", "-q", "-f", "-o", cliFile.toString(), inputFile.toString());
        long cliSize = Files.size(cliFile);
        String description = String.format(Locale.ROOT, "%s at level %d: java %d (stream %d) vs cli %d (%+.2f%%)", file, level, oneShot.length, streamed.length, cliSize, 100.0 * (oneShot.length - cliSize) / cliSize);
        System.out.println(description);
        assertThat((long) oneShot.length)
                .as(description)
                .isLessThanOrEqualTo((long) (cliSize * MAX_SIZE_RATIO_TO_CLI) + 16);

        Files.delete(oneShotFile);
        Files.delete(streamedFile);
        Files.delete(cliFile);
    }

    private static void run(String... command)
            throws IOException, InterruptedException
    {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("Timed out: " + String.join(" ", command));
        }
        assertThat(process.exitValue()).as("%s%n%s", String.join(" ", command), new String(output, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(0);
    }
}
