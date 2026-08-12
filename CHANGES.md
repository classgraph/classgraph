# Changes in ClassGraph 5.x

This file lists every user-visible change between ClassGraph 4.x and ClassGraph 5.x:
API changes, behavior changes, and changes to the supported JDK versions. It is the
basis of the v4 → v5 porting guide.

ClassGraph 5.x does not maintain binary or source compatibility with ClassGraph 4.x.

## Supported JDK versions

* **ClassGraph 5.x requires JDK 17 or newer**, both to build and to run.
  ClassGraph 4.x supports JDK 7 and newer.
* All of the reflective workarounds that existed to keep the codebase compiling and
  running on JDK 7 and JDK 8 have been removed, since the module system
  (`java.lang.module`, `ModuleLayer`) is present in every supported JDK. This is the
  reason for most of the API changes listed below.

## Artifacts

ClassGraph 4.x shipped as a single jar. ClassGraph 5.x is split into several artifacts, so
that a project depends only on the part of ClassGraph it uses:

| Artifact | Module | Contents |
| --- | --- | --- |
| `io.github.classgraph:classgraph-base` | `io.github.classgraph.base` | Shared internals; no API of its own |
| `io.github.classgraph:classgraph-vfs` | `io.github.classgraph.vfs` | Reading jarfiles, including nested jarfiles |
| `io.github.classgraph:classgraph-classpath` | `io.github.classgraph.classpath` | Finding the classpath and the module path |
| `io.github.classgraph:classgraph` | `io.github.classgraph` | Scanning and the class graph API |
| `io.github.classgraph:classgraph-viz` | `io.github.classgraph.viz` | GraphViz .dot file generation |

Each artifact depends on the one above it, so a project needs only the dependency for the
widest part of ClassGraph it uses: `classgraph-viz` brings in `classgraph`, which brings in
`classgraph-classpath`, which brings in `classgraph-vfs`, which brings in `classgraph-base`.

`classgraph-base` holds the utilities the other four share — path and URL handling, logging,
reflection, and accept/reject matching. It has no public API of its own, and exports its
packages only to the other ClassGraph modules, so it is never named directly by a project
that uses ClassGraph. It is listed here only because it appears in dependency trees.

### `classgraph-classpath` can be used on its own

Working out where a JVM loads its classes from is a self-contained job that does not need a
scan, so it is now a library of its own, with a public API of its own. It reports the
classpath elements in the order the classloaders would search them, the modules split into
system and non-system, and the module path switches the JVM was launched with:

```java
try (Classpath classpath = new ClasspathFinder().find()) {
    for (ClasspathEntry entry : classpath.getEntries()) {
        System.out.println(entry.location());
    }
}
```

`ClasspathFinder` has the same classloader, module layer and classpath override methods as
`ClassGraph` (`overrideClasspath`, `overrideClassLoaders`, `addClassLoader`,
`ignoreParentClassLoaders`, `overrideModuleLayers`, `addModuleLayer`, `ignoreModules`,
`verbose`), and finds classpath elements from the same custom container classloaders.

The classpath it returns is the full, expanded classpath. A jarfile can add more elements
to the classpath than the classloader listed: the jarfiles in its automatic lib dirs
(`lib/`, `BOOT-INF/lib/`, `WEB-INF/lib/` and so on), and the entries of its manifest's
`Class-Path` and `Bundle-ClassPath` attributes. Each of those can add more in turn, so the
finder follows them recursively, and reports each element directly after the element that
declared it, which is the order a classloader would search them in. An element that is
reached more than once is listed only at the first position it is reached at, since that is
the position that decides which copy of a duplicated class wins.

Reading those manifests means opening the jarfiles on the classpath, so `Classpath` is now
`AutoCloseable`, and closing it closes them again. The entries can still be read after it
has been closed.

The finder reports where classes and resources *would be* loaded from, so an element that
is named but is not there is still reported, and a nested jar is reported in the
`outer.jar!/inner.jar` form rather than being extracted. `ClassGraph#getClasspathFiles()`
and friends still drop the elements that are not there and strip package roots, so they are
what to use when the classpath elements need to be real files.

`ModulePathInfo` has moved from `io.github.classgraph` to `io.github.classgraph.classpath`
as part of this, and is reachable both from `ScanResult#getModulePathInfo()` as before, and
from `Classpath#getModulePathInfo()`.

### `classgraph-vfs` can be used on its own

ClassGraph's zipfile reader is faster than `java.util.zip.ZipFile`, and it can read a
jarfile that is nested inside another jarfile, as produced by Spring Boot and other
executable-jar formats, without extracting it to a temporary directory first. That reader is
now a library of its own, so a project can read jarfiles with it without scanning anything:

```java
try (ArchiveReader reader = new ArchiveReader()) {
    for (ArchiveEntry entry : reader.open("outer.jar!/lib/inner.jar").getEntries()) {
        System.out.println(entry.getName() + " (" + entry.getUncompressedSize() + " bytes)");
    }
}
```

`io.github.classgraph.vfs.ArchiveReader` opens an `Archive`, which lists its `ArchiveEntry`
instances or looks one up by name. An entry's content is read with `ArchiveEntry#open()` or
`ArchiveEntry#readAllBytes()`. Nested jarfiles are named by separating each jarfile from the
one that encloses it with `"!/"`, to any depth; a trailing `"!/"` section that names a
directory rather than a jarfile is used as the package root, e.g.
`"spring-boot-app.jar!/BOOT-INF/classes"`, so that entry names are reported relative to it.

Directory entries, encrypted entries and entries stored with an unsupported compression
method are not reported, and for a multi-release jarfile only the newest version of each
entry that the running JVM can use is reported, unless
`ArchiveReader#enableMultiReleaseVersions()` is called. The reader owns the file handles,
memory mappings and temporary files behind everything it opened, so it must be closed, and
it must stay open for as long as its entries are being read.

## API changes

### All deprecated methods have been removed

Every method that was deprecated in 4.x is gone. Each had a direct replacement, and the
replacement has the same behavior, so porting is a rename.

| Removed in 5.x | Use instead |
| --- | --- |
| `ClassGraph#whitelistPackages` | `ClassGraph#acceptPackages` |
| `ClassGraph#whitelistPackagesNonRecursive` | `ClassGraph#acceptPackagesNonRecursive` |
| `ClassGraph#whitelistPaths` | `ClassGraph#acceptPaths` |
| `ClassGraph#whitelistPathsNonRecursive` | `ClassGraph#acceptPathsNonRecursive` |
| `ClassGraph#whitelistClasses` | `ClassGraph#acceptClasses` |
| `ClassGraph#whitelistJars` | `ClassGraph#acceptJars` |
| `ClassGraph#whitelistModules` | `ClassGraph#acceptModules` |
| `ClassGraph#whitelistClasspathElementsContainingResourcePath` | `ClassGraph#acceptClasspathElementsContainingResourcePath` |
| `ClassGraph#blacklistPackages` | `ClassGraph#rejectPackages` |
| `ClassGraph#blacklistPaths` | `ClassGraph#rejectPaths` |
| `ClassGraph#blacklistClasses` | `ClassGraph#rejectClasses` |
| `ClassGraph#blacklistJars` | `ClassGraph#rejectJars` |
| `ClassGraph#blacklistModules` | `ClassGraph#rejectModules` |
| `ClassGraph#blacklistClasspathElementsContainingResourcePath` | `ClassGraph#rejectClasspathElementsContainingResourcePath` |
| `ScanResult#getResourcesWithPathIgnoringWhitelist` | `ScanResult#getResourcesWithPathIgnoringAccept` |
| `FieldInfo#getModifierStr` | `FieldInfo#getModifiersString` |
| `ClassInfoList#generateGraphVizDotFileFromClassDependencies` | `GraphVizDotFile#generateFromInterClassDependencies` (see below) |
| `ResourceList#forEachByteArray(ByteArrayConsumer, boolean)` | `ResourceList#forEachByteArray` or `#forEachByteArrayIgnoringIOException` |
| `ResourceList#forEachInputStream(InputStreamConsumer, boolean)` | `ResourceList#forEachInputStream` or `#forEachInputStreamIgnoringIOException` |
| `ResourceList#forEachByteBuffer(ByteBufferConsumer, boolean)` | `ResourceList#forEachByteBuffer` or `#forEachByteBufferIgnoringIOException` |

See below for the rest of the `ResourceList#forEach*` change — those three names are still
there, but they no longer mean the same thing.

### `ResourceList#forEach*` methods now throw `IOException`

Reading a resource can fail, and in 4.x each of the three `forEach*` families had grown
three names and two consumer interfaces to express that:

| ClassGraph 4.x | ClassGraph 5.x |
| --- | --- |
| `forEachByteArray(ByteArrayConsumer)` (deprecated) | `forEachByteArray(ByteArrayConsumer)` |
| `forEachByteArrayThrowingIOException(ByteArrayConsumerThrowsIOException)` | `forEachByteArray(ByteArrayConsumer)` |
| `forEachByteArrayIgnoringIOException(ByteArrayConsumer)` | `forEachByteArrayIgnoringIOException(ByteArrayConsumer)` |

and likewise for `forEachInputStream` and `forEachByteBuffer`. The
`ByteArrayConsumerThrowsIOException`, `InputStreamConsumerThrowsIOException` and
`ByteBufferConsumerThrowsIOException` interfaces have been deleted; `ByteArrayConsumer`,
`InputStreamConsumer` and `ByteBufferConsumer` now declare `throws IOException` on
`accept`, so a single interface serves both methods in each pair. Each family is now just
two methods: `forEachX`, which propagates any `IOException`, and
`forEachXIgnoringIOException`, which skips the resource that failed and carries on.

**This is a silent behavioral change for one case.** 4.x code that calls
`forEachByteArray(consumer)` from a method that already declares `throws IOException` still
compiles, but where it used to see an `IllegalArgumentException` wrapping the cause, it now
sees the `IOException` itself. Code that catches `IllegalArgumentException` around a
`forEach*` call needs to catch `IOException` instead. Everywhere else the compiler will
point at the call, because `IOException` is checked.

### The GraphViz .dot file generators have moved to `classgraph-viz`

GraphViz .dot file generation is no longer part of the core artifact, and is no longer
reached through `ClassInfoList`. It now lives in `io.github.classgraph:classgraph-viz`, as
static methods on `io.github.classgraph.viz.GraphVizDotFile`. Each method takes the
`ScanResult` that the classes came from, then the classes to graph:

```java
GraphVizDotFile.generate(scanResult, scanResult.getAllClasses());
```

`ClassInfoList` had eight `generateGraphVizDotFile*` overloads, and the widest of them took
two floats followed by six booleans. The options are now carried by
`GraphVizDotFileOptions`, whose no-argument constructor holds the defaults, and whose
methods each switch one option away from its default:

```java
GraphVizDotFile.generate(scanResult, scanResult.getAllClasses(),
        new GraphVizDotFileOptions().layoutSize(12, 8).hideFields().hideMethods());
```

| ClassGraph 4.x (on `ClassInfoList`) | ClassGraph 5.x (on `GraphVizDotFile`) |
| --- | --- |
| `generateGraphVizDotFile()` | `generate(scanResult, classes)` |
| `generateGraphVizDotFile(float, float)` | `generate(scanResult, classes, options)` |
| `generateGraphVizDotFile(float, float, boolean × 5)` | `generate(scanResult, classes, options)` |
| `generateGraphVizDotFile(float, float, boolean × 6)` | `generate(scanResult, classes, options)` |
| `generateGraphVizDotFile(File)` | `write(scanResult, classes, path)` |
| `generateGraphVizDotFileFromInterClassDependencies()` | `generateFromInterClassDependencies(scanResult, classes)` |
| `generateGraphVizDotFileFromInterClassDependencies(float, float)` | `generateFromInterClassDependencies(scanResult, classes, options)` |
| `generateGraphVizDotFileFromInterClassDependencies(float, float, boolean)` | `generateFromInterClassDependencies(scanResult, classes, options)` |

There is also `writeFromInterClassDependencies(scanResult, classes, path)`, which 4.x did
not have — the dependency graph could only be generated as a string.

The options and their defaults are `layoutSize(10.5f, 8.0f)`, `hideFields()`,
`hideFieldTypeDependencyEdges()`, `hideMethods()`, `hideMethodTypeDependencyEdges()`,
`hideAnnotations()` and `hideAnnotationDependencyEdges()` (all shown by default, subject to
the corresponding `ClassGraph#enable*Info()` call having been made before scanning),
`useFullyQualifiedNames()` (simple names by default), and `includeExternalClasses()` /
`excludeExternalClasses()` (by default the inter-class dependency graph follows the scan's
own `ClassGraph#enableExternalClasses()` setting). Every option is read by `generate`; the
inter-class dependency graph reads only the layout size and the external-class setting.

The three pairs of options are now symmetrical: `hideFields()`, `hideMethods()` and
`hideAnnotations()` each hide something inside the class boxes, and
`hideFieldTypeDependencyEdges()`, `hideMethodTypeDependencyEdges()` and
`hideAnnotationDependencyEdges()` each hide the corresponding edges between the boxes. In
4.x the single `showAnnotations` flag hid only the annotation edges, and there was no way
to leave the annotations out of the class boxes; the annotations on a class, and on its
fields, its methods and their parameters, are now hidden by `hideAnnotations()`, so code
that passed `showAnnotations = false` to hide the edges needs
`hideAnnotationDependencyEdges()` instead.

Writing the graph to a file is now `write` rather than a `File`-typed overload of
`generate`, so the name says which one returns the .dot file contents and which one saves
them. `write` takes a `java.nio.file.Path` rather than a `java.io.File`, returns that
`Path` rather than the list it was called on, and writes UTF-8 — 4.x wrote the platform
default charset, which mangled non-ASCII class and member names on any platform whose
default was not UTF-8.

Three smaller behavior changes came with the move:

* Passing an empty list of classes now produces an empty graph. In 4.x it threw
  `IllegalStateException("List is empty")`, because the generator read the scan's settings
  out of the first element of the list; it now takes the `ScanResult` directly. Scanning
  without `ClassGraph#enableClassInfo()`, or calling
  `generateFromInterClassDependencies` without `ClassGraph#enableInterClassDependencies()`,
  still throws `IllegalStateException`.
