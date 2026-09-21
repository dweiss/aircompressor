# Compression for Java

> **This is a fork of [airlift/aircompressor](https://github.com/airlift/aircompressor).**
> It turns the library into a pure-Java one: no `sun.misc.Unsafe`, no native code, nothing to
> unpack or load at runtime. Java packages (`io.airlift.compress.v3`) and the API of the Java
> implementations are unchanged, so it is a drop-in replacement wherever the Java codecs were used.
> Its Maven coordinates are `com.carrotsearch.thirdparty.airlift:aircompressor-v3-jdk25`; upstream's
> `io.airlift:aircompressor-v3` releases do not contain these changes.

This library provides a set of compression algorithms implemented in pure Java.
The implementations access memory through `byte[]` arrays and `VarHandle` views only,
without `sun.misc.Unsafe`, JNI or bundled native libraries.

# Differences from upstream

* **No `sun.misc.Unsafe`.** Zstd, LZ4, Snappy and LZO access memory through `byte[]` arrays and
  `VarHandle` views only, with follow-up tuning of each codec to win back the speed lost to
  bounds-checked access.
* **No native code.** The `java.lang.foreign` bindings (`*Native*` classes), the bundled shared
  libraries and their loader are gone, together with the `aircompressor.tmpdir` and
  `io.airlift.compress.v3.disable-native` system properties. The `create()` factories always
  return the Java implementations. Removed along the way, because they only worked with native code:
  XXHash3, `Lz4Compressor.create(int acceleration)`, `ZstdCompressor.create(int compressionLevel)`
  and the `useNative` argument of `Lz4HadoopStreams` and `SnappyHadoopStreams`.
* **Standalone build.** The `io.airlift:airbase` parent pom is gone; `pom.xml` declares its plugins
  and dependency versions directly and keeps the same checkstyle rules and license header check.
* **Faster bzip2 decompression**: a bulk bit reader, Huffman lookup tables and a multi-chain
  inverse BWT, brought over from the Lingo4G decoder. `CBZip2InputStream` is public: it decompresses
  concatenated streams (for example the output of `pbzip2`), or stops after the first stream and
  leaves the input positioned right after it, and it can decompress the blocks of a stream
  concurrently on an `ExecutorService` (also available as `new BZip2HadoopStreams(executor, n)`).
* **Snappy** uses the match-skip heuristic of current upstream Snappy, which speeds up
  incompressible input.

# Usage

```xml
<dependency>
    <groupId>com.carrotsearch.thirdparty.airlift</groupId>
    <artifactId>aircompressor-v3-jdk25</artifactId>
    <version>...</version>
</dependency>
```


Each algorithm provides a simple block compression API using the `io.airlift.compress.v3.Compressor` 
and `io.airlift.compress.v3.Decompressor` classes. Block compression is the simplest form of
which simply compresses a small block of data provided as a `byte[]`, or more generally a
`java.lang.foreign.MemorySegment`. Each algorithm may have one or more streaming format
which typically produces a sequence of block compressed chunks.

## byte array API
```java
byte[] data = ...

Compressor compressor = new Lz4JavaCompressor();
byte[] compressed = new byte[compressor.maxCompressedLength(data.length)];
int compressedSize = compressor.compress(data, 0, data.length, compressed, 0, compressed.length);

Decompressor decompressor = new Lz4JavaDecompressor();
byte[] uncompressed = new byte[data.length];
int uncompressedSize = decompressor.decompress(compressed, 0, compressedSize, uncompressed, 0, uncompressed.length);
```

## MemorySegment API
```java
Arena arena = ...
MemorySegment data = ...

Compressor compressor = new Lz4JavaCompressor();
MemorySegment compressed = arena.allocate(compressor.maxCompressedLength(toIntExact(data.byteSize())));
int compressedSize = compressor.compress(data, compressed);
compressed = compressed.asSlice(0, compressedSize);

Decompressor decompressor = new Lz4JavaDecompressor();
MemorySegment uncompressed = arena.allocate(data.byteSize());
int uncompressedSize = decompressor.decompress(compressed, uncompressed);
uncompressed = uncompressed.asSlice(0, uncompressedSize);
```

# Algorithms

## [Zstandard (Zstd)](https://facebook.github.io/zstd/) **(Recommended)**
Zstandard is the recommended algorithm for most compression. It provides
superior compression and performance at all levels compared to zlib. Zstandard is
an excellent choice for most use cases, especially storage and bandwidth constrained
network transfer.

Zstandard is provided by the `ZstdJavaCompressor` and `ZstdJavaDecompressor` classes.

The Zstandard streaming format is supported by `ZstdInputStream` and `ZstdOutputStream`.

## [LZ4](https://lz4.org/)
LZ4 is an extremely fast compression algorithm that provides compression ratios comparable
to Snappy and LZO. LZ4 is an excellent choice for applications that require high-performance
compression and decompression.

LZ4 is provided by `Lz4JavaCompressor` and `Lz4JavaDecompressor`.

The [LZ4 frame format](https://github.com/lz4/lz4/blob/dev/doc/lz4_Frame_format.md), which is the
format produced by the `lz4` command line tool, is supported by `Lz4FrameJavaCompressor` and
`Lz4FrameJavaDecompressor`.

## [Snappy](https://google.github.io/snappy/)
Snappy is not as fast as LZ4, but provides a guarantee on memory usage that makes it a good
choice for extremely resource-limited environments (e.g. embedded systems like a network 
switch). If your application is not highly resource constrained, LZ4 is a better choice.

Snappy is provided by `SnappyJavaCompressor` and `SnappyJavaDecompressor`.

The Snappy framed format is supported by `SnappyFramedInputStream` and `SnappyFramedOutputStream`.

## [LZO](https://www.oberhumer.com/opensource/lzo/) 
LZO is only provided for compatibility with existing systems that use LZO. We recommend 
rewriting LZO data using Zstandard or LZ4. 

The Java implementation of LZO is provided by `LzoCompressor` and `LzoDecompressor`.
Due to licensing issues, the LZO only has a Java implementation which is based on LZ4.

## Deflate
Deflate is the block compression algorithm used by the `gzip` and `zlib` libraries. Deflate is
provided for compatibility with existing systems that use Deflate. We recommend rewriting
Deflate data using Zstandard which provides superior compression and performance.

The implementation of Deflate is provided by `DeflateCompressor` and `DeflateDecompressor`.
This is implemented in the built-in Java libraries which internally use the native code.

# Hash Functions

## [XXHash64](https://xxhash.com/)
XXHash64 is an extremely fast non-cryptographic hash function with excellent distribution properties.

The implementation is provided by `XxHash64JavaHasher`. The `XxHash64Hasher` interface provides
static one-shot methods and factories for streaming hashers.

```java
// One-shot hashing
long hash = XxHash64Hasher.hash(data);
long hash = XxHash64Hasher.hash(data, seed);

// Streaming hashing
try (XxHash64Hasher hasher = XxHash64Hasher.create()) {
    hasher.update(chunk1);
    hasher.update(chunk2);
    long hash = hasher.digest();
}
```

## [XXHash32](https://xxhash.com/)
XXHash32 is the 32-bit variant of the XXHash family. It is provided primarily for formats that
require a 32-bit hash, such as the LZ4 frame format. For general-purpose hashing, prefer XXHash64.

The implementation is provided by `XxHash32JavaHasher`. The `XxHash32Hasher` interface provides
static one-shot methods and factories for streaming hashers.

```java
// One-shot hashing
int hash = XxHash32Hasher.hash(data);
int hash = XxHash32Hasher.hash(data, seed);

// Streaming hashing
try (XxHash32Hasher hasher = XxHash32Hasher.create()) {
    hasher.update(chunk1);
    hasher.update(chunk2);
    int hash = hasher.digest();
}
```

# Hadoop Compression

In addition to the raw block encoders, there are implementations of the
Hadoop streams for the above algorithms. In addition, implementations of
gzip and bzip2 are provided so that all standard Hadoop algorithms are available.
The bzip2 decompressor is pure Java, reads its input in 64 KiB chunks and also
decompresses concatenated bzip2 streams (for example the output of `pbzip2`).
`new BZip2HadoopStreams(executor, maxConcurrentInFlight)` creates input streams that
decompress the blocks of the data in parallel.

The `HadoopStreams` class provides a factory for creating `InputStream` and `OutputStream`
implementations without the need for any Hadoop dependencies.  For environments 
that have Hadoop dependencies, each algorithm also provides a `CompressionCodec` class.

# Requirements

This library requires a Java 25+ virtual machine. It does not use `sun.misc.Unsafe` and does not depend on the platform's byte order.

# Users

This library is used in projects such as Trino (https://trino.io), a distributed SQL engine.
