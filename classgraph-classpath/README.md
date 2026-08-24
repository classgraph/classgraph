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

## Say what to find

Nothing is looked for unless it is enabled, so a `ClasspathFinder` with no `enable` method called
finds nothing. The methods that say *where* to look come in pairs:

| No arguments: find what is in the environment | Varargs: use exactly what is named |
| --- | --- |
| `enableClasspath()` -- every classpath element of every classloader that can be found -- the thread context classloader, the system classloader, the classloader of the class in any frame of the call stack, and the ancestors of all of those, including `java.class.path` | `enableClassLoaders(ClassLoader...)`, `enableClasspathEntries(Object...)` |
| `enableModules()`, `enableSystemModules()`, `enableNonSystemModules()` -- the module layers that are visible to the caller | `enableModuleLayers(ModuleLayer...)` |

Call the no-argument method to find what the running application can see. Call only the varargs
method to use *just* what you name, and nothing from the environment. Calling both uses the
environment as well as what you named. Each call adds to the end of the list, so classpath sources
are searched in the order the calls were made, and modules always come first, since that is the
order in which the JVM resolves a class.

## Recipes

### Print the classpath

```java
try (Classpath classpath = new ClasspathFinder().enableClasspath().find()) {
    classpath.getLocations().forEach(System.out::println);
}
```

`getLocations()` gives the location strings in classpath order. Closing the `Classpath` releases
the file handles and temporary files taken while expanding `Class-Path:` manifest entries and
nested jarfiles.

### Look at where each entry came from

```java
try (Classpath classpath = new ClasspathFinder().enableClasspath().find()) {
    for (ClasspathEntry entry : classpath) {
        System.out.println(entry.getLocation()
                + "  [classloader: " + entry.getClassLoaderName() + "]"
                + (entry.getPackageRootPrefixes().isEmpty() ? ""
                        : "  [package roots: " + entry.getPackageRootPrefixes() + "]"));
    }
}
```

Iterating the `Classpath` iterates its entries, in classpath order; `getEntries()` returns the same
list. `getLocation()` is an absolute path, or a nested path of the form `outer.jar!/inner.jar`, or a
URL for anything that is not a local file -- so do not assume `Path.of(entry.getLocation())` will
succeed. A path is given in canonical form, spelled the way the file is stored: symbolic links are
resolved, and on a filesystem that ignores case, so is the case of each name. That is also what
decides whether two entries are the same, so a file that two classloaders reach by different paths
is listed once, at the first position it is reached at. `getPackageRootPrefixes()` lists the
prefixes to strip from entry names within that element, e.g. `WEB-INF/classes` for a Tomcat webapp
classloader; it is empty for a classloader that loads classes only from the classpath elements it
was given, which is almost all of them.

An entry that names a file or directory the filesystem says is not there, or that cannot be read, is
left out of the classpath rather than listed and then failed on, since it can contribute no class.

### Read every classfile on the classpath, without the scanner

Combining this library with [`classgraph-vfs`](../classgraph-vfs):

