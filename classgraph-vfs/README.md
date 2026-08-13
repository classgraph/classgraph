# classgraph-vfs

A jarfile reader: it opens a zipfile, lists its entries, and hands back their content, without
extracting anything to disk. It has no scanner and no classfile parser -- if you want to find
classes, use [`classgraph`](../classgraph) instead, which is built on this library.

```xml
<dependency>
    <groupId>io.github.classgraph</groupId>
    <artifactId>classgraph-vfs</artifactId>
    <version>X.Y.Z</version>
</dependency>
```

Module name: `io.github.classgraph.vfs`. Requires JDK 17 or newer. No dependencies other than
`classgraph-base`, which is pulled in transitively.

The whole API is three classes: `ArchiveReader` opens jarfiles, an `Archive` is one opened jarfile,
and an `ArchiveEntry` is one entry within it. See the
[Archive API](https://github.com/classgraph/classgraph/wiki/Archive-API) for the full reference.

## What it reads that `java.util.zip` does not

* **Jarfiles nested inside other jarfiles, to any depth**, named by separating the levels with
  `!/`, e.g. `outer.jar!/lib/inner.jar!/lib/innermost.jar`. A nested jarfile that is stored
  uncompressed is read in place, with no copy; one that is stored deflated is inflated into RAM,
  or spilled to a temporary file if it exceeds `maxBufferedJarRAMSize` (64MB by default).
* **A package root within a jarfile**, named by a trailing `!/` section that is not itself a
  jarfile, e.g. `spring-boot-app.jar!/BOOT-INF/classes`. Entry names are reported with the root
  stripped off.
* **Multi-release jarfiles**, resolved to the newest version of each entry that the running JVM can
  use. `enableMultiReleaseVersions()` reports every version instead.
* **Jarfiles at a URL**, once the scheme is allowed with `enableURLScheme("https")`. The file is
  downloaded in full first, because a zipfile's central directory is at the end.
* **Entry names encoded in IBM Code Page 437**, which the zip specification requires when bit 11 of
  the entry's general purpose bit flag is clear. Windows Explorer and Info-ZIP both write such
  names.

## Concurrency

`java.util.zip.ZipFile` serializes on the instance monitor: every public method takes
`synchronized (this)` -- `getEntry`, `getInputStream`, `entries`, `stream`, `size`, `close` -- and
`ZipFileInputStream.read` takes it on every read call. One level down, `Source.readFullyAt` and
`Source.readAt` take `synchronized` on a `RandomAccessFile` that is *shared between separate
`ZipFile` instances opened on the same file*, because `Source` instances are cached and reference
counted by file. The locking is unavoidable there: a `RandomAccessFile` has one cursor, so the
seek and the read have to be atomic with respect to each other.

`classgraph-vfs` never has a cursor to protect. Entries are read through memory-mapped
`ByteBuffer` slices with absolute indexing, and the central directory is parsed once into an
immutable index, so entry lookup and entry reads take no locks at all.

What that is worth depends on the access pattern. Bulk decompression still parallelizes reasonably
well under `ZipFile`, because inflation happens outside the monitor and dominates the time. Entry
lookup does not parallelize at all -- it gets *slower* as threads are added:

```
getEntry() on 27874 entries, x20      1 thd    2 thd    4 thd    8 thd   16 thd
java.util.zip, one shared ZipFile      46ms     55ms     84ms     77ms     82ms
java.util.zip, one ZipFile per thread  40ms     23ms     13ms     13ms     12ms
classgraph-vfs, one shared Archive     12ms      4ms      2ms      2ms      1ms

inflate every entry                   1 thd    2 thd    4 thd    8 thd
java.util.zip, one shared ZipFile     433ms    247ms    153ms    142ms
classgraph-vfs, one shared Archive    486ms    276ms    154ms    104ms
```

(32-core Linux box, `kotlin-compiler-embeddable-2.3.20.jar`, 27874 entries, JDK 26. The middle row
of the first block isolates the instance monitor as the cause: the same work scales fine once each
thread has its own `ZipFile`, at the cost of parsing the central directory once per thread and
holding one file handle per thread.)

An `ArchiveReader` and everything it hands out is safe to use from many threads at once. This is
what lets ClassGraph scan a jarfile in parallel.

## Recipes

### List the entries of a jarfile

```java
try (ArchiveReader reader = new ArchiveReader()) {
    Archive archive = reader.open("/path/to/library.jar");
    for (ArchiveEntry entry : archive.getEntries()) {
        System.out.println(entry.getName() + " (" + entry.getUncompressedSize() + " bytes)");
    }
}
```

Closing the reader releases every file handle, memory mapping and temporary file it took, and
invalidates every `Archive` and `ArchiveEntry` it handed out. Reading from an entry after that
point returns no data, so do not let them escape the `try` block.

### Read one entry

```java
try (ArchiveReader reader = new ArchiveReader()) {
    ArchiveEntry entry = reader.open("/path/to/library.jar").getEntry("META-INF/MANIFEST.MF");
    if (entry != null) {
        System.out.println(new String(entry.readAllBytes(), StandardCharsets.UTF_8));
    }
}
```

`getEntry` returns null if there is no such entry. Use `entry.open()` instead of `readAllBytes()`
to stream a large entry rather than holding it in memory.

### Read a jarfile nested inside another jarfile

```java
try (ArchiveReader reader = new ArchiveReader()) {
    Archive nested = reader.open("/path/to/outer.jar!/BOOT-INF/lib/inner.jar");
    for (ArchiveEntry entry : nested.getEntries()) {
        System.out.println(entry.getName());
    }
}
```

Nothing is extracted to disk unless the nested jarfile is stored deflated and is larger than
`maxBufferedJarRAMSize`. Call `disableNestedJars()` if `!/` in a path should only ever mean a
package root.

### Read from a package root

```java
try (ArchiveReader reader = new ArchiveReader()) {
    Archive classes = reader.open("/path/to/spring-boot-app.jar!/BOOT-INF/classes");
    // Names are reported relative to the package root, so this prints "com/xyz/MyApp.class",
    // not "BOOT-INF/classes/com/xyz/MyApp.class"
    classes.getEntries().forEach(entry -> System.out.println(entry.getName()));
    System.out.println("package root: " + classes.getPackageRoot());
}
```

### Read a jarfile over HTTPS

```java
try (ArchiveReader reader = new ArchiveReader().enableURLScheme("https")) {
    Archive archive = reader.open(
            "https://repo1.maven.org/maven2/io/github/classgraph/classgraph/4.8.192/classgraph-4.8.192.jar");
    System.out.println(archive.getEntries().size() + " entries");
}
```

### Find a jarfile's automatic module name

```java
try (ArchiveReader reader = new ArchiveReader()) {
    String moduleName = reader.open("/path/to/library.jar").getAutomaticModuleName();
    System.out.println(moduleName != null
            ? "Automatic-Module-Name: " + moduleName
            : "no Automatic-Module-Name in the manifest");
}
```

### Work out why a jarfile is not read as expected

```java
try (ArchiveReader reader = new ArchiveReader().verbose()) {
    reader.open("/path/to/library.jar");
}
```

The log is written to the `io.github.classgraph.ClassGraph` logger at `INFO` level when the reader
is closed, not as the reading happens. It is a debugging aid, not a stable output format.

## License

MIT. See [LICENSE-ClassGraph.txt](../LICENSE-ClassGraph.txt).
