# classgraph-viz

Turns the class graph found by a [ClassGraph](../classgraph) scan into a
[GraphViz](https://graphviz.org/) `.dot` file, for layout and rendering.

```xml
<dependency>
    <groupId>io.github.classgraph</groupId>
    <artifactId>classgraph-viz</artifactId>
    <version>X.Y.Z</version>
</dependency>
```

Module name: `io.github.classgraph.viz`. Requires JDK 17 or newer. Depends on
[`classgraph`](../classgraph), which is pulled in transitively. GraphViz itself is a separate
program: this library writes the `.dot` file, and `dot` renders it.

See the [GraphViz API](https://github.com/classgraph/classgraph/wiki/GraphViz-API) page for an
example of the output.

## The API

Two classes. `GraphVizDotFile` has four static entry points, and `GraphVizDotFileOptions` is the
chainable options object every one of them accepts (or defaults, if you leave it out):

| Method | Graphs |
| --- | --- |
| `GraphVizDotFile.generate(scanResult, classes[, options])` | the class graph, as a `String` |
| `GraphVizDotFile.write(scanResult, classes, file[, options])` | the class graph, to a file, in UTF-8 |
| `GraphVizDotFile.generateFromInterClassDependencies(scanResult, classes[, options])` | the dependency graph, as a `String` |
| `GraphVizDotFile.writeFromInterClassDependencies(scanResult, classes, file[, options])` | the dependency graph, to a file |

The class graph shows classes, interfaces and annotations, with their fields and methods, and the
edges between them: `extends`, `implements`, annotation, and field and method type dependencies.
The dependency graph shows only which class depends on which.

The two families have different prerequisites, and each throws `IllegalStateException` if its
prerequisite is missing: `generate`/`write` need `enableClassInfo()`, and the
`...InterClassDependencies` methods need `enableInterClassDependencies()`.

## Recipes

### Graph the classes in a package

```java
try (ScanResult scanResult = new ClassGraph().enableNonSystemModules().enableClasspath()
        .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
        .acceptPackages("com.xyz").scan()) {
    GraphVizDotFile.write(scanResult, scanResult.getAllClasses(), Path.of("classgraph.dot"));
}
```

Then render it:

```sh
dot -Tsvg classgraph.dot -o classgraph.svg
```

The graph draws fields, methods and annotations, and each of those needs its own switch, since no
configuration method turns on another one. `enableClassInfo()` alone produces a graph of just the
classes and the edges between them. Add `ignoreClassVisibility()`, `ignoreFieldVisibility()` and
`ignoreMethodVisibility()` to draw the non-public ones too.

### Graph a subset of the classes

The second argument is any `ClassInfoList`, so the graph can be narrowed to whatever a query
returns rather than everything that was scanned:

```java
try (ScanResult scanResult = new ClassGraph().enableNonSystemModules().enableClasspath()
        .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
        .acceptPackages("com.xyz").scan()) {
    ClassInfoList widgets = scanResult.getAllSubclasses("com.xyz.Widget");
    GraphVizDotFile.write(scanResult, widgets, Path.of("widgets.dot"));
}
```

### Graph inter-class dependencies

```java
try (ScanResult scanResult = new ClassGraph().enableNonSystemModules().enableClasspath()
        .enableClassInfo().enableInterClassDependencies().acceptPackages("com.xyz").scan()) {
    GraphVizDotFile.writeFromInterClassDependencies(
            scanResult, scanResult.getAllClasses(), Path.of("dependencies.dot"));
}
```

By default this graph includes external classes if the scan enabled them, and excludes them
otherwise; `includeExternalClasses()` and `excludeExternalClasses()` decide it explicitly.

### Cut the graph down

A graph of more than a few dozen classes is unreadable at any layout size, so most real use is
about leaving things out:

```java
GraphVizDotFileOptions options = new GraphVizDotFileOptions()
        .setLayoutSize(12.0f, 8.0f)   // in inches, as GraphViz measures it
        .hideFields()                 // no field rows in the class boxes
        .hideMethods()                // no method rows either
        .hideAnnotations()            // no annotation nodes
        .useFullyQualifiedNames();    // com.xyz.Widget rather than Widget

try (ScanResult scanResult = new ClassGraph().enableNonSystemModules().enableClasspath()
        .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
        .acceptPackages("com.xyz").scan()) {
    GraphVizDotFile.write(scanResult, scanResult.getAllClasses(), Path.of("overview.dot"), options);
}
```

The `hide...DependencyEdges()` options are the finer-grained version: `hideFieldTypeDependencyEdges()`,
`hideMethodTypeDependencyEdges()` and `hideAnnotationDependencyEdges()` keep the fields, methods or
annotations visible in the boxes but drop the edges they would otherwise draw, which is usually
where the clutter comes from.

Every option can be set either way round, so an options object handed on from elsewhere can be moved
to whichever setting is wanted: each `hide...()` has a matching `show...()`, and
`useFullyQualifiedNames()` has `useSimpleNames()`.

### Get the .dot source instead of a file

```java
try (ScanResult scanResult = new ClassGraph().enableNonSystemModules().enableClasspath()
        .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
        .acceptPackages("com.xyz").scan()) {
    String dot = GraphVizDotFile.generate(scanResult, scanResult.getAllClasses());
    // ... feed to a GraphViz binding, or to `dot` on stdin
}
```

## License

MIT. See [LICENSE-ClassGraph.txt](../LICENSE-ClassGraph.txt).
