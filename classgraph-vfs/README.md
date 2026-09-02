# classgraph-vfs (cgvfs)

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
module, and a `VfsEntry` is one file within it. Three more turn up as you use them: `VfsSpec` holds
the settings a `Vfs` is constructed with, `CloseableByteBuffer` wraps a buffer you have to close,
and `VfsVisitor` is the callback that `VfsRoot#walk()` takes. Those six types are the API you call.
Two more are public only because `java.nio.file` needs them to be: `VfsFileSystemProvider`, which
`ServiceLoader` instantiates to serve the `cgvfs:` URL scheme, and `VfsPath`, the `Path` of a
filesystem view, whose one addition to `Path` is `toCgvfsUri()`. `Vfs` also has one further public
constructor, which takes a type from a package that is exported only to ClassGraph's own modules, so
no other module can call it. See the
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
| A module name, in the boot layer or a given `ModuleLayer` | `vfs.openModule("java.logging")`, `vfs.openModule(name, layer)` |
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

A directory or jarfile is opened once however it is named, so the same root comes back for a plain
path, for the `file:` or `jar:` URL of the same thing, for a path that reaches it through a symlink,
and, on Windows, for a path written with backslashes rather than forward slashes, or one that names a
directory by its 8.3 short name. A root names itself at the canonical path, so `getPath()` is not
always the path you opened it by.

## What owns what

Ownership is a tree. The `Vfs` owns every root it opens; each root owns the storage behind it -- the
file handle, the memory mapping, and the temporary file that a nested jarfile has to be spilled to
when it cannot be read in place -- and the roots opened within it, which read through that storage.
Closing the `Vfs` closes every root, releasing all of it, and every root it handed out stops working
at that moment, so a `Vfs` belongs in a try-with-resources block, as it is in every example below.

The one thing that can outlast that close is a `CloseableByteBuffer` you have not closed: below JDK
22 it keeps the file it reads mapped, and Windows refuses to delete a mapped file, so a temporary
file in that situation is deleted when the last buffer of it is closed rather than when its root is.

A `VfsRoot` is `AutoCloseable` too, but rarely needs closing by hand, since closing the `Vfs` closes
it. Closing one early is for a long-lived `Vfs` that is done reading one root and wants what it
holds released now: `root.close()` releases the root's storage, closes the roots opened within it,
and takes it out of the `Vfs`'s cache, leaving the `Vfs` and every other root open. A root is
shared: a `Vfs` hands out the same root to everything that opens the same path, so two parts of a
program that open the same jarfile share one root, and closing it in one place closes it for the
other too. Short of that, nothing needs closing but
the `Vfs`:

```java
try (Vfs vfs = new Vfs()) {
    VfsRoot root = vfs.open("/path/to/library.jar");
    // ...
}
```

A `Vfs` can list what it has open: it is `Iterable<VfsRoot>`, just as a `VfsRoot` is
`Iterable<VfsEntry>`, and iterating it visits the roots that are currently open, sorted by path.
Each root is reported once, however many paths it was opened by, and a root that was read from a
stream or a byte array rather than opened from a path is not among them, since it is not cached.

A `Vfs` holds every root it has opened until it is closed, which matters only for a long-lived `Vfs`
that opens a great many of them. `vfs.evict(root)` drops one from the cache, so that the memory its
entry list occupies can be reclaimed and the next `vfs.open()` of the same path builds a new root.
It does not stop the evicted root working: anything still holding it goes on reading through it, and
it becomes garbage only when the last holder lets go.

## Reading through `java.nio.file.Files`

`root.asFileSystem()` returns a read-only `FileSystem` over any root, so anything that takes a
`Path` can read a directory, a jarfile, a jarfile nested inside another jarfile, a package root, a
jarfile that exists only in RAM, or a module, without knowing which of those it has:

