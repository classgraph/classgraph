# classgraph-vfs

A virtual filesystem: it reads directories, jarfiles and modules through one interface, however they
are named -- by path string, `File`, `Path`, `URI`, `URL`, `ModuleReference`, `InputStream` or byte
array -- and hands back the content of anything it finds as a stream, a channel, a `ByteBuffer`, a
byte array, a string, or a `java.nio.file.Path` in a read-only `FileSystem` view, so that
`java.nio.file.Files` can read a jarfile nested inside another jarfile. It has no scanner and no
classfile parser: if you want to find classes, use [`classgraph`](../classgraph) instead, which is
built on this library.

```xml
<dependency>
    <groupId>io.github.classgraph</groupId>
    <artifactId>classgraph-vfs</artifactId>
    <version>X.Y.Z</version>
</dependency>
```

Module name: `io.github.classgraph.vfs`. Requires JDK 17 or newer. No dependencies other than
`classgraph-base`, which is pulled in transitively.

Three classes carry the API: `Vfs` opens things, a `VfsRoot` is one opened directory, jarfile or
module, and a `VfsEntry` is one file within it. Two more turn up as you use them:
`CloseableByteBuffer` wraps a buffer you have to close, and `VfsVisitor` is the callback that
`VfsRoot#walk()` takes. Those five types are the public API -- `VfsEntry` has one further public
method, `getZipEntry()`, which returns a type from the internal packages, exported only to
ClassGraph's own modules. See the
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
| `java.nio.ByteBuffer` (a memory mapping where possible), wrapped in a `CloseableByteBuffer` | `entry.read()` |
| `byte[]` | `entry.load()` |
| `String`, decoded as UTF-8 | `entry.loadAsString()` |
| `java.nio.file.Path`, in the filesystem view below | `entry.asPath()` |
| `java.nio.file.FileSystem`, over a whole root | `root.asFileSystem()` |

A root also names itself in every way it can: `getPath()`, `getURI()`, `getURL()`, `getFile()`,
`getNioPath()` and `getModuleReference()`, each returning null where the underlying storage has no
such name -- a module of the running JDK has no file, and a jarfile downloaded into RAM has no path.

## What owns what

The `Vfs` owns everything opened through it: the file handles, the memory mappings, and the
temporary files that a nested jarfile has to be spilled to when it cannot be read in place. Closing
the `Vfs` releases all of them and closes every root it handed out, so a `Vfs` belongs in a
try-with-resources block, as it is in every example below.

A `VfsRoot` is `AutoCloseable` too, but closing one only drops that root: its entries and its
`FileSystem` view stop working, and opening the same path again builds a fresh root. The jarfile
behind it stays open, because other roots may be reading the same jarfile. Only the `Vfs` releases
storage.

So a root does not have to be closed, and there is nothing to leak by not closing one. An IDE
cannot know that, though: with resource analysis switched on -- which is the default in Eclipse --
`VfsRoot root = vfs.open(path)` is reported as *"Potential resource leak: 'root' may not be
closed"*, because `vfs.open()` returns an `AutoCloseable`. Declaring the root in the
try-with-resources silences it and costs nothing:

```java
try (Vfs vfs = new Vfs(); VfsRoot root = vfs.open("/path/to/library.jar")) {
    // ...
}
```

Every recipe below declares its roots that way, so none of them produces the warning. Where a root
is opened and used without being named -- `vfs.open(path).getEntries()` -- the same warning is
reported against the unnamed value, and can be ignored.

## Reading through `java.nio.file.Files`

`root.asFileSystem()` returns a read-only `FileSystem` over any root, so anything that takes a
`Path` can read a directory, a jarfile, a jarfile nested inside another jarfile, a package root, a
jarfile that exists only in RAM, or a module, without knowing which of those it has:

```java
try (Vfs vfs = new Vfs();
        VfsRoot root = vfs.open("/path/to/outer.jar!/BOOT-INF/lib/inner.jar");
        FileSystem fs = root.asFileSystem()) {
    byte[] classfile = Files.readAllBytes(fs.getPath("/com/xyz/Widget.class"));
    try (Stream<Path> paths = Files.walk(fs.getPath("/"))) {
        paths.filter(Files::isRegularFile).forEach(System.out::println);
    }
}
```

The JDK ships a zip filesystem provider, but it can only open a jarfile it can name as a `Path`. It
cannot open a module, and it cannot open a jarfile that was never written to disk. It also cannot
name a nested jarfile: given `jar:file:/path/to/outer.jar!/BOOT-INF/lib/inner.jar` it keeps only the
part before the first `!/`, and silently opens `outer.jar` instead, so the entries of `inner.jar`
are not there. Nesting can be reached by opening each level in turn and handing the inner `Path` to
the next, but that requires knowing the depth in advance, and each level is read into memory
whether or not anything is read from it.

