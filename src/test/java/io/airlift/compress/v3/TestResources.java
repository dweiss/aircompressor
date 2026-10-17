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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Reads files from {@code src/test/resources}.
 * <p>
 * The tests run patched into the (closed) {@code io.airlift.compress.v3} module, where a resource in a
 * package-shaped directory such as {@code data/zstd} is encapsulated: class loaders cannot see it. A class
 * of the module can always read its own module's resources, which is what this helper does.
 */
public final class TestResources
{
    private TestResources() {}

    /**
     * @param name the path below {@code src/test/resources}, for example {@code data/zstd/with-checksum.zst}
     */
    public static byte[] read(String name)
    {
        try (InputStream in = TestResources.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("test resource not found: " + name);
            }
            return in.readAllBytes();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