```java
try (Vfs vfs = new Vfs()) {
    VfsRoot root = vfs.open("/path/to/outer.jar!/BOOT-INF/lib/inner.jar");
    FileSystem fs = root.asFileSystem();
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
are read with `Files.newInputStream`, `Files.newByteChannel`, `FileChannel.open`,
`Files.readAllBytes` and `Files.readString`; directories are listed with `Files.newDirectoryStream`, `Files.list` and
`Files.walk`, and are synthesized from the names of the entries below them, since a jarfile need not
contain an entry for every directory whose contents it holds. `getPathMatcher` supports both `glob:`
and `regex:`. `basic` is the only attribute view.

It is a read-only filesystem: everything that would write -- `Files.delete`, `Files.write`,
`Files.copy`, `Files.move`, `Files.createDirectory`, `Files.newOutputStream`, `setTimes`, and
`newByteChannel` with a write option -- throws `ReadOnlyFileSystemException`.

`root.asFileSystem()` returns the same view every time, until that view is closed. A filesystem is a
view of its root, and the two do not close each other: `fs.close()` closes only the view, leaving
the root and the `Vfs` open and readable, and the next `root.asFileSystem()` hands out a fresh view.
Closing the root, or the `Vfs` that opened it, closes every view of it in the same one-way manner --
`isOpen()` returns false, every read through the filesystem throws `ClosedFileSystemException` as
`java.nio.file.FileSystem` specifies, and `asFileSystem()` and `entry.asPath()` throw
`ClosedFileSystemException`, since there is nothing left to hand out a view of. Release the file
handles, memory mappings and temporary files by closing the root or the `Vfs`, not by closing a
view.

The one exception is a filesystem that `FileSystems.newFileSystem` created for a `cgvfs:` URI: it
created the `Vfs` behind it, and is the only reference you have to it, so closing that filesystem
closes that `Vfs` too.

## The `cgvfs:` URL scheme

`classgraph-vfs` registers a `java.nio.file.spi.FileSystemProvider` for the `cgvfs:` scheme, so
anything that can name a filesystem by URI can name one of these. Registration happens through
`ServiceLoader`, so putting the jar on the classpath or the module path is all that is needed:

```java
try (FileSystem fs = FileSystems.newFileSystem(
        URI.create("cgvfs:/path/to/outer.jar!/BOOT-INF/lib/inner.jar"), Map.of())) {
    byte[] classfile = Files.readAllBytes(fs.getPath("/com/xyz/Widget.class"));
}
```

Everything after `cgvfs:` is a path in the syntax `Vfs.open(String)` takes, so all of these work:

| URI | Names |
| --- | --- |
| `cgvfs:/path/to/app.jar` | a jarfile |
| `cgvfs:file:/path/to/app.jar` | the same jarfile -- the inner scheme is optional |
| `cgvfs:/path/to/classes` | a directory |
| `cgvfs:/path/to/outer.jar!/lib/inner.jar` | a jarfile nested inside another jarfile |
| `cgvfs:/path/to/app.jar!/BOOT-INF/classes` | a package root within a jarfile |
| `cgvfs:jrt:/java.logging` | a module of the boot layer |
| `cgvfs:https://example.com/app.jar` | a jarfile at a URL |

Which of `newFileSystem` and `getPath` you call decides whether the last `!/` section is opened as a
filesystem of its own or read as a path within the enclosing one, the same way a `jar:` URI works
for zipfs. `FileSystems.newFileSystem(uri, env)` opens the whole path as a filesystem;
`Paths.get(uri)` reads the last `!/` section against the longest prefix that already has a
filesystem open, so with the filesystem above open, `cgvfs:/path/to/outer.jar!/lib/inner.jar!/com/xyz/Widget.class`
is a path *inside* `inner.jar`, not inside `outer.jar`.

One filesystem is open at a path at a time: a second `newFileSystem` at the same path throws
`FileSystemAlreadyExistsException` until the first is closed, and `FileSystems.getFileSystem(uri)`
finds the open one or throws `FileSystemNotFoundException`. A filesystem created this way owns the
`Vfs` behind it, so closing it releases everything that `Vfs` took.

Installing the scheme changes one thing outside it. `FileSystems.newFileSystem(path)` tries each
installed provider in turn and takes the first that does not decline the file, and this provider
accepts a *directory*, which no built-in provider reads as a filesystem — so with `classgraph-vfs`
on the classpath that call returns a `cgvfs:` filesystem over the directory where it would otherwise
throw `ProviderNotFoundException`. Nothing else moves: an archive still goes to the JDK's own zipfs,
which is tried first, and a file this provider cannot read as a filesystem is declined with
`UnsupportedOperationException` so that the search carries on to the providers behind it.

