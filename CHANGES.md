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
| `ResourceList#forEachByteArray(ByteArrayConsumer)` | `ResourceList#forEachByteArrayThrowingIOException` |
| `ResourceList#forEachByteArray(ByteArrayConsumer, boolean)` | `ResourceList#forEachByteArrayIgnoringIOException` or `#forEachByteArrayThrowingIOException` |
| `ResourceList#forEachInputStream(InputStreamConsumer)` | `ResourceList#forEachInputStreamThrowingIOException` |
| `ResourceList#forEachInputStream(InputStreamConsumer, boolean)` | `ResourceList#forEachInputStreamIgnoringIOException` or `#forEachInputStreamThrowingIOException` |
| `ResourceList#forEachByteBuffer(ByteBufferConsumer)` | `ResourceList#forEachByteBufferThrowingIOException` |
| `ResourceList#forEachByteBuffer(ByteBufferConsumer, boolean)` | `ResourceList#forEachByteBufferIgnoringIOException` or `#forEachByteBufferThrowingIOException` |

The `ResourceList#forEach*` replacements are not quite a rename: the removed overloads
wrapped an `IOException` in an `IllegalArgumentException`, whereas
`forEach*ThrowingIOException` throws the `IOException` itself, so the consumer is a
`*ThrowsIOException` functional interface and the caller has to handle or declare
`IOException`.

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

## Bug fixes

Bugs found during the port. Each of these is a pre-existing bug in ClassGraph 4.x, and
is fixed on the 4.x branch as well.

* The month of an MS-DOS zip entry timestamp was read from three bits rather than
  four, so a zip entry that carries only an MS-DOS timestamp (i.e. no extended
  timestamp extra field) reported the wrong last modified time if it was modified
  in August or later: September to December were read as January to April, and
  August was read as December of the previous year. This affects
  `Resource#getLastModified()`.
Two further bugs found during the port were only reachable through the JSON
serialization API, which 5.x removes (`AnnotationParameterValue#toString()` threw
`NullPointerException` for a null parameter value, and `ScanResult#fromJSON(String)` did
not register the root object under its JSON id, so a reference back to the root was not
restored). Both are fixed on the 4.x branch.
