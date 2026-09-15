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
package io.airlift.compress.v3.internal;

/**
 * Availability of the incubating Vector API ({@code jdk.incubator.vector}) at runtime. The module is optional: it
 * has to be added with {@code --add-modules jdk.incubator.vector}, and the Java implementations fall back to
 * scalar code when it is absent. Setting the {@code io.airlift.compress.v3.disable-vector} system property disables
 * the vector paths even when the module is present.
 */
public final class VectorSupport
{
    private static final boolean ENABLED;
    private static final int VECTOR_BYTES;

    static {
        boolean enabled = false;
        int vectorBytes = 0;
        if (System.getProperty("io.airlift.compress.v3.disable-vector") == null) {
            try {
                vectorBytes = VectorCopy.VECTOR_BYTES;
                // 128-bit vectors and up; nothing to gain over long-at-a-time code below that
                enabled = vectorBytes >= 16;
            }
            catch (LinkageError ignored) {
                // jdk.incubator.vector is not resolved in this runtime
            }
        }
        ENABLED = enabled;
        VECTOR_BYTES = enabled ? vectorBytes : 0;
    }

    private VectorSupport() {}

    public static boolean isEnabled()
    {
        return ENABLED;
    }

    /**
     * Width of the preferred vector in bytes, or 0 when vectors are not enabled.
     */
    public static int vectorBytes()
    {
        return VECTOR_BYTES;
    }
}
