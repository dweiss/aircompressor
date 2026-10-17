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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The choice between the Java and the native zstd implementation is made once, when ZstdNative initializes,
 * from the {@code io.airlift.compress.v3.zstd.implementation} property and whether native access is enabled.
 * Each case therefore runs in its own JVM, with the module on the module path. Needs libzstd on the system.
 */
@EnabledIf("isNativeEnabled")
class TestZstdImplementationSelection
{
    private static final String MODULE_NAME = "io.airlift.compress.v3";

    @TempDir
    Path tempDir;

    static boolean isNativeEnabled()
    {
        return ZstdNativeCompressor.isEnabled();
    }

    @Test
    void testAutoWithNativeAccess()
            throws Exception
    {
        String output = run(List.of("--enable-native-access=" + MODULE_NAME), null);
        assertThat(output).contains("compressor ZstdNativeCompressor", "decompressor ZstdNativeDecompressor");
        assertThat(output).doesNotContain("WARNING");
    }

    @Test
    void testAutoWithoutNativeAccessStaysSilentlyInJava()
            throws Exception
    {
        String output = run(List.of(), null);
        assertThat(output).contains("compressor ZstdJavaCompressor", "decompressor ZstdJavaDecompressor");
        // a library must not make the JDK print the restricted-method warning on its own
        assertThat(output).doesNotContain("WARNING");
    }

    @Test
    void testJava()
            throws Exception
    {
        String output = run(List.of("--enable-native-access=" + MODULE_NAME), "java");
        assertThat(output).contains("compressor ZstdJavaCompressor", "decompressor ZstdJavaDecompressor");
        assertThat(output).doesNotContain("WARNING");
    }

    @Test
    void testNative()
            throws Exception
    {
        String output = run(List.of("--enable-native-access=" + MODULE_NAME), "native");
        assertThat(output).contains("compressor ZstdNativeCompressor", "decompressor ZstdNativeDecompressor");
    }

    @Test
    void testNativeWithoutNativeAccessStillLoads()
            throws Exception
    {
        // explicitly requested: the library loads, and the JDK's warning about the restricted call is the user's choice
        String output = run(List.of(), "native");
        assertThat(output).contains("compressor ZstdNativeCompressor", "decompressor ZstdNativeDecompressor");
    }

    @Test
    void testNativeWithMissingLibraryFails()
            throws Exception
    {
        String output = run(List.of("--enable-native-access=" + MODULE_NAME, "-D" + ZstdNative.IMPLEMENTATION_PROPERTY + ".library=libdoes-not-exist.so.1"), "native");
        assertThat(output).contains("IllegalStateException", "Zstd native library is not enabled", "libdoes-not-exist.so.1");
    }

    @Test
    void testAutoWithMissingLibraryFallsBack()
            throws Exception
    {
        String output = run(List.of("--enable-native-access=" + MODULE_NAME, "-D" + ZstdNative.IMPLEMENTATION_PROPERTY + ".library=libdoes-not-exist.so.1"), null);
        assertThat(output).contains("compressor ZstdJavaCompressor", "decompressor ZstdJavaDecompressor");
    }

    private String run(List<String> jvmOptions, String mode)
            throws IOException, InterruptedException
    {
        Path source = tempDir.resolve("Selection.java");
        Files.writeString(source, """
                import io.airlift.compress.v3.zstd.ZstdCompressor;
                import io.airlift.compress.v3.zstd.ZstdDecompressor;

                public class Selection
                {
                    public static void main(String[] args)
                    {
                        try {
                            ZstdCompressor compressor = ZstdCompressor.create();
                            System.out.println("compressor " + compressor.getClass().getSimpleName());
                            ZstdDecompressor decompressor = ZstdDecompressor.create();
                            System.out.println("decompressor " + decompressor.getClass().getSimpleName());
                            byte[] input = "selection smoke test ".repeat(1000).getBytes();
                            byte[] compressed = new byte[compressor.maxCompressedLength(input.length)];
                            int compressedSize = compressor.compress(input, 0, input.length, compressed, 0, compressed.length);
                            byte[] decompressed = new byte[input.length];
                            if (decompressor.decompress(compressed, 0, compressedSize, decompressed, 0, decompressed.length) != input.length) {
                                throw new AssertionError("round trip failed");
                            }
                            System.out.println("ok");
                        }
                        catch (Exception e) {
                            // printed, not thrown, so that the test can assert on the message
                            System.out.println(e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                    }
                }
                """);
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElseThrow());
        command.addAll(jvmOptions);
        if (mode != null) {
            command.add("-D" + ZstdNative.IMPLEMENTATION_PROPERTY + "=" + mode);
        }
        command.add("--module-path");
        command.add(Path.of(ZstdJavaCompressor.class.getProtectionDomain().getCodeSource().getLocation().getPath()).toString());
        command.add("--add-modules");
        command.add(MODULE_NAME);
        command.add(source.toString());

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("timed out: " + String.join(" ", command));
        }
        assertThat(process.exitValue()).as("%s%n%s", String.join(" ", command), output).isEqualTo(0);
        return output;
    }
}
