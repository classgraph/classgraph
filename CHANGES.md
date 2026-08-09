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
| `ClassGraph#whitelistLibOrExtJars` | `ClassGraph#acceptLibOrExtJars` |
| `ClassGraph#whitelistModules` | `ClassGraph#acceptModules` |
| `ClassGraph#whitelistClasspathElementsContainingResourcePath` | `ClassGraph#acceptClasspathElementsContainingResourcePath` |
| `ClassGraph#blacklistPackages` | `ClassGraph#rejectPackages` |
| `ClassGraph#blacklistPaths` | `ClassGraph#rejectPaths` |
| `ClassGraph#blacklistClasses` | `ClassGraph#rejectClasses` |
| `ClassGraph#blacklistJars` | `ClassGraph#rejectJars` |
| `ClassGraph#blacklistLibOrExtJars` | `ClassGraph#rejectLibOrExtJars` |
| `ClassGraph#blacklistModules` | `ClassGraph#rejectModules` |
| `ClassGraph#blacklistClasspathElementsContainingResourcePath` | `ClassGraph#rejectClasspathElementsContainingResourcePath` |
| `ScanResult#getResourcesWithPathIgnoringWhitelist` | `ScanResult#getResourcesWithPathIgnoringAccept` |
| `FieldInfo#getModifierStr` | `FieldInfo#getModifiersStr` |
| `ClassInfoList#generateGraphVizDotFileFromClassDependencies` | `ClassInfoList#generateGraphVizDotFileFromInterClassDependencies` |
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

### The GraphViz .dot file generators now take an options object

`ClassInfoList` had eight `generateGraphVizDotFile*` overloads, and the widest of them took
two floats followed by six booleans:

```java
classInfoList.generateGraphVizDotFile(12, 8, false, true, false, true, true);
```

The options are now carried by `GraphVizDotFileOptions`, whose no-argument constructor
holds the defaults, and whose methods each switch one option away from its default:

```java
classInfoList.generateGraphVizDotFile(
        new GraphVizDotFileOptions().layoutSize(12, 8).hideFields().hideMethods());
```

| ClassGraph 4.x | ClassGraph 5.x |
| --- | --- |
| `generateGraphVizDotFile()` | `generateGraphVizDotFile()` |
| `generateGraphVizDotFile(float, float)` | `generateGraphVizDotFile(options)` |
| `generateGraphVizDotFile(float, float, boolean × 5)` | `generateGraphVizDotFile(options)` |
| `generateGraphVizDotFile(float, float, boolean × 6)` | `generateGraphVizDotFile(options)` |
| `generateGraphVizDotFile(File)` | `writeGraphVizDotFile(File)` |
| `generateGraphVizDotFileFromInterClassDependencies()` | `generateGraphVizDotFileFromInterClassDependencies()` |
| `generateGraphVizDotFileFromInterClassDependencies(float, float)` | `generateGraphVizDotFileFromInterClassDependencies(options)` |
| `generateGraphVizDotFileFromInterClassDependencies(float, float, boolean)` | `generateGraphVizDotFileFromInterClassDependencies(options)` |

The options and their defaults are `layoutSize(10.5f, 8.0f)`, `hideFields()`,
`hideFieldTypeDependencyEdges()`, `hideMethods()`, `hideMethodTypeDependencyEdges()` and
`hideAnnotations()` (all shown by default, subject to the corresponding
`ClassGraph#enable*Info()` call having been made before scanning),
`useFullyQualifiedNames()` (simple names by default), and `includeExternalClasses()` /
`excludeExternalClasses()` (by default the inter-class dependency graph follows the scan's
own `ClassGraph#enableExternalClasses()` setting). Every option is read by
`generateGraphVizDotFile`; the inter-class dependency graph reads only the layout size and
the external-class setting.

