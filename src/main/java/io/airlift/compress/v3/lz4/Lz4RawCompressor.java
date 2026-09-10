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
package io.airlift.compress.v3.lz4;

import java.util.Arrays;

import static io.airlift.compress.v3.lz4.Lz4Constants.LAST_LITERAL_SIZE;
import static io.airlift.compress.v3.lz4.Lz4Constants.MIN_MATCH;
import static io.airlift.compress.v3.lz4.Lz4Constants.SIZE_OF_LONG;
import static io.airlift.compress.v3.lz4.Lz4Constants.SIZE_OF_SHORT;
import static java.lang.Math.clamp;
import static java.lang.Math.toIntExact;

final class Lz4RawCompressor
{
    private static final int MAX_INPUT_SIZE = 0x7E000000;   /* 2 113 929 216 bytes */

    private static final int HASH_LOG = 12;

    private static final int MIN_TABLE_SIZE = 16;
    public static final int MAX_TABLE_SIZE = (1 << HASH_LOG);

    private static final int COPY_LENGTH = 8;
    private static final int MATCH_FIND_LIMIT = COPY_LENGTH + MIN_MATCH;

    private static final int MIN_LENGTH = MATCH_FIND_LIMIT + 1;

    private static final int ML_BITS = 4;
    private static final int ML_MASK = (1 << ML_BITS) - 1;
    private static final int RUN_BITS = 8 - ML_BITS;
    private static final int RUN_MASK = (1 << RUN_BITS) - 1;

    private static final int MAX_DISTANCE = ((1 << 16) - 1);

    private static final int SKIP_TRIGGER = 6;  /* Increase this value ==> compression run slower on incompressible data */

    private Lz4RawCompressor() {}

    private static int hash(long value, int mask)
    {
        // Multiplicative hash. It performs the equivalent to
        // this computation:
        //
        //  value * frac(a)
        //
        // for some real number 'a' with a good & random mix
        // of 1s and 0s in its binary representation
        //
        // For performance, it does it using fixed point math
        return (int) ((value * 889523592379L >>> 28) & mask);
    }

    public static int maxCompressedLength(int sourceLength)
    {
        return sourceLength + sourceLength / 255 + 16;
    }

    public static int compress(
            final byte[] inputBase,
            final long inputAddress,
            final int inputLength,
            final byte[] outputBase,
            final long outputAddress,
            final long maxOutputLength,
            final int[] table)
    {
        // int arithmetic throughout: every value is an index into a byte[]
        return compress(inputBase, toIntExact(inputAddress), inputLength, outputBase, toIntExact(outputAddress), toIntExact(maxOutputLength), table);
    }

    private static int compress(
            final byte[] inputBase,
            final int inputAddress,
            final int inputLength,
            final byte[] outputBase,
            final int outputAddress,
            final int maxOutputLength,
            final int[] table)
    {
        int tableSize = computeTableSize(inputLength);
        Arrays.fill(table, 0, tableSize, 0);

        int mask = tableSize - 1;

        if (inputLength > MAX_INPUT_SIZE) {
            throw new IllegalArgumentException("Max input length exceeded");
        }

        if (maxOutputLength < maxCompressedLength(inputLength)) {
            throw new IllegalArgumentException("Max output length must be larger than " + maxCompressedLength(inputLength));
        }

        int input = inputAddress;
        int output = outputAddress;

        final int inputLimit = inputAddress + inputLength;
        final int matchFindLimit = inputLimit - MATCH_FIND_LIMIT;
        final int matchLimit = inputLimit - LAST_LITERAL_SIZE;

        if (inputLength < MIN_LENGTH) {
            output = emitLastLiteral(outputBase, output, inputBase, input, inputLimit - input);
            return output - outputAddress;
        }

        int anchor = input;

        // First Byte
        // put position in hash
        table[hash((long) Mem.LONG_LE.get(inputBase, input), mask)] = input - inputAddress;

        input++;
        int nextHash = hash((long) Mem.LONG_LE.get(inputBase, input), mask);

        boolean done = false;
        do {
            int nextInputIndex = input;
            int findMatchAttempts = 1 << SKIP_TRIGGER;
            int step = 1;

            // find 4-byte match
            int matchIndex;
            do {
                int hash = nextHash;
                input = nextInputIndex;
                nextInputIndex += step;

                step = (findMatchAttempts++) >>> SKIP_TRIGGER;

                if (nextInputIndex > matchFindLimit) {
                    return emitLastLiteral(outputBase, output, inputBase, anchor, inputLimit - anchor) - outputAddress;
                }

                // get position on hash
                matchIndex = inputAddress + table[hash];
                nextHash = hash((long) Mem.LONG_LE.get(inputBase, nextInputIndex), mask);

                // put position on hash
                table[hash] = input - inputAddress;
            }
            while ((int) Mem.INT_LE.get(inputBase, matchIndex) != (int) Mem.INT_LE.get(inputBase, input) || matchIndex + MAX_DISTANCE < input);

            // catch up
            while ((input > anchor) && (matchIndex > inputAddress) && (inputBase[input - 1] == inputBase[matchIndex - 1])) {
                --input;
                --matchIndex;
            }

            int literalLength = input - anchor;
            int tokenAddress = output;

            output = emitLiteral(inputBase, outputBase, anchor, literalLength, tokenAddress);

            // next match
            while (true) {
                // find match length
                int matchLength = count(inputBase, input + MIN_MATCH, matchLimit, matchIndex + MIN_MATCH);
                output = emitMatch(outputBase, output, tokenAddress, (short) (input - matchIndex), matchLength);

                input += matchLength + MIN_MATCH;

                anchor = input;

                // are we done?
                if (input > matchFindLimit) {
                    done = true;
                    break;
                }

                int position = input - 2;
                table[hash((long) Mem.LONG_LE.get(inputBase, position), mask)] = position - inputAddress;

                // Test next position
                int hash = hash((long) Mem.LONG_LE.get(inputBase, input), mask);
                matchIndex = inputAddress + table[hash];
                table[hash] = input - inputAddress;

                if (matchIndex + MAX_DISTANCE < input || (int) Mem.INT_LE.get(inputBase, matchIndex) != (int) Mem.INT_LE.get(inputBase, input)) {
                    input++;
                    nextHash = hash((long) Mem.LONG_LE.get(inputBase, input), mask);
                    break;
                }

                // go for another match
                tokenAddress = output++;
                outputBase[tokenAddress] = (byte) 0;
            }
        }
        while (!done);

        // Encode Last Literals
        output = emitLastLiteral(outputBase, output, inputBase, anchor, inputLimit - anchor);

        return output - outputAddress;
    }

