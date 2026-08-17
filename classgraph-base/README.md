# classgraph-base

The code shared by the other four ClassGraph libraries: path and URL handling, the verbose log,
accept/reject matching, reflection, interruption checking, and a few string and collection helpers.

**There is almost nothing here to call.** This library is a dependency of
[`classgraph-vfs`](../classgraph-vfs), [`classgraph-classpath`](../classgraph-classpath),
[`classgraph`](../classgraph) and [`classgraph-viz`](../classgraph-viz), and comes in transitively
with any of them. Depend on it directly only if you are writing something that implements a
ClassGraph extension point and needs one of the two types it exports.

Module name: `io.github.classgraph.base`. Requires JDK 17 or newer. It has no dependencies that
reach a downstream project: `org.jspecify` supplies the nullness annotations and is `provided`
scope, so it is never on the runtime classpath, and `io.github.toolfactory:narcissus` is optional,
used as the reflection driver only if you put it on the classpath yourself -- it reads fields
natively, which lets ClassGraph read the private fields of a classloader that the module system
would otherwise keep closed.

## The public API

Two types, both to do with the verbose log that ClassGraph writes what it is doing to.

### `ClassGraphLog`

The narrower of the two, `io.github.classgraph.base.ClassGraphLog`:

```java
public interface ClassGraphLog {
    ClassGraphLog log(@Nullable String msg);
    ClassGraphLog log(@Nullable String msg, Throwable e);
}
```

This is the verbose log, and it is handed to code that ClassGraph calls out to -- in practice, to a
[`ClassLoaderHandler`](../classgraph-classpath) implementation. Each call returns the node that
sub-entries can be added to, so the log forms a tree:

```java
@Override
public void findClasspathOrder(ClassLoader classLoader, ClasspathOrder classpathOrder,
        ClassGraphLog log) {
    // log is null whenever verbose logging is switched off, so every use has to be null-guarded
    ClassGraphLog subLog = log == null ? null : log.log("Reading the classpath of " + classLoader);
    try {
        classpathOrder.addClasspathEntry(readClasspathFrom(classLoader), classLoader, subLog);
    } catch (Exception e) {
        if (subLog != null) {
            subLog.log("Could not read the classpath", e);
        }
    }
}
```

Two things to know about it. The log is written by many threads at once in an arbitrary order, and
is only sorted and printed when it is flushed at the end of a scan, so a message does not appear at
the moment it is logged. And the node is null when verbose logging is off, so every use of it needs
a null check -- there is no no-op instance.

### `LogNode`

`io.github.classgraph.base.LogNode` is the log itself, and implements `ClassGraphLog`. Use it when
you want to write a log of your own and have what ClassGraph logs nested under the part of it that
it belongs to, rather than printed as a separate tree:

```java
LogNode log = new LogNode();
LogNode readingJars = log.log("Reading the jarfiles");
try (Vfs vfs = new Vfs()) {
    VfsRoot root = vfs.open("/path/to/library.jar", readingJars);
    // ...
}
log.flush();  // Writes the whole tree to the io.github.classgraph.ClassGraph logger, at INFO level
```

Every method that takes a `LogNode` has an overload that does not, so passing one is never
required. A node's children are printed in the order they were added, unless a sort key is given, in
which case they are ordered by sort key, so a log written by several threads at once reads the same
way every time. The output is meant to be read by a person working out why something was or was not
found, and is not a stable format to parse.

## Everything else

The rest of the library is in `io.github.classgraph.base.internal.*`. Those packages are qualified
exports in `module-info.java`, readable only by the other four ClassGraph modules, and are not
covered by any compatibility guarantee -- they change between releases without notice. If you find
yourself wanting something out of them, open an issue instead, and it can be considered for the
public API of whichever library it belongs to.

| Package | Contents |
| --- | --- |
| `internal.concurrency` | Interruption checking shared by the threads of a scan, and a map that builds each value exactly once however many threads ask for it at once |
| `internal.filter` | The accept/reject criteria behind ClassGraph's package, class and jarfile filters |
| `internal.path` | Path and URL parsing, resolution and canonicalization, and the file checks that go with them |
| `internal.reflection` | Reflection that degrades gracefully when the module system blocks it |
| `internal.utils` | Argument checks, string and collection helpers, an `InputStream` wrapper, and version lookup for the JDK and for ClassGraph itself |

## License

MIT. See [LICENSE-ClassGraph.txt](../LICENSE-ClassGraph.txt).
