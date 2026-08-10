# Memory mapping benchmark

ClassGraph can read jarfiles through `FileChannel#map` rather than through positioned `FileChannel#read` calls.
In 4.x this was an opt-in, `ClassGraph#enableMemoryMapping()`, and it was off by default. This file records what
mapping is actually worth on each of the three major operating systems, so that the decision to keep the option,
remove it, or turn mapping on by default rests on measurements rather than on folklore. The measurements are
what led to the 5.0 behavior: the option is gone, and ClassGraph maps on Windows and reads through the channel
everywhere else.

## How the numbers were produced

`benchmark/Bench.java` runs paired scans: within each pair it scans the whole corpus once with mapping off and
once with mapping on, swapping which arm goes first on alternate pairs so that neither arm always pays the
warm-up cost. The first third of the pairs is discarded as JIT warm-up, and the median of the rest is reported.
Every scan is a full `enableAllInfo()` scan followed by `getAllClasses()`.

Three workloads, all built from the same Maven dependency set:

| Workload | Contents | Point of it |
|---|---|---|
| `corpus` | 144 jars, 122 MB, 61555 classes | An ordinary application classpath |
| `mixed`  | 280 jars, 172 MB, 61555 classes | The same jars plus their source jars, so only about a third of the bytes are classfiles, as on a real classpath full of resources |
| `huge`   | 1 jar, 59 MB (`kotlin-compiler-embeddable`) | One very large jar, where mapping has the least per-file overhead to amortize |

Two page-cache states. **Warm** means the corpus was just read, so it is all in the page cache. **Cold** means
the corpus was evicted first — with `posix_fadvise(POSIX_FADV_DONTNEED)` per file on Linux, and with `purge` on
macOS. Windows has no equivalent that does not need a third-party tool, so Windows is measured warm only.

The cross-platform numbers come from GitHub Actions runners (`ubuntu-latest`, `windows-latest`, `macos-latest`)
on Zulu JDK 17 and JDK 25. JDK 17 maps and unmaps through the cleaner; JDK 22 and above map and unmap through a
`java.lang.foreign.Arena`, so both paths are covered. Linux and macOS have two independent samples; the Windows
jobs in the first run failed for an unrelated reason, so Windows has one.

To take the measurements again: populate the corpus with
`./mvnw -f benchmark/pom.xml dependency:copy-dependencies -DoutputDirectory=benchmark/corpus`, then run
`benchmark/Bench.java` locally, or copy `benchmark/benchmark-workflow.yml` into `.github/workflows` and push the
branch it names. `benchmark/evict.py` is what drops a file from the Linux page cache between cold runs.

## Warm page cache, median milliseconds

Lower is better. `off` is mapping disabled, `on` is mapping enabled.

| Platform | corpus off → on | mixed off → on | huge off → on |
|---|---|---|---|
| Linux, JDK 17   | 1995 → 1935 &nbsp;&nbsp; (1956 → 1774) | 2090 → 1881 &nbsp;&nbsp; (2118 → 1838) | 772 → 737 &nbsp;&nbsp; (808 → 859) |
| Linux, JDK 25   | 1964 → 1906 &nbsp;&nbsp; (1952 → 1852) | 2012 → 1909 &nbsp;&nbsp; (1990 → 1869) | 801 → 754 &nbsp;&nbsp; (750 → 745) |
| **Windows, JDK 17** | **3621 → 2290** | **3863 → 2411** | **1539 → 1036** |
| **Windows, JDK 25** | **2719 → 2255** | **2820 → 2372** | **1121 → 945** |
| macOS, JDK 17   | 3375 → 3341 &nbsp;&nbsp; (2460 → 2243) | 2929 → 3019 &nbsp;&nbsp; (2300 → 2577) | 1214 → 1132 &nbsp;&nbsp; (1537 → 1653) |
| macOS, JDK 25   | 2705 → 2729 &nbsp;&nbsp; (3189 → 3127) | 3179 → 3109 &nbsp;&nbsp; (3297 → 3273) | 1296 → 1122 &nbsp;&nbsp; (1591 → 1444) |

The second figure in brackets is the independent second sample.

## Cold page cache, median milliseconds

| Platform | corpus off → on | mixed off → on | huge off → on |
|---|---|---|---|
| Linux, JDK 17 | 2292 → 1934 &nbsp;&nbsp; (2078 → 1882) | 2163 → 1936 &nbsp;&nbsp; (1986 → 1966) | 923 → 993 &nbsp;&nbsp; (1196 → 877) |
| Linux, JDK 25 | 2227 → 2043 &nbsp;&nbsp; (1902 → 2008) | 2048 → 2007 &nbsp;&nbsp; (2053 → 2026) | 861 → 901 &nbsp;&nbsp; (957 → 920) |
| macOS, JDK 17 | 4600 → 3306 &nbsp;&nbsp; (4567 → 3985) | 4743 → 3842 &nbsp;&nbsp; (4241 → 4753) | 2297 → 1883 &nbsp;&nbsp; (1677 → 1466) |
| macOS, JDK 25 | 3913 → 4438 &nbsp;&nbsp; (3420 → 4254) | 3511 → 3915 &nbsp;&nbsp; (3682 → 4136) | 1639 → 1628 &nbsp;&nbsp; (1679 → 1937) |

The macOS cold numbers disagree between samples in both directions, and should be read as noise: `purge` empties
the whole page cache rather than just the corpus, so every cold macOS scan also re-faults the JDK, the runner's
own files and everything else.

## The one clear regression, on local hardware

