# classgraph-base

The code shared by the other four ClassGraph libraries: path and URL handling, logging, reflection,
classfile and manifest parsing, concurrency, memory management, and accept/reject matching.

**There is almost nothing here to call.** This library is a dependency of
[`classgraph-vfs`](../classgraph-vfs), [`classgraph-classpath`](../classgraph-classpath),
[`classgraph`](../classgraph) and [`classgraph-viz`](../classgraph-viz), and comes in transitively
with any of them. Depend on it directly only if you are writing something that implements a
ClassGraph extension point and needs the one type it exports.

Module name: `io.github.classgraph.base`. Requires JDK 17 or newer. It has no dependencies that
reach a downstream project: `org.jspecify` supplies the nullness annotations and is `provided`
scope, so it is never on the runtime classpath, and `io.github.toolfactory:narcissus` is optional,
used as the reflection driver only if you put it on the classpath yourself -- it reads fields
natively, which lets ClassGraph read the private fields of a classloader that the module system
would otherwise keep closed.

## The public API

One interface, `io.github.classgraph.base.ClassGraphLog`:

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
        @Nullable ClassGraphLog log) {
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

## Everything else

The rest of the library is in `io.github.classgraph.base.internal.*`. Those packages are qualified
exports in `module-info.java`, readable only by the other four ClassGraph modules, and are not
covered by any compatibility guarantee -- they change between releases without notice. If you find
yourself wanting something out of them, open an issue instead, and it can be considered for the
public API of whichever library it belongs to.

| Package | Contents |
| --- | --- |
| `internal.concurrency` | The work queue that drives the parallel scan, and interruption handling |
| `internal.parser` | Classfile constant pool, type signature and manifest parsing |
| `internal.recycler` | Pooling of objects that are expensive to create and not thread safe, such as `Inflater` |
| `internal.reflection` | Reflection that degrades gracefully when the module system blocks it |
| `internal.utils` | Path and URL resolution, file utilities, the log implementation, version lookup |

## License

MIT. See [LICENSE-ClassGraph.txt](../LICENSE-ClassGraph.txt).