Two options can be passed in the `env` map: `"vfsSpec"`, a `VfsSpec` configuring the `Vfs` that will
be created (see [Options](#options)), and `"layer"`, a `ModuleLayer` to resolve a `cgvfs:jrt:/...`
module name against instead of the boot layer.

`VfsPath.toCgvfsUri()` writes a path back out as a `cgvfs:` URI, and throws
`FileSystemNotFoundException` if the scheme is not installed in this JVM, rather than handing back a
URI that nothing could resolve. (`ServiceLoader` only finds the provider if `classgraph-vfs` is
loaded by the system class loader, so the scheme is silently absent inside a servlet container, a
Spring Boot fat jar or an OSGi bundle, which load it in a child loader.) `Path.toUri()` is
unchanged, and still returns the `file:`, `jar:` or `jrt:` URI of the underlying storage, which
names the same bytes to code that has never heard of ClassGraph.

## What it reads that `java.util.zip` does not

* **Jarfiles nested inside other jarfiles, to any depth**, named by separating the levels with
  `!/`, e.g. `outer.jar!/lib/inner.jar!/lib/innermost.jar`. A nested jarfile that is stored
  uncompressed is read in place, with no copy; one that is stored deflated is inflated into RAM,
  or spilled to a temporary file if it exceeds the maximum buffered jar RAM size (64MB by default).
* **A package root within a jarfile**, named by a trailing `!/` section that is not itself a
  jarfile, e.g. `spring-boot-app.jar!/BOOT-INF/classes`. Entry names are reported with the root
  stripped off.
* **Multi-release jarfiles**, resolved to the newest version of each entry that the running JVM can
  use, or every version if multi-release versions were enabled on the `Vfs`.
* **Jarfiles at a URL**, for any scheme the JVM has a handler for, unless that scheme was denied on
  the `Vfs`. The file is downloaded in full first, because a zipfile's central directory is at the
  end. A scheme with a `FileSystemProvider` installed for it is read in place instead, with no copy.
  See [Custom URL schemes](#custom-url-schemes).
* **Entry names encoded in IBM Code Page 437**, which the zip specification requires when bit 11 of
  the entry's general purpose bit flag is clear. Windows Explorer and Info-ZIP both write such
  names.

## Options

How storage is read is set by a `VfsSpec`, which the `Vfs` constructor takes. Each setting is
changed by a method that returns the same `VfsSpec`, so the ones that differ from the default can be
chained onto the constructor call and the rest left alone:

```java
new Vfs(new VfsSpec().disableURLScheme("https").setMaxBufferedJarRAMSize(65536))
```

| Setting | Default | Effect |
| --- | --- | --- |
| `enableNestedJars()` / `disableNestedJars()` | enabled | Whether `!/` in a path may name a jarfile within a jarfile, rather than only a package root within a jarfile |
| `enableMultiReleaseVersions()` / `disableMultiReleaseVersions()` | enabled | Whether to serve each entry of a multi-release jarfile in the highest version that does not exceed this JVM's major version, as the JVM does, rather than reporting every version under its own `META-INF/versions/<N>/` path |
| `disableURLScheme(String)` / `enableURLScheme(String)` | none denied | A URL scheme a jarfile may **not** be opened from, e.g. `"https"`. Every scheme the JVM has a handler for is allowed until it is denied. Denying `file:` or `jar:` has no effect, since both prefixes are stripped before a path is opened. Call once per scheme |
| `setMaxBufferedJarRAMSize(int)` | 64MB | How many bytes of a jarfile may be held in RAM before it is spilled to a temporary file |

`new Vfs()` uses the default of every setting. Each setting has a matching getter --
`isNestedJarsEnabled()`, `isMultiReleaseVersionsEnabled()`, `getDeniedURLSchemes()` and
`getMaxBufferedJarRAMSize()` -- and `VfsSpec.DEFAULT_ENABLE_NESTED_JARS`,
`VfsSpec.DEFAULT_ENABLE_MULTI_RELEASE_VERSIONS` and `VfsSpec.DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE` name
the defaults.

The `VfsSpec` is held by the `Vfs`, not copied, and each setting is read where it is needed, so a
setting should be chosen before the `Vfs` opens anything -- a setting changed while entries are
being read takes effect for some of them and not others. Changing one is safe from any thread: every
setting is held in a volatile field.

## Custom URL schemes

A path handed to `vfs.open(String)` that starts with a URL scheme is opened as a URL, and a `Vfs`
opens whatever the JVM can open. There is nothing to register with `classgraph-vfs`: a scheme is
openable because the JDK ships a handler for it, or because the application registered one through
one of the two JDK service provider interfaces below.

| Registered as | Registered by | How `classgraph-vfs` reads it |
| --- | --- | --- |
| `java.net.URLStreamHandler` | `URL.setURLStreamHandlerFactory(factory)`, a `java.net.spi.URLStreamHandlerProvider` on the module path or in `META-INF/services`, or the `java.protocol.handler.pkgs` system property | The jarfile is downloaded in full through the handler's `URLConnection`, into RAM or a temporary file |
| `java.nio.file.spi.FileSystemProvider` | A provider on the module path or in `META-INF/services` | `Path.of(uri)` resolves through the provider and the jarfile is read in place, with no copy |

A `FileSystemProvider` wins where both are registered for a scheme: reading in place beats
downloading. It is tried first, and a `FileSystemNotFoundException` from it is what falls back to
the download. The two exceptions are `http` and `https`, which are always downloaded, since a URL at
either is remote by definition.

The JDK ships handlers for `file`, `jar`, `http`, `https`, `ftp`, `mailto`, `jrt` and `jmod`
(measured on JDK 17 and JDK 26), and installed `FileSystemProvider`s for `file`, `jar` and `jrt`.
So opening `s3://bucket/lib.jar` works exactly when something in the application has registered an
`s3` handler or filesystem provider, and fails with the JVM's own report otherwise:

```
java.io.IOException: Could not open s3://bucket/lib.jar :
    java.io.IOException: Could not parse URL (java.net.MalformedURLException: unknown protocol: s3): s3://bucket/lib.jar
```

To keep a jarfile from being fetched over a scheme, name it:

```java
new Vfs(new VfsSpec().disableURLScheme("https"))
```

A `Vfs` denies nothing by default. `ClassGraph` and `ClasspathFinder` construct theirs with `http`,
`https`, `ftp` and `mailto` denied, because a classpath is not always something the caller wrote,
and those four fetch over a network on any JVM. `enableURLScheme(String)` takes a scheme back off
that list.

The list is a deny list rather than an allow list because there is no way to build the allow list:
the JDK offers no way to enumerate the registered schemes. `URL`'s handler table is private, and
`setAccessible` on it throws `InaccessibleObjectException` on JDK 17 and later, since `java.base`
does not open `java.net`; `ServiceLoader<URLStreamHandlerProvider>` sees only the providers declared
in service files, so it misses every handler installed by `URL.setURLStreamHandlerFactory`; and the
`URLConnection` a handler returns does not say who registered it, because a custom handler that
delegates to `file:` returns the JDK's own `sun.net.www.protocol.file.FileURLConnection`. Requiring
each scheme to be enabled would therefore have meant every application that installs a handler also
naming it here, to no benefit -- installing the handler is already the statement that those URLs are
meant to be read.

To sidestep URL handling altogether, open a `Path` instead: `vfs.open(Path)` reads through whatever
filesystem the `Path` belongs to, and never looks at a scheme.

```java
try (FileSystem fs = FileSystems.newFileSystem(URI.create("s3://bucket"), Map.of());
        Vfs vfs = new Vfs()) {
    VfsRoot root = vfs.open(fs.getPath("/lib/library.jar"));
    System.out.println(root.getEntries().size() + " entries");
}
```

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
[memory mapping benchmark](https://github.com/classgraph/classgraph/wiki/Memory-Mapping-Benchmark).
A mapped file is unmapped when the root that mapped it is closed, which closing the `Vfs` does to
every root, on every JDK version: on JDK 22
or later by closing the `java.lang.foreign.Arena` that mapped it, and below that by
`Unsafe::invokeCleaner`, the only method there is that can unmap a file on demand. That method frees
the address range whether or not anything is still reading it, and a thread that reads one byte
afterwards takes a SIGSEGV that kills the JVM, so below JDK 22 a file is left mapped while a
`CloseableByteBuffer` that the caller has not closed yet is still a view of it, and the last such
buffer to be closed unmaps the file instead. This matters on Windows, which refuses to delete,
rename or overwrite a file while it is mapped: a close that returned with the files it had mapped
still mapped would leave them locked.)

A `Vfs` and everything it hands out is safe to use from many threads at once. This is what lets
ClassGraph scan a jarfile in parallel. What that is worth is measured in
[Benchmarks](#benchmarks) below.

## Benchmarks

Two things are compared against, on the same work:

* The JDK's own zip filesystem provider, since both it and cgvfs are `java.nio.file.FileSystem`
  implementations over a zipfile, and the same `java.nio.file.Files` calls read either one. The
  benchmark runs identical code against both, differing only in how the filesystem was opened:

  ```java
  FileSystems.newFileSystem(zipFile, Map.of());                        // zipfs
  FileSystems.newFileSystem(URI.create("cgvfs:" + zipFile), Map.of()); // cgvfs
  ```

* `java.util.zip.ZipFile`, for entry lookup and bulk inflation, where there is no nio provider in
  the picture on either side.

Parameters:

* AMD Ryzen 9 3950X, 16 physical cores with two hardware threads each (32 in total), 125GB RAM,
  Linux, JDK 26.0.2. Archives are in `/tmp`, which is `tmpfs` here, so every read comes out of
  memory and no disk is in the picture.
* `random.zip` -- 5120 files of 1MB each from `/dev/urandom`, **stored** uncompressed (5.0GB, which
  makes it a ZIP64 archive). Random data does not compress, so this measures the read path with the
  inflater out of the picture.
* `books.zip` -- 256 ebooks downloaded from Project Gutenberg, replicated to 5120 entries under
  distinct names, **deflated** at level 9 (2.6GB of text compressed to 0.9GB). Both providers use
  the same `java.util.zip.Inflater`.
* `kotlin-compiler-embeddable-2.4.10.jar`, 26970 entries, for the lookup and inflation tables.
* Timings against zipfs are the mean of two runs, which agreed to within 6% on every cell; the
  partial-read timings are the mean of three, which agreed to within 2%. Cells below 100ms vary by
  up to 40% between runs, so read those as "small" rather than as exact. Both providers were checked
  to read the same number of bytes: 5368709120 and 2828491700.

### Opening, enumerating and closing

The archive is opened, every regular file in it is enumerated with `Files.walk`, and it is closed --
100 times, after 20 warmup iterations. This measures parsing the central directory, which is what
you pay before reading anything at all.

| Archive | zipfs | cgvfs | speedup |
| --- | ---: | ---: | ---: |
| `random.zip` | 18.68 ms | **5.14 ms** | 3.63× |
| `books.zip` | 17.82 ms | **4.74 ms** | 3.76× |

Opening an archive and listing it costs about a quarter of what it costs under zipfs.

### Reading every file

Every file in the archive is read in full with `Files.readAllBytes`, spread across a fixed thread
pool. One provider is benchmarked at a time, with its own filesystem open for the whole sweep, so
that the two never contend with each other. Total wall time for all 5120 entries.

| Archive | Threads | zipfs | cgvfs | speedup |
| --- | ---: | ---: | ---: | ---: |
| `random.zip` (stored, 5.0GB) | 1 | 2526 ms | **1182 ms** | 2.14× |
| | 2 | 1727 ms | **780 ms** | 2.21× |
| | 4 | 1442 ms | **669 ms** | 2.16× |
| | 8 | 1382 ms | **618 ms** | 2.23× |
| | 16 | 1440 ms | **681 ms** | 2.11× |
| | 32 | 1605 ms | **1063 ms** | 1.51× |
| `books.zip` (deflated, 2.6GB) | 1 | 7480 ms | **6624 ms** | 1.13× |
| | 2 | 3856 ms | **3437 ms** | 1.12× |
| | 4 | 2038 ms | **1784 ms** | 1.14× |
| | 8 | 1151 ms | **980 ms** | 1.17× |
| | 16 | 797 ms | **617 ms** | 1.29× |
| | 32 | 841 ms | **639 ms** | 1.31× |

Reading a stored archive is a little over twice as fast; reading a deflated one is 1.1-1.3× as fast,
since both providers spend most of that time in the same inflater. Neither provider improves beyond
16 threads on this machine, and both get slower at 32.

### Reading part of an entry

A caller that opens a channel rather than a stream often wants a header, a footer, or a field at a
known offset, and a deflated entry cannot be read at an offset without inflating everything up to
it. cgvfs inflates only as far as the furthest offset that has been read, buffering what it has
inflated so far, so that going back over a part already read does not inflate it again. Reading the
first 64 bytes of each of the 5120 entries of `books.zip` through `Files.newByteChannel`.

| Threads | zipfs | cgvfs | speedup |
| ---: | ---: | ---: | ---: |
| 1 | 7133 ms | **212 ms** | 33.7× |
| 2 | 3669 ms | **114 ms** | 32.2× |
| 4 | 1878 ms | **58 ms** | 32.4× |
| 8 | 993 ms | **31 ms** | 32.0× |
| 16 | 606 ms | **20 ms** | 30.4× |
| 32 | 564 ms | **16 ms** | 34.8× |

Reading a small part of a deflated entry costs about 32× less than under zipfs, at every thread
count.

### Entry lookup

Looking up every one of the 26970 entries of `kotlin-compiler-embeddable-2.4.10.jar` by name, 400
times over.

| Threads | `java.util.zip` (shared) | `java.util.zip` (per thread) | cgvfs | speedup vs shared | speedup vs per thread |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 927 ms | 969 ms | **153 ms** | 6.1× | 6.3× |
| 2 | 1081 ms | 459 ms | **74 ms** | 14.6× | 6.2× |
| 4 | 1615 ms | 230 ms | **41 ms** | 39.4× | 5.6× |
| 8 | 1496 ms | 145 ms | **35 ms** | 42.7× | 4.1× |
| 16 | 1593 ms | 102 ms | **14 ms** | 113.8× | 7.3× |
| 32 | 1552 ms | 74 ms | **11 ms** | 141.1× | 6.7× |

Lookup under one shared `ZipFile` gets *slower* as threads are added; the middle column shows the
same work scaling once each thread has its own `ZipFile`, at the cost of parsing the central
directory and holding a file handle per thread.

### Bulk inflation

Inflating every entry of the same jarfile, 20 times over.

| Threads | `java.util.zip` (shared) | cgvfs | speedup |
| ---: | ---: | ---: | ---: |
| 1 | 9342 ms | **8593 ms** | 1.09× |
| 2 | 5123 ms | **4532 ms** | 1.13× |
| 4 | 3215 ms | **2457 ms** | 1.31× |
| 8 | 3081 ms | **1510 ms** | 2.04× |
| 16 | 3103 ms | **978 ms** | 3.17× |
| 32 | 3206 ms | **941 ms** | 3.41× |

Inflation under a shared `ZipFile` stops scaling at four threads, while cgvfs keeps scaling to 16.

The benchmarks are
[`ZipfsVsCgvfsBenchmark`](src/test/perf/io/github/classgraph/vfs/perf/ZipfsVsCgvfsBenchmark.java),
which documents how to build the two archives and how to run it, and
[`ZipFileLockingBenchmark`](src/test/perf/io/github/classgraph/vfs/perf/ZipFileLockingBenchmark.java).
The partial-read numbers were measured with a throwaway harness rather than a checked-in benchmark.

## Recipes

### List what is in a directory, a jarfile or a module

```java
try (Vfs vfs = new Vfs()) {
    VfsRoot dir = vfs.open("/path/to/classes");
    VfsRoot jar = vfs.open("/path/to/library.jar");
    VfsRoot module = vfs.open(ModuleFinder.ofSystem().find("java.logging").orElseThrow());
    for (VfsRoot root : List.of(dir, jar, module)) {
        System.out.println(root + " (" + root.getClass().getSimpleName() + ")");
        for (VfsEntry entry : root) {
            System.out.println("  " + entry.getName() + " (" + entry.getLength() + " bytes)");
        }
    }
}
```

A root is `Iterable<VfsEntry>`, and iterating it lists files, not directories, and names them
relative to the root, so the same loop walks all three kinds of root. `getEntries()` returns the
same entries as a `List`. The three roots are named here rather than iterating the `Vfs` itself,
which would visit the same three, so that the outer loop reports them in the order they were opened
rather than in path order. Closing the `Vfs` releases every file handle, memory mapping and
temporary file it took, and invalidates every `VfsRoot` and `VfsEntry` it handed out, so do not let
them escape the `try` block.

For a directory, an entry may name a file the process has no permission to read: telling the files
from the subdirectories uses the metadata the walk already reads, and a permission check would cost
a second syscall per file. Reading such an entry throws an `IOException`.

### List only the part of a root you want

Iterating a root builds a list of every file in it. When only some of them are wanted, `walk()`
is cheaper: it offers each directory before the entries in it, so an unwanted one can be skipped,
and for a directory tree a skipped directory is never even listed.

```java
try (Vfs vfs = new Vfs()) {
    VfsRoot root = vfs.open("/path/to/classes");
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
directories below it are still offered. That is deliberate -- a caller that strips a package root
prefix such as `BOOT-INF/classes/` from the names before judging them would otherwise prune
`BOOT-INF/` and lose everything under it.

When the part you want is simply everything under one path, `getEntries(String)` does the same walk
for you, and skips the directories that cannot hold a match:

```java
try (Vfs vfs = new Vfs()) {
    VfsRoot root = vfs.open("/path/to/app.jar");
    // Every jarfile the application bundles
    for (VfsEntry entry : root.getEntries("BOOT-INF/lib/")) {
        System.out.println(entry.getName());
    }
}
```

The prefix is matched against the whole entry name, so it need not end at a directory boundary:
`getEntries("com/xyz/Wid")` finds `com/xyz/Widget.class` too.

### Read one entry

```java
try (Vfs vfs = new Vfs()) {
    VfsRoot root = vfs.open("/path/to/library.jar");
    VfsEntry entry = root.getEntry("META-INF/MANIFEST.MF");
    if (entry != null) {
        System.out.println(entry.loadAsString());
    }
}
```

`getEntry` returns null if there is no *readable* entry with that name: for a directory the name may
not exist, may name a directory rather than a file, may name a file the process has no permission to
read, or may point outside the root once `..` sections are resolved; for a jarfile or a module it may
not exist, or may be an entry this root does not report -- an encrypted entry, an entry stored with an
unsupported compression method, or an entry hidden by a newer multi-release version of itself. Null
does not say which, so test for the file directly if the difference matters.

`getEntry` matches the name exactly, including the case of every character, on every operating system
and for every kind of root, so a name it finds is a name a classloader will also find. Windows and
macOS have case-insensitive filesystems, and a lookup that such a filesystem answered by folding the
case of the name is rejected here rather than handing back an entry under a name that is not the one
it is stored under. A name that reaches a file through a symbolic link is the one exception:
following the link changes the path by more than the case of its characters, which is the only thing
that tells a folded name from a followed link.

When the case should be ignored instead, `getEntryCaseInsensitive` returns the first entry whose name
matches with the case of both ignored, and `getEntriesCaseInsensitive` returns all of them -- a root
can hold several, since a zipfile is free to store both `META-INF/MANIFEST.MF` and
`meta-inf/manifest.mf`, and a case-sensitive filesystem is free to hold both as files. Both behave
the same way on every operating system and for every kind of root, and the entries they return carry
the name they are stored under, not the name they were asked for:

```java
// Finds "Com/Xyz/Widget.txt" too
VfsEntry entry = root.getEntryCaseInsensitive("com/xyz/widget.txt");
```

There are four ways to read an entry, differing only in what you get back and who has to release it.

`entry.open()` streams the content, so a large entry never has to be held in memory. The stream is
yours to close:

```java
try (InputStream inputStream = entry.open()) {
    // ... Read from inputStream ...
}
```

`entry.read()` hands back the content as a `ByteBuffer` -- which is the memory mapping itself, with
no copy, where the entry is stored uncompressed in a file that could be mapped. It is wrapped in a
`CloseableByteBuffer` because some of those buffers own storage that has to be handed back when you
have finished with it, which `close()` does: a file read from a directory owns its mapping, and a
module resource owns a buffer that the module reader lends out. An entry read from inside a jarfile
owns nothing of its own -- the jarfile is released when its root is closed -- so closing it does
nothing, but the wrapper is the same either way, so the calling code does not have to know which
kind of entry it is reading:

```java
try (CloseableByteBuffer closeableBuffer = entry.read()) {
    ByteBuffer byteBuffer = closeableBuffer.getByteBuffer();
    // ... Read from byteBuffer ...
}
```

The buffer can be the mapping itself, so it must not be read after the root it came from, or the
`Vfs`, is closed, even while
the wrapper is still open. That is the one place in this API where closing during a read is not
reported as an `IOException`, because nothing sits between a raw `ByteBuffer` and the caller to
translate the failure. What such a read does instead depends on how the JDK releases the mapping: on
JDK 22 or later the file is unmapped as the root closes, and the read throws
`IllegalStateException`; below JDK 22 the open wrapper holds the file mapped until it is closed, so
the read quietly returns the file content.

`entry.load()` and `entry.loadAsString()` copy the content into a `byte[]` and a UTF-8 `String`
respectively. There is nothing to close, and the result stays valid after the root and the `Vfs`
are closed:

```java
byte[] classfile = entry.load();
String manifest = entry.loadAsString();
```

All four throw `IOException` if the entry cannot be read, or if the root, or the `Vfs`, has been
closed. A fifth,
`entry.openChannel()`, returns a `ReadableByteChannel` for code that reads that way.

### Read a jarfile that is not on disk

```java
try (Vfs vfs = new Vfs();
        InputStream inputStream = URI.create("https://example.com/library.jar").toURL().openStream()) {
    VfsRoot root = vfs.open(inputStream, "downloaded.jar");
    System.out.println(root.getEntries().size() + " entries");
}
```

The stream is read into RAM, or spilled to a temporary file if it is larger than the maximum
buffered jar RAM size. `vfs.open(byte[], String)` does the same for a jarfile you already hold.
Alternatively, let the library do the fetching: `vfs.open("https://.../library.jar")` opens the URL
directly.

### Read a jarfile nested inside another jarfile

```java
try (Vfs vfs = new Vfs()) {
    VfsRoot nested = vfs.open("/path/to/outer.jar!/BOOT-INF/lib/inner.jar");
    for (VfsEntry entry : nested) {
        System.out.println(entry.getName());
    }
}
```

Nothing is extracted to disk unless the nested jarfile is stored deflated and is larger than the
maximum buffered jar RAM size. Construct the `Vfs` with nested jars disabled if `!/` in a path
should only ever mean a package root.

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
try (FileSystem fileSystem = FileSystems.newFileSystem(Path.of("/path/to/library.jar"));
        Vfs vfs = new Vfs()) {
    VfsRoot root = vfs.open(fileSystem.getPath("/"));
    root.getEntries().forEach(entry -> System.out.println(entry.getName()));
}
```

Any `Path` works, whatever provider it belongs to, whether it names a directory or a jarfile.

### Read a root's manifest

```java
try (Vfs vfs = new Vfs()) {
    VfsRoot root = vfs.open("/path/to/library.jar");
    System.out.println("main class: " + root.getManifestEntry("Main-Class"));

    Map<String, String> manifest = root.getManifest();
    if (manifest != null) {
        manifest.forEach((name, value) -> System.out.println(name + " = " + value));
    }
}
```

Only the main section of `META-INF/MANIFEST.MF` is read -- the sections after it describe individual
entries of the jarfile rather than the jarfile as a whole. Attribute names are case insensitive, and
a value split across several lines is joined back together. Both methods return null if the root has
no manifest, and the manifest is read once and then cached.

A directory and a module have a manifest read from the same place, so an exploded jarfile is
described by its manifest just as the jarfile it was exploded from is.

A jarfile written by a tool that lower-cased its entry names still has a manifest, and it is found:
the canonical name is looked for first, since that is the name it is stored under in all but a
handful of jarfiles, and then the same name with the case of both ignored. A module is the one
exception, because searching one that way means listing the whole module, and a module of the running
JDK carries no manifest at all.

### Find a root's module name

```java
try (Vfs vfs = new Vfs()) {
    VfsRoot root = vfs.open("/path/to/library.jar");
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
try (Vfs vfs = new Vfs().verbose()) {
    VfsRoot root = vfs.open("/path/to/library.jar");
    System.out.println(root.getEntries().size() + " entries");
}
```

The log is written to the `io.github.classgraph.ClassGraph` logger at `INFO` level when the `Vfs`
is closed, not as the reading happens. It is a debugging aid, not a stable output format.

## License

MIT. See [LICENSE-ClassGraph.txt](../LICENSE-ClassGraph.txt).
