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
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Selecting the implementation in code. (The test JVM has native access enabled, so AUTO follows the library's presence.) */
class TestZstdImplementation
{
    @Test
    void testJavaIsAlwaysAvailable()
            throws IOException
    {
        assertThat(ZstdCompressor.create(Implementation.JAVA)).isInstanceOf(ZstdJavaCompressor.class);
        assertThat(ZstdCompressor.create(9, Implementation.JAVA)).isInstanceOf(ZstdJavaCompressor.class);
        assertThat(ZstdDecompressor.create(Implementation.JAVA)).isInstanceOf(ZstdJavaDecompressor.class);
        assertThat(new ZstdOutputStream(new ByteArrayOutputStream(), 3, Implementation.JAVA).isNative()).isFalse();
        assertThat(new ZstdInputStream(new ByteArrayInputStream(new byte[0]), Implementation.JAVA).isNative()).isFalse();
    }

    @Test
    void testNativeFollowsTheLibrary()
            throws IOException
    {
        if (ZstdNative.isEnabled()) {
            assertThat(ZstdCompressor.create(Implementation.NATIVE)).isInstanceOf(ZstdNativeCompressor.class);
            assertThat(ZstdCompressor.create(19, Implementation.NATIVE)).isInstanceOf(ZstdNativeCompressor.class);
            assertThat(ZstdDecompressor.create(Implementation.NATIVE)).isInstanceOf(ZstdNativeDecompressor.class);
            assertThat(new ZstdOutputStream(new ByteArrayOutputStream(), 3, Implementation.NATIVE).isNative()).isTrue();
            assertThat(new ZstdInputStream(new ByteArrayInputStream(new byte[0]), Implementation.NATIVE).isNative()).isTrue();
        }
        else {
            assertThatThrownBy(() -> ZstdCompressor.create(Implementation.NATIVE)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ZstdDecompressor.create(Implementation.NATIVE)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new ZstdOutputStream(new ByteArrayOutputStream(), 3, Implementation.NATIVE)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new ZstdInputStream(new ByteArrayInputStream(new byte[0]), Implementation.NATIVE)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void testAutoPrefersNative()
            throws IOException
    {
        boolean expectNative = ZstdNative.isEnabled();
        assertThat(ZstdCompressor.create() instanceof ZstdNativeCompressor).isEqualTo(expectNative);
        assertThat(ZstdCompressor.create(Implementation.AUTO) instanceof ZstdNativeCompressor).isEqualTo(expectNative);
        assertThat(ZstdDecompressor.create() instanceof ZstdNativeDecompressor).isEqualTo(expectNative);
        assertThat(new ZstdOutputStream(new ByteArrayOutputStream()).isNative()).isEqualTo(expectNative);
        assertThat(new ZstdInputStream(new ByteArrayInputStream(new byte[0])).isNative()).isEqualTo(expectNative);
    }
}