The separator is `/` and the root directory is `/`, whichever kind of root it is a view of. Files
are read with `Files.newInputStream`, `Files.newByteChannel`, `Files.readAllBytes` and
`Files.readString`; directories are listed with `Files.newDirectoryStream`, `Files.list` and
`Files.walk`, and are synthesized from the names of the entries below them, since a jarfile need not
contain an entry for every directory whose contents it holds. `getPathMatcher` supports both `glob:`
and `regex:`. `basic` is the only attribute view.

It is a read-only filesystem: everything that would write -- `Files.delete`, `Files.write`,
`Files.copy`, `Files.move`, `Files.createDirectory`, `Files.newOutputStream`, `setTimes`, and
`newByteChannel` with a write option -- throws `ReadOnlyFileSystemException`.

Its `close()` closes the root it is a view of, and closing the root closes it, so either can go in
the try-with-resources. After that `isOpen()` returns false and every read of it throws
`ClosedFileSystemException`, as `java.nio.file.FileSystem` specifies. Neither releases any storage:
the file handles, memory mappings and temporary files belong to the `Vfs`, and are released when the
`Vfs` is closed, which also makes every filesystem view it handed out report itself closed.

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

`classgraph-vfs` never has a cursor to protect. Every read names the absolute offset it wants: a
file is read either through a memory-mapped `ByteBuffer`, which is indexed absolutely, or through
`FileChannel#read(ByteBuffer, long)`, which takes the position as an argument rather than carrying
one, and which reaches `pread` without taking the channel's position lock. The central directory is
parsed once into an immutable index. So entry lookup and entry reads take no locks at all.

