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
package io.airlift.compress.v3;

import io.airlift.compress.v3.zstd.ZstdJavaCompressor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The jar is a Java module: checks the compiled module descriptor and runs the module on the module path
 * from a separate JVM, the way a consumer would.
 */
class TestJavaModule
{
    private static final String MODULE_NAME = "io.airlift.compress.v3";

    @TempDir
    Path tempDir;

    @Test
    void testModuleDescriptor()
    {
        ModuleDescriptor descriptor = ModuleFinder.of(compiledModule())
                .find(MODULE_NAME)
                .orElseThrow(() -> new AssertionError("no module " + MODULE_NAME + " in " + compiledModule()))
                .descriptor();

        assertThat(descriptor.isAutomatic()).isFalse();
        // an open module: everything is open to reflection, which also lets the test suite run inside it
        assertThat(descriptor.isOpen()).isTrue();

        // every package is exported, unconditionally
        assertThat(descriptor.exports())
                .allSatisfy(export -> assertThat(export.isQualified()).isFalse())
                .extracting(Exports::source)
                .containsExactlyInAnyOrderElementsOf(descriptor.packages());
        assertThat(descriptor.packages()).containsExactlyInAnyOrder(
                "io.airlift.compress.v3",
                "io.airlift.compress.v3.bzip2",
                "io.airlift.compress.v3.deflate",
                "io.airlift.compress.v3.lz4",
                "io.airlift.compress.v3.lzo",
                "io.airlift.compress.v3.snappy",
                "io.airlift.compress.v3.xxhash",
                "io.airlift.compress.v3.zstd");

        // no dependencies at all
        assertThat(descriptor.requires())
                .extracting(Requires::name)
                .containsExactly("java.base");
    }

    @Test
    void testSuiteRunsInsideTheModule()
    {
        // surefire patches the test classes into the module, so the tests exercise the real module boundaries
        Module module = ZstdJavaCompressor.class.getModule();
        assertThat(module.isNamed()).as("%s", module).isTrue();
        assertThat(module.getName()).isEqualTo(MODULE_NAME);
        assertThat(getClass().getModule()).isSameAs(module);
    }

    @Test
    void testRunsOnModulePath()
            throws Exception
    {
        String output = runOnModulePath();
        assertThat(output).startsWith("ok " + MODULE_NAME);
    }

    /** Runs a small program (compiled by the source launcher) against the module on the module path. */
    private String runOnModulePath()
            throws IOException, InterruptedException
    {
        Path source = tempDir.resolve("ModuleSmoke.java");
        Files.writeString(source, """
                import io.airlift.compress.v3.zstd.ZstdJavaCompressor;
                import io.airlift.compress.v3.zstd.ZstdJavaDecompressor;

                public class ModuleSmoke
                {
                    public static void main(String[] args)
                            throws Exception
                    {
                        Module module = ZstdJavaCompressor.class.getModule();
                        if (!module.isNamed()) {
                            throw new AssertionError("not loaded as a named module: " + module);
                        }

                        byte[] input = "a small, repetitive input for the module smoke test. ".repeat(1000).getBytes();
                        ZstdJavaCompressor compressor = new ZstdJavaCompressor(9);
                        byte[] compressed = new byte[compressor.maxCompressedLength(input.length)];
                        int compressedSize = compressor.compress(input, 0, input.length, compressed, 0, compressed.length);
                        byte[] decompressed = new byte[input.length];
                        int decompressedSize = new ZstdJavaDecompressor().decompress(compressed, 0, compressedSize, decompressed, 0, decompressed.length);
                        if (decompressedSize != input.length || !java.util.Arrays.equals(input, decompressed)) {
                            throw new AssertionError("round trip failed");
                        }
                        System.out.println("ok " + module.getName());
                    }
                }
                """);

        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElseThrow());
        command.add("--module-path");
        command.add(compiledModule().toString());
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

    /** The compiled module: target/classes when run from the build, or the jar. */
    private static Path compiledModule()
    {
        try {
            return Path.of(ZstdJavaCompressor.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        }
        catch (java.net.URISyntaxException e) {
            throw new AssertionError(e);
        }
    }
}
