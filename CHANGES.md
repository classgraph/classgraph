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
* `AnnotationParameterValue#toString()`, and `AnnotationInfo#toString()` through it,
  threw `NullPointerException` rather than printing `null` when an annotation parameter
  value was null. A classfile cannot express a null annotation parameter value, so this
  was only reachable via a `ScanResult` deserialized from JSON that contained one.
* When deserializing a `ScanResult` from JSON (`ScanResult#fromJSON(String)`), the
  test that registers the root object under its JSON id was inverted, so the root
  object was not registered. A serialized object graph in which a nested object
  refers back to the root would not have had that reference restored.
