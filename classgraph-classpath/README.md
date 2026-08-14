# classgraph-classpath

Works out what is actually on the classpath and module path of a running JVM, and in what order.
It does not read any of it -- there is no scanner and no classfile parser here. If you want to find
classes, use [`classgraph`](../classgraph) instead, which is built on this library.

```xml
<dependency>
    <groupId>io.github.classgraph</groupId>
    <artifactId>classgraph-classpath</artifactId>
    <version>X.Y.Z</version>
</dependency>
```

Module name: `io.github.classgraph.classpath`. Requires JDK 17 or newer. Depends on
[`classgraph-vfs`](../classgraph-vfs), which is pulled in transitively.

See the [Classpath API](https://github.com/classgraph/classgraph/wiki/Classpath-API) for the full
reference, and
[Classpath Specification Mechanisms](https://github.com/classgraph/classgraph/wiki/Classpath-Specification-Mechanisms)
for the list of places a classpath can come from.

## Why this is not just `System.getProperty("java.class.path")`

That property holds the classpath the JVM was started with. It is empty or misleading in most
non-trivial deployments, because the code that is actually running was loaded by a classloader that
built its own classpath afterwards. This library asks the classloaders themselves:

* **Container and framework classloaders**, whose classpath is not on the command line at all.
  Handlers ship for Ant, OSGi (Equinox, Felix, and the OSGi default classloader), JBoss/WildFly,
  WebLogic, WebSphere (Liberty and traditional), Spring Boot's restart classloader, Tomcat, Apache
  CXF, Maven's Plexus class worlds, Quarkus, UNO OneJar, and JPMS module layers, with
  `URLClassLoader` and a reflective fallback behind them.
* **The module path**, including modules added by `--add-modules`, and the `--patch-module`,
  `--add-exports`, `--add-opens` and `--add-reads` settings that go with it.
* **`Class-Path:` manifest entries**, followed recursively, so a jarfile that names its
  dependencies in its manifest contributes them too.
* **Package roots inside jarfiles**, such as a Spring Boot application's `BOOT-INF/classes`.
* **Classloader delegation order**, so entries come back in the order a classloader would actually
  search them -- which is what decides which copy of a duplicated class wins.

## Recipes

### Print the classpath

```java
try (Classpath classpath = new ClasspathFinder().find()) {
    classpath.getLocations().forEach(System.out::println);
}
```

`getLocations()` gives the location strings in classpath order. Closing the `Classpath` releases
the file handles and temporary files taken while expanding `Class-Path:` manifest entries and
nested jarfiles.

### Look at where each entry came from

```java
try (Classpath classpath = new ClasspathFinder().find()) {
    for (ClasspathEntry entry : classpath) {
        System.out.println(entry.location()
                + "  [classloader: " + entry.classLoaderName() + "]"
                + (entry.packageRootPrefixes().isEmpty() ? ""
                        : "  [package roots: " + entry.packageRootPrefixes() + "]"));
    }
}
```

Iterating the `Classpath` iterates its entries, in classpath order; `getEntries()` returns the same
list. `location()` is an absolute path, or a nested path of the form `outer.jar!/inner.jar`, or a
URL for anything that is not a local file -- so do not assume `Path.of(entry.location())` will
succeed. `packageRootPrefixes()` lists the prefixes to strip from entry names within that element,
e.g. `BOOT-INF/classes` for a Spring Boot jarfile; it is empty for an ordinary jarfile.

### Read every classfile on the classpath, without the scanner

Combining this library with [`classgraph-vfs`](../classgraph-vfs):

```java
try (Classpath classpath = new ClasspathFinder().find()) {
    // The virtual filesystem that the classpath was read through
    Vfs vfs = classpath.getVfs();
    for (ClasspathEntry entry : classpath) {
        // A classpath entry can be a directory, a jarfile or a jarfile nested in another jarfile.
        // The virtual filesystem opens all of them, and lists their contents the same way.
        for (VfsEntry resource : entry.open(vfs)) {
            if (resource.getName().endsWith(".class")) {
                System.out.println(resource.getPath());
            }
        }
    }
}
```

`classpath.getVfs()` is the same `Vfs` that the jarfiles' manifests were read through while the
classpath was being found, so the jarfiles are still open and their central directories have already
been parsed -- opening a `new Vfs()` here instead would read every one of them a second time. It is
closed by `classpath.close()`, along with every root and entry it handed out, so do not let those
escape the `try` block.

`entry.open(vfs)` opens the classpath element in whichever form the classloader named it in -- a
path string, a `File`, a `Path`, a `URL` or a `URI` -- rather than flattening it to `location()` and
parsing that back. That matters for the forms a location cannot round-trip: a `Path` in a filesystem
other than the default one is reached only through its own filesystem, and a `URL` keeps the scheme
it was found with. `ClasspathEntry` is a sealed type with one subclass per form, so code that needs
the original object can ask for it:

```java
if (entry instanceof ClasspathEntry.OfURL urlEntry) {
    System.out.println("Served over " + urlEntry.url().getProtocol());
}
```

A classpath entry can also be a URL for something that is not a local file, and `open` will
throw `IOException` for one of those unless its scheme is allowed. Call
`new ClasspathFinder().enableURLScheme("https")` before `find()`, which allows the scheme both while
the classpath is being found and on the `Vfs` that `Classpath.getVfs()` hands back.

### List the modules

```java
try (Classpath classpath = new ClasspathFinder().find()) {
    for (ModuleReference module : classpath.getNonSystemModules()) {
        System.out.println(module.descriptor().name());
    }
}
```

Modules are a separate list from the classpath entries: `getModules()` returns all of them,
`getSystemModules()` the ones from the JDK itself, and `getNonSystemModules()` the rest. Iterating
the `Classpath` alone will not see them. A module's contents are read through the same virtual
filesystem as a classpath element:

```java
try (Classpath classpath = new ClasspathFinder().find()) {
    Vfs vfs = classpath.getVfs();
    for (ModuleReference module : classpath.getNonSystemModules()) {
        for (VfsEntry resource : vfs.open(module)) {
            System.out.println(resource.getPath());
        }
    }
}
```

### Read the module path settings

```java
try (Classpath classpath = new ClasspathFinder().find()) {
    ModulePathInfo modulePathInfo = classpath.getModulePathInfo();
    System.out.println("--module-path:  " + modulePathInfo.getModulePath());
    System.out.println("--add-modules:  " + modulePathInfo.getAddModules());
    System.out.println("--patch-module: " + modulePathInfo.getPatchModules());
    System.out.println("--add-exports:  " + modulePathInfo.getAddExports());
    System.out.println("--add-opens:    " + modulePathInfo.getAddOpens());
    System.out.println("--add-reads:    " + modulePathInfo.getAddReads());
}
```

### Scan a classpath of your own choosing

```java
try (Classpath classpath = new ClasspathFinder()
        .overrideClasspath(Path.of("/path/to/a.jar"), Path.of("/path/to/classes"))
        .find()) {
    classpath.getLocations().forEach(System.out::println);
}
```

The varargs and `Iterable` overloads take one classpath entry per element. A `String`, `File`,
`Path`, `URL` or `URI` is kept in that form, and is what `entry.open(vfs)` later opens; anything
else is read by its `toString()`. The `String` overload instead takes a whole path and splits it on
the platform's path separator. Related switches:
`overrideClassLoaders(...)` and `addClassLoader(...)` to control which
classloaders are consulted, `ignoreParentClassLoaders()` to stop at the given one,
`overrideModuleLayers(...)` and `addModuleLayer(...)` for JPMS layers, and `ignoreModules()` to skip
the module path entirely.

### Teach it about a classloader it does not know

Implement `ClassLoaderHandler` and register it. This is the extension point that lets ClassGraph
support a container it has never seen:

```java
public class MyClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public boolean canHandle(Class<?> classLoaderClass, @Nullable ClassGraphLog log) {
        return classIsOrExtendsOrImplements(classLoaderClass, "com.xyz.MyClassLoader");
    }

    @Override
    public void findClassLoaderOrder(ClassLoader classLoader, ClassLoaderOrder classLoaderOrder,
            @Nullable ClassGraphLog log) {
        // Search this classloader before its parent (i.e. this one is parent-last)
        classLoaderOrder.add(classLoader, log);
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
    }

    @Override
    public void findClasspathOrder(ClassLoader classLoader, ClasspathOrder classpathOrder,
            @Nullable ClassGraphLog log) {
        for (URL url : ((MyClassLoader) classLoader).getRepositoryURLs()) {
            classpathOrder.addClasspathEntry(url, classLoader, log);
        }
    }
}
```

```java
try (Classpath classpath = new ClasspathFinder()
        .registerClassLoaderHandler(new MyClassLoaderHandler())
        .find()) {
    classpath.getLocations().forEach(System.out::println);
}
```

`addClasspathEntry` accepts a `String`, `File`, `Path`, `URL` or `URI`, and returns false if the
entry was rejected (because it does not exist, or was already added). Override the default
`getPackageRootPrefixes()` if classes live under a prefix within the classpath elements this
classloader returns, as they do for Spring Boot's `BOOT-INF/classes`. The same handler can be
registered with the scanner via `new ClassGraph().registerClassLoaderHandler(...)`.

If a handler is useful to more than your own project, please open a pull request so it can ship
with ClassGraph.

### Work out why an entry is or is not on the classpath

```java
try (Classpath classpath = new ClasspathFinder().verbose().find()) {
    classpath.getLocations().forEach(System.out::println);
}
```

The log is written to the `io.github.classgraph.ClassGraph` logger at `INFO` level when the
`Classpath` is closed. It is a debugging aid, not a stable output format.

## Strong encapsulation

Since JDK 16 the module system has enforced strong encapsulation, and a classloader that only
exposes its classpath through a private field cannot be read by reflection. If a classloader's
classpath comes back empty and the same code worked on JDK 15 or earlier, that is the reason. The
fixes are to add `io.github.toolfactory:narcissus` to the classpath (an optional dependency of
`classgraph-base` that reads fields natively), or to open the relevant module with `--add-opens`.

## License

MIT. See [LICENSE-ClassGraph.txt](../LICENSE-ClassGraph.txt).
