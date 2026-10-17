# Compression for Java

> **This is a fork of [airlift/aircompressor](https://github.com/airlift/aircompressor).**
> It turns the library into a pure-Java module: no `sun.misc.Unsafe`, no native code, nothing to
> unpack or load at runtime, and no dependencies. The Hadoop integration is gone (the
> `CompressionCodec` implementations and the `HadoopStreams` factories), so this is **not a drop-in
> replacement** for code that used those. The Java packages (`io.airlift.compress.v3`) and the API of
> the plain Java compressors, decompressors and streams are unchanged.
> Its Maven coordinates are `com.carrotsearch.thirdparty.airlift:aircompressor-v3-jdk25`; upstream's
> `io.airlift:aircompressor-v3` releases do not contain these changes. Upstream commits are ported
> selectively; [UPSTREAM.md](UPSTREAM.md) records the last reviewed upstream commit and the decisions.

This library provides a set of compression algorithms implemented in pure Java.
The implementations access memory through `byte[]` arrays and `VarHandle` views only,
without `sun.misc.Unsafe`, JNI or bundled native libraries.

# Differences from upstream

* **No `sun.misc.Unsafe`.** Zstd, LZ4, Snappy and LZO access memory through `byte[]` arrays and
  `VarHandle` views only, with follow-up tuning of each codec to win back the speed lost to
  bounds-checked access.
* **No bundled native code.** Upstream's shared libraries, their loader and the `aircompressor.tmpdir`
  and `io.airlift.compress.v3.disable-native` system properties are gone; the LZ4, Snappy, Deflate and
  xxHash `*Native*` classes with them (and XXHash3 and `Lz4Compressor.create(int acceleration)`, which
  only worked natively). For Zstandard the foreign function binding is back, but against the `libzstd`
  installed on the system, used only when present and when native access is enabled; see below.
* **Optional system `libzstd`.** The system property `io.airlift.compress.v3.zstd.implementation`
  (`auto`, `java` or `native`) decides whether the installed `libzstd` is used; in the default `auto`
  mode that also requires `--enable-native-access=io.airlift.compress.v3` (`ALL-UNNAMED` on the class
  path) on the command line, otherwise the Java implementation is used, silently. The `Implementation`
  enum makes the same choice per object in code.
* **No Hadoop integration.** The `CompressionCodec` implementations, the `HadoopStreams`
  factories and the gzip package, which only existed for Hadoop, are gone, and so is the lzop
  (`.lzo` file) reader that was built on them. The library has no dependencies at all.
* **Zstd compression levels.** `new ZstdJavaCompressor(level)` and `new ZstdOutputStream(out, level)`
  select the compression level (upstream always uses level 3). Levels 3 and 4 (double fast) and
  5 to 12 (greedy, lazy and lazy2 parsers with zstd's row hash and hash chain match finders, ported
  from zstd 1.5.7) are implemented in Java; the level table is the one of zstd 1.5.7. Levels 1 and 2
  and 13 and up throw `IllegalArgumentException`.
* **A Java module.** The jar carries a module descriptor: `module io.airlift.compress.v3`, which
  exports every package, opens none of them to reflection and requires nothing but `java.base`. The
  test suite runs patched into the module, on the module path.
* **Standalone build.** The `io.airlift:airbase` parent pom is gone; `pom.xml` declares its plugins
  and dependency versions directly and keeps the same checkstyle rules and license header check.
* **Faster bzip2 decompression**: a bulk bit reader, Huffman lookup tables and a multi-chain
  inverse BWT, brought over from the Lingo4G decoder. `BZip2InputStream` decompresses
  concatenated streams (for example the output of `pbzip2`), or stops after the first stream and
  leaves the input positioned right after it, and it can decompress the blocks of a stream
  concurrently on an `ExecutorService`.
  `BZip2OutputStream` writes complete `.bz2` streams. (`CBZip2InputStream`, the name of the
  decompressor in version 3.8, is deprecated.)
* **lz4-java block streams**: `Lz4BlockInputStream` and `Lz4BlockOutputStream` read and write the
  format of lz4-java's `LZ4BlockInputStream`/`LZ4BlockOutputStream` (checksummed blocks with an
  end mark), on top of the Java LZ4 codec.
* **Snappy** uses the match-skip heuristic of current upstream Snappy, which speeds up
  incompressible input.

# Usage

