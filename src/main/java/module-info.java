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

/**
 * Pure-Java compression codecs: LZ4, Snappy, Zstandard, LZO, bzip2 and Deflate, plus xxHash. The module
 * depends on nothing but {@code java.base}; every package is exported and none is open to reflection.
 */
module io.airlift.compress.v3 {
    exports io.airlift.compress.v3;
    exports io.airlift.compress.v3.bzip2;
    exports io.airlift.compress.v3.deflate;
    exports io.airlift.compress.v3.lz4;
    exports io.airlift.compress.v3.lzo;
    exports io.airlift.compress.v3.snappy;
    exports io.airlift.compress.v3.xxhash;
    exports io.airlift.compress.v3.zstd;
}
