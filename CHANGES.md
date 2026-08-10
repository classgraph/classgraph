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
| `FieldInfo#getModifierStr` | `FieldInfo#getModifiersString` |
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
`hideFieldTypeDependencyEdges()`, `hideMethods()`, `hideMethodTypeDependencyEdges()`,
`hideAnnotations()` and `hideAnnotationDependencyEdges()` (all shown by default, subject to
the corresponding `ClassGraph#enable*Info()` call having been made before scanning),
`useFullyQualifiedNames()` (simple names by default), and `includeExternalClasses()` /
`excludeExternalClasses()` (by default the inter-class dependency graph follows the scan's
own `ClassGraph#enableExternalClasses()` setting). Every option is read by
`generateGraphVizDotFile`; the inter-class dependency graph reads only the layout size and
the external-class setting.

The three pairs of options are now symmetrical: `hideFields()`, `hideMethods()` and
`hideAnnotations()` each hide something inside the class boxes, and
`hideFieldTypeDependencyEdges()`, `hideMethodTypeDependencyEdges()` and
`hideAnnotationDependencyEdges()` each hide the corresponding edges between the boxes. In
4.x the single `showAnnotations` flag hid only the annotation edges, and there was no way
to leave the annotations out of the class boxes; the annotations on a class, and on its
fields, its methods and their parameters, are now hidden by `hideAnnotations()`, so code
that passed `showAnnotations = false` to hide the edges needs
`hideAnnotationDependencyEdges()` instead.

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

Load classes yourself instead, with the classloader that the class was found under, which
`ClassInfo` now reports:

```java
ClassInfo classInfo = scanResult.getClassInfo("com.xyz.Widget");
Class<?> cls = Class.forName(classInfo.getName(), /* initialize = */ false, classInfo.getClassLoader());
```

`ClassInfo#getClassLoader()` is new in 5.x. It returns the classloader that the classfile
was found under during the scan, or null if the class was never scanned (e.g. a superclass
outside the accepted packages). Reflection then gives you methods, fields, constructors,
enum constants and annotation instances directly, from the JDK, with the JDK's semantics.

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
| `ModuleRef#getLocationStr()` | `ModuleRef#getLocationString()` |

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
| `ModuleRef#getLocation()` | `ModuleRef#getLocationURI()` |
| `ModuleInfo#getLocation()` | `ModuleInfo#getLocationURI()` |

Both time values were already in milliseconds since the epoch; the names now say so, as
`FastZipEntry#getLastModifiedTimeMillis()` and `Resource#getLastModifiedMillis()`'s own
Javadoc already did. The value returned by `Resource#getLastModifiedMillis()` is 0L when
the last modified time is unknown, as before.

The two `getLocation()` methods return a `URI`, and each sits next to a
`getLocationString()` and (on `ModuleRef`) a `getLocationFile()` that say in their names
what they return. `getLocationURI()` completes that set.

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
  * `ClassInfoList#generateGraphVizDotFile*` and `#writeGraphVizDotFile*` on an empty list;
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
  ClassGraph's parsed module path.
* `ClassGraph.CIRCUMVENT_ENCAPSULATION` was a `public static` field holding an enum value.
  It is now a private `volatile` field behind
  `ClassGraph#getCircumventEncapsulationMethod()` and
  `ClassGraph#setCircumventEncapsulationMethod(CircumventEncapsulationMethod)`.
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
* `ClassInfoList#writeGraphVizDotFile(File)`, `#writeGraphVizDotFile(File,
  GraphVizDotFileOptions)`, `#writeGraphVizDotFileFromInterClassDependencies(File)` and
  `#writeGraphVizDotFileFromInterClassDependencies(File, GraphVizDotFileOptions)` return the
  `ClassInfoList`.

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

* **`enableMemoryMapping()` now loads the classes needed to release memory-mapped
  buffers, rather than the `ClassGraph` constructor doing it.** Releasing a mapped buffer
  when a `ScanResult` is closed needs classes that ClassGraph defines lazily, and closing
  can happen long after the scan, by which time the classloader that loaded ClassGraph
  may no longer be able to define anything — in a container that has already torn down
  the enclosing classloader, closing would fail with `NoClassDefFoundError`. Loading them
  ahead of time avoids that. 4.x did this work in the `ClassGraph` constructor, so every
  user paid for it whether or not they memory-mapped anything: on a JDK with
  `java.lang.foreign` it loaded roughly 35 further classes on the first `new ClassGraph()`.
  Since memory mapping is the only thing that makes ClassGraph allocate such buffers, and
  it is opt-in, the work has moved to `enableMemoryMapping()`. A program that does not
  call that method no longer loads any of those classes. One further consequence: on JDK
  17 to 21, where the buffer cleaner is reached reflectively, a security manager that
  denies the reflective access made the `ClassGraph` constructor throw; now only
  `enableMemoryMapping()` can throw for that reason.

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
    backslash itself, `\t`, `\b`, `\f`, control characters and characters outside the
    Latin-1 range — rather than just the quote characters, `\n` and `\r`.

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

## Bug fixes

Bugs found during the port. Each of these is a pre-existing bug in ClassGraph 4.x, and
is fixed on the 4.x branch as well.

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
  `getClasspathElementFile()` and `getModuleRef()`, already threw `IllegalStateException`
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

* A URL scheme containing a digit, such as `s3:` or `vfs2:`, was not recognized as a scheme,
  because the pattern that matches custom schemes allowed only letters, `+`, `-` and `.`.
  RFC 3986 allows digits after the first character. Such a URL silently lost one of the
  slashes after its scheme (`s3://bucket/key` became `s3:/bucket/key`), and the part after
  the scheme was treated as a relative path.

Two further bugs found during the port were only reachable through the JSON
serialization API, which 5.x removes (`AnnotationParameterValue#toString()` threw
`NullPointerException` for a null parameter value, and `ScanResult#fromJSON(String)` did
not register the root object under its JSON id, so a reference back to the root was not
restored). Both are fixed on the 4.x branch.