```java
try (Classpath classpath = new ClasspathFinder().enableClasspath().find()) {
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
path string, a `File`, a `Path`, a `URL` or a `URI` -- rather than flattening it to `getLocation()`
and parsing that back. That matters for the forms a location does not reliably round-trip: a `Path`
in a filesystem other than the default one is read through that filesystem, whether or not its
`toUri()` form resolves back to it and whatever URL schemes are denied, and a `URL` keeps
the scheme it was found with. `ClasspathEntry` is a sealed type with one subclass per form, so code
that needs the original object can ask for it:

```java
if (entry instanceof ClasspathEntry.OfURL urlEntry) {
    System.out.println("Served over " + urlEntry.getURL().getProtocol());
}
```

A classpath entry can also be a URL for something that is not a local file. Any scheme the JVM has a
handler for is opened, including one an application registered itself -- see
[custom URL schemes](../classgraph-vfs/README.md#custom-url-schemes). The exceptions are the four
schemes that fetch over a network: `http`, `https`, `ftp` and `mailto` are denied to begin with,
because a classpath is not always something the caller wrote. An entry with a denied scheme is still
reported, but `open` throws `IOException` for it, so the elements it declares are not found. Call
`new ClasspathFinder().enableClasspath().enableURLScheme("https")` before `find()` to allow one, which allows it both
while the classpath is being found and on the `Vfs` that `Classpath.getVfs()` hands back;
`disableURLScheme(String)` denies a further scheme.

Enabling a scheme is also what keeps a `':'`-separated classpath string from being split at that
scheme's own colon, so a custom scheme is worth naming for that reason even though it needs no
enabling to be fetched from.

### List the modules

```java
try (Classpath classpath = new ClasspathFinder().enableNonSystemModules().find()) {
    for (ModuleReference module : classpath.getNonSystemModules()) {
        System.out.println(module.descriptor().name());
    }
}
```

Modules are a separate list from the classpath entries: `getModules()` returns all of them,
`getSystemModules()` the ones from the JDK itself, and `getNonSystemModules()` the rest. Iterating
the `Classpath` alone will not see them. All three lists are empty unless a module source was
enabled: `enableNonSystemModules()`, `enableSystemModules()`, `enableModules()` for both kinds, or
`enableModuleLayers(...)` for a layer of your own. A module's contents are read through the same virtual
filesystem as a classpath element:

```java
try (Classpath classpath = new ClasspathFinder().enableNonSystemModules().find()) {
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
try (Classpath classpath = new ClasspathFinder().enableNonSystemModules().find()) {
    ModulePathInfo modulePathInfo = classpath.getModulePathInfo();
    System.out.println("--module-path:  " + modulePathInfo.getModulePath());
    System.out.println("--add-modules:  " + modulePathInfo.getAddModules());
    System.out.println("--patch-module: " + modulePathInfo.getPatchModules());
    System.out.println("--add-exports:  " + modulePathInfo.getAddExports());
    System.out.println("--add-opens:    " + modulePathInfo.getAddOpens());
    System.out.println("--add-reads:    " + modulePathInfo.getAddReads());
}
```

### Use a classpath of your own choosing

```java
try (Classpath classpath = new ClasspathFinder()
        .enableClasspathEntries(Path.of("/path/to/a.jar"), Path.of("/path/to/classes"))
        .find()) {
    classpath.getLocations().forEach(System.out::println);
}
```

Since `enableClasspath()` was not called, nothing from the environment is included: the result is
exactly the two entries named, in that order. The varargs and `Iterable` overloads take one
classpath entry per element. A `String`, `File`, `Path`, `URL` or `URI` is kept in that form, and is
what `entry.open(vfs)` later opens; anything else is read by its `toString()`. The `String` overload
instead takes a whole path and splits it on the platform's path separator. Related switches:
`enableClassLoaders(...)` to name the classloaders to read the classpath from,
`ignoreParentClassLoaders()` to stop at the given one, and `enableModuleLayers(...)` to name JPMS
layers.

### Teach it about a classloader it does not know

Check first whether you need to: a `URLClassLoader` subclass is read automatically, and only needs a
handler of its own if it searches its classpath in a different order, as Spring Boot's restart
classloader does. Writing one is not free either -- a handler that names a subclass of
`URLClassLoader` *replaces* the built-in `URLClassLoader` handler for that subclass rather than
running alongside it, so it has to add the classloader's own URLs itself.

Otherwise, implement `ClassLoaderHandler` and register it. This is the extension point that lets
ClassGraph support a container it has never seen:

```java
public class MyClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public boolean canHandle(Class<?> classLoaderClass, ClassGraphLog log) {
        return classIsOrExtendsOrImplements(classLoaderClass, "com.xyz.MyClassLoader");
    }

    @Override
    public void findClassLoaderOrder(ClassLoader classLoader, ClassLoaderOrder classLoaderOrder,
            ClassGraphLog log) {
        // Search this classloader before its parent (i.e. this one is parent-last)
        classLoaderOrder.add(classLoader, log);
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
    }

    @Override
    public void findClasspathOrder(ClassLoader classLoader, ClasspathOrder classpathOrder,
            ClassGraphLog log) {
        for (URL url : ((MyClassLoader) classLoader).getRepositoryURLs()) {
            classpathOrder.addClasspathEntry(url, classLoader, log);
        }
    }
}
```

The `log` handed to each method is null unless verbose logging is switched on, which it is not by
default. Pass it straight through to the methods that take one, as above -- every one of them
accepts null -- and null-check it before calling it yourself.

```java
try (Classpath classpath = new ClasspathFinder().enableClasspath()
        .registerClassLoaderHandler(new MyClassLoaderHandler())
        .find()) {
    classpath.getLocations().forEach(System.out::println);
}
```

`addClasspathEntry` accepts a `String`, `File`, `Path`, `URL` or `URI`, and returns false if the
entry was rejected -- because it is empty, because the filesystem says it is not there or that it
cannot be read, because a classpath element filter rejected it, or because it was already added.
There is no need to check any of that before calling: hand it everything the classloader offers, in
the order the classloader would search it. A handler must be stateless,
since one instance handles every classloader in every scan, and scans can run concurrently; state
belonging to a single scan goes in the `ClasspathOrder` that is passed in. The same handler can be
registered with the scanner via `new ClassGraph().registerClassLoaderHandler(...)`.

When more than one handler can handle the same classloader, only the handlers that name the most
specific classloader class are used, so a handler written for a subclass never has a handler for one
of its superclasses running alongside it and placing the same classloaders in a different order.
A handler you registered is never dropped this way, even when a built-in handler names a more
specific class, since you registered it in order to have it run. The handlers that are kept run in
turn, the ones you registered first, and a classloader or classpath entry that has already been
placed keeps the position the first handler to place it gave it. Nothing depends on the order the
built-in handlers are registered in.

Two more methods say what to look for *within* each classpath element the handler contributes:
`getPackageRootPrefixes()` names the directories the classloader may root the package hierarchy at,
and `getLibDirPrefixes()` names the directories it loads jarfiles from without listing them as
classpath elements. Both default to none, since a classloader normally loads classes only from the
classpath elements it was given -- `URLClassLoader` has no automatic package roots or lib dirs at
all. Override one only if your classloader's own code goes looking in a directory of a fixed name:

```java
@Override
public List<String> getLibDirPrefixes() {
    return List.of("my-container-lib/");
}
```

Declare a prefix only if the classloader really does look there.
`BOOT-INF` and `WEB-INF` are unambiguous, because a hyphen is not legal in a Java identifier, so a
directory with one of those names cannot be a package; an ordinary name like `classes/` or `lib/`
can be, and declaring one wrongly either hides a real package or puts jarfiles that are only
resources on the classpath.

If a handler is useful to more than your own project, please open a pull request so it can ship with
ClassGraph, registered alongside the built-in handlers in `ClassLoaderHandlerRegistry`.

### Work out why an entry is or is not on the classpath

```java
try (Classpath classpath = new ClasspathFinder().verbose().enableClasspath().find()) {
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

Both of those are workarounds applied at the wrong end. The real fix is to ask the maintainers of
the classloader to expose its full classpath through a public method or field, and it is worth
asking: the JDK is progressively restricting the use of JNI, and the warning printed when a native
library is loaded without `--enable-native-access` is scheduled to become a hard error, at which
point Narcissus will not load unless the JVM is launched with that flag. Once that happens, neither
workaround works out of the box, and only a public accessor on the classloader does.

## License

MIT. See [LICENSE-ClassGraph.txt](../LICENSE-ClassGraph.txt).
