# classgraph-vfs

A virtual filesystem: it reads directories, jarfiles and modules through one interface, however they
are named -- by path string, `File`, `Path`, `URI`, `URL`, `ModuleReference`, `InputStream` or byte
array -- and hands back the content of anything it finds as a stream, a channel, a `ByteBuffer`, a
byte array or a string. It has no scanner and no classfile parser: if you want to find classes, use
[`classgraph`](../classgraph) instead, which is built on this library.

```xml
<dependency>
    <groupId>io.github.classgraph</groupId>
    <artifactId>classgraph-vfs</artifactId>
    <version>X.Y.Z</version>
</dependency>
```

Module name: `io.github.classgraph.vfs`. Requires JDK 17 or newer. No dependencies other than
`classgraph-base`, which is pulled in transitively.

The whole API is three classes: `Vfs` opens things, a `VfsRoot` is one opened directory, jarfile or
module, and a `VfsEntry` is one file within it. See the
[Vfs API](https://github.com/classgraph/classgraph/wiki/Vfs-API) for the full reference.

## One interface over every kind of storage

Java names a place to read from in at least seven ways, and reads from it in at least five. Which
of those a library accepts is usually an accident of what its author needed, so most code that
handles more than one kind of storage ends up with a branch per pairing. `Vfs` collapses that: every
way in produces the same `VfsRoot`, and every `VfsEntry` reads out in every way.

| Named by | Opened with |
| --- | --- |
| A path string, with `!/` separating nested jarfiles | `vfs.open("outer.jar!/lib/inner.jar")` |
| A `java.io.File` | `vfs.open(file)` |
| A `java.nio.file.Path`, in any filesystem | `vfs.open(path)` |
| A `java.net.URI` or `java.net.URL` | `vfs.open(uri)`, `vfs.open(url)` |
| A `java.lang.module.ModuleReference` | `vfs.open(moduleReference)` |
| An `InputStream`, for a jarfile that is not on disk | `vfs.open(inputStream, "name.jar")` |
| A byte array holding a jarfile | `vfs.open(jarBytes, "name.jar")` |

| Read as | Method |
| --- | --- |
| `java.io.InputStream` | `entry.open()` |
| `java.nio.channels.ReadableByteChannel` | `entry.openChannel()` |
| `java.nio.ByteBuffer` (a memory mapping where possible) | `entry.read()` |
| `byte[]` | `entry.load()` |
| `String`, decoded as UTF-8 | `entry.loadAsString()` |

A root also names itself in every way it can: `getPath()`, `getURI()`, `getURL()`, `getFile()`,
`getNioPath()` and `getModuleReference()`, each returning null where the underlying storage has no
such name -- a module of the running JDK has no file, and a jarfile downloaded into RAM has no path.

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
classgraph-vfs, one shared VfsRoot     12ms      4ms      2ms      2ms      1ms

inflate every entry                   1 thd    2 thd    4 thd    8 thd
java.util.zip, one shared ZipFile     433ms    247ms    153ms    142ms
classgraph-vfs, one shared VfsRoot    486ms    276ms    154ms    104ms
```

(32-core Linux box, `kotlin-compiler-embeddable-2.3.20.jar`, 27874 entries, JDK 26. The middle row
of the first block isolates the instance monitor as the cause: the same work scales fine once each
thread has its own `ZipFile`, at the cost of parsing the central directory once per thread and
holding one file handle per thread.)

A `Vfs` and everything it hands out is safe to use from many threads at once. This is what lets
ClassGraph scan a jarfile in parallel.

## Recipes

### List what is in a directory, a jarfile or a module

```java
try (Vfs vfs = new Vfs()) {
    List<VfsRoot> roots = List.of(
            vfs.open("/path/to/classes"),
            vfs.open("/path/to/library.jar"),
            vfs.open(ModuleFinder.ofSystem().find("java.logging").orElseThrow()));
    for (VfsRoot root : roots) {
        System.out.println(root + " (" + root.getKind() + ")");
        for (VfsEntry entry : root.getEntries()) {
            System.out.println("  " + entry.getName() + " (" + entry.getLength() + " bytes)");
        }
    }
}
```

`getEntries()` lists files, not directories, and names them relative to the root, so the same loop
walks all three kinds of root. Closing the `Vfs` releases every file handle, memory mapping and
temporary file it took, and invalidates every `VfsRoot` and `VfsEntry` it handed out, so do not let
them escape the `try` block.

### Read one entry

```java
try (Vfs vfs = new Vfs()) {
    VfsEntry entry = vfs.open("/path/to/library.jar").getEntry("META-INF/MANIFEST.MF");
    if (entry != null) {
        System.out.println(entry.loadAsString());
    }
}
```

`getEntry` returns null if there is no such entry. Use `entry.open()` to stream a large entry rather
than holding it in memory, or `entry.read()` to get a `ByteBuffer` -- which is the memory mapping
itself, with no copy, where the entry is stored uncompressed in a file that could be mapped.

### Read a jarfile that is not on disk

```java
try (Vfs vfs = new Vfs();
        InputStream inputStream = new URL(url).openStream()) {
    VfsRoot root = vfs.open(inputStream, "downloaded.jar");
    System.out.println(root.getEntries().size() + " entries");
}
```

The stream is read into RAM, or spilled to a temporary file if it is larger than
`maxBufferedJarRAMSize`. `vfs.open(byte[], String)` does the same for a jarfile you already hold.
Alternatively, let the library do the fetching: `new Vfs().enableURLScheme("https")` allows
`vfs.open("https://.../library.jar")`.

### Read a jarfile nested inside another jarfile

```java
try (Vfs vfs = new Vfs()) {
    VfsRoot nested = vfs.open("/path/to/outer.jar!/BOOT-INF/lib/inner.jar");
    for (VfsEntry entry : nested.getEntries()) {
        System.out.println(entry.getName());
    }
}
```

Nothing is extracted to disk unless the nested jarfile is stored deflated and is larger than
`maxBufferedJarRAMSize`. Call `disableNestedJars()` if `!/` in a path should only ever mean a
package root.

### Read from a package root

```java
try (Vfs vfs = new Vfs()) {
    VfsRoot classes = vfs.open("/path/to/spring-boot-app.jar!/BOOT-INF/classes");
    // Names are reported relative to the package root, so this prints "com/xyz/MyApp.class",
    // not "BOOT-INF/classes/com/xyz/MyApp.class"
    classes.getEntries().forEach(entry -> System.out.println(entry.getName()));
    System.out.println("package root: " + classes.getPackageRoot());
}
```

### Read from a filesystem other than the default one

```java
try (FileSystem fileSystem = FileSystems.newFileSystem(Path.of("/path/to/library.jar"), null);
        Vfs vfs = new Vfs()) {
    VfsRoot root = vfs.open(fileSystem.getPath("/"));
    root.getEntries().forEach(entry -> System.out.println(entry.getName()));
}
```

Any `Path` works, whatever provider it belongs to, whether it names a directory or a jarfile.

### Find a root's module name

```java
try (Vfs vfs = new Vfs()) {
    String moduleName = vfs.open("/path/to/library.jar").getModuleName();
    System.out.println(moduleName != null
            ? "module name: " + moduleName
            : "no Automatic-Module-Name in the manifest");
}
```

For a jarfile this is the `Automatic-Module-Name` manifest entry; for a module it is the module's
own name.

### Work out why something is not read as expected

```java
try (Vfs vfs = new Vfs().verbose()) {
    vfs.open("/path/to/library.jar");
}
```

The log is written to the `io.github.classgraph.ClassGraph` logger at `INFO` level when the `Vfs`
is closed, not as the reading happens. It is a debugging aid, not a stable output format.

## License

MIT. See [LICENSE-ClassGraph.txt](../LICENSE-ClassGraph.txt).