Writing the graph to a file is now `writeGraphVizDotFile` rather than a `File`-typed
overload of `generateGraphVizDotFile`, so the name says which one returns the .dot file
contents and which one saves them, and the inter-class dependency graph has a matching
`writeGraphVizDotFileFromInterClassDependencies`.

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
`ScanResult` written by a different version of ClassGraph failed. If you need to persist
scan results, serialize the specific facts your application needs with a real JSON library
(Jackson, Gson, `jackson-jr`, …), from your own value types.

Removing it also removes the no-argument constructor that every scan result class
(`ClassInfo`, `FieldInfo`, `MethodInfo`, `AnnotationInfo`, `PackageInfo`, `ModuleInfo` and
the rest) carried purely for the deserializer to call, which left instances
half-initialized until the deserializer filled in their fields. Those classes are only
ever constructed by a scan, so the constructors were not usable from outside ClassGraph
anyway.

### Module APIs are now strongly typed

In 4.x, every method that took or returned a module-system object used `Object` as the
type, so that the code would still compile on JDK 7 and JDK 8, and the objects were
manipulated by reflection. These now use the real types from `java.lang.module`:

| ClassGraph 4.x | ClassGraph 5.x |
| --- | --- |
| `ClassGraph#addModuleLayer(Object)` | `ClassGraph#addModuleLayer(ModuleLayer)` |
| `ClassGraph#overrideModuleLayers(Object...)` | `ClassGraph#overrideModuleLayers(ModuleLayer...)` |
| `ModuleRef#getReference()` returns `Object` | returns `java.lang.module.ModuleReference` |
| `ModuleRef#getLayer()` returns `Object` | returns `java.lang.ModuleLayer` |
| `ModuleRef#getDescriptor()` returns `Object` | returns `java.lang.module.ModuleDescriptor` |
| `ModuleRef(Object, Object, ReflectionUtils)` | `ModuleRef(ModuleReference, ModuleLayer)` |

Callers that already passed a `ModuleLayer` need no source change for the `ClassGraph`
methods, but the calls do have to be recompiled. Callers of the `ModuleRef` getters can
drop their casts. The `ModuleRef` constructor no longer takes a `ReflectionUtils`
parameter, since no reflection is involved any more.

### `ModuleReaderProxy` has been removed (#860)

`ModuleReaderProxy` existed only to wrap `java.lang.module.ModuleReader`, which did not
exist on JDK 8. The class has been deleted, and `ModuleRef#open()` now returns a
`java.lang.module.ModuleReader` directly:

```java
// ClassGraph 4.x
try (ModuleReaderProxy moduleReader = moduleRef.open()) {
    List<String> paths = moduleReader.list();
    ByteBuffer content = moduleReader.read(path);
}

// ClassGraph 5.x
try (ModuleReader moduleReader = moduleRef.open()) {
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

### Reduced visibility

Several members of the exported `io.github.classgraph` package were `public` or
`protected` even though their types or parameters are internal, so nothing outside
ClassGraph could usefully call or override them. They are now package-private, and the
compiler's `-Xlint:exports` check is enabled to keep it that way.

* `ScanResult#reflectionUtils` was `protected`, exposing the internal
  `nonapi.io.github.classgraph.reflection.ReflectionUtils` type to subclasses.
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
  `HierarchicalTypeSignature` were `public`, which is meaningless on an abstract class:
  they are now `protected`. Subclasses are unaffected.
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
| `ModuleRef(ModuleReference, ModuleLayer)` | `IllegalArgumentException` | `NullPointerException` |
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
| `ClassInfo#getInterfaces()` | `ClassInfo#getAllInterfaces()` | `ClassInfo#getDirectInterfaces()` |
| `ClassInfo#getClassesImplementing()` | `ClassInfo#getAllClassesImplementing()` | `ClassInfo#getDirectClassesImplementing()` |
| `ClassInfo#getSubinterfaces()` | `ClassInfo#getAllSubinterfaces()` | `ClassInfo#getDirectSubinterfaces()` |
| `ClassInfo#getAnnotations()` | `ClassInfo#getAllAnnotations()` | `ClassInfo#getDirectAnnotations()` |
| `ClassInfo#getAnnotationInfo()` | `ClassInfo#getAllAnnotationInfo()` | `ClassInfo#getDirectAnnotationInfo()` |
| `ClassInfo#getAnnotationInfo(Class \| String)` | `ClassInfo#getAllAnnotationInfo(Class \| String)` | `ClassInfo#getDirectAnnotationInfo(Class \| String)` |
| `ClassInfo#getAnnotationInfoRepeatable(Class \| String)` | `ClassInfo#getAllAnnotationInfoRepeatable(Class \| String)` | `ClassInfo#getDirectAnnotationInfoRepeatable(Class \| String)` |
| `MethodInfo` / `FieldInfo` `#getAnnotationInfo(...)` | `#getAllAnnotationInfo(...)` | `#getDirectAnnotationInfo(...)` |
| `MethodInfo` / `FieldInfo` `#getAnnotationInfoRepeatable(...)` | `#getAllAnnotationInfoRepeatable(...)` | `#getDirectAnnotationInfoRepeatable(...)` |
| `ScanResult#getSubclasses(Class \| String)` | `ScanResult#getAllSubclasses(...)` | `ScanResult#getDirectSubclasses(...)` |
| `ScanResult#getSuperclasses(Class \| String)` | `ScanResult#getAllSuperclasses(...)` | `ScanResult#getSuperclass()` on the `ClassInfo` |
| `ScanResult#getInterfaces(Class \| String)` | `ScanResult#getAllInterfaces(...)` | `ScanResult#getDirectInterfaces(...)` |
| `ScanResult#getClassesImplementing(Class \| String)` | `ScanResult#getAllClassesImplementing(...)` | `ScanResult#getDirectClassesImplementing(...)` |
| `ScanResult#getSubinterfaces(Class \| String)` | `ScanResult#getAllSubinterfaces(...)` | `ScanResult#getDirectSubinterfaces(...)` |
| `ScanResult#getAnnotationsOnClass(String)` | `ScanResult#getAllAnnotationsOnClass(...)` | `ScanResult#getDirectAnnotationsOnClass(...)` |

For the annotation queries, "all" means the annotations directly present on the class or
member plus the meta-annotations reachable from them (and, for a class, any `@Inherited`
annotation on a superclass) — exactly what 4.x returned. `MethodParameterInfo`,
`PackageInfo` and `ModuleInfo` never expanded meta-annotations, so their
`getAnnotationInfo()` keeps its name.

`.directOnly()` still exists on `ClassInfoList` and `AnnotationInfoList`, so the 4.x
idiom keeps working; the `getDirect...` methods are just the direct way to ask.

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

## Bug fixes

Bugs found during the port. Each of these is a pre-existing bug in ClassGraph 4.x, and
is fixed on the 4.x branch as well.

* The month of an MS-DOS zip entry timestamp was read from three bits rather than
  four, so a zip entry that carries only an MS-DOS timestamp (i.e. no extended
  timestamp extra field) reported the wrong last modified time if it was modified
  in August or later: September to December were read as January to April, and
  August was read as December of the previous year. This affects
  `Resource#getLastModified()`.

* `ClassInfo#getTypeDescriptor()` synthesizes a type descriptor for a class that has no
  generic type signature, standing in for the classfile's own `super_class` and
  `interfaces[]` entries so that type annotations on the `extends` and `implements`
  clauses have somewhere to attach. It was building that descriptor from the class'
  *transitive* interfaces, so the descriptor claimed the class directly implemented every
  interface reachable from it, including the superinterfaces of its own interfaces and the
  interfaces of its superclasses. It now uses the directly implemented interfaces, in
  classfile order. This affects `ClassInfo#getTypeDescriptor()` and
  `ClassInfo#getTypeSignatureOrTypeDescriptor()` for non-generic classes.

Two further bugs found during the port were only reachable through the JSON
serialization API, which 5.x removes (`AnnotationParameterValue#toString()` threw
`NullPointerException` for a null parameter value, and `ScanResult#fromJSON(String)` did
not register the root object under its JSON id, so a reference back to the root was not
restored). Both are fixed on the 4.x branch.