    private static int emitLiteral(byte[] inputBase, byte[] outputBase, int input, int literalLength, int output)
    {
        output = encodeRunLength(outputBase, output, literalLength);

        final int outputLimit = output + literalLength;
        do {
            Mem.LONG_LE.set(outputBase, output, (long) Mem.LONG_LE.get(inputBase, input));
            input += SIZE_OF_LONG;
            output += SIZE_OF_LONG;
        }
        while (output < outputLimit);

        return outputLimit;
    }

    private static int emitMatch(byte[] outputBase, int output, int tokenAddress, short offset, int matchLength)
    {
        // write offset
        Mem.SHORT_LE.set(outputBase, output, offset);
        output += SIZE_OF_SHORT;

        // write match length
        if (matchLength >= ML_MASK) {
            outputBase[tokenAddress] = (byte) (outputBase[tokenAddress] | ML_MASK);
            int remaining = matchLength - ML_MASK;
            while (remaining >= 510) {
                Mem.SHORT_LE.set(outputBase, output, (short) 0xFFFF);
                output += SIZE_OF_SHORT;
                remaining -= 510;
            }
            if (remaining >= 255) {
                outputBase[output++] = (byte) 255;
                remaining -= 255;
            }
            outputBase[output++] = (byte) remaining;
        }
        else {
            outputBase[tokenAddress] = (byte) (outputBase[tokenAddress] | matchLength);
        }

        return output;
    }

    /**
     * matchAddress must be < inputAddress
     */
    static int count(byte[] inputBase, final int inputAddress, final int inputLimit, final int matchAddress)
    {
        int input = inputAddress;
        int match = matchAddress;

        int remaining = inputLimit - inputAddress;

        // first, compare long at a time
        int count = 0;
        while (count < remaining - (SIZE_OF_LONG - 1)) {
            long diff = (long) Mem.LONG_LE.get(inputBase, match) ^ (long) Mem.LONG_LE.get(inputBase, input);
            if (diff != 0) {
                return count + (Long.numberOfTrailingZeros(diff) >> 3);
            }

            count += SIZE_OF_LONG;
            input += SIZE_OF_LONG;
            match += SIZE_OF_LONG;
        }

        while (count < remaining && inputBase[match] == inputBase[input]) {
            count++;
            match++;
            input++;
        }

        return count;
    }

    private static int emitLastLiteral(
            final byte[] outputBase,
            final int outputAddress,
            final byte[] inputBase,
            final int inputAddress,
            final int length)
    {
        int output = encodeRunLength(outputBase, outputAddress, length);
        Mem.copyMemory(inputBase, inputAddress, outputBase, output, length);

        return output + length;
    }

    private static int encodeRunLength(
            final byte[] base,
            int output,
            final int length)
    {
        if (length >= RUN_MASK) {
            base[output++] = (byte) (RUN_MASK << ML_BITS);

            int remaining = length - RUN_MASK;
            while (remaining >= 255) {
                base[output++] = (byte) 255;
                remaining -= 255;
            }
            base[output++] = (byte) remaining;
        }
        else {
            base[output++] = (byte) (length << ML_BITS);
        }

        return output;
    }

    static int computeTableSize(int inputSize)
    {
        // smallest power of 2 larger than inputSize
        int target = Integer.highestOneBit(inputSize - 1) << 1;

        // keep it between MIN_TABLE_SIZE and MAX_TABLE_SIZE
        return clamp(target, MIN_TABLE_SIZE, MAX_TABLE_SIZE);
    }
}
