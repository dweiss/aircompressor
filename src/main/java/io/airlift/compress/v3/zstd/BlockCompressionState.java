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

import java.util.Arrays;

class BlockCompressionState
{
    public final int[] hashTable;
    // hash chain match finders (and the second hash table of the double-fast strategy); null otherwise
    public final int[] chainTable;
    // row hash match finder: one tag byte per hash table entry, the first of each row being the row's head; null otherwise
    public final byte[] tagTable;

    // greedy/lazy strategies: positions below this one (relative to baseAddress) are in the tables
    public int nextToUpdate;

    // row hash match finder: the hashes of the next positions to insert, computed ahead so that their rows
    // can be touched early (the Java stand-in for zstd's prefetches); and the sink those touches feed
    public final int[] hashCache;
    public int prefetchSink;

    private final int baseAddress;

    // starting point of the window with respect to baseAddress
    private int windowBaseOffset;

    public BlockCompressionState(CompressionParameters parameters, int baseAddress)
    {
        this.baseAddress = baseAddress;
        hashTable = new int[1 << parameters.getHashLog()];
        chainTable = parameters.usesChainTable() ? new int[1 << parameters.getChainLog()] : null;
        tagTable = parameters.usesRowMatchFinder() ? new byte[1 << parameters.getHashLog()] : null;
        hashCache = parameters.usesRowMatchFinder() ? new int[LazyBlockCompressor.ROW_HASH_CACHE_SIZE] : null;
    }

    public void slideWindow(int slideWindowSize)
    {
        // The window base is expressed in the same coordinates as the table entries and must be
        // rebased too, otherwise it keeps pointing past the slid data and all matches are rejected.
        windowBaseOffset = Math.max(0, windowBaseOffset - slideWindowSize);
        nextToUpdate = Math.max(0, nextToUpdate - slideWindowSize);

        rebase(hashTable, slideWindowSize);
        if (chainTable != null) {
            rebase(chainTable, slideWindowSize);
        }
        // the tag table holds hashes and row heads, not positions
    }

    private static void rebase(int[] table, int slideWindowSize)
    {
        for (int i = 0; i < table.length; i++) {
            int newValue = table[i] - slideWindowSize;
            // if new value is negative, set it to zero branchless
            newValue = newValue & (~(newValue >> 31));
            table[i] = newValue;
        }
    }

    public void reset()
    {
        Arrays.fill(hashTable, 0);
        if (chainTable != null) {
            Arrays.fill(chainTable, 0);
        }
        if (tagTable != null) {
            Arrays.fill(tagTable, (byte) 0);
        }
        nextToUpdate = 0;
    }

    public void enforceMaxDistance(int inputLimit, int maxDistance)
    {
        int distance = inputLimit - baseAddress;

        int newOffset = distance - maxDistance;
        if (windowBaseOffset < newOffset) {
            windowBaseOffset = newOffset;
        }
    }

    public int getBaseAddress()
    {
        return baseAddress;
    }

    public int getWindowBaseOffset()
    {
        return windowBaseOffset;
    }
}
