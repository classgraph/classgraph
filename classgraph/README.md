# classgraph

The scanner and the class graph API: this is the main ClassGraph library, and the one to depend on
unless you know you want something narrower.

```xml
<dependency>
    <groupId>io.github.classgraph</groupId>
    <artifactId>classgraph</artifactId>
    <version>X.Y.Z</version>
</dependency>
```

Module name: `io.github.classgraph`. Requires JDK 17 or newer. Depends on
[`classgraph-classpath`](../classgraph-classpath) and, through it,
[`classgraph-vfs`](../classgraph-vfs) and [`classgraph-base`](../classgraph-base), all pulled in
transitively.

The [repository README](../README.md) is the introduction; the
[wiki](https://github.com/classgraph/classgraph/wiki) is the full documentation, in particular the
[ClassGraph API](https://github.com/classgraph/classgraph/wiki/ClassGraph-API) (building a scan),
[ScanResult API](https://github.com/classgraph/classgraph/wiki/ScanResult-API) (querying one),
[ClassInfo API](https://github.com/classgraph/classgraph/wiki/ClassInfo-API), and
[Recipes](https://github.com/classgraph/classgraph/wiki/Recipes).

## What it does

ClassGraph inverts the Java reflection API. Reflection can tell you the superclass of a class you
already have; ClassGraph tells you every class that extends a given class, implements a given
interface, or carries a given annotation -- anywhere on the classpath or module path, **without
loading or initializing any of the scanned classes**. It reads classfiles directly, so a class that
would fail to load (a missing dependency, a static initializer with side effects, a different JDK
target) is still fully described.

It also indexes non-class resources, so you can find every file matching a pattern across every
classpath element and module, rather than asking one classloader for one known path.

Scanning runs in parallel across all available cores, and jarfiles are read in place, without
extracting anything to disk.

Nothing a scan produces holds a classloader: `ScanResult`, `ClassInfo` and `Resource` describe what
was found, and loading a class is left to you, with a classloader you hold.

## Say what to scan

Nothing is scanned unless it is enabled, so a scan with no `enable` method called finds nothing.
The methods that say *where* to scan come in pairs:

| No arguments: scan what is in the environment | Varargs: scan exactly what is named |
| --- | --- |
| `enableClasspath()` -- every classpath element of every classloader that can be found -- the thread context classloader, the system classloader, the classloader of the class in any frame of the call stack, and the ancestors of all of those, including `java.class.path` | `enableClassLoaders(ClassLoader...)`, `enableClasspathEntries(Object...)` |
| `enableSystemModules()`, `enableNonSystemModules()` -- the modules of the module layers that are visible to the caller | `enableModuleLayers(ModuleLayer...)` -- the modules of exactly the layers named |

Call the no-argument method to scan what the running application can see. Call only the varargs
method to scan *just* what you name, and nothing from the environment. Calling both scans the
environment as well as what you named. Each call adds to the end of the list, so classpath sources
are scanned in the order the calls were made, and modules are always scanned first, since that is
the order in which the JVM resolves a class.

`ignoreParentClassLoaders()` and `ignoreParentModuleLayers()` narrow what is reached from an enabled
source; `disableJarScanning()` and `disableDirScanning()` narrow what kind of classpath element is
read. After a scan, `ScanResult.getClasspathURIs()` and `ScanResult.getModuleReferences()` report
exactly what was scanned.

## Recipes

Every scan follows the same shape: configure a `ClassGraph`, call `scan()`, query the `ScanResult`,
close it. A `ScanResult` holds the file handles and memory mappings taken during the scan, so it is
`AutoCloseable` and belongs in a try-with-resources block.

### Find every subclass of a class

```java
try (ScanResult scanResult = new ClassGraph().enableNonSystemModules().enableClasspath()
        .enableClassInfo().acceptPackages("com.xyz").scan()) {
    for (ClassInfo subclass : scanResult.getAllSubclasses(Widget.class)) {
        System.out.println(subclass.getName());
    }
}
```

`acceptPackages` restricts the scan to the packages you care about, and is the single biggest thing
you can do for scan time -- without it, everything on the classpath is scanned.
`getDirectSubclasses` returns only the immediate subclasses; `getAllSubclasses` returns the whole
subtree. Both have a `String` overload, for when the superclass itself should not be loaded.

### Find every class implementing an interface, and instantiate them

```java
try (URLClassLoader classLoader = new URLClassLoader(urls);
        ScanResult scanResult = new ClassGraph().enableClassLoaders(classLoader)
                .enableClassInfo().acceptPackages("com.xyz").scan()) {
    for (ClassInfo classInfo : scanResult.getAllClassesImplementing(Plugin.class)) {
        Class<?> cls = Class.forName(classInfo.getName(), /* initialize = */ false, classLoader);
        Plugin plugin = (Plugin) cls.getDeclaredConstructor().newInstance();
        plugin.start();
    }
}
```

Naming a classloader, without also calling `enableClasspath()`, confines the scan to that
classloader and its parents -- nothing from the surrounding application is scanned. Add
`ignoreParentClassLoaders()` to confine it to that classloader alone.

`Class.forName` is the point at which a class is actually loaded -- everything before it is
classfile parsing only. Hold the classloader you scanned with, and load through it.

### Find classes by annotation, and read the annotation's parameters

```java
try (ScanResult scanResult = new ClassGraph().enableNonSystemModules().enableClasspath()
        .enableClassInfo().enableAnnotationInfo().acceptPackages("com.xyz").scan()) {
    for (ClassInfo routeClass : scanResult.getClassesWithAnnotation("com.xyz.Route")) {
        AnnotationInfo route = routeClass.getAllAnnotationInfo("com.xyz.Route");
        System.out.println(routeClass.getName() + " -> " + route.getParameterValues().getValue("value"));
    }
}
```

Annotation parameter values are read out of the classfile, so neither the annotation class nor the
annotated class has to be loadable. `getParameterValues()` includes values that fall back to the
annotation's declared defaults; `getDeclaredParameterValues()` returns only what was written.
`getDirectAnnotationInfo(...)` ignores meta-annotations, where `getAllAnnotationInfo(...)` follows
them.

### Find annotated methods

```java
try (ScanResult scanResult = new ClassGraph().enableNonSystemModules().enableClasspath()
        .enableClassInfo().enableMethodInfo().enableAnnotationInfo()
        .acceptPackages("com.xyz").scan()) {
    for (ClassInfo classInfo : scanResult.getClassesWithMethodAnnotation("com.xyz.Handler")) {
        for (MethodInfo method : classInfo.getMethodInfoWithAnnotation("com.xyz.Handler")) {
            System.out.println(classInfo.getName() + "#" + method.getName() + " returns "
                    + method.getTypeSignatureOrTypeDescriptor().getResultType());
        }
    }
}
```

`enableMethodInfo()` and `enableFieldInfo()` are off by default because they cost scan time and
memory. No configuration method turns on another one, so each kind of information you want has to be
asked for by name, and an option that only modifies another option -- `ignoreMethodVisibility()`, say
-- is refused by `scan()` unless the option it modifies was enabled too.

### Find resources, not classes

```java
try (ScanResult scanResult = new ClassGraph().enableNonSystemModules().enableClasspath()
        .acceptPaths("templates").scan()) {
    for (Resource resource : scanResult.getResourcesWithExtension("html")) {
        System.out.println(resource.getPath() + " in " + resource.getClasspathElementURI());
        System.out.println(resource.loadAsString());
    }
}
```

No `enableClassInfo()` here: resource scanning needs no classfile parsing at all, so leaving it off
makes the scan much faster. Sibling methods are `getAllResources()`, `getResourcesWithPath(path)`,
and `getResourcesMatchingWildcard(pattern)`. A `Resource` can be read with `loadAsString()`,
`load()` (byte array), `open()` (stream), or `read()` (a `ByteBuffer` wrapped in a
`CloseableByteBuffer` -- a memory mapping with no copy, where the resource can be mapped). It also
has `getVfsEntry()`, which hands back the [`classgraph-vfs`](../classgraph-vfs) entry the resource is
read from, for anything the `Resource` API itself does not offer.

If all you want is to list the files in a jarfile, with no scan at all, use
[`classgraph-vfs`](../classgraph-vfs) directly.

### Look at the module graph

```java
try (ScanResult scanResult = new ClassGraph()
        .enableClassInfo().enableSystemJars().enableSystemModules().enableNonSystemModules()
        .scan()) {
    for (ModuleInfo moduleInfo : scanResult.getModuleInfo()) {
        System.out.println(moduleInfo.getName() + ": " + moduleInfo.getClassInfo().size() + " classes");
    }
}
```

`enableSystemModules()` and `enableNonSystemModules()` enable the two kinds of module separately;
`enableSystemJars()` additionally allows the JDK's own jarfiles to be scanned if any are on the
classpath. Neither of the latter two is enabled by `enableNonSystemModules()`, since almost no scan
wants the JDK's own classes.

### Read an enum's constants without loading it

```java
try (ScanResult scanResult = new ClassGraph().enableNonSystemModules().enableClasspath()
        .enableClassInfo().enableFieldInfo().acceptPackages("com.xyz").scan()) {
    ClassInfo enumInfo = scanResult.getClassInfo("com.xyz.Color");
    if (enumInfo != null && enumInfo.isEnum()) {
        enumInfo.getEnumConstants().forEach(constant -> System.out.println(constant.getName()));
    }
}
```

This is the general pattern for anything you might have reached for `Class.forName` to get:
annotation parameters, enum constants, method and field signatures and modifiers, and class
references inside annotations are all in the scan result already.

### Scan once at build time

There is no `ScanResult` serialization -- work out what you need during the build, save just that,
and read it back at runtime. This is also how ClassGraph is used with Android and GraalVM
`native-image`, where there is no usable runtime classpath to scan. See
[Build-Time Scanning](https://github.com/classgraph/classgraph/wiki/Build-Time-Scanning).

### Work out why a class is not found

```java
try (ScanResult scanResult = new ClassGraph().verbose().enableNonSystemModules().enableClasspath()
        .enableClassInfo().scan()) {
    // ...
}
```

The verbose log names every classpath element found, every classloader consulted, and every
classfile accepted or rejected, and is the first thing to attach to a bug report. It is written to
the `io.github.classgraph.ClassGraph` logger at `INFO` level.

## License

MIT. See [LICENSE-ClassGraph.txt](../LICENSE-ClassGraph.txt).
