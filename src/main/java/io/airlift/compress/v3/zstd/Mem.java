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

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/** Little-endian multi-byte access on byte arrays through VarHandle views (replaces sun.misc.Unsafe). */
final class Mem
{
    static final VarHandle SHORT_LE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    static final VarHandle INT_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private Mem() {}

    /**
     * Returns the backing byte array of a heap segment (its {@link MemorySegment#address()} is then the offset
     * into that array), or null if the segment is not backed by a byte array.
     */
    static byte[] heapArray(MemorySegment segment)
    {
        Object base = segment.heapBase().orElse(null);
        if (base instanceof byte[] array) {
            return array;
        }
        return null;
    }

    static void copyMemory(byte[] srcBase, long srcOffset, byte[] dstBase, long dstOffset, long length)
    {
        System.arraycopy(srcBase, (int) srcOffset, dstBase, (int) dstOffset, (int) length);
    }
}
