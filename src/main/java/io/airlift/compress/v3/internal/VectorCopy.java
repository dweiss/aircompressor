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

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Wide memory copies through the incubating Vector API.
 * <p>
 * This class must only be referenced when {@link VectorSupport#isEnabled()} is true: loading it fails with a
 * {@link LinkageError} when the {@code jdk.incubator.vector} module is not resolved.
 */
public final class VectorCopy
{
    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;

    static final int VECTOR_BYTES = SPECIES.length();

    private VectorCopy() {}

    /**
     * Copies {@code length} bytes, rounded up to whole vectors: reads up to {@code VECTOR_BYTES - 1} bytes past
     * {@code source + length} and writes as far past {@code destination + length}. The source and destination arrays
     * are expected to be distinct.
     */
    public static void copyRoundedUp(byte[] source, int sourceIndex, byte[] destination, int destinationIndex, int length)
    {
        int end = destinationIndex + length;
        do {
            ByteVector.fromArray(SPECIES, source, sourceIndex).intoArray(destination, destinationIndex);
            sourceIndex += VECTOR_BYTES;
            destinationIndex += VECTOR_BYTES;
        }
        while (destinationIndex < end);
    }
}
