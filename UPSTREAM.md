# Syncing with upstream

This fork tracks [airlift/aircompressor](https://github.com/airlift/aircompressor) selectively:
upstream commits are reviewed and ported (cherry-picked or reimplemented) when they apply to the
pure-Java code base. Native-only changes, native library updates, the `airbase` parent-pom
bumps and anything about the Hadoop codecs and streams (removed here) do not apply.

## Last reviewed upstream commit

- `c325c36` on `airlift/aircompressor` `master`, 2026-10-16 ("[maven-release-plugin] prepare for
  next development iteration", right after the 3.11 release).

## How to review the next batch

```
git fetch upstream
git log --cherry-pick --right-only --no-merges --format='%h %ad %s' --date=short \
    master-jdk25...upstream/master
```

Lists upstream commits whose patch is not already on `master-jdk25`. For each one decide: port
(`git cherry-pick -x <sha>`, or by hand when the Unsafe/native code it touches no longer exists
here), or skip. Record the new last reviewed commit above and the decisions below.

## Review log

### Through `c325c36` (3.11), reviewed 2026-10-17

Ported:

- `4f6d8ea` Handle Snappy length headers consistently (cherry-picked).
- `b528f25` Use hardware CRC32C for Snappy checksums (cherry-picked).
- `48c91cf` Avoid temp array in xxHash updateLE (cherry-picked).
- `fbed7a8` Report corrupt input as MalformedInputException: only its tests
  (`TestZstdMalformedInput`, tightened assertions in `AbstractTestZstd` and `AbstractTestLz4`);
  the fix itself concerned the native decompressors.

Skipped:

- Native-only: `6a8d655` (ZSTD_isError without a downcall), `0e21314` (deflate native segment
  reuse), `759d00b`, `7d2fb17`, `1c410c3`, `3dbd347` (native library updates), `b1e3fb7` (drop
  macOS x86_64 natives).
- Build and release bookkeeping: `airbase` bumps (`46512f6`, `e08d92f`), release-plugin commits for
  3.8 to 3.11, GitHub Actions bumps (`4c06d16`, `bdfac87`, `3f50e29`, `f247a92`), Maven wrapper
  bumps (`f9a916f`, `5ac0dab`).
- Dependency bumps: taken later, directly to the latest releases (zstd-jni 1.5.7-23, lz4-java 1.12.0)
  rather than by cherry-picking `a6ea509`, `b128548`, `8dfa74a`, `a81ef49`. zstd-jni is the reference
  the zstd level tests compare sizes against, so re-run `TestZstdCompressionLevels` after bumping it.