* Annotation edges no longer include the shortcut edges that 4.x drew from a class to the
  meta-annotations of its annotations, and from a subclass to the `@Inherited` annotations
  of its superclasses. Those annotations are still in the graph, and are still reachable
  from the class along a path of edges, so the shortcut edges only added clutter.
* Annotations within a class box are now listed in sorted order, rather than in the order
  they appeared in the classfile.

Two methods were added to the core API to support this, and are useful in their own right:
`ScanResult#isClassInfoEnabled()` and its siblings for field, method and annotation info,
inter-class dependencies, external classes, and ignored field and method visibility; and
`ClassMemberInfo#getClassDependencies()` (inherited by `FieldInfo` and `MethodInfo`), which
returns the classes referred to by a single field or method.

### The JSON serializer and deserializer have been removed

ClassGraph 4.x could serialize a `ScanResult` to JSON and read it back:

| Removed in 5.x |
| --- |
| `ScanResult#toJSON()` |
| `ScanResult#toJSON(int indentWidth)` |
| `ScanResult#fromJSON(String json)` |
| `ScanResult#isObtainedFromDeserialization()` |

The internal package that implemented it, `nonapi.io.github.classgraph.json`, has been
deleted too. It was a hand-written general-purpose object-to-JSON mapper, complete with
its own parser, type resolution and object id/reference scheme — a second library living
inside ClassGraph, an order of magnitude more code than the `ScanResult` serialization it
supported, and unrelated to scanning the classpath. Its classes (`JSONSerializer`,
`JSONDeserializer`, `Id` and the rest) were not part of the public API, but were reachable
by anything that ignored the `nonapi` package naming.

A serialized `ScanResult` was never a stable format: it carried an internal format version
that changed whenever the fields of the scan result classes changed, and reading a
`ScanResult` written by a different version of ClassGraph failed.

JSON serialization of a `ScanResult`, or of the objects linked from it (`ClassInfo`,
`MethodInfo`, `FieldInfo`, and the rest), is not supported in 5.x, whether by ClassGraph or
by pointing a JSON library at them. If you need scan results as JSON, read the information
you need from the `ScanResult`, then generate your own JSON format from that.

Removing it also removes the no-argument constructor that every scan result class
(`ClassInfo`, `FieldInfo`, `MethodInfo`, `AnnotationInfo`, `PackageInfo`, `ModuleInfo` and
the rest) carried purely for the deserializer to call, which left instances
half-initialized until the deserializer filled in their fields. Those classes are only
ever constructed by a scan, so the constructors were not usable from outside ClassGraph
anyway.

### ClassGraph no longer loads classes

ClassGraph reads classfiles; it does not load them any more. Everything that turned a
scan result into a live `Class`, `Method`, `Field`, `Constructor`, enum constant or
annotation instance has been removed, along with the classloader that did the loading:

| Removed in 5.x |
| --- |
| `ScanResult#loadClass(String, boolean)` |
| `ScanResult#loadClass(String, Class<T>, boolean)` |
| `ClassInfo#loadClass()` and `#loadClass(boolean)` |
| `ClassInfo#loadClass(Class<T>)` and `#loadClass(Class<T>, boolean)` |
| `ClassInfo#getEnumConstantObjects()` |
| `ClassInfoList#loadClasses()` and `#loadClasses(boolean)` |
| `ClassInfoList#loadClasses(Class<T>)` and `#loadClasses(Class<T>, boolean)` |
| `ArrayClassInfo#loadClass()`, `#loadClass(boolean)`, `#loadElementClass()`, `#loadElementClass(boolean)` |
| `ArrayTypeSignature#loadClass()`, `#loadClass(boolean)`, `#loadElementClass()`, `#loadElementClass(boolean)` |
| `ClassRefTypeSignature#loadClass()` and `#loadClass(boolean)` |
| `AnnotationClassRef#loadClass()` and `#loadClass(boolean)` |
| `AnnotationEnumValue#loadClassAndReturnEnumValue()` and `#loadClassAndReturnEnumValue(boolean)` |
| `AnnotationInfo#loadClassAndInstantiate()` |
| `FieldInfo#loadClassAndGetField()` |
| `MethodInfo#loadClassAndGetMethod()` and `#loadClassAndGetConstructor()` |
| `ClassGraph#initializeLoadedClasses()` |
| The public class `ClassGraphClassLoader` |

Load classes yourself instead, with a classloader you hold:

```java
ClassInfo classInfo = scanResult.getClassInfo("com.xyz.Widget");
Class<?> cls = Class.forName(classInfo.getName(), /* initialize = */ false, myClassLoader);
```

Reflection then gives you methods, fields, constructors, enum constants and annotation
instances directly, from the JDK, with the JDK's semantics.

ClassGraph no longer hands you the classloader a class was found under —
`ClassInfo#getClassLoader()` has been removed, and a scan does not keep a classloader
reference at all. To find out which classloader a class came from, for logging or for
choosing which loader to load it with, `ClassInfo#getClassLoaderString()` reports that
classloader's `toString()`; see "Classloaders are no longer retained after a scan", below.

Why this went away, given that it was one of the older parts of the API:

* **A scan and a classload can disagree.** ClassGraph found a classfile at a particular
  position in the classpath order; the classloader that then loads it applies its own
  delegation rules, so on a parent-last or otherwise non-standard classloader the class
  you got back could be a different class with the same name from a different classpath
  element. The 4.x `ClassGraphClassLoader` existed to narrow that gap, and could not close
  it.
* **Loading has side effects that scanning does not.** It runs static initializers (unless
  suppressed), pins classes and their classloaders in memory for the lifetime of the loader,
  and can throw `NoClassDefFoundError` or `ExceptionInInitializerError` from deep inside
  unrelated code. Half the API surface here — every `ignoreExceptions` parameter, every
  nullable return — was there to paper over that.
* **The annotation proxies were not the JDK's annotations.** `loadClassAndInstantiate()`
  built a dynamic proxy that reimplemented `equals()`, `hashCode()` and `toString()` for
  annotations; it had to keep working after the `ScanResult` was closed, and did not always
  agree with the annotation instance the JDK hands you for the same annotation.

Where a scan result was previously used only to reach reflection, use the scanned
information directly: `AnnotationInfo#getParameterValues()` for annotation parameters
(including defaults), `AnnotationEnumValue#getClassName()`/`#getValueName()` for enum
constants, `AnnotationClassRef#getName()` for class references, `ClassInfo#getEnumConstants()`
for enum constant names, and `MethodInfo`/`FieldInfo` for signatures and modifiers.

### Classloaders are no longer retained after a scan

Now that loading classes is the caller's job, ClassGraph uses a classloader for exactly one
thing: asking it which files, directories and URLs are on its classpath. Those are then
read directly, not through the classloader, so once they have been extracted the
classloader is dropped, and only its `toString()` is kept, as an identifier.

This matters because a classloader can hold a large amount of memory — every class it has
loaded, and everything those classes reference — so a leaked classloader reference is one
of the more expensive kinds of leak, and one of the harder ones to track down. In 4.x,
several references outlived the scan: every `ClassInfo` object held the classloader its
class was found under, every classpath element held the classloader it came from, and the
`ScanSpec` inside the `ScanResult` held the classloader and module-layer lists the caller
had supplied.

In 5.x, nothing a scan produces — no `ScanResult`, `ClassInfo`, `ClasspathElement` or
`Resource` — holds a classloader, weakly or otherwise, so nothing ClassGraph returns to you
can keep a classloader alive. The classloaders and module layers you supply with
`ClassGraph#addClassLoader()`, `#overrideClassLoaders()`, `#addModuleLayer()` and
`#overrideModuleLayers()` are held by the `ClassGraph` instance itself, so that the same
instance can be scanned with more than once; they are not reachable from the `ScanResult`.

`ClassInfo#getClassLoaderString()` replaces 4.x's `ClassInfo#getClassLoader()`. It returns
the `toString()` of the classloader the classfile was found under — the same string the
verbose scanning log shows for the classpath element — or null if the class was not itself
scanned (e.g. a superclass outside the accepted packages), or was found in a module loaded
by the bootstrap classloader. It is an identifier, useful for logging and for working out
where a class came from; to load the class, use a classloader you hold yourself:

```java
try (URLClassLoader classLoader = new URLClassLoader(urls);
        ScanResult scanResult = new ClassGraph().overrideClassLoaders(classLoader)
                .enableAllInfo().scan()) {
    ClassInfo classInfo = scanResult.getClassInfo("com.xyz.Widget");
    Class<?> cls = Class.forName(classInfo.getName(), false, classLoader);
}
```

### Module APIs are now strongly typed

In 4.x, every method that took or returned a module-system object used `Object` as the
type, so that the code would still compile on JDK 7 and JDK 8, and the objects were
manipulated by reflection. These now use the real types from `java.lang.module`:

| ClassGraph 4.x | ClassGraph 5.x |
| --- | --- |
| `ClassGraph#addModuleLayer(Object)` | `ClassGraph#addModuleLayer(ModuleLayer)` |
| `ClassGraph#overrideModuleLayers(Object...)` | `ClassGraph#overrideModuleLayers(ModuleLayer...)` |

Callers that already passed a `ModuleLayer` need no source change, but the calls do have to
be recompiled.

### `ModuleRef` has been removed — modules are now `java.lang.module.ModuleReference`

`ModuleRef` was a wrapper around `java.lang.module.ModuleReference`, written when ClassGraph
still had to compile and run on JDK 7 and JDK 8, where that type did not exist. It held the
reference, its layer, its descriptor, its packages and its location, and reached all of them
by reflection. On JDK 17 there is nothing left for it to do: the module system's own type
carries everything ClassGraph exposed. So `ModuleRef` is gone, and every method that
returned one now returns a `ModuleReference`:

| ClassGraph 4.x | ClassGraph 5.x |
| --- | --- |
| `ClassInfo#getModuleRef()` | `ClassInfo#getModuleReference()` |
| `ModuleInfo#getModuleRef()` | `ModuleInfo#getModuleReference()` |
| `Resource#getModuleRef()` | `Resource#getModuleReference()` |
| `ScanResult#getModules()` | `ScanResult#getModuleReferences()` |
| `ClassGraph#getModules()` | `ClassGraph#getModuleReferences()` |

The `ModuleRef` accessors map onto `ModuleReference` as follows:

| `ModuleRef` (4.x) | `ModuleReference` (5.x) |
| --- | --- |
| `getName()` | `descriptor().name()` |
| `getReference()` | the object itself |
| `getDescriptor()` | `descriptor()` |
| `getPackages()` | `descriptor().packages()` |
| `getRawVersion()` | `descriptor().rawVersion().orElse(null)` |
| `getLocation()` | `location().orElse(null)` |
| `getLocationString()` | `location().map(URI::toString).orElse(null)` |
| `open()` | `open()` |

Four members have no direct equivalent:

* `getLayer()` returned the `ModuleLayer` the module was resolved in. ClassGraph does not
  need it, and holding it kept that layer's classloaders alive for the lifetime of the
  `ScanResult` — the one exception to "classloaders are no longer retained after a scan",
  described above, which no longer exists. If you need the layer, you have it already: you
  are the one who supplied it to `addModuleLayer()`, or it is `ModuleLayer.boot()`.
* `getClassLoader()` returned the classloader for the module within that layer. Loading
  classes is now the caller's job, as described above.
* `isSystemModule()` tested the module name against the `java.`, `jdk.`, `javafx.` and
  `oracle.` prefixes. That is a scanning-time classification, not a property of a module,
  and it is now internal to ClassGraph.
* `getLocationFile()` returned the module's location as a `File`, or null for a location
  that is not a file. `location()` gives you the `URI`.

Note also that `ModuleRef#getPackages()` returned the package names sorted, whereas
`descriptor().packages()` returns them in no particular order — sort them yourself if the
order matters.

### `ModuleReaderProxy` has been removed (#860)

`ModuleReaderProxy` existed only to wrap `java.lang.module.ModuleReader`, which did not
exist on JDK 8. The class has been deleted; open a module with `ModuleReference#open()`,
which returns a `java.lang.module.ModuleReader` directly:

```java
// ClassGraph 4.x
try (ModuleReaderProxy moduleReader = moduleRef.open()) {
    List<String> paths = moduleReader.list();
    ByteBuffer content = moduleReader.read(path);
}

// ClassGraph 5.x
try (ModuleReader moduleReader = moduleReference.open()) {
    List<String> paths = moduleReader.list().toList();
    ByteBuffer content = moduleReader.read(path).orElseThrow();
}
```

The differences to be aware of when porting:

* `ModuleReader#list()` returns `Stream<String>`, not `List<String>`.
* `ModuleReader#open(String)`, `#read(String)` and `#find(String)` return `Optional`
  values, and throw `IOException` rather than wrapping it in an unchecked exception.
* `ModuleReader#close()` throws `IOException`, whereas `ModuleReaderProxy#close()`
  swallowed it.

### Narcissus is now used automatically, and the reflection driver can no longer be selected