On a 32-core Linux workstation with local NVMe storage and JDK 26, scanning a real Maven repository — 200 jars,
345 MB, 41353 classes, so mostly *not* classfiles — mapping is the slower option when the cache is cold:

| Page cache | off | on |
|---|---|---|
| warm | 785 ms | 767 ms |
| **cold** | **1458 ms** | **2003 ms (37% slower)** |

Reproduced twice with the same tool used in CI. Neither the CI corpus nor the mixed corpus reproduces it on the
same machine, so the corpus composition drives it, not the machine.

## Why: read amplification

Measured from `/proc/self/io` `read_bytes`, which counts bytes actually fetched from the block device. Fully
deterministic across four runs of each row.

| Corpus | Size | Device reads, mapping off | Device reads, mapping on |
|---|---|---|---|
| Maven repository | 345 MB | 108 MB (31%) | **172 MB (50%)** |
| CI corpus | 122 MB | 120 MB (98%) | 120 MB (99%) |
| mixed | 172 MB | 125 MB (73%) | 137 MB (80%) |
| huge (one jar) | 59 MB | 59 MB (100%) | 59 MB (100%) |

A positioned `FileChannel#read` fetches the bytes asked for. A page fault on a mapping fetches the faulting page
plus the kernel's fault-around window, so a jar whose classfiles are scattered among resources that ClassGraph
never reads costs extra device traffic. That is why the effect appears on the Maven repository and vanishes on a
corpus that is nearly all classfiles.

Honest caveat: amplification alone does not fully account for the timing gap. The mixed corpus shows
amplification (73% → 80%) with no time penalty on fast NVMe. Amplification is measured; it is a contributing
mechanism, not a complete explanation.

## Two hypotheses that were tested and refuted

**Mapping does not make a scanned jar undeletable.** `benchmark/LockProbe.java` scans a jar and tries to delete
it while the `ScanResult` is open and again after it is closed:

| Platform | Delete while open, mapping off | Delete while open, mapping on | After close |
|---|---|---|---|
| Linux | deleted | deleted | deleted |
| macOS | deleted | deleted | deleted |
| Windows | `FileSystemException: ... being used by another process` | `FileSystemException: ... being used by another process` | deleted |

Windows refuses the delete either way, because ClassGraph holds the file open regardless of how it reads it. So
"mapping locks the file on Windows" is not a reason to leave mapping off — the lock is already there.

**`FileChannel` monitor contention is not the mechanism on Linux.** Lucene
[#16044](https://github.com/apache/lucene/issues/16044) reports positioned `FileChannel` reads contending on
`sun.nio.ch.NativeThreadSet`'s monitor above about four threads, and ClassGraph really does share one
`FileChannel` per jar across scan threads. Measuring the single-jar workload at 1, 2, 4, 8, 16 and 32 threads
(`benchmark/ThreadScaling.java`) gives a flat advantage at every thread count:

| Threads | 1 | 2 | 4 | 8 | 16 | 32 |
|---|---|---|---|---|---|---|
| off | 1271 | 760 | 528 | 415 | 393 | 405 |
| on | 1210 | 729 | 511 | 400 | 387 | 390 |

About 4% at one thread and about 4% at thirty-two. If channel contention were the mechanism the gap would widen
with thread count; it does not.

## What other software does

- The JDK's own `java.util.zip.ZipFile` does not map at all — it reads through a `RandomAccessFile` with
  `readFullyAt`. The JDK maps the modules image (`jdk.internal.jimage.BasicImageReader` maps the whole jimage on
  64-bit), but that is one file opened once for the life of the VM, not a set of user-supplied jars.
- Lucene's `MMapDirectory` is the best-known argument for mapping, and Lucene has spent years on its sharp
  edges: [#16044](https://github.com/apache/lucene/issues/16044) (2026-05-08) reports page-fault storms under
  cgroup memory limits, which is exactly the environment a containerized application scans in.
- The CIDR 2022 paper *"Are You Sure You Want to Use MMAP in Your DBMS?"* argues against mapping for two reasons
  that apply here: error handling, and throughput on fast storage.

## Error handling is worse with mapping

If a jar is truncated while it is being scanned, a positioned read returns `-1` and ClassGraph reports a clean
end of file. A mapping turns the same event into a `java.lang.InternalError` raised from a signal handler, which
cannot be handled meaningfully. Verified on JDK 17 and JDK 26.

## Conclusion

Memory mapping earns its place on Windows and nowhere else:

- **Windows:** 16% to 38% faster, on both JDKs and all three workloads, with the `off` and `on` ranges not even
  overlapping (JDK 17 corpus: `off` never faster than 3446 ms, `on` never slower than 2530 ms).
- **Linux:** 0% to 10% faster warm, a wash cold, and up to 37% *slower* cold on a resource-heavy classpath.
- **macOS:** inside the noise, in both directions.

Mapping everywhere is wrong, because it regresses the cold, resource-heavy Linux case, which is the one users
notice. Mapping nowhere is wrong too, because it gives up a win on Windows that is larger than anything else in
this file. And leaving it to the user as an opt-in asks a question no one can answer without repeating this
whole exercise on their own workload.

So in 5.0 **ClassGraph memory-maps on Windows and reads through the channel on every other platform**, and
`ClassGraph#enableMemoryMapping()` has been removed. There is nothing to configure.

The measurement that would change this: a bare-metal Windows machine with local NVMe showing the same gap. The
Windows numbers here come from a GitHub Actions VM with network-backed storage and only one sample, so it is not
yet clear whether Windows is genuinely slower at positioned reads or whether the virtualized disk exaggerates
it. If bare metal shows no gap, the right call becomes reading through the channel everywhere.