```xml
<dependency>
    <groupId>com.carrotsearch.thirdparty.airlift</groupId>
    <artifactId>aircompressor-v3-jdk25</artifactId>
    <version>3.8.3</version>
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

Zstandard is provided by the `ZstdJavaCompressor` and `ZstdJavaDecompressor` classes, and by
`ZstdNativeCompressor` and `ZstdNativeDecompressor`, which call the `libzstd` installed on the system
through the foreign function API (nothing is bundled, nothing is written to disk). `ZstdCompressor.create()`,
`ZstdCompressor.create(level)` and `ZstdDecompressor.create()` return the native implementation when it is
available and the Java one otherwise; the check happens once, when the classes initialize. The system
property `io.airlift.compress.v3.zstd.implementation` controls it:

* `auto` (default): native when `libzstd` 1.4 or newer is found (`libzstd.so.1`, `libzstd.1.dylib` or
  `libzstd.dll`, or the file named by `io.airlift.compress.v3.zstd.implementation.library`) **and** native
  access is enabled for the module: run with `--enable-native-access=io.airlift.compress.v3`
  (`ALL-UNNAMED` on the class path). Without that flag the JDK would print a warning on the first
  foreign call, which a library should not cause on its own, so Java is used instead, silently.
* `java`: never load the native library.
* `native`: always use it; `create()` and the native constructors throw `IllegalStateException` when
  it cannot be loaded, and the JDK prints its warning if native access is not enabled.

The choice can also be made in code, per object, with the `Implementation` enum (`AUTO`, `NATIVE`,
`JAVA`): `ZstdCompressor.create(Implementation)`, `ZstdCompressor.create(level, Implementation)`,
`ZstdDecompressor.create(Implementation)`, `new ZstdOutputStream(out, level, Implementation)` and
`new ZstdInputStream(in, Implementation)`. `NATIVE` throws `IllegalStateException` when the library
cannot be used; the property still has the last word in that it decides whether the library is loaded
at all (`java` vetoes it, `auto` requires native access). Which implementation this JVM ended up with,
and why, is logged once at `FINE` (`System.Logger` level `DEBUG`) under the logger
`io.airlift.compress.v3.zstd.ZstdNative`.

The native implementation covers one-shot compression and decompression (every zstd level, byte arrays
and memory segments) and the streams (`ZstdOutputStream` writes through the native streaming API
without buffering the input, `ZstdInputStream` decompresses straight into the caller's buffer). Both
implementations report invalid input as `MalformedInputException`.

The Zstandard streaming format is supported by `ZstdInputStream` and `ZstdOutputStream`.
Both compress at level 3 by default; `new ZstdJavaCompressor(level)` and
`new ZstdOutputStream(out, level)` select another level. The Java encoder accepts only levels whose
match-finding strategy it implements: the double-fast levels 3 and 4 and the greedy and lazy levels 5
to 12 (the level table, the parsers and the choice between the row hash and the hash chain match
finder follow zstd 1.5.7). Other levels throw `IllegalArgumentException` unless the native library is
in use, which accepts every level. For inputs of 256 KB and less, zstd assigns binary tree strategies
to some of the levels 9 to 12; the Java encoder uses the lazy2 parser for those and compresses slightly
less than the C library would.

## [LZ4](https://lz4.org/)
LZ4 is an extremely fast compression algorithm that provides compression ratios comparable
to Snappy and LZO. LZ4 is an excellent choice for applications that require high-performance
compression and decompression.

LZ4 is provided by `Lz4JavaCompressor` and `Lz4JavaDecompressor`.

Streams in the format of [lz4-java](https://github.com/yawkat/lz4-java)'s `LZ4BlockOutputStream`
are supported by `Lz4BlockInputStream` and `Lz4BlockOutputStream`.

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

# Requirements

This library requires a Java 25+ virtual machine. It does not use `sun.misc.Unsafe` and does not depend on the platform's byte order.

Everything works without any native library. To let Zstandard use the `libzstd` installed on the system
(1.4 or newer), start the JVM with `--enable-native-access=io.airlift.compress.v3` (or
`--enable-native-access=ALL-UNNAMED` when the jar is on the class path); without that flag the JDK would
warn about the foreign function calls, so the library stays with its Java implementation. The system
property `io.airlift.compress.v3.zstd.implementation` overrides the choice: `java` never loads the native
library, `native` always does (and fails if it is missing), `auto` is the default described above.

# Users

This library is used in projects such as Trino (https://trino.io), a distributed SQL engine.