Since JDK 16 the JDK enforces strong encapsulation, so ClassGraph may be unable to read the
classpath from a classloader that keeps it in a private field. The workaround is the
[Narcissus](https://github.com/toolfactory/narcissus) library, which reads fields and
invokes methods through JNI, where Java's access checks do not apply.

In 4.x you had to add Narcissus to your project *and* select it in code. In 5.x, adding it
to your project is the whole of it: ClassGraph looks for Narcissus reflectively when it
starts up, and uses it as its reflection driver if it is present and its native library
loads. If it is absent, ClassGraph uses standard reflection, silently, as before. If it is
present but its native library will not load, ClassGraph prints a message to `System.err`
and falls back to standard reflection.

One thing to be ready for: Narcissus works by loading a native library, and on JDK 24+ that
draws a warning unless the JVM was launched with `--enable-native-access`
([JEP 472](https://openjdk.org/jeps/472)). With Narcissus on the classpath of an
application launched without it, the JVM prints:

```
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by io.github.toolfactory.narcissus.LibraryLoader in an unnamed module (file:/path/to/narcissus-1.0.11.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
```

Adding `--enable-native-access=ALL-UNNAMED` to the launch command silences it (or name the
module Narcissus is in, if you put it on the module path). This is not cosmetic for long:
the JEP states that restricted methods will be refused outright in a later release, so at
that point the switch becomes required rather than optional. ClassGraph's own build passes
it to Surefire for exactly this reason.

So the API for selecting a driver is gone, with nothing to replace it:

| Removed in 5.x | Use instead |
| --- | --- |
| `ClassGraph.CircumventEncapsulationMethod` (enum) | Nothing — add Narcissus as a dependency |
| `ClassGraph#getCircumventEncapsulationMethod()` | Nothing |
| `ClassGraph#setCircumventEncapsulationMethod(CircumventEncapsulationMethod)` | Nothing |

The driver is now chosen once per JVM rather than once per `ClassGraph` instance, so it no
longer matters when in the lifetime of your program ClassGraph is first used.

Adding Narcissus can therefore change what a scan finds, which is the reason to add it. The
JDK's own classloaders keep their classpath in a private `ucp` field, and standard
reflection cannot read it on JDK 16+, so ClassGraph falls back to the `java.class.path`
system property. Three things that property does not cover:

* **Classpath entries added after startup**, by a Java agent calling
  `Instrumentation#appendToSystemClassLoaderSearch(JarFile)`. Its javadoc says outright that
  this "does not change the value of `java.class.path`", so without Narcissus these entries
  are invisible, and the classes in them are missing from the scan.
* **Entries appended to the boot classpath**, with `-Xbootclasspath/a` or with the
  `Boot-Class-Path` attribute of a Java agent's manifest. The JVM keeps these in a *saved*
  property that is removed before the application can read the system properties, so the
  only record of them is the `ucp` field of the bootstrap classloader — which is itself not
  reachable through `ClassLoader#getParent()`, since that returns null for the platform
  classloader. ClassGraph 5.x splices the bootstrap classloader into the classloader
  delegation order when it has entries to contribute, so these are now scanned (they were
  missed entirely in 4.x). They are scanned whether or not
  `ClassGraph#enableSystemJarsAndModules()` was called, since they are the application's own
  jars rather than part of the JDK.
* **The parents of a classloader you pass to `ClassGraph#overrideClassLoaders`**. Overriding
  the classloaders means the `java.class.path` fallback does not apply, so if a parent is
  one of the JDK's own classloaders, its entries can only be reached through `ucp`.
* **Entries that a search path holds only in its internal bookkeeping.** A `ucp` is a
  `jdk.internal.loader.URLClassPath`, which records its entries in three separate fields, and
  the only one of them that any public API exposes is the one `getURLs()` copies. Entries
  expanded from the `Class-Path` manifest attribute of a jar the JVM has opened go into the
  other two and are never added to the one `getURLs()` returns. This is not limited to the
  JDK's own classloaders: it applies equally to a plain `java.net.URLClassLoader`, whose
  `getURLs()` is just a call through to the same field. ClassGraph 5.x reads all three fields.
  In practice ClassGraph expands the `Class-Path` attribute itself, so those particular
  entries are ones it already had; reading the other two fields is what stops anything that
  reaches a search path by some other route from being missed.

If ClassGraph cannot see classes that you know are on the classpath, and any of these
applies to you, adding Narcissus is likely to be the fix. The same goes for a
strongly-encapsulated third-party classloader that keeps its classpath in a private field.

Reading `ucp` is not a duplicate of reading the module path. Anything loaded from the class
path is a member of its classloader's **unnamed module**, and the unnamed module cannot be
enumerated through the JPMS API: it has no name and no `ModuleDescriptor`
(`Module#getDescriptor()` returns null for it), and it is in no module layer
(`Module#getLayer()` "always returns null when invoked on an unnamed module", since "a module
layer contains named modules"). Listing the contents of a module means going through a
`ModuleReader`, which is opened from a `ModuleReference`, which always carries a
`ModuleDescriptor` — so no `ModuleReader` exists for the unnamed module, and the class path
can only be read from the classloader. This holds even for a jar that *does* contain a
`module-info.class`: on the class path the descriptor is ignored and the jar's classes go into
the unnamed module, rather than becoming a named module. (Placing that same jar on the
**module path** is what makes it a named module, and a plain jar there becomes an automatic
module.)

Going the other way, module path entries never appear in `ucp`, so the two are separate,
non-overlapping sources.

This does not affect whether **system** classes are scanned, which is still controlled only
by `ClassGraph#enableSystemJarsAndModules()`. System classes come from modules, read through
the JPMS API, which is a separate mechanism from the `ucp` field. The platform classloader
has no `ucp` at all on a modern JDK, and the bootstrap classloader has one only when the
boot classpath was appended to, in which case it contains only what was appended, never the
JDK's own classes.

### `acceptLibOrExtJars` and `rejectLibOrExtJars` have been removed

These two methods accepted or rejected jars found in the JRE/JDK `lib/` and `ext/`
directories, and in the directories named by the `java.ext.dirs` system property. The
extension mechanism that put jars in those directories was removed from the JDK in JDK 9
(JEP 220): on every JDK that ClassGraph 5.x supports, `lib/ext/` does not exist, the only
jar under `lib/` is `jrt-fs.jar` (which ClassGraph has always skipped), and the JVM
refuses to start at all if `java.ext.dirs` is set. So the methods could never match
anything, and the code that searched for those jars has been deleted along with them.

| Removed in 5.x | Use instead |
| --- | --- |
| `ClassGraph#acceptLibOrExtJars(String...)` | Nothing — no such jars exist on JDK 9+ |
| `ClassGraph#rejectLibOrExtJars(String...)` | Nothing — no such jars exist on JDK 9+ |

Relatedly, `ClassGraph#enableSystemJarsAndModules()` now only affects system modules and
system packages; it no longer adds any jars to the classpath.

### The internal packages have moved out of the `nonapi` namespace

ClassGraph's internal classes used to live under a top-level `nonapi` package, which sat
outside the project's own `io.github.classgraph` namespace. That was how the library
signalled "this is not API" before it was modular. It now says so with the module system
instead: each module exports its internal packages only to the ClassGraph modules above
it, the OSGi manifest marks them `x-internal:=true`, and they are left out of the Javadoc.

Each module's internals now sit under that module's own package, in a package named
`internal`:

| Was | Is now |
| --- | --- |
| `nonapi.io.github.classgraph.utils` | `io.github.classgraph.base.internal.utils` |
| `nonapi.io.github.classgraph.reflection` | `io.github.classgraph.base.internal.reflection` |
| `nonapi.io.github.classgraph.concurrency` | `io.github.classgraph.base.internal.concurrency` |
| `nonapi.io.github.classgraph.recycler` | `io.github.classgraph.base.internal.recycler` |
| `nonapi.io.github.classgraph.fastzipfilereader` | `io.github.classgraph.vfs.internal.zip` |
| `nonapi.io.github.classgraph.fileslice` | `io.github.classgraph.vfs.internal.slice` |
| `nonapi.io.github.classgraph.fileslice.reader` | `io.github.classgraph.vfs.internal.slice.reader` |
| `nonapi.io.github.classgraph.vfsspec` | `io.github.classgraph.vfs.internal.spec` |
| `nonapi.io.github.classgraph.classpath` | `io.github.classgraph.classpath.internal` |
| `nonapi.io.github.classgraph.classloaderhandler` | `io.github.classgraph.classpath.internal.classloaderhandler` |
| `nonapi.io.github.classgraph.classpathspec` | `io.github.classgraph.classpath.internal.spec` |
| `nonapi.io.github.classgraph.scanspec` | `io.github.classgraph.internal.scanspec` |
| `nonapi.io.github.classgraph.types` | `io.github.classgraph.internal.types` |

Nothing in the public API refers to these packages, so this only affects code that reached
into ClassGraph's internals, and OSGi or JPMS configuration that names them.

### `Classpath` is spelled with a lowercase `p`, everywhere

The classpath finder's public types are `Classpath`, `ClasspathEntry` and
`ClasspathFinder`, matching `ClassGraph#getClasspath()`, `#overrideClasspath()` and the
other long-standing method names, rather than mixing `ClassPath` and `Classpath` one
capital letter apart.

### Reduced visibility

Several members of the exported `io.github.classgraph` package were `public` or
`protected` even though their types or parameters are internal, so nothing outside
ClassGraph could usefully call or override them. They are now package-private, and the
compiler's `-Xlint:exports` check is enabled to keep it that way.

* `ScanResult#reflectionUtils` was `protected`, exposing the internal
  `io.github.classgraph.base.internal.reflection.ReflectionUtils` type to subclasses.
* `Resource(ClasspathElement, long)` was a `public` constructor taking the internal
  `ClasspathElement` type. `Resource` instances only ever come from a scan.
* The `protected` `findReferencedClassInfo(Map, Set, LogNode)` methods of `ClassInfo`,
  `MethodInfo`, `MethodInfoList`, `FieldInfo`, `FieldInfoList`, `AnnotationInfo`,
  `AnnotationInfoList`, `AnnotationParameterValue`, `AnnotationParameterValueList`,
  `TypeSignature`, `ClassTypeSignature` and `MethodTypeSignature` took the internal
  `LogNode` type.
* The `protected` `addTypeAnnotation` methods of `HierarchicalTypeSignature`,
  `TypeSignature` and their subclasses took the internal `Classfile.TypePathNode` type.
* The constructors of the abstract classes `ClassMemberInfo` and
  `HierarchicalTypeSignature` were `public`, which is meaningless on an abstract class.
  They are now package-private, along with the other constructors and fields listed under
  "Public and `protected` fields are now private, behind getters" below. Subclasses inside
  ClassGraph are unaffected.
* `TypeArgument#findReferencedClassNames(Set)` was `public`, while the same method on
  all nine of its sibling type signature classes is `protected`. It is now `protected`
  too. It takes an output set that only the scanner populates, so there was nothing a
  caller could do with it.
* `ArrayClassInfo` and `InfoList` (and so all of its subclasses) declared `equals(Object)`
  and `hashCode()` overrides whose whole body was a call to `super`. These have been
  removed, so the methods are simply inherited. Behavior is identical; only reflection
  over the declared methods of those classes sees a difference.

### Nullability is now declared, using JSpecify

The `io.github.classgraph` package (and the `io.github.classgraph` module) is annotated
[`@NullMarked`](https://jspecify.dev/): **every type in an API signature is non-null
unless it is explicitly annotated `@Nullable`**. Every method return, parameter and field
that can be null is now annotated `org.jspecify.annotations.@Nullable`, and the whole of
the main source tree is checked by [NullAway](https://github.com/uber/NullAway) in
JSpecify mode, which passes with no findings.

Nothing about this changes what ClassGraph does at runtime — it documents behavior that
was already the case. What it changes for you:

* If you use a null-analysis tool that understands JSpecify (IntelliJ IDEA, Eclipse JDT,
  NullAway, Kotlin), it will now flag calls that dereference a nullable ClassGraph result
  without checking it, and calls that pass null where ClassGraph does not accept null.
  These are pre-existing latent bugs in the calling code, not new restrictions.
* Kotlin callers see ClassGraph types as proper platform-free types: a `@Nullable`
  return is `T?`, and everything else is `T` rather than `T!`.

Which methods are `@Nullable` is documented in the Javadoc as before; the annotations
just make the same statement machine-readable.

The build adds one new dependency, `org.jspecify:jspecify`, in `provided` scope, so it
does **not** become a runtime or transitive dependency of your project. JSpecify's
annotations are `RUNTIME`-retained, but the JVM silently ignores annotations whose type
is not on the classpath, so ClassGraph runs fine without it. The module descriptor
declares `requires static transitive org.jspecify` (`static` because it is optional at
runtime, `transitive` because the annotations appear in exported signatures), and the
OSGi manifest imports `org.jspecify.annotations` with `resolution:="optional"`. Add the
JSpecify jar to your own build only if you want your tools to read the annotations.

### Null arguments are now rejected, consistently, with `NullPointerException`

`@NullMarked` is a compile-time contract, and it only protects callers that run a null
checker of their own. So 5.x also checks arguments at runtime: **every public API method
that does not accept null now throws `NullPointerException` if it is passed one**, with a
message naming the parameter, e.g. `packageNames[1] must not be null`.

This matters most where 4.x accepted the null and carried on. About 25 public methods
silently ignored a null argument or returned a "not found" answer for it, which is
indistinguishable from a legitimate miss:

* `ClassGraph#acceptPackages`, `#rejectPackages`, `#acceptClasses`, `#acceptPaths`,
  `#acceptJars`, `#acceptModules` and the rest of the accept/reject family dropped a null
  varargs element and scanned with the remaining criteria, so a null in a list of package
  names quietly widened the scan.
* `ClassGraph#addClassLoader(null)` and `#addModuleLayer(null)` were ignored.
* `ScanResult#getClassInfo`, `#getPackageInfo`, `#getModuleInfo`,
  `#getResourcesWithLeafName`, `ClassInfo#getFieldInfo(String)`,
  `#getMethodInfo(String)`, `ClassInfoList#get(String)`, `MethodInfoList#get(String)`,
  `ResourceList#get(String)`, `PackageInfo#getClassInfo(String)` and
  `ModuleInfo#getClassInfo(String)` returned null for a null name.
* `ClassInfo#hasAnnotation(String)`, `#hasDeclaredMethod(String)`,
  `ClassInfoList#containsName(String)` and the other `has*`/`contains*` queries returned
  false for a null name.

Several methods that already rejected null did so as `IllegalArgumentException`, or with
a misleading or empty message. These now throw `NullPointerException`, following the
convention used by the JDK itself (`NullPointerException` for a null argument,
`IllegalArgumentException` for an argument that is present but invalid):

| Method | ClassGraph 4.x | ClassGraph 5.x |
| --- | --- | --- |
| `ClassGraph#scanAsync(ExecutorService, int, ScanResultProcessor, FailureHandler)`, for a null processor or handler | `IllegalArgumentException` | `NullPointerException` |
| `ClassGraph#addModuleLayer`, `#overrideModuleLayers` | `IllegalArgumentException` | `NullPointerException` |
| `ClassGraph#enableURLScheme(null)` | `IllegalArgumentException: URL schemes must contain at least two characters` | `NullPointerException: scheme must not be null` |
| `ClassGraph#filterClasspathElements(null)` | `IllegalArgumentException` with a null message | `NullPointerException: classpathElementFilter must not be null` |
| `ClassInfoList#getAssignableTo(null)` | `IllegalArgumentException` | `NullPointerException` |
| `ClassInfoList#exclude(null)` | `NullPointerException` with a null message | `NullPointerException: other must not be null` |
| `ClassInfo#loadClass((Class<?>) null)` | `IllegalArgumentException: Could not load class <name>` | `NullPointerException: superclassOrInterfaceType must not be null` |
| `TypeSignature#resolveTypeVariables(null)` | `IllegalArgumentException` | `NullPointerException` |
| `ScanResult#loadClass(String, boolean)` and `#loadClass(String, Class, boolean)`, for an empty class name | `NullPointerException: className cannot be null or empty` | `IllegalArgumentException: className must not be empty` (an empty string is not a null) |

The messages themselves are not part of the API contract and may change; the exception
types are.

The exception is methods for which accepting null is the sensible, expected behavior:
comparisons. `equals(Object)` and `TypeSignature#equalsIgnoringTypeParams(TypeSignature)`
still answer null with `false`, rather than throwing, and the latter's parameter is now
annotated `@Nullable` to say so.

Code that relied on passing null to mean "no filter" has to stop passing it. Code that
was already passing non-null arguments is unaffected.

### Empty lists returned by the API are now `List.of()` / `Set.of()`

Methods that return an empty collection (for example `InfoList#getNames()`,
`ClassInfoList#loadClasses()`) used to return `Collections.emptyList()`, and now return
`List.of()`. Both are immutable and empty, but the JDK's `List.of()` rejects a null
argument to `contains()`, `indexOf()` and `lastIndexOf()` with `NullPointerException`,
where `Collections.emptyList()` answered `false` / `-1`. This matches the rest of 5.x,
which rejects null rather than answering it.

### Hierarchy and annotation queries now say `getDirect...` or `getAll...` (#559)

In 4.x, `getSubclasses()`, `getInterfaces()`, `getClassesImplementing()` and the rest
returned the *transitive* closure, and you narrowed the result to the directly-related
classes by calling `.directOnly()` on it. Nothing in the method name said which one you
were getting, so the common mistake was to use the transitive answer believing it was the
direct one. The old names are gone; each query is now spelled out as either `getAll...`
(transitive, what 4.x did) or `getDirect...` (what `.directOnly()` used to give you). Code
that is not updated will not compile, rather than silently changing meaning.

| Removed in 5.x | Transitive (same as 4.x) | Direct only |
| --- | --- | --- |
| `ClassInfo#getSubclasses()` | `ClassInfo#getAllSubclasses()` | `ClassInfo#getDirectSubclasses()` |
| `ClassInfo#getSuperclasses()` | `ClassInfo#getAllSuperclasses()` | `ClassInfo#getSuperclass()` (single) |
| `ClassInfo#getInterfaces()` | `ClassInfo#getAllSuperinterfaces()` | `ClassInfo#getDirectSuperinterfaces()` |
| `ClassInfo#getClassesImplementing()` | `ClassInfo#getAllClassesImplementing()` | `ClassInfo#getDirectClassesImplementing()` |
| `ClassInfo#getSubinterfaces()` | `ClassInfo#getAllSubinterfaces()` | `ClassInfo#getDirectSubinterfaces()` |
| `ClassInfo#getAnnotations()` | `ClassInfo#getAllAnnotations()` | `ClassInfo#getDirectAnnotations()` |
| `ClassInfo#getAnnotationInfo()` | `ClassInfo#getAllAnnotationInfo()` | `ClassInfo#getDirectAnnotationInfo()` |
| `ClassInfo#getAnnotationInfo(Class \| String)` | `ClassInfo#getAllAnnotationInfo(Class \| String)` | `ClassInfo#getDirectAnnotationInfo(Class \| String)` |
| `ClassInfo#getAnnotationInfoRepeatable(Class \| String)` | `ClassInfo#getAllAnnotationInfoRepeatable(Class \| String)` | `ClassInfo#getDirectAnnotationInfoRepeatable(Class \| String)` |
| `MethodInfo` / `FieldInfo` `#getAnnotationInfo(...)` | `#getAllAnnotationInfo(...)` | `#getDirectAnnotationInfo(...)` |
| `MethodInfo` / `FieldInfo` `#getAnnotationInfoRepeatable(...)` | `#getAllAnnotationInfoRepeatable(...)` | `#getDirectAnnotationInfoRepeatable(...)` |
| `MethodParameterInfo#getAnnotationInfo(...)` | `#getAllAnnotationInfo(...)` | `#getDirectAnnotationInfo(...)` |
| `MethodParameterInfo#getAnnotationInfoRepeatable(...)` | `#getAllAnnotationInfoRepeatable(...)` | `#getDirectAnnotationInfoRepeatable(...)` |
| `PackageInfo` / `ModuleInfo` `#getAnnotationInfo(...)` | `#getAllAnnotationInfo(...)` | `#getDirectAnnotationInfo(...)` |
| (none in 4.x) | `PackageInfo` / `ModuleInfo` `#getAllAnnotationInfoRepeatable(...)` | `#getDirectAnnotationInfoRepeatable(...)` |
| `ScanResult#getSubclasses(Class \| String)` | `ScanResult#getAllSubclasses(...)` | `ScanResult#getDirectSubclasses(...)` |
| `ScanResult#getSuperclasses(Class \| String)` | `ScanResult#getAllSuperclasses(...)` | `ScanResult#getSuperclass()` on the `ClassInfo` |
| `ScanResult#getInterfaces(Class \| String)` | `ScanResult#getAllSuperinterfaces(...)` | `ScanResult#getDirectSuperinterfaces(...)` |
| `ScanResult#getClassesImplementing(Class \| String)` | `ScanResult#getAllClassesImplementing(...)` | `ScanResult#getDirectClassesImplementing(...)` |
| `ScanResult#getSubinterfaces(Class \| String)` | `ScanResult#getAllSubinterfaces(...)` | `ScanResult#getDirectSubinterfaces(...)` |
| `ScanResult#getAnnotationsOnClass(String)` | `ScanResult#getAllAnnotationsOnClass(...)` | `ScanResult#getDirectAnnotationsOnClass(...)` |

For the annotation queries, "all" means the annotations directly present on the class,
member or method parameter plus the meta-annotations reachable from them (and, for a
class, any `@Inherited` annotation on a superclass) — exactly what 4.x returned.
`MethodParameterInfo` had only the "all" half of this pair, under the name
`getAnnotationInfo(...)`; it now has both halves, named like the rest.

`PackageInfo` and `ModuleInfo` had neither half: their `getAnnotationInfo(...)` methods
returned only the annotations directly present on `package-info.class` /
`module-info.class`, without saying so in the name, and there was no way to ask for the
meta-annotations. Both classes now carry the same twelve-method annotation surface as
`ClassInfo` and the class members, so `getAllAnnotationInfo()` on a package or module
expands meta-annotations, and `getDirectAnnotationInfo()` is what 4.x's
`getAnnotationInfo()` returned. **This is a behavior change as well as a rename**: if a
package annotation is itself annotated, the meta-annotation now shows up in
`getAllAnnotationInfo()`, and `hasAnnotation()` (which asks the "all" list) now answers
true for it. Use `getDirectAnnotationInfo(...)` to get 4.x's answer. (`@Inherited` plays
no part here, since a package and a module have no superclass.)

`.directOnly()` still exists on `ClassInfoList` and `AnnotationInfoList`, so the 4.x
idiom keeps working; the `getDirect...` methods are just the direct way to ask.

All twelve of these annotation methods now come from a new public interface,
`HasAnnotations`, implemented by `ClassInfo`, `FieldInfo`, `MethodInfo`,
`MethodParameterInfo`, `PackageInfo` and `ModuleInfo` — the ClassGraph counterpart of
`java.lang.reflect.AnnotatedElement`. This is additive: every method keeps the name and
signature it had, and existing code compiles unchanged. What it buys you is that a method
which only cares about annotations can now accept any annotated element:

```java
static boolean isDeprecated(HasAnnotations element) {
    return element.hasAnnotation(Deprecated.class);
}
```

Only `getAllAnnotationInfo()` is abstract; the other eleven methods are `default`s derived
from it, which is also how the five implementing classes stopped carrying five copies of
the same code.

Two overloads were added for symmetry, since every other query on `ScanResult` accepts
either a `Class<?>` or a class name: `ScanResult#getAllAnnotationsOnClass(Class)` and
`ScanResult#getDirectAnnotationsOnClass(Class)`.

The reverse queries — `ScanResult#getClassesWithAnnotation(...)`,
`#getClassesWithAllAnnotations(...)`, `#getClassesWithAnyAnnotation(...)`, and the
method- and field-annotation equivalents — deliberately keep their names, because
`getAllClassesWithAllAnnotations` is not readable. They return the transitive answer, as
in 4.x; call `.directOnly()` on the result for the direct one.

`ClassInfoList#getInterfaces()` and `ClassInfoList#getAnnotations()` are unchanged. They
are list filters ("keep the entries of this list that are interfaces"), not hierarchy
queries, and no longer collide in name with anything on `ClassInfo`.

The interface queries say "superinterface" rather than "interface", which is the term the
JVM specification uses for the entries of a classfile's `interfaces[]` array, whether the
classfile is a class or an interface. This also keeps `ScanResult#getAllInterfaces()` —
the no-argument query for every interface in the scan result — from meaning something
unrelated to `ScanResult#getAllInterfaces(Class | String)`, which was the one overload
pair in the API where the same name answered two different questions. The no-argument
`ScanResult#getAllInterfaces()` therefore keeps its name; only the two per-class overloads
are renamed.

### There is now one glob syntax, used everywhere (#643, #870, #940)

4.x had three different glob dialects, and which one you got depended on which method you
were calling. `acceptPackages("com.*")` treated `*` as "within one package segment";
`acceptClasses("com.*")` treated the same `*` as "any characters, including dots";
`getResourcesMatchingWildcard()` had a third dialect in which `**` meant "any characters"
and unrecognized syntax was passed through to the regex engine.

5.x has one syntax, shared by every accept/reject criterion and by
`ScanResult#getResourcesMatchingWildcard()`:

| Wildcard | Matches |
| --- | --- |
| `*` | zero or more characters within one package or path segment |
| `**` | zero or more whole segments — must form a complete segment on its own |
| `?` | exactly one character, other than the separator |

Every other character is matched literally, including regex metacharacters.

What this changes in existing code:

* **Class name globs.** `*` no longer spans `.`, so the 4.x idiom for "a class with this
  name in any package" changes from `acceptClasses("*.*Suffix")` to
  `acceptClasses("**.*Suffix")`. The same applies to `rejectClasses()`.
* **Classpath element resource path globs.** `acceptClasspathElementsContainingResourcePath("META-INF/*")`
  now matches only resources directly in `META-INF`; use `"META-INF/**"` to match at any
  depth. The same applies to the `reject` form.
* **Module name globs.** `acceptModules("java.*")` now matches `java.base` but not
  `java.xml.crypto`; use `"java.**"` for the 4.x meaning. Two-segment module names are
  unaffected.
* **Resource wildcards.** `getResourcesMatchingWildcard()` no longer passes unrecognized
  syntax through to the regex engine, so character sets like `[abc]` are now matched
  literally — use `getResourcesMatchingPattern(Pattern)` for anything a glob can't
  express. `**` must now form a complete path segment, so `"**.txt"` throws
  `IllegalArgumentException` (the message names the problem); write `"**/*.txt"`.
  `"**/*.txt"` also now matches a resource at the classpath root, since `**` can match
  zero segments — in 4.x it required at least one directory level.
* **`?` is now a wildcard everywhere**, so a literal `?` can no longer be matched by a
  glob. `?` cannot occur in a package, class or module name, so this only affects paths
  and jar leafnames.
* **Jar leafname globs** (`acceptJars()`, `rejectJars()`) are unchanged for `*`, since a
  leafname contains no separator, but they now support `?`, and `**` in a leafname throws
  rather than behaving like `*`.

Two 4.x bugs disappear with the old dialects. A glob containing a regex metacharacter
other than `.` used to have that character copied into the pattern unescaped, so
`acceptClasses("com.Outer$Inner*")` compiled to a pattern with an end-of-input anchor in
the middle and matched nothing; metacharacters are now escaped. And
`getResourcesMatchingWildcard("**/*.txt")` used to require at least one directory level,
as described above.

### `Str` in method names is now spelled `String`

The abbreviation was only ever there to keep the names short:

| ClassGraph 4.x | ClassGraph 5.x |
| --- | --- |
| `ClassInfo#getModifiersStr()` | `ClassInfo#getModifiersString()` |
| `ClassInfo#getTypeSignatureStr()` | `ClassInfo#getTypeSignatureString()` |
| `ClassMemberInfo#getModifiersStr()` | `ClassMemberInfo#getModifiersString()` |
| `ClassMemberInfo#getTypeDescriptorStr()` | `ClassMemberInfo#getTypeDescriptorString()` |
| `ClassMemberInfo#getTypeSignatureStr()` | `ClassMemberInfo#getTypeSignatureString()` |
| `ClassMemberInfo#getTypeSignatureOrTypeDescriptorStr()` | `ClassMemberInfo#getTypeSignatureOrTypeDescriptorString()` |
| `FieldInfo#getModifiersStr()` | `FieldInfo#getModifiersString()` |
| `MethodInfo#getModifiersStr()` | `MethodInfo#getModifiersString()` |
| `MethodParameterInfo#getModifiersStr()` | `MethodParameterInfo#getModifiersString()` |
| `ArrayClassInfo#getTypeSignatureStr()` | `ArrayClassInfo#getTypeSignatureString()` |
| `ArrayTypeSignature#getTypeSignatureStr()` | `ArrayTypeSignature#getTypeSignatureString()` |
| `BaseTypeSignature#getTypeStr()` | `BaseTypeSignature#getTypeName()` (see below) |

`BaseTypeSignature`'s method is the exception: it does not return a signature string, it
returns the name of a primitive type (`"int"`, `"void"`). It is now `getTypeName()`,
matching `Class#getTypeName()` and `java.lang.reflect.Type#getTypeName()`, which return
exactly the same string for the same type.

### Getters and predicates now say `get...` / `is...`, and milliseconds say `Millis`

| ClassGraph 4.x | ClassGraph 5.x |
| --- | --- |
| `Resource#getLastModified()` | `Resource#getLastModifiedMillis()` |
| `ScanResult#classpathContentsLastModifiedTime()` | `ScanResult#getClasspathContentsLastModifiedMillis()` |
| `ScanResult#classpathContentsModifiedSinceScan()` | `ScanResult#isClasspathContentsModifiedSinceScan()` |
| `ModuleInfo#getLocation()` | `ModuleInfo#getLocationURI()` |

Both time values were already in milliseconds since the epoch; the names now say so, as
`FastZipEntry#getLastModifiedTimeMillis()` and `Resource#getLastModifiedMillis()`'s own
Javadoc already did. The value returned by `Resource#getLastModifiedMillis()` is 0L when
the last modified time is unknown, as before.

`ModuleInfo#getLocation()` returns a `URI`, which its name did not say; `getLocationURI()`
does.

### `AnnotationInfo#getParameterValues(boolean)` is now two methods

The boolean selected whether the annotation type's default parameter values were filled in
for parameters that the use site did not give explicitly. A boolean at a call site does not
say which of the two answers you asked for, so there are now two named methods:

| ClassGraph 4.x | ClassGraph 5.x |
| --- | --- |
| `getParameterValues()` | `getParameterValues()` |
| `getParameterValues(true)` | `getParameterValues()` |
| `getParameterValues(false)` | `getDeclaredParameterValues()` |

`getDeclaredParameterValues()` is named for the same distinction as
`ClassInfo#getDeclaredMethodInfo()` — what is written at the site itself, with nothing
inherited or defaulted in. `getDefaultParameterValues()`, which returns the defaults
declared by the annotation type, is unchanged.

### Misuse now throws `IllegalStateException` or `UnsupportedOperationException`

4.x threw `IllegalArgumentException` for almost every kind of misuse, including many with
no argument involved. 5.x follows the JDK's convention:

* **`IllegalStateException`** when the failure depends on the state of the receiver, not on
  an argument. This covers, throughout the API:
  * every "Please call `ClassGraph#enableXInfo()` before `#scan()`" guard — on `ClassInfo`,
    `ClassMemberInfo`, `FieldInfo`, `MethodParameterInfo`, `PackageInfo`, `ModuleInfo`,
    `ClassInfoList` and `ScanResult`. `PackageInfo` and `ModuleInfo` are new to this list:
    in 4.x, asking a package or module for its annotations without calling
    `enableAnnotationInfo()` returned an empty list, so a forgotten call looked like a
    package with no annotations rather than an error;
  * using a `ScanResult` after it has been closed;
  * asking a class for something it is not — `ClassInfo#getEnumConstants()` on a
    non-enum, `ClassInfo#getAnnotationDefaultParameterValues()` on a non-annotation;
  * a classpath element, resource or module whose URI or URL cannot be determined —
    `ClassInfo#getClasspathElementURI()`/`#getClasspathElementURL()`/`#getClasspathElementFile()`,
    `Resource#getURI()`/`#getURL()`/`#getClasspathElementURL()`;
  * `TypeVariableSignature#resolve()` when the class defining the type variable was not
    found during the scan.
* **`UnsupportedOperationException`** when the operation is one the receiver does not
  support at all:
  * every mutating method of an unmodifiable `InfoList` (`add`, `remove`, `set`, `clear`,
    `sort`, the iterator's `remove`, and the rest). This is what `java.util.List` documents
    for an unmodifiable list, and what `Collections.unmodifiableList()` throws;
  * `getClassName()` and `getClassInfo()` on the scan result objects that do not stand for
    a class — `AnnotationClassRef`, `AnnotationParameterValue`, `TypeArgument`,
    `TypeParameter`;
  * `ClassTypeSignature#getClassName()`/`#getClassInfo()` and
    `MethodTypeSignature#getClassName()`/`#getClassInfo()`.

`IllegalArgumentException` is now thrown only where an argument is present but invalid —
for example a malformed glob passed to `getResourcesMatchingWildcard()`, or a type
signature string that does not parse. A null argument throws `NullPointerException`, as
described above.

`ClassGraphException` — thrown by `ClassGraph#scan()` when a scan is interrupted or a
worker thread throws — extended `IllegalArgumentException` for the same reason, and now
extends `RuntimeException` directly. A failed scan is not a bad argument, and catching
`IllegalArgumentException` around `scan()` also caught unrelated argument errors.

Code that catches `IllegalArgumentException` around any of these calls needs to catch the
new type. All of them are unchecked, so nothing fails to compile; if you catch
`RuntimeException`, or `ClassGraphException` by name, nothing changes.

### Every list and map returned by the API is now unmodifiable

In 4.x, some returned lists were unmodifiable and some were not, with no way to tell which
was which from the method signature — and several of the modifiable ones were ClassGraph's
own internal lists, so writing to a returned list silently corrupted the scan result. In
5.x the rule is uniform: **every `List` and `Map` handed back by the public API is
unmodifiable**. Reads are unchanged; `add`, `remove`, `set`, `clear`, `sort`, `removeIf`,
`replaceAll`, `put` and the rest all throw `UnsupportedOperationException`, as do the
iterators, list iterators and sublists obtained from them. This includes derived lists such
as those returned by `filter()`, `union()`, `intersect()`, `exclude()` and `directOnly()`,
the plain `java.util.List` returns such as `InfoList#getNames()` and
`ResourceList#getPaths()`, and the lists nested inside returned maps such as
`MethodInfoList#asMap()` and `ResourceList#asMap()`.

Copy the collection if you need a modifiable version of it, e.g. `new ArrayList<>(list)` or
`new HashMap<>(map)`. Lists you construct yourself, through a public constructor such as
`new ClassInfoList(collection)`, are still modifiable.

The returned maps are live views, as they always were: they reflect the scan result they
came from, and stop being usable once it is closed.

One consequence inside ClassGraph: `ScanResult#close()` no longer calls `clear()` on the
cached resource list and resource map before dropping them, since the caller may be holding
one of them. It drops the references instead, so the collections are still released.

A mutation that would not have changed the contents of the list — `clear()` on an empty
one, `addAll(List.of())`, `retainAll()` with a collection that already holds everything —
threw in some of those cases in 4.x and silently did nothing in others. All of them now
throw, which is what `Collections.unmodifiableList()` does, so the type of the list no
longer decides whether a no-op write is rejected.

### Arrays returned by the API are now unmodifiable lists

An array return hands the caller a copy of ClassGraph's own array (or, worse, the array
itself), and nothing in the signature says whether writing to it is safe. These two now
return unmodifiable `List`s, like every other multi-valued return in the API:

| ClassGraph 4.x | ClassGraph 5.x |
| --- | --- |
| `MethodInfo#getParameterInfo()` → `MethodParameterInfo[]` | → `List<MethodParameterInfo>` |
| `MethodInfo#getThrownExceptionNames()` → `String[]` | → `List<String>` |

`MethodInfo#getThrownExceptions()` already returned a `ClassInfoList` and is unchanged.
`Resource#load()` still returns `byte[]`: it is file content rather than a collection, and
each call already returns a fresh array.

### Public and `protected` fields are now private, behind getters

Mutable state that was reachable from outside the class is now private, with a getter that
returns an unmodifiable view where the value is a collection:

* `ModulePathInfo`'s six `public final Set<String>` fields — `modulePath`, `addModules`,
  `patchModules`, `addExports`, `addOpens`, `addReads` — are private, and are read through
  `getModulePath()`, `getAddModules()`, `getPatchModules()`, `getAddExports()`,
  `getAddOpens()` and `getAddReads()`, each returning an unmodifiable `Set<String>`. In 4.x
  the sets were `final` but their *contents* were not, so a caller could add entries to
  ClassGraph's parsed module path. Each getter returns a snapshot taken while holding the
  instance's lock, so a scan thread recording an `Add-Exports` or `Add-Opens` manifest entry
  cannot disturb a set that the caller is still iterating.
* The `protected` fields of `ClassInfo` (`name`, `typeSignatureStr`, `isExternalClass`,
  `isScannedClass`, `classfileResource`), `ClassMemberInfo` (`declaringClassName`, `name`,
  `modifiers`, `typeDescriptorStr`, `typeSignatureStr`, `annotationInfo`), `Resource`
  (`inputStream`, `byteBuffer`, `length`) and `HierarchicalTypeSignature`
  (`typeAnnotationInfo`) are now package-private. Every one of them already had a public
  getter.
* The `protected` constructors of `ClassInfo`, `ClassMemberInfo`, `HierarchicalTypeSignature`,
  `TypeSignature`, `ReferenceTypeSignature`, `ClassRefOrTypeVariableSignature` and
  `TypeParameter` are now package-private, along with `MethodParameterInfo#setScanResult`.
  These classes only ever come from a scan — a subclass built outside ClassGraph has no
  `ScanResult` behind it and throws as soon as it is asked anything — so `protected` was
  advertising an extension point that never worked.

### Method chaining

Methods that returned `void` but had an obvious receiver to hand back now return it, so
they can be chained:

* `ResourceList#forEachByteArray`, `#forEachByteArrayIgnoringIOException`,
  `#forEachInputStream`, `#forEachInputStreamIgnoringIOException`, `#forEachByteBuffer` and
  `#forEachByteBufferIgnoringIOException` return the `ResourceList`.

Nothing else changes about these methods; code that ignores the return value is unaffected.

### The callback interfaces have been replaced by `Predicate` and `Consumer`

ClassGraph declared ten single-method interfaces of its own, each one structurally
identical to a JDK functional interface that has existed since Java 8. They are gone, and
the methods that took them take the JDK type instead:

| Removed interface | Now takes |
| --- | --- |
| `ClassInfoList.ClassInfoFilter` | `Predicate<ClassInfo>` |
| `AnnotationInfoList.AnnotationInfoFilter` | `Predicate<AnnotationInfo>` |
| `FieldInfoList.FieldInfoFilter` | `Predicate<FieldInfo>` |
| `MethodInfoList.MethodInfoFilter` | `Predicate<MethodInfo>` |
| `PackageInfoList.PackageInfoFilter` | `Predicate<PackageInfo>` |
| `ModuleInfoList.ModuleInfoFilter` | `Predicate<ModuleInfo>` |
| `ResourceList.ResourceFilter` | `Predicate<Resource>` |
| `ClassGraph.ClasspathElementFilter` | `Predicate<String>` |
| `ClassGraph.ClasspathElementURLFilter` | `Predicate<URL>` |
| `ClassGraph.ScanResultProcessor` | `Consumer<ScanResult>` |
| `ClassGraph.FailureHandler` | `Consumer<Throwable>` |

A lambda or method reference passed to any of these methods compiles unchanged, since the
shape of the argument is the same. What has to change is code that names one of the types
— an anonymous class, a stored filter in a field, or an implementing class — and the
method name inside it: the filters' `accept(...)` is `Predicate#test(...)`,
`ScanResultProcessor#processScanResult(...)` and `FailureHandler#onFailure(...)` are both
`Consumer#accept(...)`.

In exchange, the JDK's combinators are available: `filter(p.negate())`,
`filter(p1.and(p2))`, `filter(p1.or(p2))`. `ResourceList#nonClassFilesOnly()` is now
written as `classFilesOnly`'s predicate, negated.

`ResourceList`'s `ByteArrayConsumer`, `InputStreamConsumer` and `ByteBufferConsumer` are
**kept**, because their methods throw `IOException` and no JDK functional interface does.

### `ClassInfoList#getAssignableTo` accepts a class or a class name

`getAssignableTo(ClassInfo)` was the only way to ask, so callers holding a `Class<?>` or a
class name had to look the `ClassInfo` up first, and handle the case where the class was
not found in the scan result. `getAssignableTo(Class<?>)` and `getAssignableTo(String)` do
that, returning the empty list if the class was not found — matching the `Class<?>` /
`String` overload pair that the rest of the query API already offers.

### `enableMemoryMapping()` has been removed; ClassGraph now decides by platform

Reading jarfiles through a `MappedByteBuffer` rather than through positioned `FileChannel`
reads was an opt-in that a user had no way of evaluating without benchmarking their own
workload. It has now been benchmarked on all three major platforms, on two JDKs, against
three corpora, warm and cold — the numbers, the method and the tools are in BENCHMARK.md.
The result is one-sided: on Windows, mapping is 16% to 38% faster on every workload
measured, with the two ranges not even overlapping; on Linux it is at best about 10%
faster warm, and up to 37% *slower* on a cold page cache when the jars hold many resources
that are not read, since a page fault reads more than was asked for; on macOS it is within
the noise in both directions.

So ClassGraph now memory-maps on Windows and reads through the channel everywhere else,
and `enableMemoryMapping()` is gone. Remove the call; there is nothing to replace it with.
A program that called it gets what it asked for on Windows, and, on Linux and macOS,
loses a setting that was as likely to cost time as to save it.

One consequence for Windows users: the classes needed to release a mapped buffer when a
`ScanResult` is closed are now loaded at the start of every scan, since every scan maps.
On Linux and macOS they are not loaded at all.

### `CloseableByteBuffer#close()` no longer declares `IOException`

Nothing in it can throw one — it releases the buffer and runs the close action, and any
exception the close action throws is swallowed. Callers can drop the `catch (IOException)`
around a `CloseableByteBuffer` they use in a try-with-resources; a `catch` that has nothing
else in the block that could throw `IOException` now has to be removed, since catching a
checked exception that cannot be thrown does not compile.

## Behavior changes

* **Malformed classfiles are now reported rather than silently producing null names.**
  A handful of places in the classfile parser read a string from the constant pool that
  the JVM specification requires to be present — an annotation's class name, an
  annotation parameter name or value, an enum constant name, a field name or type
  descriptor, a thrown exception class name, and the enclosing class and method names of
  an anonymous inner class. If the constant pool held a null string reference in one of
  these positions, 4.x would carry the null through into the scan result, producing
  entries whose name was null or a string containing `"null"`. 5.x throws
  `ClassfileFormatException` instead, which the scanner catches per classfile: the
  classfile is logged as invalid and skipped, and the rest of the scan proceeds. Valid
  classfiles are unaffected.
* **`AnnotationParameterValue#getValue()` returns the stored array itself for every array
  type.** In 4.x, an array of a reference type (`String[]`, `Class[]`, an array of enum
  constants or of nested annotations) was rebuilt into a fresh array on every call, while
  an array of a primitive type was returned by reference. Both are now returned by
  reference, which is consistent and avoids the repeated copying, but it does mean that
  writing to the returned array changes what later calls return. Copy the array first if
  you need to modify it.
* **`enableURLScheme()` now rejects anything that is not a URL scheme.** 4.x only checked
  that the scheme was at least two characters long, and stored whatever it was given, so a
  scheme written with its trailing colon — `enableURLScheme("s3:")`, an easy mistake to
  make — was accepted and then never matched anything, silently scanning nothing. The
  argument is now required to be a scheme name as defined by RFC 3986 (a letter, followed
  by letters, digits, `+`, `-` or `.`), and `IllegalArgumentException` is thrown if it is
  not. A one-character scheme is still rejected, since it cannot be told apart from a
  Windows drive letter.
* **Reading from a `Resource`'s `InputStream` after closing it no longer throws
  `NullPointerException`.** The stream wrapper used to null out its reference to the
  wrapped stream on close, so a subsequent read hit a null. It now keeps the reference
  and delegates, so the read fails the way the wrapped stream fails — for the streams
  ClassGraph returns, `IOException: Stream closed`.
* **`java.lang.Object` is now visible as the universal superclass.** 4.x hid `Object`
  from the class graph in two ways: its classfile was never scanned, even when it was
  explicitly accepted, and the superclass link from a class to `Object` was never
  recorded. Both are gone in 5.x, which changes four things:

  * `ClassInfo#getSuperclass()` and `ScanResult#getSuperclass(...)` now return `Object`
    for a standard class that extends no other class, instead of null. As with
    `Class#getSuperclass()`, null is now returned only for `Object` itself and for
    interfaces. (An interface's classfile names `Object` as its superclass, but
    interfaces do not extend `Object`, so that link is still not recorded.) **This is
    the change most likely to affect existing code**: a loop that walks up the
    superclass chain until `getSuperclass()` returns null now takes one more step, and
    a null check that was reading as "this class extends nothing" now needs to compare
    the name against `java.lang.Object`.
  * `ClassInfo#getAllSuperclasses()` and `ScanResult#getAllSuperclasses(...)` now end
    with `Object`, if the whole superclass chain was scanned. In 4.x `Object` was
    always excluded.
  * If `java.lang.Object` is accepted, it is now scanned like any other class, so it
    appears in `getAllClasses()` and its fields, methods and annotations can be read.
    It also now appears in `getAllClasses()` and `getAllStandardClasses()` whenever
    `enableExternalClasses()` is called, since it is a referenced external class like
    any other.
  * `getDirectSubclasses("java.lang.Object")` is now answered from the recorded
    superclass links, rather than by looking for standard classes with no recorded
    superclass. The result is the same.

  `getAllSubclasses("java.lang.Object")` still returns every standard class in the scan
  result (excluding `Object` itself, and excluding interfaces, which don't extend
  `Object`), whether or not each class' superclass chain was scanned. Rendered output is
  unchanged: `ClassInfo#toString()` and the type signature classes still leave out an
  `extends java.lang.Object` clause, and neither the class graph .dot file nor
  `getClassDependencies()` includes `Object`.
* **External classes are now included or excluded by one consistent rule.** An external
  class is a class that was reached during the scan without being accepted itself —
  typically the superclass or an interface of an accepted class, pulled in so that the
  accepted class' own declarations can be reported. 4.x decided whether to report
  external classes based on whether the class the query started from was itself external,
  which meant the same query could include or exclude external classes depending on where
  in the hierarchy it was asked. The rule in 5.x is:

  * A query that looks **upwards** — for the superclasses, interfaces, annotations,
    meta-annotations or outer classes of a class — includes external classes. It is
    reporting what an accepted classfile itself declares, and leaving part of that out
    would misreport the class.
  * A query that looks **downwards** — `getAllSubclasses()`, `getDirectSubclasses()`,
    `getAllClassesImplementing()`, `getDirectClassesImplementing()`,
    `getAllSubinterfaces()`, `getDirectSubinterfaces()`, `getClassesWithAnnotation()`,
    `getClassesWithMethodAnnotation()`, `getClassesWithFieldAnnotation()` and the rest
    of that family — returns only accepted classes. It can only ever report what the
    scan happened to reach, so reporting external classes there gives an answer that
    depends on the scan's incidental coverage.

  Only the downward queries change. Where 4.x returned external classes from a downward
  query, 5.x leaves them out; calling `enableExternalClasses()` returns them, along with
  all the other external classes the scan reached.

  Classes that are only reachable *through* an external class are still found. For
  example, if an accepted class extends an external class that implements interface `I`,
  the accepted class is still returned by `getClassesImplementing(I)`; likewise for a
  class that inherits an `@Inherited` annotation from an external superclass, and for a
  class whose field or method is annotated by an external annotation that is
  meta-annotated by the annotation being queried. (4.x could drop these, because it
  filtered partway through the traversal rather than at the end.)

* **External classes are no longer listed as members of their package or module.** An
  external class was read only so that an accepted class' own declarations could be
  reported, but 4.x still registered it with its `PackageInfo` and `ModuleInfo`. That
  disagreed with `getAllClasses()`, which leaves external classes out: scanning a project
  that uses JUnit, for example, listed `org.junit.jupiter.api` among the packages, with
  member classes that `getAllClasses()` did not return. In 5.x:

  * `PackageInfo#getClassInfo()`, `PackageInfo#getClassInfoRecursive()` and
    `ModuleInfo#getClassInfo()` list only the classes that `getAllClasses()` returns.
  * A package or module that contains nothing but external classes no longer appears in
    `ScanResult#getPackageInfo()` or `ScanResult#getModuleInfo()`.
  * `ClassInfo#getPackageInfo()` and `ClassInfo#getModuleInfo()` return null for an
    external class. (Both were already documented as nullable.)

  Calling `enableExternalClasses()` makes external classes members of their package and
  module again, as it makes them part of the scan result everywhere else.

* **A system module accepted by name is now scanned without calling
  `enableSystemJarsAndModules()`.** In 4.x, `acceptModules("jdk.compiler")` found nothing
  unless `enableSystemJarsAndModules()` was also called, because system modules were not
  even enumerated without it, while accepting a non-system module by name worked on its
  own. `enableSystemJarsAndModules()` is now only needed in order to scan *all* system
  modules and the JRE/JDK `lib/` and `ext/` jars; naming a system module is taken as
  asking for that one module, and only the named system modules are scanned, so the cost
  of scanning the rest of the JDK is still not incurred.

* **The JDK's application and platform classloaders are now mapped to the scanning
  mechanism that can reach their classes.** Neither of them exposes the locations it
  loads classes from, so neither can be scanned as a classloader; ClassGraph has always
  had to substitute something else when one of them was named. That substitution is now
  the same in both directions, and applies to `addClassLoader()` as well as to
  `overrideClassLoaders()`:

  * The **application** classloader (`ClassLoader.getSystemClassLoader()`, and usually
    also `Thread.currentThread().getContextClassLoader()`) loads the classes on
    `java.class.path` and the application's own modules, so both are scanned. In 4.x,
    the non-system modules were not, so `overrideClassLoaders(appClassLoader)` found
    nothing at all in an application launched on the module path.
  * The **platform** classloader loads only system modules, so the system jars and
    modules are scanned, as if `enableSystemJarsAndModules()` had been called. In 4.x
    the `java.class.path` classpath was scanned too, so
    `overrideClassLoaders(platformClassLoader)` returned the whole application
    classpath — classes that the platform classloader cannot load.
  * In 4.x, `addClassLoader()` did neither of these, so adding one of the two
    classloaders had no effect on what was scanned.

* **The class hierarchy above an accepted class is now completed through modules that are
  not being scanned (#902).** When ClassGraph reaches a superclass, interface or
  annotation of an accepted class, it extends the scan upwards to that class so that the
  accepted class' own declarations can be reported in full. In 4.x that search could only
  look in the classpath elements and modules that were being scanned, and system modules
  are not scanned unless they are asked for, so a hierarchy stopped as soon as it reached
  a JDK class: a class extending `java.util.TimerTask` did not report `Runnable` among
  its interfaces, and its superclass chain ended at `TimerTask` instead of reaching
  `java.lang.Object`. In 5.x, the classfile of such a class is read from its module even
  though that module is not being scanned.

  * Only *reject* criteria block this. A module excluded with `rejectModules()` is never
    read from, but a module that simply wasn't accepted can still have individual
    classfiles read out of it. This is the rule that already applied to classes:
    extending the scan upwards has always consulted the reject list only, since an
    accepted class' superclass is usually outside the accepted packages.
  * The classes read this way are external classes, so they do not appear in
    `getAllClasses()`, and their packages and modules do not appear in
    `getPackageInfo()` or `getModuleInfo()`, unless `enableExternalClasses()` is called.
    No JDK class, package or module is added to the scan result by this change.
  * `java.lang.Object` is still never read, so its methods and fields are never reported.
  * With `enableMethodInfo()`, `enableFieldInfo()` or `enableAnnotationInfo()`, the
    queries that include inherited members — `getMethodInfo()`, `getFieldInfo()`,
    `getAnnotationInfo()` and their variants — now report the members a class inherits
    from JDK supertypes, since those supertypes are now in the class graph. For example,
    an annotation type now reports `annotationType()`, `equals()`, `hashCode()` and
    `toString()`, inherited from `java.lang.annotation.Annotation`. The `getDeclared...`
    queries are unaffected.
  * Nothing changes when ClassGraph does not enumerate modules at all: after
    `disableModuleScanning()`, and after `overrideClasspath()` or `overrideClassLoaders()`
    without the application classloader, unless a module is also asked for by name or
    `enableSystemJarsAndModules()` is called.

* **An annotation that can be reached in more than one way is now reported from the most
  direct of those ways (#559).** `getAllAnnotationInfo(annotationName)` looks up the name
  in the list returned by `getAllAnnotationInfo()`, which holds the annotations directly
  present on a class or member, the annotations inherited from a superclass, and the
  meta-annotations of both. In 4.x that list was sorted by annotation name and then by
  parameter value, so when the same annotation appeared more than once in it, which one
  the lookup returned came down to alphabetical order of the parameter values. An
  annotation that annotates itself is the case where this is most obvious: given
  `@Foo("bar")` declared on `@Foo` itself, a class annotated `@Foo("baz")` reported
  `@Foo("bar")` — the annotation's annotation, not the class'.

  The list is now sorted by name, then by how directly each annotation is related to the
  class or member: directly present first, then inherited from a superclass, then reached
  as a meta-annotation. So `getAllAnnotationInfo(name)`, and the first entry of
  `getAllAnnotationInfoRepeatable(name)`, return the annotation on the class or member
  itself whenever there is one. This applies to `ClassInfo`, `MethodInfo`, `FieldInfo`
  and `MethodParameterInfo` alike. `getDirectAnnotationInfo(...)` is still the way to ask
  for only the annotations present on the element itself.

  An annotation reached twice by the *same* route is now listed once rather than twice,
  which is again visible for a self-annotating annotation: `@Foo`'s own
  `getAllAnnotationInfo()` listed `@Foo` twice, once as a direct annotation and once as
  its own meta-annotation.

* **`ModuleInfo#getPackageInfo()` returns an unmodifiable empty list for a module with no
  packages.** It used to return a fresh modifiable empty `PackageInfoList` in that one
  case, and an unmodifiable one otherwise; `ModuleInfo#getClassInfo()` already returned the
  unmodifiable empty list. Adding to the returned list never affected the scan result, so
  the only change is that it now throws `UnsupportedOperationException`.

* **Method annotations wrap in the class graph .dot file.** In the node for a class,
  each method is a row of three cells — its annotations, its name, and its parameters —
  and the parameter cell already wrapped onto continuation rows once it passed a
  threshold, while the annotation cell was written on a single line however long it got.
  A class with heavily annotated methods therefore produced a node many times wider than
  it needed to be. The annotation cell now wraps at the same width, onto continuation
  rows that leave the name and parameter cells empty. Only the layout of the generated
  .dot file changes; the same annotations, methods and parameters are listed.

* **The classes needed to release memory-mapped buffers are loaded at the start of a
  scan that maps, rather than by the `ClassGraph` constructor.** Releasing a mapped buffer
  when a `ScanResult` is closed needs classes that ClassGraph defines lazily, and closing
  can happen long after the scan, by which time the classloader that loaded ClassGraph
  may no longer be able to define anything — in a container that has already torn down
  the enclosing classloader, closing would fail with `NoClassDefFoundError`. Loading them
  ahead of time avoids that. 4.x did this work in the `ClassGraph` constructor, so every
  user paid for it whether or not they memory-mapped anything: on a JDK with
  `java.lang.foreign` it loaded roughly 35 further classes on the first `new ClassGraph()`.
  Since memory mapping is the only thing that makes ClassGraph allocate such buffers, and
  mapping now happens on Windows only, the work has moved to the start of the scan and is
  skipped entirely on Linux and macOS. One further consequence: on JDK 17 to 21, where the
  buffer cleaner is reached reflectively, a security manager that denies the reflective
  access made the `ClassGraph` constructor throw; now only a scan on Windows can throw for
  that reason.

* **Memory mapping now also applies to a jar reached through a `Path`.** A jar at a URL
  whose scheme is backed by a `FileSystem` provider — as opposed to a jar on disk, or one
  downloaded from an `http(s):` URL — is read through the `Path` API, and that path never
  memory-mapped, whatever the setting said. On a platform where ClassGraph maps, it now
  maps such a jar too, as long as the provider's `FileChannel` supports mapping, and falls
  back to reading through the channel if it does not. Resources in a directory classpath element are
  still read rather than mapped: they are read once and then closed, and mapping and
  unmapping each one costs several times more than reading it.

* **`toString()` now follows the JDK's own rendering of the same thing, wherever the JDK
  renders it.** ClassGraph's `toString()` methods are meant to read as Java source
  declarations, and several of them had drifted from the corresponding JDK method. Five
  changes are visible:

  * **A constructor is named after the class it constructs, not `<init>`.**
    `MethodInfo#toString()` used to print `public <init>(java.lang.String a)`, using the
    constructor's classfile name. It now prints `public com.xyz.Widget(java.lang.String
    a)`, matching `Constructor#toString()` and Java source syntax.
    `MethodInfo#getName()` still returns `"<init>"`, as before. Static initializer
    blocks are still shown as `<clinit>`, since the JDK has no rendering for them.

  * **A variadic parameter is shown as `T...` in `MethodParameterInfo#toString()`.** It
    used to print the parameter's declared array type, `int[] b`, even though
    `MethodInfo#toString()` printed the same parameter as `int... b`.
    `MethodParameterInfo#isVarArgs()` is new, and answers the same question as
    `java.lang.reflect.Parameter#isVarArgs()`; `MethodParameterInfo#getIndex()` is also
    new.

  * **A `TYPE_USE` annotation on a parameter is listed once by
    `MethodParameterInfo#toString()`.** It was printed twice, once as a parameter
    annotation and once as a type annotation on the parameter's type.
    `MethodInfo#toString()` already listed it once.

  * **Annotation parameter values are rendered in the Java source syntax for their
    type**, as `Annotation#toString()` renders them. Previously only the type-independent
    `String#valueOf` form was printed for numeric values, and `String` and `char` values
    were quoted and escaped only when they were not inside an array:

    | Value | 4.x | 5.x |
    | --- | --- | --- |
    | `String[]` | `{a, b}` | `{"a", "b"}` |
    | `char[]` | `{a, b}` | `{'a', 'b'}` |
    | `byte` | `1` | `(byte)0x01` |
    | `long` | `4` | `4L` |
    | `float` | `1.5` | `1.5f` |
    | `Float.NaN` | `NaN` | `0.0f/0.0f` |
    | `Double.POSITIVE_INFINITY` | `Infinity` | `1.0/0.0` |

    String and character escaping also now covers everything the JDK escapes — the
    backslash itself, `\t`, `\b`, `\f`, and every character outside the printable ASCII
    range, including an accented letter such as `é`, which is written `\u00e9` — rather
    than just the quote characters, `\n` and `\r`.

    Two deliberate differences from `Annotation#toString()` remain. ClassGraph names a
    nested class the way the rest of its API names classes, with the classfile's `$`
    separator (`@com.xyz.Outer$Inner`, not `@com.xyz.Outer.Inner`), so that the printed
    name is the one `ScanResult#getClassInfo(String)` accepts. And ClassGraph qualifies
    an enum constant with its type (`java.lang.annotation.RetentionPolicy.RUNTIME`,
    not `RUNTIME`), which is what `AnnotationEnumValue#toString()` has to print to be
    meaningful on its own.

  * **A field's constant initializer value is escaped the same way**, in
    `FieldInfo#toString()`. It previously escaped only the backslash and the quote
    character, so a `String` constant containing a newline broke the rendering across
    two lines. Numeric constant initializers keep the plain form (`static final long X =
    4`, not `4L`), since a field declaration prints the type immediately before the
    value.

  * **`PackageInfo#toString()` and `ModuleInfo#toString()` name what they are**, as
    `Package#toString()` and `Module#toString()` do: `package com.xyz` and `module
    java.base`, rather than the bare name. Call `getName()` for the name alone.

* **`getURL()` works for system modules and jlink'd runtime images.** ClassGraph
  documented `Resource#getURL()`, `Resource#getClasspathElementURL()`,
  `ClassInfo#getClasspathElementURL()` and `ResourceList#getURLs()` as throwing
  `IllegalStateException` for a resource with a `jrt:` location, and told you to use the
  `getURI()` form instead, on the grounds that `java.net.URL` cannot represent the `jrt:`
  scheme. That has not been true since JDK 9, which registers a URL protocol handler for
  `jrt:`, so the URI converts to a URL and the URL opens. Nothing changes at run time —
  the documented exception could never actually be thrown on a JDK new enough to produce
  a `jrt:` URI in the first place — but the documentation and the dead special case that
  went with it are gone, and `ScanResult#getClasspathURLs()` no longer claims to skip
  system modules.

* **ClassGraph no longer runs its reflective calls inside
  `AccessController#doPrivileged`.** 4.x looked `AccessController` up reflectively and
  wrapped its reflective reads of classloader fields in a privileged block, so that an
  application running under a Security Manager could grant the permissions to ClassGraph's
  own jar rather than to the whole application. The Security Manager was deprecated for
  removal in JDK 17 (JEP 411) and permanently disabled in JDK 24 (JEP 486):
  `System.setSecurityManager` throws `UnsupportedOperationException`, and
  `-Djava.security.manager=allow` is a fatal error at VM startup. The privileged blocks
  are gone. This only affects you if you run on JDK 17–23, explicitly opt in with
  `-Djava.security.manager=allow`, and grant ClassGraph's jar more permissions than the
  calling code — in that case, grant the calling code the same permissions instead.

## Bug fixes

Bugs found during the port. Each of these is a pre-existing bug in ClassGraph 4.x, and
is fixed on the 4.x branch as well.

* The same jar or directory was listed twice as a classpath element if it was reachable
  both as a module and as a classpath entry -- if it is on both the module path and the
  classpath, or is spliced into a module with `--patch-module` while also being on the
  classpath (which is what Maven Surefire and IDEs do), or is reached through a symlink in
  one of the two. `ScanResult#getClasspathURIs()`, `getClasspathURLs()`,
  `getClasspathFiles()` and `getClasspath()` are all documented to return the *unique*
  classpath elements, and the duplicate element was also opened and scanned a second time.
  The module now takes precedence, since that is where the JVM loads the classes from, and
  the duplicate classpath element is dropped. Duplicate *resources* were already removed in
  this case, so this does not change which classes or resources a scan returns.

* `ModuleInfo#getLocation()` is documented to return null for a module whose location is
  unknown, and `ModuleReference#location()` can return an empty `Optional`, but the method
  threw rather than returning null in exactly that case: with no location of its own it
  fell back to asking its classpath element for a URI, and a module classpath element with
  no location throws. It now returns null, as documented.

* The month of an MS-DOS zip entry timestamp was read from three bits rather than
  four, so a zip entry that carries only an MS-DOS timestamp (i.e. no extended
  timestamp extra field) reported the wrong last modified time if it was modified
  in August or later: September to December were read as January to April, and
  August was read as December of the previous year. This affects
  `Resource#getLastModifiedMillis()`.

* The two zip extra fields that carry a Unix last modified time -- the extended timestamp
  extra field (tag 0x5455), which most zip tools write, and the deprecated Info-ZIP Unix
  extra field (tag 0x5855) -- were read as 8-byte times, but both formats store 4-byte
  signed Unix times, in seconds. The size checks that guarded the reads could therefore
  never be satisfied by a conforming central directory entry, so neither field was ever
  read, and the last modified time always fell back to the entry's MS-DOS timestamp. An
  MS-DOS timestamp records local time with no timezone and has a resolution of two
  seconds, so `Resource#getLastModifiedMillis()` was wrong by the timezone offset of
  whoever built the jar, and by up to a second on top of that, for every entry of every
  jar that carries an extended timestamp.

* A jarfile too large to buffer in RAM is spilled to a temporary file, and the loop that
  copied it stopped as soon as a read returned zero rather than at the end of the stream.
  A stream that returns zero from a read of a non-empty buffer is not conforming, but the
  JDK's own `InputStream#transferTo` tolerates one, and the consequence here was a
  silently truncated jar rather than an error.

* `ClassInfo#getTypeDescriptor()` synthesizes a type descriptor for a class that has no
  generic type signature, standing in for the classfile's own `super_class` and
  `interfaces[]` entries so that type annotations on the `extends` and `implements`
  clauses have somewhere to attach. It was building that descriptor from the class'
  *transitive* interfaces, so the descriptor claimed the class directly implemented every
  interface reachable from it, including the superinterfaces of its own interfaces and the
  interfaces of its superclasses. It now uses the directly implemented interfaces, in
  classfile order. This affects `ClassInfo#getTypeDescriptor()` and
  `ClassInfo#getTypeSignatureOrTypeDescriptor()` for non-generic classes.

* Rejecting a single system module switched off system module scanning entirely, so
  `enableSystemJarsAndModules().rejectModules("jdk.compiler")` scanned no system modules
  at all, not even `java.base`. System modules were only scanned if the module accept and
  reject criteria were both empty, or if a module was specifically accepted; a reject on
  its own matched neither case. System modules now follow the same accept/reject rule as
  every other module once system module scanning is enabled: with no accept criteria, all
  but the rejected modules are scanned.

* `overrideClassLoaders()` did not scan what the classloader it was given loads from,
  when that classloader was one of the two JDK system classloaders: the platform
  classloader caused the `java.class.path` classpath to be scanned, which it cannot load
  from, and the application classloader did not cause the non-system modules to be
  scanned, which it does load from. See the behavior change above; only the part that
  applies to `addClassLoader()` is new in 5.x.

* `ScanResult#close()`, `ClassGraph#removeTemporaryFilesAfterScan()` and the scan spec all
  documented that temporary files are removed by a `ScanResult` finalizer if `close()` is
  not called. There is no finalizer, and there has not been one for a long time: temporary
  files are registered with `File#deleteOnExit()` when they are created, so they survive
  until the JVM exits. The documentation now says so. Nothing about when the files are
  actually deleted has changed — only the description of it, which mattered because it
  told readers that an uncollected `ScanResult` would eventually be cleaned up by the
  garbage collector, and it will not be.

* `ClassInfo#getClasspathElementURI()` and `ClassInfo#getClasspathElementURL()` threw a
  bare `NullPointerException`, with no message, when called on a `ClassInfo` for a class
  that was referenced by a scanned class but was never itself scanned (`java.lang.Object`,
  for example, when only your own packages are accepted). The two sibling accessors,
  `getClasspathElementFile()` and `getModuleReference()`, already threw `IllegalStateException`
  with an explanatory message in that situation. All four now do. This is still the
  behavior on the 4.x branch, where the exception type is part of the released API.

* `AnnotationInfo#isInherited()` and `AnnotationInfo#getDefaultParameterValues()` threw a
  bare `NullPointerException`, with no message, for an annotation whose own class was not
  scanned. Both read their answer from the annotation class' `ClassInfo`, and asserted
  that it was non-null. A declaration annotation always has at least a placeholder
  `ClassInfo`, because the annotation is recorded in the class graph, but a type
  annotation does not, so any type annotation declared outside the accepted packages hit
  this — including JSpecify's `@Nullable`, which made the two methods unusable on most
  real scans. `isInherited()` now returns false, since an unscanned annotation class is
  not known to be meta-annotated with `@Inherited`, and `getDefaultParameterValues()` now
  returns the empty list, as its documentation already said it would when there are no
  default values.

* The GraphViz class graph wrote an `ANNOTATIONS` heading into a class box whenever the
  class carried any annotation at all, but meta-annotations (`java.lang.annotation.*`) are
  deliberately left out of the listing under that heading, so every annotation class in the
  graph got a heading with nothing under it. The heading is now written only if at least one
  annotation will be listed.

* A type variable that a class' own type signature refers to -- in the bound of another of
  its type parameters (`class C<T extends Number, U extends T>`), or in a type argument of
  its superclass or one of its superinterfaces (`class C<T> extends ArrayList<T>`) -- was
  parsed without recording which class declares it, on the grounds that a class' signature
  cannot refer to its own type variables. It can, in exactly those two places.
  `TypeVariableSignature#resolve()` therefore could not find the declaration, and returned
  an unbounded type parameter with only the variable's name, and
  `TypeVariableSignature#toStringWithTypeBound()` printed the name with no bound. Both now
  resolve against the declaring class.

* `TypeSignature#equalsIgnoringTypeParams(TypeSignature)` gave the wrong answer for a type
  variable compared with a class reference, in three ways. A type variable with no bound of
  its own is written into the classfile with `java.lang.Object` as its bound, which was
  compared against the class reference like any other bound, so an unbounded type variable
  could not be reconciled with any class -- although the comparison the other way round,
  against a `java.lang.Object` class reference, always succeeded. A type variable bounded by
  another type variable (`U extends T`) compared itself against its own bound instead of
  against the class reference, which was false unless the two variables were spelled the
  same. And the bound was compared with `equals()` rather than `equalsIgnoringTypeParams()`,
  so a bound of `List<String>` was not reconcilable with `List<Integer>`, even though the
  method's whole purpose is to ignore type arguments.

* `ClassGraph#filterClasspathElementsByURL(Predicate<URL>)` never saw a classpath element
  with a `file:` or `jar:file:` URL, which is almost every classpath element there is: the
  filter only ran for elements whose URL had some other scheme. Classpath element paths are
  resolved before they are filtered, and resolving a path strips the `file:` or `jar:file:`
  prefix off it, so by the time the filter was applied there was no URL left to pass to it,
  and the element was accepted unconditionally. Every classpath element is now offered to
  the filter: one whose URL was stripped is passed the `file:` or `jar:file:` URL rebuilt
  from its resolved path.

* `ClassGraph#scanAsync(ExecutorService, int, Consumer<ScanResult>, Consumer<Throwable>)`
  did not call the failure handler if the scan failed before it started -- which is when a
  user-supplied classpath element filter runs, so a filter that threw was enough to trigger
  it. Only `InterruptedException`, `CancellationException` and `ExecutionException` were
  caught; anything else was thrown on the `ExecutorService`'s thread, where nothing was
  waiting for it, and the caller waited forever for a callback that never came. The failure
  handler is now called for anything thrown during the scan.

* The root path, `/`, was not recognized as an absolute path, because the check for a
  leading separator required a separator plus at least one more character. A classpath
  entry of `/` was therefore resolved against the current directory rather than being taken
  as it was written, and when there was no base path to resolve against, it came out as the
  empty string, which names no directory at all. The root path now resolves to itself. The
  same check gave a bare Windows drive designation (`C:`, or `/C:` as spelled in a URL) the
  same treatment; that is now absolute too, matching `C:\`, which already was.

* A `file:` URL with an empty authority — `file:///C:/a/b`, which is the spelling that
  `Path#toUri()` produces — resolved to `///C:/a/b` on Windows, which names neither a drive
  nor a network share, so the classpath element was unusable. The two slashes of the empty
  authority were read as the start of a UNC path. They are now dropped, and `file:///C:/a/b`
  resolves to `C:/a/b`. A real UNC path, whether spelled `file://server/share` with the
  server as the authority or `file:////server/share` with an empty one, still resolves to
  `//server/share`.

* A `file:` URL naming the local machine by its `localhost` authority —
  `file://localhost/tmp/a`, which RFC 8089 says means the same as `file:///tmp/a` — resolved
  to `/localhost/tmp/a` on Linux and macOS, naming a directory that does not exist, so the
  classpath element was silently dropped. The authority is now dropped, and the URL resolves
  to `/tmp/a`. On Windows it still resolves to the UNC path `//localhost/tmp/a`, which is
  what `Path#of(URI)` produces there.

* Every `!` in a resource or classpath element URL was treated as a nested jar separator: a
  `/` was inserted after it and the URL was given a `jar:` prefix. A jar inside a directory
  whose name contains a `!` therefore got a URL naming a different, nonexistent path — a
  resource in `/tmp/with!bang/x.jar` came back as
  `jar:file:///tmp/with!/bang/x.jar!/pkg/X.class`, and opening it threw
  `NoSuchFileException`. A `!` is now only treated as a separator when the path before it
  names an existing file, which is the same rule the scanner uses.

* A URL scheme containing a digit, such as `s3:` or `vfs2:`, was not recognized as a scheme,
  because the pattern that matches custom schemes allowed only letters, `+`, `-` and `.`.
  RFC 3986 allows digits after the first character. Such a URL silently lost one of the
  slashes after its scheme (`s3://bucket/key` became `s3:/bucket/key`), and the part after
  the scheme was treated as a relative path.

* Whitespace after a classpath separator hid the URL scheme of the element that followed
  it. A classpath entry is trimmed before it is used, so `/a/x.jar: http://domain/y.jar` is
  meant to name the same two entries as `/a/x.jar:http://domain/y.jar`, but the scheme was
  only recognized when it started immediately after the separator. The space made
  `http:` look like an ordinary colon, so the second entry was split in two, giving the two
  nonexistent classpath elements `http` and `//domain/y.jar`. Whitespace between a
  separator and a URL scheme is now skipped. This affects `java.class.path`, the
  `Class-Path:` manifest entry, and the `--module-path` and `--patch-module` commandline
  arguments, all of which are split the same way.

* `enableURLScheme()` matched the scheme of a classpath element case-sensitively against
  the schemes that had been enabled, even though URL schemes are case-insensitive and are
  stored lowercased. An element written `S3://bucket/x.jar` was therefore rejected with
  "Scanning of URL scheme "S3" has not been enabled", although `enableURLScheme("s3")` had
  been called. Through `ClassGraph` this was masked, because a classpath element goes
  through `java.net.URL`, which lowercases the scheme; it is directly reachable through
  `ArchiveReader#open(String)` in `classgraph-vfs`, which takes the path as given.

* `overrideClasspath()` given a single `Path` split it into its name elements, and treated
  each one as a separate classpath entry. A `Path` is an `Iterable` of its own name
  elements, so a lone `Path` argument binds to the `Iterable<?>` overload rather than to
  the `Object...` overload: `overrideClasspath(Path.of("/opt/lib/app.jar"))` looked for
  classpath entries named `opt`, `lib` and `app.jar`, each relative to the current
  directory, found none of them, and scanned nothing. A `Path` is now added as one
  classpath entry.

* Every classloader was placed in the classpath ahead of the classloaders it delegates to,
  whatever its real delegation order, so the classpath was reported in the reverse of the
  order that a parent-first classloader resolves classes in. Each of the classloader
  handlers already stated its container's delegation order, by placing itself either
  before or after it delegates to its parent -- the Tomcat handler even reads Tomcat's own
  `delegate` flag and branches on it -- but none of that had any effect, because a
  classloader was added to the order before its handler ran, and adding it a second time
  is a no-op. A classloader is now placed where its handler places it. For a plain
  `URLClassLoader` with a parent, for instance, the parent's classpath elements now come
  first, which is where the JVM resolves classes from. This affects the order of
  `ScanResult#getClasspath()` and friends, and which copy of a class defined in more than
  one classpath element is reported, in any setup where classloaders are nested. It does
  not change the order for the JDK's own classloaders in a normal application, since the
  classloaders that the application classloader delegates to contribute only system jars
  and modules, which are not scanned unless `enableSystemJarsAndModules()` is called.

* On Windows, the same jarfile reached through two different paths was opened twice, and
  reported under two different paths. ClassGraph canonicalizes every path it is given, so
  that a file has one identity no matter how it is reached, but it was doing so with two
  different APIs: `Path#toRealPath()` where a classpath element is normalized and where a
  resource's file identity is computed, and `File#getCanonicalFile()` where a jarfile is
  opened. The two agree on Linux and macOS, but on Windows `File#getCanonicalFile()`
  resolves neither directory symlinks and junctions nor 8.3 short names (e.g.
  `C:\Users\RUNNER~1` for `C:\Users\runneradmin`), so a path that one call site had already
  resolved was left unresolved by the other. This was reachable through a nested jar
  entry (`outer.jar!/inner.jar`), whose outer jar is a string rather than a `Path` and so
  never passes through the scanner's normalization at all. All three call sites now share
  one routine, which resolves the path fully; for a path that does not yet exist, it
  resolves the closest ancestor directory that does exist and appends the rest of the path
  to it, so a missing file gets the same path it would have had if it had been created.

* A classpath entry that reached a directory through a symlink followed by `".."` was
  scanned as the wrong directory, or was not scanned at all, on Linux and macOS. A `".."`
  in a classpath entry was resolved textually, by deleting the segment before it. That is
  what the Windows path APIs do, but not what the Linux and macOS filesystems do: there,
  after a symlinked directory, `".."` names the parent of the directory the symlink points
  at, not the parent of the symlink. Given `link -> real/other`, the classpath entry
  `link/../classes` names `real/classes` to the JVM's own classloader on Linux and macOS,
  but ClassGraph resolved it to `classes` beside the symlink, and scanned that directory
  if it happened to exist, or nothing at all if it did not. A `".."` in a classpath entry
  that names a file on disk is now left for the platform to resolve, so ClassGraph reaches
  the same directory as the classloader it is running beside, on each platform. (The two
  behaviours were confirmed by running `java -cp "link/../classes"` on all three.)
  Everything after a nested jar separator (`outer.jar!/...`) is still collapsed textually,
  since an archive has no symlinks and no filesystem to ask, and collapsing there is what
  stops an entry name from escaping the archive it is in. The `".."` clamping applied to a
  `Class-Path:` manifest entry is also unchanged.

* On Windows, a resource in a directory on a UNC network share could not be opened through
  the URL that `Resource#getURL()` returned. The URL for a directory classpath element came
  from `Path#toUri()`, which renders the UNC path `\\server\share\x` as
  `file://server/share/x`, putting the server in the URI authority; `java.net.URL` reads
  that back as the local path `\share\x`, so opening it either failed or, if a path of that
  name happened to exist locally, silently read the wrong file. The URL is now spelled
  `file:////server/share/x`, with an empty authority and the server in the path, which
  reads back as the UNC path it came from. Both spellings are permitted by RFC 8089
  appendix E.3.2, but only the second round-trips. A jar on a UNC share was not affected,
  and no path that is not a UNC path changes. Separately, the URL normalization that jar
  classpath elements go through collapsed every run of leading slashes after `file:` down
  to one, so a UNC jar path handed to it in the four-slash spelling came back naming a
  local path instead of the share; it now drops the two slashes that introduce the URI
  authority and leaves the rest of the path alone.

* A jarfile below a directory whose name contains a `'!'` was never matched by
  `acceptJars()` or `rejectJars()`, so with any accept criterion in place it was silently
  skipped -- even `acceptJars("*.jar")` matched nothing. Those criteria match the leafname
  of the jar, and the leafname ended at the first `'!'` in the path, which is only a
  nested jar separator if the path before it names a file. Where the classpath entry was
  in URL form (as it is for a classpath element found through a classloader), the search
  for the separator also ran before the `file:` prefix was stripped, and so took the rule
  used for remote URLs, where the filesystem cannot be consulted and the first `'!'` is
  assumed to be the separator. Both now find the separator the same way as the rest of the
  library: by testing whether the path before a `'!'` names a file on disk.

* Reading a deflated zip entry whose data has been truncated never reached the end of the
  entry. The "nowrap" inflater option needs one dummy byte at the end of the input, and
  that dummy byte was supplied afresh every time the inflater asked for more input after
  the entry's data had run out, rather than only once. Each dummy byte decodes as more
  deflate symbols, so the entry produced an endless supply of garbage bytes:
  `Resource#load()` ran until it died with `OutOfMemoryError: Required array size too
  large`, and reading the stream returned by `Resource#open()` never terminated at all.
  Such an entry now throws `EOFException` at the point where its data ends.

* The stream returned by `Resource#open()` for a deflated zip entry threw
  `IllegalArgumentException` from both `mark(int)` and `reset()`. `InputStream` requires
  `mark` to be a no-op when `markSupported()` is false, and requires `reset()` to throw
  `IOException`, which is what a caller written against `InputStream` will be catching.
  They now behave that way.

* A short read from a `FileChannel` was treated as a truncated file. `FileChannel#read` is
  not required to transfer the whole of the requested range in a single call, and a read
  from a network filesystem can be short, but every read of a fixed-size field took the
  first return at its word and threw `IOException: Premature EOF`. A read is now repeated
  until the requested number of bytes has been read or the end of the file is reached.

* A short read from an `InputStream` was treated the same way when a classfile was read
  from a module or from a directory, throwing `IOException: Buffer underflow` even though
  the whole of the classfile was available. `InputStream#read(byte[], int, int)` is not
  required to transfer the whole of the requested range in a single call, and both of the
  streams a classfile is read through are channel-backed and really can transfer less. A
  read is now repeated until the requested number of bytes has been read or the stream is
  exhausted.

* A few numbers in the verbose log were formatted in the JVM's default locale, although
  the log is meant to read the same way whatever that locale is -- which is why its
  timestamps and elapsed times already used a fixed locale. The one that showed up in the
  output was the total scan time, printed with the locale's decimal separator and digits:
  under `ar-EG` it read `Total time: ٠٫١١٣ sec`. The others were the strings the log sorts
  its entries by, which are never printed but are compared as text, so formatting them in
  a locale-specific digit set risked an ordering that depended on where the code was run.
  All of them now use the same fixed locale as the timestamps.

* A backslash in a `.dot` file was escaped as `&lsol;`, which is not an entity GraphViz
  knows -- and neither is `&bsol;`, the other name for it. GraphViz stops with
  `Error: undefined entity`, exits with an error status, and falls back to treating the
  whole label of the offending class as plain text, so that class is drawn as a box full
  of raw HTML markup instead of as a table. A backslash reaches the graph through the
  string values of annotation parameters and through field and method type signatures. It
  is now escaped as `&#x5C;`, the numeric character reference, which GraphViz does
  understand.

* `rejectClasspathElementsContainingResourcePath()` rejected only the matching resource,
  not the classpath element that contained it. The method is documented to stop the whole
  classpath element from being scanned, and originally did, but the element-level skip was
  lost in 4.8.150 (November 2022) while fixing an unrelated problem -- an I/O error while
  reading one directory entry used the same flag to abort the scan of the entire classpath
  element, and in removing that, the flag stopped being set for a rejected resource path as
  well. What replaced it tested the classpath element's own absolute path against the
  reject criteria, which can never match, because those criteria are matched against
  relative resource paths. So from then on, a rejected element was scanned in full apart
  from the one resource that matched, and it stayed in `ScanResult#getClasspathURIs()` and
  friends. Rejecting a resource path now excludes the whole classpath element again, as
  documented: scanning of that element stops at the rejected resource, and the element is
  left out of the scan result and out of the reported classpath. The accept side
  (`acceptClasspathElementsContainingResourcePath()`) was unaffected, and is unchanged.

* `enableMultiReleaseVersions()` permanently switched off runtime-invisible (`CLASS`
  retention) annotations. The method turns off the parts of a scan that do not make sense
  when every version of a multi-release class is scanned, annotation info among them, and
  it did that partly by setting the internal flag that suppresses runtime-invisible
  annotations. That flag is only read when annotation info is enabled, so it had no effect
  on the scan the method was configuring, but it survived a later
  `enableAnnotationInfo()` or `enableAllInfo()` on the same `ClassGraph` instance: a scan
  configured as `enableMultiReleaseVersions().enableAllInfo()` silently returned no
  `CLASS`-retention annotations. The flag is no longer set.

* `MethodInfoList#getSingleMethod(String)` named the wrong class in the exception it
  throws when the list holds more than one method with the requested name. It built the
  message from the first element of the list, which need not be one of the methods that
  the name matched, so for a subclass' method list the message reported the subclass
  although the overloads were declared by the superclass. It now names the class that
  declares the overloads. The method also threw `NullPointerException` rather than
  `IllegalArgumentException` if that first element had no `ClassInfo`.

* The modules of the JDK's own runtime image were added as classpath elements, one
  `jrt:/<module>` element per module, whenever the classpath was recovered from a
  classloader that ClassGraph does not recognize and whose parent is null. When such a
  classloader reports nothing about its own classpath, it is asked for the resources that
  sit in the root of a classpath element, and the resource path is stripped from the URLs
  it returns. Resources that the classloader serves only because it delegates to its parent
  are skipped, but the bootstrap classloader is reached by delegating to a *null* parent,
  and its resources were not enumerated at all -- so the bootstrap classloader's copy of
  `module-info.class`, which it serves once per module of the runtime image, was never
  recognized as coming from the parent. Those elements are now filtered out, as they always
  were for a classloader with a non-null parent. On 5.x this is visible through
  `ClasspathFinder`, which is public API of `classgraph-classpath`.

Two further bugs found during the port were only reachable through the JSON
serialization API, which 5.x removes (`AnnotationParameterValue#toString()` threw
`NullPointerException` for a null parameter value, and `ScanResult#fromJSON(String)` did
not register the root object under its JSON id, so a reference back to the root was not
restored). Both are fixed on the 4.x branch.
