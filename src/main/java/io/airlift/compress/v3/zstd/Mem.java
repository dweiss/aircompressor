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

/** Little-endian access on byte arrays through VarHandle views (replaces sun.misc.Unsafe). */
final class Mem
{
    private static final VarHandle SHORT_LE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

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

    static byte getByte(byte[] base, long offset)
    {
        return base[(int) offset];
    }

    static short getShort(byte[] base, long offset)
    {
        return (short) SHORT_LE.get(base, (int) offset);
    }

    static int getInt(byte[] base, long offset)
    {
        return (int) INT_LE.get(base, (int) offset);
    }

    static long getLong(byte[] base, long offset)
    {
        return (long) LONG_LE.get(base, (int) offset);
    }

    static void putByte(byte[] base, long offset, byte value)
    {
        base[(int) offset] = value;
    }

    static void putShort(byte[] base, long offset, short value)
    {
        SHORT_LE.set(base, (int) offset, value);
    }

    static void putInt(byte[] base, long offset, int value)
    {
        INT_LE.set(base, (int) offset, value);
    }

    static void putLong(byte[] base, long offset, long value)
    {
        LONG_LE.set(base, (int) offset, value);
    }

    static void copyMemory(byte[] srcBase, long srcOffset, byte[] dstBase, long dstOffset, long length)
    {
        System.arraycopy(srcBase, (int) srcOffset, dstBase, (int) dstOffset, (int) length);
    }
}
