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

/**
 * Position hashes shared by the match finders (zstd's ZSTD_hash4 .. ZSTD_hash8).
 */
final class ZstdHash
{
    private static final int PRIME_4_BYTES = 0x9E3779B1;
    private static final long PRIME_5_BYTES = 0xCF1BBCDCBBL;
    private static final long PRIME_6_BYTES = 0xCF1BBCDCBF9BL;
    private static final long PRIME_7_BYTES = 0xCF1BBCDCBFA563L;
    private static final long PRIME_8_BYTES = 0xCF1BBCDCB7A56463L;

    private ZstdHash()
    {
    }

    /**
     * Hashes the {@code matchSearchLength} (4..8) bytes at {@code inputAddress} into {@code bits} bits.
     * Reads 8 bytes for lengths above 4, so the caller must guarantee they are within the input.
     */
    static int hash(byte[] inputBase, long inputAddress, int bits, int matchSearchLength)
    {
        return switch (matchSearchLength) {
            case 8 -> hash8((long) Mem.LONG_LE.get(inputBase, (int) inputAddress), bits);
            case 7 -> hash7((long) Mem.LONG_LE.get(inputBase, (int) inputAddress), bits);
            case 6 -> hash6((long) Mem.LONG_LE.get(inputBase, (int) inputAddress), bits);
            case 5 -> hash5((long) Mem.LONG_LE.get(inputBase, (int) inputAddress), bits);
            default -> hash4((int) Mem.INT_LE.get(inputBase, (int) inputAddress), bits);
        };
    }

    static int hash4(int value, int bits)
    {
        return (value * PRIME_4_BYTES) >>> (Integer.SIZE - bits);
    }

    static int hash5(long value, int bits)
    {
        return (int) (((value << (Long.SIZE - 40)) * PRIME_5_BYTES) >>> (Long.SIZE - bits));
    }

    static int hash6(long value, int bits)
    {
        return (int) (((value << (Long.SIZE - 48)) * PRIME_6_BYTES) >>> (Long.SIZE - bits));
    }

    static int hash7(long value, int bits)
    {
        return (int) (((value << (Long.SIZE - 56)) * PRIME_7_BYTES) >>> (Long.SIZE - bits));
    }

    static int hash8(long value, int bits)
    {
        return (int) ((value * PRIME_8_BYTES) >>> (Long.SIZE - bits));
    }
}