(Which of the two is used is a speed choice, not a correctness one. Memory mapping is measurably
faster on Windows and is not on Linux or macOS, where it can be slower, so it is used on Windows
only. See the
[memory mapping benchmark](https://github.com/classgraph/classgraph/wiki/Memory-Mapping-Benchmark).)

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
try (Vfs vfs = new Vfs();
        VfsRoot dir = vfs.open("/path/to/classes");
        VfsRoot jar = vfs.open("/path/to/library.jar");
        VfsRoot module = vfs.open(ModuleFinder.ofSystem().find("java.logging").orElseThrow())) {
    for (VfsRoot root : List.of(dir, jar, module)) {
        System.out.println(root + " (" + root.getKind() + ")");
        for (VfsEntry entry : root) {
            System.out.println("  " + entry.getName() + " (" + entry.getLength() + " bytes)");
        }
    }
}
```

A root is `Iterable<VfsEntry>`, and iterating it lists files, not directories, and names them
relative to the root, so the same loop walks all three kinds of root. `getEntries()` returns the
same entries as a `List`. Closing the `Vfs` releases every file handle, memory mapping and
temporary file it took, and invalidates every `VfsRoot` and `VfsEntry` it handed out, so do not let
them escape the `try` block.

### List only the part of a root you want

Iterating a root builds a list of every file in it. When only some of them are wanted, `walk()`
is cheaper: it offers each directory before the entries in it, so an unwanted one can be skipped,
and for a directory tree a skipped directory is never even listed.

```java
try (Vfs vfs = new Vfs(); VfsRoot root = vfs.open("/path/to/classes")) {
    root.walk(new VfsVisitor() {
        @Override
        public boolean enterDirectory(String dirName) {
            // Do not descend into a test tree
            return !dirName.startsWith("com/xyz/test/");
        }

        @Override
        public boolean visitEntry(VfsEntry entry) {
            System.out.println(entry.getName());
            // Return false to stop the walk early
            return true;
        }
    });
}
```

How much a skipped directory skips differs by root, and the difference matters: a directory tree
skips the whole subtree, because not listing it is the entire saving, whereas a jarfile or a module
already has its entry list in hand, so only that directory's own entries are skipped and the
directories below it are still offered. That is deliberate — a caller that strips a package root
prefix such as `BOOT-INF/classes/` from the names before judging them would otherwise prune
`BOOT-INF/` and lose everything under it.

### Read one entry

```java
try (Vfs vfs = new Vfs(); VfsRoot root = vfs.open("/path/to/library.jar")) {
    VfsEntry entry = root.getEntry("META-INF/MANIFEST.MF");
    if (entry != null) {
        System.out.println(entry.loadAsString());
    }
}
```

`getEntry` returns null if there is no such entry. There are four ways to read one, differing only
in what you get back and who has to release it.

`entry.open()` streams the content, so a large entry never has to be held in memory. The stream is
yours to close:

```java
try (InputStream inputStream = entry.open()) {
    // ... Read from inputStream ...
}
```

`entry.read()` hands back the content as a `ByteBuffer` -- which is the memory mapping itself, with
no copy, where the entry is stored uncompressed in a file that could be mapped. It is wrapped in a
`CloseableByteBuffer` because it has to be released or unmapped when you have finished with it,
which `close()` does:

```java
try (CloseableByteBuffer closeableBuffer = entry.read()) {
    ByteBuffer byteBuffer = closeableBuffer.getByteBuffer();
    // ... Read from byteBuffer ...
}
```

`entry.load()` and `entry.loadAsString()` copy the content into a `byte[]` and a UTF-8 `String`
respectively. There is nothing to close, and the result stays valid after the `Vfs` is closed:

```java
byte[] classfile = entry.load();
String manifest = entry.loadAsString();
```

All four throw `IOException` if the entry cannot be read, or if the `Vfs` has been closed. A fifth,
`entry.openChannel()`, returns a `ReadableByteChannel` for code that reads that way.

### Read a jarfile that is not on disk

```java
try (Vfs vfs = new Vfs();
        InputStream inputStream = URI.create(url).toURL().openStream();
        VfsRoot root = vfs.open(inputStream, "downloaded.jar")) {
    System.out.println(root.getEntries().size() + " entries");
}
```

The stream is read into RAM, or spilled to a temporary file if it is larger than
`maxBufferedJarRAMSize`. `vfs.open(byte[], String)` does the same for a jarfile you already hold.
Alternatively, let the library do the fetching: after `vfs.enableURLScheme("https")`, the URL can be
opened directly with `vfs.open("https://.../library.jar")`.

### Read a jarfile nested inside another jarfile

```java
try (Vfs vfs = new Vfs();
        VfsRoot nested = vfs.open("/path/to/outer.jar!/BOOT-INF/lib/inner.jar")) {
    for (VfsEntry entry : nested) {
        System.out.println(entry.getName());
    }
}
```

Nothing is extracted to disk unless the nested jarfile is stored deflated and is larger than
`maxBufferedJarRAMSize`. Call `disableNestedJars()` if `!/` in a path should only ever mean a
package root.

A `jar:` or `jar:file:` prefix is accepted, but is not needed and changes nothing:
`/path/outer.jar!/lib/inner.jar`, `file:/path/outer.jar!/lib/inner.jar` and
`jar:file:/path/outer.jar!/lib/inner.jar` all open the same nested jarfile. Which `!` separates the
levels is not decided by the prefix, because it cannot be: `!` is a legal filename character on
every platform, `JarURLConnection` defines the separator as `!/` and offers no way to escape a
literal one, so `/dir!/x.jar` is genuinely ambiguous between a jarfile `x.jar` in a directory named
`dir!` and an entry `x.jar` inside a jarfile named `dir`. It is decided by looking at the storage
instead: each `!` is tried in turn, and the first one whose preceding path names an existing file
is the separator, since the outermost jarfile has to exist to be read at all. A path with a remote
URL scheme cannot be checked that way, so for those the first `!` is taken to be the separator.

### Read from a package root

```java
try (Vfs vfs = new Vfs();
        VfsRoot classes = vfs.open("/path/to/spring-boot-app.jar!/BOOT-INF/classes")) {
    // Names are reported relative to the package root, so this prints "com/xyz/MyApp.class",
    // not "BOOT-INF/classes/com/xyz/MyApp.class"
    classes.getEntries().forEach(entry -> System.out.println(entry.getName()));
    System.out.println("package root: " + classes.getPackageRoot());
}
```

### Read from a filesystem other than the default one

```java
try (FileSystem fileSystem = FileSystems.newFileSystem(Path.of("/path/to/library.jar"));
        Vfs vfs = new Vfs();
        VfsRoot root = vfs.open(fileSystem.getPath("/"))) {
    root.getEntries().forEach(entry -> System.out.println(entry.getName()));
}
```

Any `Path` works, whatever provider it belongs to, whether it names a directory or a jarfile.

### Find a root's module name

```java
try (Vfs vfs = new Vfs(); VfsRoot root = vfs.open("/path/to/library.jar")) {
    String moduleName = root.getModuleName();
    System.out.println(moduleName != null
            ? "module name: " + moduleName
            : "no Automatic-Module-Name in the manifest");
}
```

For a jarfile this is the `Automatic-Module-Name` manifest entry; for a module it is the module's
own name.

### Work out why something is not read as expected

```java
try (Vfs vfs = new Vfs()) {
    vfs.verbose();
    try (VfsRoot root = vfs.open("/path/to/library.jar")) {
        System.out.println(root.getEntries().size() + " entries");
    }
}
```

The log is written to the `io.github.classgraph.ClassGraph` logger at `INFO` level when the `Vfs`
is closed, not as the reading happens. It is a debugging aid, not a stable output format.

## License

MIT. See [LICENSE-ClassGraph.txt](../LICENSE-ClassGraph.txt).
