FastClasspathScanner
====================

FastClasspathScanner is an uber-fast, ultra-lightweight classpath scanner for Java, Scala and other JVM languages. Users have reported an order of magnitude speedup when switching to FastClasspathScanner from other classpath scanning methods such as [Reflections](https://github.com/ronmamo/reflections).

**UPDATE:** FastClasspathScanner now supports [multithreaded classpath scanning](#parallel-classpath-scanning) by default for extra speed.

**What is classpath scanning?** Classpath scanning involves scanning directories and jar/zip files on the classpath to find files (especially classfiles) that meet certain criteria. In many ways, classpath scanning offers the *inverse of the Java reflection API:*

* The Java reflection API can tell you the superclass of a given class, but classpath scanning can find all classes that extend a given superclass.
* The Java reflection API can give you the list of annotations on a given class, but classpath scanning can find all classes that are annotated with a given annotation.
* etc. (Many other classpath scanning objectives are listed below.)

Classpath scanning can also be used to produce a visualization of the class graph (the "class hierarchy"). Class graph visualizations can be useful in understanding complex codebases, and for finding architectural design issues (e.g. in the graph visualization below, you can see that `ShapeImpl` only needs to implement `Shape`, not `Renderable`, because `Renderable` is already a superinterface of `Shape`). [[see graph legend here]](#12-generate-a-graphviz-dot-file-from-the-classgraph)<a name="visualization"></a>

<p align="center">
  <img src="https://github.com/lukehutch/fast-classpath-scanner/blob/master/src/test/java/com/xyz/classgraph-fig.png" alt="Class graph visualization"/>
</p>

**FastClasspathScanner is able to:**

1. [find classes that subclass a given class](#1-matching-the-subclasses-or-finding-the-superclasses-of-a-class) or one of its subclasses;
2. [find interfaces that extend a given interface](#2-matching-the-subinterfaces-or-finding-the-superinterfaces-of-an-interface) or one of its subinterfaces;
3. [find classes that implement an interface](#3-matching-the-classes-that-implement-an-interface) or one of its subinterfaces, or whose superclasses implement the interface or one of its subinterfaces;
4. [find classes that have a specific class annotation or meta-annotation](#4-matching-classes-with-a-specific-annotation-or-meta-annotation);
5. find the constant literal initializer value in a classfile's constant pool for a [specified static final field](#5-fetching-the-constant-initializer-values-of-static-final-fields);
6. [find all classes that contain a field of a given type](#6-find-all-classes-that-contain-a-field-of-a-given-type) (including identifying fields based on array element type and generic parameter type); 
7. find files (even non-classfiles) anywhere on the classpath that have a [path that matches a given string or regular expression](#7-finding-files-even-non-classfiles-anywhere-on-the-classpath-whose-path-matches-a-given-string-or-regular-expression);
8. perform the actual [classpath scan](#8-performing-the-actual-scan);
9. [detect changes](#9-detecting-changes-to-classpath-contents-after-the-scan) to the files within the classpath since the first time the classpath was scanned;
10. return a list of the [names of all classes, interfaces and/or annotations on the classpath](#10-get-a-list-of-all-whitelisted-and-non-blacklisted-classes-interfaces-or-annotations-on-the-classpath) (after whitelist and blacklist filtering);
11. return a list of [all directories and files on the classpath](#11-get-all-unique-directories-and-files-on-the-classpath) (i.e. all classpath elements) as a list of File objects, with the list deduplicated and filtered to include only classpath directories and files that actually exist, saving you from the complexities of working with the classpath and classloaders; and
12. [generate a GraphViz .dot file](#12-generate-a-graphviz-dot-file-from-the-classgraph) from the class graph for visualization purposes, as shown above. A class graph visualizatoin depicts connections between classes, interfaces, annotations and meta-annotations, and connections between classes and the types of their fields.

**Benefits of FastClasspathScanner compared to other classpath scanning methods:**

1. FastClasspathScanner parses the classfile binary format directly, instead of using reflection, which makes scanning particularly fast. (Reflection causes the classloader to load each class, which can take an order of magnitude more time than parsing the classfile directly, and can lead to unexpected behavior due to static initializer blocks of classes being called on class load.)
2. FastClasspathScanner appears to be the only classpath scanning library that supports multithreaded scanning (overlapping disk/SSD reading, jar decompression, and classfile binary format parsing on different threads). Disk/SSD bandwidth consequently becomes the bottleneck, making FastClasspathScanner the fastest possible solution for classpath scanning.
2. FastClasspathScanner is extremely lightweight, as it does not depend on any classfile/bytecode parsing or manipulation libraries like [Javassist](http://jboss-javassist.github.io/javassist/) or [ObjectWeb ASM](http://asm.ow2.org/).
3. FastClasspathScanner handles many [diverse and complicated means](#classpath-mechanisms-handled-by-fastclasspathscanner) used to specify the classpath, and has a pluggable architecture for handling other classpath specification methods (in the general case, finding all classpath elements is not as simple as reading the `java.class.path` system property and/or getting the path URLs from the system `URLClassLoader`).
4. FastClasspathScanner has built-in support for generating GraphViz visualizations of the classgraph, as shown above.
5. FastClasspathScanner can find classes not just by annotation, but also by [meta-annotation](#4-matching-classes-with-a-specific-annotation-or-meta-annotation) (e.g. if annotation `A` annotates annotation `B`, and annotation `B` annotates class `C`, you can find class `C` by scanning for classes annotated by annotation `A`). This makes annotations more powerful, as they can be used as a hierarchy of inherited traits (similar to how interfaces work in Java). In the graph above, the class `Figure` has the annotation `@UIWidget`, and the annotation class `UIWidget` has the annotation `@UIElement`, so by transitivity, `Figure` also has the meta-annotation `@UIElement`. 
6. FastClasspathScanner can find all classes that have [fields of a given type](#6-find-all-classes-that-contain-a-field-of-a-given-type) (this feature is normally only found in an IDE, e.g. `References > Workspace` in Eclipse).

### Usage

There are two different mechanisms for using FastClasspathScanner. (The two mechanisms can be used together.)

**Mechanism 1:**

1. Create a FastClasspathScanner instance, [passing the constructor](#constructor) a whitelist of package prefixes to scan within (and/or a blacklist of package prefixes to ignore);
2. Add one or more [`MatchProcessor`](https://github.com/lukehutch/fast-classpath-scanner/tree/master/src/main/java/io/github/lukehutch/fastclasspathscanner/matchprocessor) instances to the FastClasspathScanner by calling a `FastClasspathScanner#match...()` method on the FastClasspathScanner instance;
3. Optionally call 'FastClasspathScanner#verbose()` to give verbose output for debugging purposes; and
4. Call `FastClasspathScanner#scan()` to start the scan.

This is the pattern shown in the following example. (Note: this example uses Java 8 lambda expressions to automatically construct the appropriate type of `MatchProcessor` corresponding to each `FastClasspathScanner#match...()` method, based on a `MatchProcessor` being a `FunctionalInterface`. See the [Tips](#tips) section for the Java 7 equivalent.)
 
```java
// Package prefixes to scan are listed in the constructor:
// -- "com.xyz.widget" is whitelisted for scanning;
// -- "com.xyz.widget.internal" is blacklisted (ignored), as it is prefixed by "-".
new FastClasspathScanner("com.xyz.widget", "-com.xyz.widget.internal")  
    .matchSubclassesOf(Widget.class,
        // c is a subclass of Widget or a descendant subclass.
        // This lambda expression is of type SubclassMatchProcessor.
        c -> System.out.println("Subclass of Widget: " + c.getName()))
    .matchSubinterfacesOf(Tweakable.class,
        // c is an interface that extends the interface Tweakable.
        // This lambda expression is of type SubinterfaceMatchProcessor.
        c -> System.out.println("Subinterface of Tweakable: " + c.getName()))
    .matchClassesImplementing(Changeable.class,
        // c is a class that implements the interface Changeable; more precisely,
        // c or one of its superclasses implements the interface Changeable, or
        // implements an interface that is a descendant of Changeable.
        // This lambda expression is of type InterfaceMatchProcessor.
        c -> System.out.println("Implements Changeable: " + c.getName()))
    .matchClassesWithAnnotation(BindTo.class,
        // c is a class annotated with BindTo.
        // This lambda expression is of type AnnotationMatchProcessor.
        c -> System.out.println("Has a BindTo class annotation: " + c.getName()))
    .matchStaticFinalFieldNames("com.xyz.widget.Widget.LOG_LEVEL",
        // The following method is called when any static final fields with
        // names matching one of the above fully-qualified names are
        // encountered, as long as those fields are initialized to constant
        // values. The value returned is the value in the classfile, not the
        // value that would be returned by reflection, so this can be useful
        // in hot-swapping of changes.
        // This lambda expression is of type StaticFinalFieldMatchProcessor.
        (String className, String fieldName, Object fieldConstantValue) ->
            System.out.println("Static field " + fieldName + " in class "
            + className + " " + " currently has constant literal value "
            + fieldConstantValue + " in the classfile"))
    .matchFilenamePattern("^template/.*\\.html",
        // relativePath is the section of the matching path relative to the
        // classpath element it is contained in; fileContentBytes is the content
        // of the file.
        // This lambda expression is of type FileMatchContentProcessor.
        (relativePath, fileContentBytes) ->
            registerTemplate(relativePath, new String(fileContentBytes, "UTF-8")))
    // Optional, in case you want to debug any issues with scanning
    .verbose()
    // Actually perform the scan
    .scan();

// [...Some time later...]
// See if any timestamps on the classpath are more recent than the time of the
// previous scan. Much faster than standard classpath scanning, because
// only timestamps are checked, and jarfiles don't have to be opened.
boolean classpathContentsModified =
    fastClassPathScanner.classpathContentsModifiedSinceScan();
```

**Mechanism 2:** Construct a `FastClasspathScanner` instance, potentially without adding any MatchProcessors, then call `FastClasspathScanner#scan()` to scan the classpath. This will return a `ScanResult` object with additional `ScanResult#get...()` methods that will let you query for classes, interfaces and annotations of interest without actually calling the classloader on any matching classes.

The `ScanResult#getNamesOf...()` methods return sorted lists of strings, rather than lists of `Class<?>` references (because returning class references requires calling the classloader). Since scanning is done by reading the classfile directly, the classloader does not need to be called for these `ScanResult#get...()` methods to return their results. This can be useful if the static initializer code for matching classes would trigger unwanted side effects if run during a classpath scan.

An example of this usage pattern is:

```java
List<String> subclassesOfWidget = new FastClasspathScanner("com.xyz.widget")
    // No need to add any MatchProcessors, just create a new scanner and then call
    // .scan() to parse the class hierarchy of all classfiles on the classpath.
    // This will return an object of type ScanResult, which can be queried.
    .scan()
    // Get the names of all subclasses of Widget on the classpath from the
    // ScanResult (without calling the classloader on any matching classes):
    .getNamesOfSubclassesOf("com.xyz.widget.Widget");
```

Note that Mechanism 2 only works with class, interface and annotation matches; there are no corresponding `ScanResult#getNamesOf...()` methods for filename pattern or static field matches, since these methods are only looking at the DAG of whitelisted classes and interfaces encountered during the scan.

**Use with Java 7:** FastClasspathScanner needs to be built with JDK 8, since [`MatchProcessors`](https://github.com/lukehutch/fast-classpath-scanner/tree/master/src/main/java/io/github/lukehutch/fastclasspathscanner/matchprocessor) are declared with a `@FunctionalInterface` annotation, which does not exist in JDK 7. (There are no other Java 8 features in use in FastClasspathScanner currently.) If you need to build with JDK 1.7, you can always manually remove the `@FunctionalInterface` annotations from the MatchProcessors. However, the project can be compiled in Java 7 compatibility mode, which does not complain about these annotations, and can generate a jarfile that works with both Java 7 and Java 8. The jarfile available from [Maven Central](#downloading) is compatible with Java 7.

The usage examples shown above use lambda expressions (functional interfaces) from Java 8 in the Mechanism 1 examples for syntactic simplicity. The Java 7 equivalent is as follows (note that there is a different [`MatchProcessor`](https://github.com/lukehutch/fast-classpath-scanner/tree/master/src/main/java/io/github/lukehutch/fastclasspathscanner/matchprocessor) class corresponding to each `FastClasspathScanner#match...()` method, e.g. `FastClasspathScanner#matchSubclassesOf()` takes a `SubclassMatchProcessor`):

```java
new FastClasspathScanner("com.xyz.widget")  
    .matchSubclassesOf(Widget.class, new SubclassMatchProcessor<Widget>() {
        @Override
        public void processMatch(Class<? extends Widget> matchingClass) {
            System.out.println("Subclass of Widget: " + matchingClass))
        }
    })
    .scan();
```

Note that the first usage of Java 8 features like lambda expressions or Streams incurs a one-time startup penalty of 30-40ms (this startup cost is incurred by the JDK, not FastClasspathScanner).

**Protip: using Java 8 method references:** The `FastClasspathScanner#match...()` methods (e.g. `FastClasspathScanner#matchSubclassesOf()`) take a [`MatchProcessor`](https://github.com/lukehutch/fast-classpath-scanner/tree/master/src/main/java/io/github/lukehutch/fastclasspathscanner/matchprocessor) as one of their arguments, which are single-method interfaces annotated with `@FunctionalInterface`. FunctionalInterfaces are interchangeable as long as the number and types of arguments match. You may find it useful to use Java 8 method references in the place of MatchProcessors, e.g. if you have a variable `List<Class<? extends Widget>> matchingClasses`, its `.add()` method can be referenced using `matchingClasses::add`, which has a single parameter of type `Class<? extends Widget>`. This method reference is interchangeable with `SubclassMatchProcessor<T>::processMatch(Class<? extends T> matchingClass)` assuming you call the `FastClasspathScanner#matchSubclassesOf()` with `Widget.class` as the type parameter:

```java
List<Class<? extends Widget>> matchingClasses = new ArrayList<>();
new FastClasspathScanner("com.xyz.widget")
    .matchSubclassesOf(Widget.class, matchingClasses::add)  // Method ref for List.add()
    .scan();
```

### Classpath mechanisms handled by FastClasspathScanner

FastClasspathScanner handles a number of classpath specification mechanisms, including some non-standard ClassLoader implementations:
* The `java.class.path` system property, supporting specification of the classpath using the `-cp` JRE commandline switch.
* The standard Java `URLClassLoader`, and both standard and custom subclasses. (Some runtime environments override URLClassLoader for their own purposes, but do not set `java.class.path` -- FastClasspathScanner fetches classpath URLs from all visible URLClassLoaders.)
* [Class-Path references](https://docs.oracle.com/javase/tutorial/deployment/jar/downman.html) in a jarfile's `META-INF/MANIFEST.MF`, whereby jarfiles may add other external jarfiles to their own classpaths. FastClasspathScanner is able to determine the transitive closure of these references, breaking cycles if necessary.
* The JBoss/WildFly classloader.
* The WebLogic classloader.
* The OSGi Equinox classloader (e.g. for Eclipse PDE).
* Eventually, the Java 9 module system [work has not started on this yet -- patches are welcome].

[Note that if you have a custom classloader in your runtime that is not covered by one of the above cases, you can add your own [ClassLoaderHandler](https://github.com/lukehutch/fast-classpath-scanner/tree/master/src/main/java/io/github/lukehutch/fastclasspathscanner/classloaderhandler), which will be loaded from your own project's jarfile by FastClasspathScanner using the Java [ServiceLoader](https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html) framework, via an entry in [META-INF/services](https://github.com/lukehutch/fast-classpath-scanner/blob/master/src/main/resources/META-INF/services/io.github.lukehutch.fastclasspathscanner.classloaderhandler.ClassLoaderHandler).]

Scanning is supported on Linux and Windows. (Mac OS X should work, please test and let me know.) 

# API

Most of the methods in the API return `this` (of type `FastClasspathScanner`), so that you can use the [method chaining](http://en.wikipedia.org/wiki/Method_chaining) calling style, as shown in the example above.

Note that the `|` character is used below to compactly describe overloaded methods below, e.g. `getNamesOfSuperclassesOf(Class<?> subclass | String subclassName)`. 

## Constructor

You can pass a scanning specification to the constructor of `FastClasspathScanner` to describe what should be scanned. This prevents irrelevant classpath entries from being unecessarily scanned, which can be time-consuming. (Note that calling the constructor does not start the scan, you must separately call `FastClasspathScanner#scan()` to perform the actual scan.)

```java
// Constructor for FastClasspathScanner
public FastClasspathScanner(String... scanSpec)
```

The constructor accepts a list of whitelisted package prefixes / jar names to scan, as well as blacklisted packages/jars not to scan, where blacklisted entries are prefixed with the `'-'` character. For example:

**Default constructor**

* `new FastClasspathScanner()`: If you don't specify any whitelisted package prefixes, all jarfiles and all directories on the classpath will be scanned, with the exception of the JRE system jars and the `java` and `sun` packages, which are blacklisted by default for efficiency (i.e. `java.lang`, `java.util` etc. are never scanned). See below for how to override these default blacklists.

**Whitelisting packages**

* `new FastClasspathScanner("com.x")` limits scanning to the package `com.x` and its sub-packages in all jarfiles and all directory entries on the classpath.
  * **Semantics of whitelisting:** Superclasses, subclasses etc. that are in a package that is not whitelisted (or that is blacklisted) will not be returned by a query, but can be used to query. For example, consider a class `com.external.X` that is a superclass of `com.xyz.X`, with a whitelist scanSpec of `com.xyz`. Then `ScanResult#getNamesOfSuperclassesOf("com.xyz.X")` will return an empty result, but `ScanResult#getNamesOfSubclassesOf("com.external.X")` will return `["com.xyz.X"]`.

**Blacklisting packages**

* `new FastClasspathScanner("com.x", "-com.x.y")` limits scanning to `com.x` and all sub-packages *except* `com.x.y` in all jars and directories on the classpath.

**Whitelisting or blacklisting specific classes**

* `new FastClasspathScanner("com.x", "javax.persistence.Entity")` limits scanning to `com.x` but also whitelists a specific external class `javax.persistence.Entity`. This makes it possible to search for classes annotated with the otherwise-non-whitelisted annotation class `javax.persistence.Entity` -- [see below](#detecting-annotations-superclasses-and-implemented-interfaces-outside-of-whitelisted-packages) for more info.
* `new FastClasspathScanner("com.x", "-com.x.BadClass")` scans within `com.x`, but blacklists the class `com.x.BadClass`. Note that a capital letter after the final '.' indicates a whitelisted or blacklisted class, as opposed to a package.

**Limiting scanning to specific jars**

* `new FastClasspathScanner("com.x", "-com.x.y", "jar:deploy.jar", "jar:library-*.jar", "jar:otherlibrary-*")` limits scanning to `com.x` and all its sub-packages except `com.x.y`, but only looks in jars named `deploy.jar`, `library-*.jar` and `otherlibrary-*` on the classpath (globs containing `*` are supported, and the `.jar` extension will be matched by `*` if not specified, along with other extensions such as `.zip`). Note:
  1. Whitelisting one or more jar entries prevents non-jar entries (directories) on the classpath from being scanned.
  2. Only the leafname of a jarfile can be specified in a `jar:` or `-jar:` entry, so if there is a chance of conflict, make sure the jarfile's leaf name is unique.
* `new FastClasspathScanner("com.x", "jar:")` limits scanning to `com.x` and all sub-packages, but only looks in jarfiles on the classpath -- directories are not scanned. (i.e. `"jar:"` is a wildcard to indicate that all jars are whitelisted, and as in the example above, whitelisting jarfiles prevents non-jars (directories) from being scanned.)
  
**Blacklisting specific jars**
  
* `new FastClasspathScanner("com.x", "-jar:irrelevant.jar")` limits scanning to `com.x` and all sub-packages in all directories on the classpath, and in all jars except `irrelevant.jar`. (i.e. blacklisting a jarfile only excludes the specified jarfile, it doesn't prevent all directories from being scanned, as with whitelisting a jarfile.)
* `new FastClasspathScanner("com.x", "-jar:")` limits scanning to `com.x` and all sub-packages, but only looks in directories on the classpath -- jarfiles are not scanned. (i.e. `"-jar:"` is a wildcard to indicate that all jars are blacklisted.)

**Matching system classes / scanning system jars**

* `new FastClasspathScanner("!", "com.x")`: Adding `"!"` to the scanning specification overrides the blacklisting of the system packages (`java.*` and `sun.*`), meaning classes in those packages can be used as match criteria. You will need this option if, for example, you are trying to find all classes that implement `java.lang.Comparable`, and you get the error `java.lang.IllegalArgumentException: Can't scan for java.lang.Comparable, it is in a blacklisted system package`. Note that if you disable system package blacklisting, you can scan for non-system classes that refer to system classes even if system jars are still blacklisted (e.g. if you add `"!"` to the spec, you can search for classes that implement `java.lang.Comparable` even though `java.lang.Comparable` is in a system jar, so won't itself be scanned). Adding `"!"` to the spec may increase the time and memory required to scan, since there are many references to system classes by non-system classes. 
* `new FastClasspathScanner("!!", "com.x")`: Adding `"!!"` to the spec overrides the blacklisting of the JRE system jars (`rt.jar` etc.), meaning those jars will be scanned, and also overrides the blacklisting of system packages (`java.*` and `sun.*`). You will need this option if you want to look for system classes that match a given criterion. Adding `"!!"` to the spec will increase the time and memory required to scan, since the system jars are large.
  * `"!!"` also implies `"!"`.
  * Note that without `"!!'` for disabling system jar blacklisting, if you put custom classes into the `lib/ext` directory in your JRE folder (which is a valid but rare way of adding jarfiles to the classpath), those classes will not be scanned, by association with the JRE.

## Detecting annotations, superclasses and implemented interfaces outside of whitelisted packages

In general, FashClasspathScanner cannot find relationships between classes, interfaces and annotations unless the entire path of references between them falls within a whitelisted (and non-blacklisted) package. This is intentional, to avoid calling the classloader on classes that fall outside the whitelisted path (class loading is time consuming, and triggers a class' static initializer code to run, which may have unintended consequences).

However, as shown below, it is possible to match classes based on references to other "external" classes, defined as superclasses, implemented interfaces, superinterfaces and annotations/meta-annotations that are defined outside of the whitelisted packages but that are *directly* referred to by a class defined within a whitelisted package: an external class is a class that is *exactly one reference link away* from a class in a whitelisted package.

External classes are not whitelisted by default, so are not returned by `ScanResult#getNamesOf...()` methods. However, you may whitelist external class names (in addition to package names) in the scan spec passed to the constructor. The FastClasspathScanner constructor determines that a passed string is a class name and not a package name if the letter after the last `'.'` is in upper case, as per standard Java conventions for package and class names. This will cause the external class to be returned by `ScanResult#getNamesOf...()` methods.

```java
// Given a class com.xyz.MyEntity that is annotated with javax.persistence.Entity:

// Result: ["com.xyz.MyEntity"], because com.xyz.MyEntity is in the whitelisted path com.xyz
List<String> matches1 = new FastClasspathScanner("com.xyz").scan()
    .getNamesOfClassesWithAnnotation("javax.persistence.Entity");

// Result: [], because javax.persistence.Entity is not explicitly whitelisted, and is not defined
// in a whitelisted package
List<String> matches2 = new FastClasspathScanner("com.xyz").scan()
    .getNamesOfAllAnnotationClasses();

// Result: ["javax.persistence.Entity"], because javax.persistence.Entity is explicitly whitelisted
List<String> matches3 = new FastClasspathScanner("com.xyz", "javax.persistence.Entity").scan()
    .getNamesOfAllAnnotationClasses();
```   

### 1. Matching the subclasses (or finding the superclasses) of a class

FastClasspathScanner can find all classes on the classpath within whitelisted package prefixes that extend a given superclass.

*Important note:* the ability to detect that a class extends another depends upon the entire ancestral path between the two classes being within one of the whitelisted package prefixes. (However, [see above](#detecting-annotations-superclasses-and-implemented-interfaces-outside-of-whitelisted-packages) for info on "external" class references.)

You can scan for classes that extend a specified superclass by calling `FastClasspathScanner#matchSubclassesOf()` with a `SubclassMatchProcessor` parameter before calling `FastClasspathScanner#scan()`. This method will call the classloader on each matching class (using `Class.forName()`) so that a class reference can be passed into the match processor. There are also methods `ScanResult#getNamesOfSubclassesOf(String superclassName)` and `ScanResult#getNamesOfSubclassesOf(Class<?> superclass)` that can be called after `FastClasspathScanner#scan()` to find the names of the subclasses of a given class (whether or not a corresponding match processor was added to detect this) without calling the classloader.

Furthermore, the methods `ScanResult#getNamesOfSuperclassesOf(String subclassName)` and `ScanResult#getNamesOfSuperclassesOf(Class<?> subclass)` are able to return all superclasses of a given class after a call to `FastClasspathScanner#scan()`. (Note that there is not currently a SuperclassMatchProcessor or .matchSuperclassesOf().)

```java
// Mechanism 1: Attach a MatchProcessor before calling .scan():

@FunctionalInterface
interface SubclassMatchProcessor<T> {
    public void processMatch(Class<? extends T> matchingClass);
}

FastClasspathScanner FastClasspathScanner#matchSubclassesOf(Class<T> superclass,
    SubclassMatchProcessor<T> subclassMatchProcessor)

// Mechanism 2: Call one of the following after calling .scan():

List<String> ScanResult#getNamesOfSubclassesOf(
    Class<?> superclass | String superclassName)

List<String> ScanResult#getNamesOfSuperclassesOf(
    Class<?> subclass | String subclassName)
```

### 2. Matching the subinterfaces (or finding the superinterfaces) of an interface

FastClasspathScanner can find all interfaces on the classpath within whitelisted package prefixes that that extend a given interface or its subinterfaces.

*Important note:* The ability to detect that an interface extends another interface depends upon the entire ancestral path between the two interfaces being within one of the whitelisted package prefixes. (However, [see above](#detecting-annotations-superclasses-and-implemented-interfaces-outside-of-whitelisted-packages) for info on "external" class references.)

You can scan for interfaces that extend a specified superinterface by calling `FastClasspathScanner#matchSubinterfacesOf()` with a `SubinterfaceMatchProcessor` parameter before calling `FastClasspathScanner#scan()`. This method will call the classloader on each matching class (using `Class.forName()`) so that a class reference can be passed into the match processor. There are also methods `ScanResult#getNamesOfSubinterfacesOf(String ifaceName)` and `ScanResult#getNamesOfSubinterfacesOf(Class<?> iface)` that can be called after `FastClasspathScanner#scan()` to find the names of the subinterfaces of a given interface (whether or not a corresponding match processor was added to detect this) without calling the classloader.

Furthermore, the methods `ScanResult#getNamesOfSuperinterfacesOf(String ifaceName)` and `ScanResult#getNamesOfSuperinterfacesOf(Class<?> iface)` are able to return all superinterfaces of a given interface after a call to `FastClasspathScanner#scan()`. (Note that there is not currently a SuperinterfaceMatchProcessor or .matchSuperinterfacesOf().)

```java
// Mechanism 1: Attach a MatchProcessor before calling .scan():

@FunctionalInterface
interface SubinterfaceMatchProcessor<T> {
    public void processMatch(Class<? extends T> matchingInterface);
}

FastClasspathScanner FastClasspathScanner#matchSubinterfacesOf(
    Class<T> superInterface,
    SubinterfaceMatchProcessor<T> subinterfaceMatchProcessor)

// Mechanism 2: Call one of the following after calling .scan():

List<String> ScanResult#getNamesOfSubinterfacesOf(
    Class<?> superinterface | String superinterfaceName)

List<String> ScanResult#getNamesOfSuperinterfacesOf(
    Class<?> subinterface | String subinterfaceName)
```

### 3. Matching the classes that implement an interface

FastClasspathScanner can find all classes on the classpath within whitelisted package prefixes that that implement a given interface. The matching logic here is trickier than it would seem, because FastClassPathScanner also has to match classes whose superclasses implement the target interface, or classes that implement a sub-interface (descendant interface) of the target interface, or classes whose superclasses implement a sub-interface of the target interface.

*Important note:* The ability to detect that a class implements an interface depends upon the entire ancestral path between the class and the interface (and any relevant sub-interfaces or superclasses along the path between the two) being within one of the whitelisted package prefixes. (However, [see above](#detecting-annotations-superclasses-and-implemented-interfaces-outside-of-whitelisted-packages) for info on "external" class references.)

You can scan for classes that implement a specified interface by calling `FastClasspathScanner#matchClassesImplementing()` with a `InterfaceMatchProcessor` parameter before calling `FastClasspathScanner#scan()`. This method will call the classloader on each matching class (using `Class.forName()`) so that a class reference can be passed into the match processor. There are also methods `ScanResult#getNamesOfClassesImplementing(String ifaceName)` and `ScanResult#getNamesOfClassesImplementing(Class<?> iface)` that can be called after `FastClasspathScanner#scan()` to find the names of the classes implementing a given interface (whether or not a corresponding match processor was added to detect this) without calling the classloader.

N.B. There are also convenience methods for matching classes that implement *all of* a given list of annotations (an "and" operator). 

```java
// Mechanism 1: Attach a MatchProcessor before calling .scan():

@FunctionalInterface
interface InterfaceMatchProcessor<T> {
    public void processMatch(Class<? extends T> implementingClass);
}

FastClasspathScanner FastClasspathScanner#matchClassesImplementing(
    Class<T> implementedInterface,
    InterfaceMatchProcessor<T> interfaceMatchProcessor)

// Mechanism 2: Call one of the following after calling .scan():

List<String> ScanResult#getNamesOfClassesImplementing(
    Class<?> implementedInterface | String implementedInterfaceName)

List<String> ScanResult#getNamesOfClassesImplementingAllOf(
    Class<?>... implementedInterfaces | String... implementedInterfaceNames)
```

### 4. Matching classes with a specific annotation or meta-annotation

FastClassPathScanner can detect classes that have a specified annotation. This is the inverse of the Java reflection API: the Java reflection API allows you to find the annotations on a given class, but FastClasspathScanner allows you to find all classes that have a given annotation.

*Important note:* The ability to detect that an annotation annotates or meta-annotates a class depends upon the annotation and the class being within one of the whitelisted package prefixes. (However, [see above](#detecting-annotations-superclasses-and-implemented-interfaces-outside-of-whitelisted-packages) for info on "external" class references.)

FastClassPathScanner also allows you to detect **meta-annotations** (annotations that annotate annotations that annotate a class of interest). Java's reflection methods (e.g. `Class.getAnnotations()`) do not directly return meta-annotations, they only look one level back up the annotation graph. FastClasspathScanner follows the annotation graph, allowing you to scan for both annotations and meta-annotations using the same API. This allows for the use of multi-level annotations as a means of implementing "multiple inheritance" of annotated traits. (Compare with [@dblevins](https://github.com/dblevins)' [metatypes](https://github.com/dblevins/metatypes/).)

Consider this graph of classes (`A`, `B` and `C`) and annotations (`D`..`L`): [[see graph legend here]](#12-generate-a-graphviz-dot-file-from-the-classgraph)<a name="visualization"></a>

<p align="center">
  <img src="https://github.com/lukehutch/fast-classpath-scanner/blob/master/src/test/java/com/xyz/meta-annotation-fig.png" alt="Meta-annotation graph"/>
</p>

* Class `A` is annotated by `F` and meta-annotated by `J`.
* Class `B` is annonated or meta-annotated by all the depicted annotations except for `G` (since all annotations but `G` can be reached along a directed path of annotations from `B`)
* Class `C` is only annotated by `G`.
* Note that the annotation graph can contain cycles: here, `H` annotates `I` and `I` annotates `H`. These are handled appropriately by FastClasspathScanner by determining the transitive closure of the directed annotation graph.

You can scan for classes with a given annotation or meta-annotation by calling `FastClasspathScanner#matchClassesWithAnnotation()` with a `ClassAnnotationMatchProcessor` parameter before calling `FastClasspathScanner#scan()`, as shown below, or by calling `ScanResult#getNamesOfClassesWithAnnotation()` or similar methods after calling `FastClasspathScanner#scan()`.

```java
// Mechanism 1: Attach a MatchProcessor before calling .scan():

@FunctionalInterface
interface ClassAnnotationMatchProcessor {
    public void processMatch(Class<?> matchingClass);
}

FastClasspathScanner FastClasspathScanner#matchClassesWithAnnotation(
    Class<?> annotation,
    ClassAnnotationMatchProcessor classAnnotationMatchProcessor)

// Mechanism 2: Call one of the following after calling .scan():

// (a) Get names of classes that have the specified annotation(s)
// or meta-annotation(s). Only returns non-annotation classes
// (i.e. standard classes or interfaces with the annotation).

List<String> ScanResult#getNamesOfClassesWithAnnotation(
    Class<?> annotation | String annotationName)

List<String> ScanResult#getNamesOfClassesWithAnnotationsAllOf(
    Class<?>... annotations | String... annotationNames)

List<String> ScanResult#getNamesOfClassesWithAnnotationsAnyOf(
    Class<?>... annotations | String... annotationNames)

// (b) Get names of annotations that have the specified meta-annotation

List<String> ScanResult#getNamesOfAnnotationsWithMetaAnnotation(
    Class<?> metaAnnotation | String metaAnnotationName)

// (c) Get the annotations and meta-annotations on a class or interface,
// or the meta-annotations on an annotation. This is more powerful than
// Class.getAnnotations(), because it also returns meta-annotations.

List<String> ScanResult#getNamesOfAnnotationsOnClass(
    Class<?> classOrInterface | String classOrInterfaceName)

List<String> ScanResult#getNamesOfMetaAnnotationsOnAnnotation(
    Class<?> annotation | String annotationName)
```

Properties of the annotation scanning API:

1. There are convenience methods for matching classes that have **AnyOf** a given list of annotations/meta-annotations (an **OR** operator), and methods for matching classes that have **AllOf** a given list of annotations/meta-annotations (an **AND** operator). 
2. The method `getNamesOfClassesWithAnnotation()` (which maps from an annotation/meta-annotation to classes it annotates/meta-annotates) is the inverse of the method `getNamesOfAnnotationsOnClass()` (which maps from a class to annotations/meta-annotations on the class; this is related to `Class.getAnnotations()` in the Java reflections API, but it returns not just direct annotations on a class, but also meta-annotations that are in the transitive closure of the annotation graph, starting at the class of interest). Note that this method does not return annotations that are meta-annotated with the requested (meta-)annotation, it only returns standard classes or interfaces that have the requested (meta-)annotation.
3. The method `getNamesOfAnnotationsWithMetaAnnotation()` (which maps from meta-annotations to annotations they meta-annotate) is the inverse of the method `getNamesOfMetaAnnotationsOnAnnotation()` (which maps from annotations to the meta-annotations that annotate them; this also retuns the transitive closure of the annotation graph, starting at an annotation of interest).

Note that meta-annotations are inherited between annotations (a meta-meta-annotation is still a meta-annotation), but neither annotations nor meta-annotations are passed from annotated classes to the sub-classes of those annotated classes:

* If annotation `A` meta-annotates annotation `B`, and annotation `B` meta-annotates class `C`, then annotation `A` meta-annotates class `C`.
* However, as per the regular Java annotation system, if class `P` is a superclass of class `Q`, and class `P` has annotation `R`, subclass `Q` does *not* inherit the annotation `R` from its superclass (or any of `R`'s meta-annotations). 

### 5. Fetching the constant initializer values of static final fields

FastClassPathScanner is able to scan the classpath for matching fully-qualified static final fields, e.g. for the fully-qualified field name `com.xyz.Config.POLL_INTERVAL`, FastClassPathScanner will look in the class `com.xyz.Config` for the static final field `POLL_INTERVAL`, and if it is found, and if it has a constant literal initializer value, that value will be read directly from the classfile and passed into a provided `StaticFinalFieldMatchProcessor`.

Field values are obtained directly from the constant pool in a classfile, not from a loaded class using reflection. This allows you to detect changes to the classpath and then run another scan that picks up the new values of selected static constants without reloading the class. [(Class reloading is fraught with issues.)](http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html)

This can be useful in hot-swapping of changes to static constants in classfiles if the constant value is changed and the class is re-compiled while the code is running. (Neither the JVM nor the Eclipse debugger will hot-replace static constant initializer values if you change them while running code, so you can pick up changes this way instead). 

By default, this only matches static final fields that have public visibility. To override this (and allow the matching of private, protected and package-private static final fields), call `FastClasspathScanner#ignoreFieldVisibility()` before calling `FastClasspathScanner#scan()`. This may cause the scan to take longer and consume more memory. (By default, only public fields are scanned for efficiency reasons, and to conservatively respect the Java visibility rules.)

```java
// Only Mechanism 1 is applicable -- attach a MatchProcessor before calling .scan():

@FunctionalInterface
interface StaticFinalFieldMatchProcessor {
    public void processMatch(String className, String fieldName,
    Object fieldConstantValue);
}

FastClasspathScanner FastClasspathScanner#matchStaticFinalFieldNames(
    HashSet<String> fullyQualifiedStaticFinalFieldNames
    | String fullyQualifiedStaticFinalFieldName
    | String[] fullyQualifiedStaticFinalFieldNames,
    StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor)
```

*Note:* Only static final fields with constant-valued literals are matched, not fields with initializer values that are the result of an expression or reference, except for cases where the compiler is able to simplify an expression into a single constant at compiletime, [such as in the case of string concatenation](https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-5.html#jvms-5.1). The following are examples of constant static final fields:

```java
static final int w = 5;          // Literal ints, shorts, chars etc. are constant
static final String x = "a";     // Literal Strings are constant
static final String y = "a" + "b";  // Referentially equal to interned String "ab"
static final byte b = 0x7f;      // StaticFinalFieldMatchProcessor is passed boxed types (here Byte)
private static final int z = 1;  // Visibility is ignored; non-public constant fields also match 
```

whereas the following fields are non-constant, non-static and/or non-final, so these fields cannot be matched:

```java
static final Integer w = 5;         // Non-constant due to autoboxing
static final String y = "a" + w;    // Non-constant because w is non-constant
static final int[] arr = {1, 2, 3}; // Arrays are non-constant
static int n = 100;                 // Non-final
final int q = 5;                    // Non-static 
```

Primitive types (int, long, short, float, double, boolean, char, byte) are boxed in the corresponding wrapper class (Integer, Long etc.) before being passed to the provided StaticFinalFieldMatchProcessor.

### 6. Find all classes that contain a field of a given type

One of the more unique capabilities of FastClasspathScanner is to find classes in the whitelisted (non-blacklisted) package hierarchy that have fields of a given type, assuming both the class and the types of its fields are in whitelisted (non-blacklisted) packages. (In particular, you cannot search for fields of a type defined in a system package, e.g. `java.lang.String` or `java.lang.Object` by default, because system packages are always blacklisted by default -- this can be overridden by passing `"!"` or `"!!"` to the [constructor](#constructor).)

Matching field types also matches type parameters and array types. For example, `ScanResult#getNamesOfClassesWithFieldOfType("com.xyz.Widget")` will match classes that contain fields of the form:

* `Widget widget`
* `Widget[] widgets`
* `ArrayList<? extends Widget> widgetList`
* `HashMap<String, Widget> idToWidget`
* etc.

By default, only the types of public fields are indexed. To override this (and allow the indexing of private, protected and package-private fields), call `FastClasspathScanner#ignoreFieldVisibility()` before calling `FastClasspathScanner#scan()`. This may cause the scan to take longer and consume more memory. (By default, only public fields are scanned for efficiency reasons, and to conservatively respect the Java visibility rules.)

```java
// Mechanism 1: Attach a MatchProcessor before calling .scan():

@FunctionalInterface
interface ClassMatchProcessor {
    public void processMatch(Class<?> klass);
}

FastClasspathScanner FastClasspathScanner#matchClassesWithFieldOfType(
        Class<T> fieldType,
        ClassMatchProcessor classMatchProcessor)

// Mechanism 2: Call the following after calling .scan():

List<String> ScanResult#getNamesOfClassesWithFieldOfType(
        Class<?> fieldType | String fieldTypeName)
```

### 7. Finding files (even non-classfiles) anywhere on the classpath whose path matches a given string or regular expression

This can be useful for detecting changes to non-classfile resources on the classpath, for example a web server's template engine can hot-reload HTML templates when they change by including the template directory in the classpath and then detecting changes to files that are in the template directory and have the extension ".html".

A `FileMatchProcessor` is passed the `InputStream` for any `File` or `ZipFileEntry` in the classpath that has a path matching the pattern provided in the `FastClasspathScanner#matchFilenamePattern()` method (or other related methods, see below). You do not need to close the passed `InputStream` if you choose to read the stream contents; the stream is closed by the caller.

The value of `relativePath` is relative to the classpath entry that contained the matching file.

```java
// Only Mechanism 1 is applicable -- attach a MatchProcessor before calling .scan():

// Use this interface if you want to be passed an (unbuffered) InputStream.
// (You do not to close the InputStream before exiting, it is closed by the caller.)
@FunctionalInterface
interface FileMatchProcessor {
    public void processMatch(String relativePath, InputStream inputStream,
        long inputStreamLengthBytes) throws IOException;
}

// Use this interface if you want to be passed a byte array with the file contents.
@FunctionalInterface
interface FileMatchContentsProcessor {
    public void processMatch(String relativePath, byte[] fileContents)
        throws IOException;
}

// The following two MatchProcessor variants are available if you need to know
// where on the classpath the match was found.

@FunctionalInterface
interface FileMatchProcessorWithContext {
    public void processMatch(File classpathElt, String relativePath,
        InputStream inputStream, long inputStreamLengthBytes) throws IOException;
}

@FunctionalInterface
interface FileMatchContentsProcessorWithContext {
    public void processMatch(File classpathElt, String relativePath,
        byte[] fileContents) throws IOException;
}

// Pass one of the above FileMatchProcessor variants to one of the following methods:

// Match a pattern, such as "^com/pkg/.*\\.html$"
FastClasspathScanner FastClasspathScanner#matchFilenamePattern(String pathRegexp,
        FileMatchProcessor fileMatchProcessor
        | FileMatchProcessorWithContext fileMatchProcessorWithContext 
        | FileMatchContentsProcessor fileMatchContentsProcessor
        | FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext)
        
// Match a (non-regexp) relative path, such as "com/pkg/WidgetTemplate.html"
FastClasspathScanner FastClasspathScanner#matchFilenamePath(String relativePathToMatch,
        FileMatchProcessor fileMatchProcessor
        | FileMatchProcessorWithContext fileMatchProcessorWithContext 
        | FileMatchContentsProcessor fileMatchContentsProcessor
        | FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext)
        
// Match a leafname, such as "WidgetTemplate.html"
FastClasspathScanner FastClasspathScanner#matchFilenameLeaf(String leafToMatch,
        FileMatchProcessor fileMatchProcessor
        | FileMatchProcessorWithContext fileMatchProcessorWithContext 
        | FileMatchContentsProcessor fileMatchContentsProcessor
        | FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext)
        
// Match a file extension, e.g. "html" matches "WidgetTemplate.html"
FastClasspathScanner FastClasspathScanner#matchFilenameExtension(String extensionToMatch,
        FileMatchProcessor fileMatchProcessor
        | FileMatchProcessorWithContext fileMatchProcessorWithContext 
        | FileMatchContentsProcessor fileMatchContentsProcessor
        | FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext)
```

### 8. Performing the actual scan

The `FastClasspathScanner#scan()` method performs the actual scan. There are several versions:

```java
// Scans the classpath for matching files, and calls any MatchProcessors if a match
// is identified. Temporarily starts up a new fixed thread pool for scanning, with
// the default number of threads.
ScanResult FastClasspathScanner#scan()

// Scans the classpath for matching files, and calls any MatchProcessors if a match
// is identified. Temporarily starts up a new fixed thread pool for scanning, with
// the requested number of threads.
ScanResult FastClasspathScanner#scan(int numThreads)

// Scans the classpath for matching files, and calls any MatchProcessors if a match
// is identified. Uses the provided ExecutorService, and divides the work according
// to the requested degree of parallelism.
ScanResult FastClasspathScanner#scan(ExecutorService executorService,
        int numWorkerThreads)

// Asynchronously scans the classpath for matching files, and calls any MatchProcessors
// if a match is identified. Returns a Future<ScanResult> object immediately after
// starting the scan. To block on scan completion, get the result of the returned
// Future. Uses the provided ExecutorService, and divides the work according to the
// requested degree of parallelism.
Future<ScanResult> FastClasspathScanner#scanAsync(ExecutorService executorService,
        int numWorkerThreads)
```

In most cases, calling `FastClasspathScanner#scan()` is the right call to use, although if you have your own ExecutorService already running, you can submit the scanning work to that ExecutorService using one of the other variants of `FastClasspathScanner#scan()`, or using `FastClasspathScanner#scanAsync()`.

Note that classpath masking is in effect for all files on the classpath: if two or more files with the same relative path are encountered on the classpath, the second and subsequent occurrences are ignored, in order to follow Java's class masking behavior.

The `FastClasspathScanner#scan()` method may be called multiple times on the same `FastClasspathScanner` object, although there is usually no point performing additional scans unless `classpathContentsModifiedSinceScan()` returns true (unless you need to detect the addition of new resources in whitelisted packages, since `classpathContentsModifiedSinceScan()` only detects changes to existing resources).

There are some synchronization considerations when using the non-blocking `FastClasspathScanner#scanAsync()` with MatchProcessors -- see [Parallel Classpath Scanning](#parallel-classpath-scanning) for info.

If the scan is interrupted by the interrupt status being set on the main thread or any worker threads, then `FastClasspathScanner#scan()` will throw the unchecked exception `ScanInterruptedException`. If you care about thread interruption, you should catch this exception.

### 9. Detecting changes to classpath contents after the scan

When the classpath is scanned using `FastClasspathScanner#scan()`, the latest last modified timestamp of whitelisted files and jarfiles encountered during the scan is recorded.

After a call to `FastClasspathScanner#scan()`, it is possible to later call `FastClasspathScanner#classpathContentsModifiedSinceScan()` at any point to check if something within the classpath has changed. This method does not look inside classfiles and does not call any match processors, but merely looks at the last modified timestamps of files within whitelisted package prefixes on the classpath, and of whitelisted jars. If the latest last modified timestamp has changed since the initial scan, this method will return true.

Since `FastClasspathScanner#classpathContentsModifiedSinceScan()` only checks file modification timestamps, it works much faster than the original call to `FastClasspathScanner#scan()`. It is therefore a very lightweight operation that can be called in a polling loop to periodically detect changes to classpath contents for hot reloading of resources.

**Important:** this method does not detect resources that are *newly added* to whitelisted packages (you need to perform a full scan for that), it only detects changes in the timestamps of files found during the previous scan. If you need to check for added/removed resources in a fast way, you can use a full scan to detect a specific file in a directory of interest, and then watch the directory yourself for added/removed files.     

The function `ScanResult#classpathContentsLastModifiedTime()` can also be called after `FastClasspathScanner#scan()` to find the maximum timestamp of all files in the classpath, in epoch millis. This should be less than the system time, and if anything on the classpath changes, this value should increase, assuming the timestamps and the system time are trustworthy and accurate. 

```java
boolean ScanResult#classpathContentsModifiedSinceScan()

long ScanResult#classpathContentsLastModifiedTime()
```

### 10. Get a list of all whitelisted (and non-blacklisted) classes, interfaces or annotations on the classpath

The names of all classes, interfaces and/or annotations in whitelisted (and non-blacklisted) packages can be returned using the methods shown below. Note that system classes (e.g. `java.lang.String` and `java.lang.Object`) are not enumerated or returned by any of these methods.

```java
// Mechanism 1: Attach a MatchProcessor before calling .scan():

@FunctionalInterface
interface ClassMatchProcessor {
    public void processMatch(Class<?> klass);
}

// Enumerate all standard classes, interfaces and annotations
FastClasspathScanner FastClasspathScanner#matchAllClasses(
    ClassMatchProcessor classMatchProcessor)

// Enumerate all standard classes (but not interfaces/annotations)
FastClasspathScanner FastClasspathScanner#matchAllStandardClasses(
    ClassMatchProcessor classMatchProcessor)

// Enumerate all interfaces
FastClasspathScanner FastClasspathScanner#matchAllInterfaceClasses(
    ClassMatchProcessor classMatchProcessor)

// Enumerate all annotations
FastClasspathScanner FastClasspathScanner#matchAllAnnotationClasses(
    ClassMatchProcessor classMatchProcessor)

// Mechanism 2: Call one of the following after calling .scan():

// Get names of all standard classes, interfaces and annotations
List<String> ScanResult#getNamesOfAllClasses()

// Get names of all standard classes (but not interfaces/annotations)
List<String> ScanResult#getNamesOfAllStandardClasses()

// Get names of all interfaces
List<String> ScanResult#getNamesOfAllInterfaceClasses()

// Get names of all annotations
List<String> ScanResult#getNamesOfAllAnnotationClasses()
```

### 11. Get all unique directories and files on the classpath

The list of all directories and files on the classpath is returned by `FastClasspathScanner#getUniqueClasspathElements()`. The resulting list is filtered to include only unique classpath elements (duplicates are eliminated), and to include only directories and files that actually exist. The elements in the list are in classpath order.

This method is useful if you want to see what's actually on the classpath -- note that `System.getProperty("java.class.path")` does not always return the [complete classpath](https://docs.oracle.com/javase/8/docs/technotes/tools/findingclasses.html) because [Classloading is a very complicated process](https://docs.oracle.com/javase/8/docs/technotes/tools/findingclasses.html). FastClasspathScanner looks for classpath entries in `java.class.path` and in various system classloaders, but it can also transitively follow [Class-Path references](https://docs.oracle.com/javase/tutorial/deployment/jar/downman.html) in a jarfile's `META-INF/MANIFEST.MF`.

Note that FastClasspathScanner does not scan [JRE system, bootstrap or extension jarfiles](https://docs.oracle.com/javase/8/docs/technotes/tools/findingclasses.html), so the classpath entries for these system jarfiles will not be listed by `FastClasspathScanner#getUniqueClasspathElements()`.

```java
List<File> FastClasspathScanner#getUniqueClasspathElements()
```

### 12. Generate a GraphViz dot file from the classgraph

During scanning, the class graph (the connectivity between classes, interfaces and annotations) is determined for all whitelisted (non-blacklisted) packages. The class graph can [very simply](https://github.com/lukehutch/fast-classpath-scanner/blob/master/src/test/java/com/xyz/GenerateClassGraphFigDotFile.java) be turned into a [GraphViz](http://www.graphviz.org/) .dot file for visualization purposes, as shown [above](#visualization).

Call the following after `FastClasspathScanner#scan()`, where the `sizeX` and `sizeY` params give the layout size in inches:

```java
String ScanResult#generateClassGraphDotFile(float sizeX, float sizeY)
```

The returned string can be saved to a .dot file and fed into GraphViz using

```
dot -Tsvg < graph.dot > graph.svg
```

or similar, generating a graph with the following conventions:

<p align="center">
  <img src="https://github.com/lukehutch/fast-classpath-scanner/blob/master/src/test/java/com/xyz/classgraph-fig-legend.png" alt="Class graph legend"/>
</p>

**Notes:**

1. Graph nodes will only be added for classes, interfaces and annotations that are within whitelisted (non-blacklisted) packages. In particular, the Java standard libraries are excluded by default from classpath scanning for efficiency, so these classes will not by default appear in class graph visualizations. (Override this by passing `"!"` or `"!!"` to the [constructor](#constructor).)
2. Only public fields are scanned by default, so the graph won't show relationships between a class and the field types of the class unless the fields are public, or `FastClasspathScanner#ignoreFieldVisibility()` is called before `FastClasspathScanner#scan()`.   

## Parallel classpath scanning

As of version 1.90.0, FastClasspathScanner performs multithreaded scanning, which overlaps disk/SSD reads, jarfile decompression and classfile parsing across multiple threads. This typically reduces scan time by 30-60%. (The speedup will increase by a factor of two on the second and subsequent scan of the same classpath by the same JVM instance, because disk/SSD read bandwidth is the bottleneck, and file content is cached within a JVM session.)

Note that any custom MatchProcessors that you add are all currently run on a single thread, so they do not necessarily need to be threadsafe relative to each other (though it's a good futureproofing habit to always write threadsafe code, even in supposedly single-threaded contexts). However, MatchProcessors are run in a different thread than the main thread. If you use the blocking call `FastClasspathScanner#scan()`, then the main thread is blocked waiting on the result of the scan when the MatchProcessors are run. However, if you use the non-blocking call `FastClasspathScanner#scanAsync()`, which returns a `Future<ScanResult>`, then your main thread will return immediately, and will potentially be running in parallel with the thread that runs the MatchProcessors. You therefore need to properly synchronize communication and data access between MatchProcessors and the main thread in the async case. 

If you want to do CPU-intensive processing in a `MatchProcessor`, and need the speed advantage of doing the work in parallel across all matching classes, you should use the `MatchProcessor` to obtain the data you need on matching classes, and then schedule the work to be done in parallel after `FastClasspathScanner#scan()` has finished.

With this change, according to profiling results, FastClasspathScanner is running at close to the theoretical maximum possible speed for a classpath scanner, because it is I/O-bound, as well as limited by the decompression speed of `java.util.zip` (which is a JNI wrapper over a native decompressor, and appears to currently be the fastest unzip library for Java).

## Debugging

If FastClasspathScanner is not finding the classes, interfaces or files you think it should be finding, you can debug the scanning behavior by calling 'FastClasspathScanner#verbose()` before `FastClasspathScanner#scan()`:

```java
FastClasspathScanner FastClasspathScanner#verbose()

FastClasspathScanner FastClasspathScanner#verbose(boolean verbose)
```

## More complex usage

### Working in platforms with non-standard ClassLoaders (JBoss/WildFly, WebLogic, OSGi Equinox, Maven, Tomcat etc.)

FastClasspathScanner handles a number of non-standard ClassLoaders. There is [basic support](https://github.com/lukehutch/fast-classpath-scanner/tree/master/src/main/java/io/github/lukehutch/fastclasspathscanner/classloaderhandler) for JBoss/WildFly, WebLogic and OSGi Equinox, which implement their own ClassLoaders. Maven works when it sets `java.class.path`, but YMMV, since it [has its own](https://github.com/sonatype/plexus-classworlds) unique ClassLoader system that is not yet supported. Tomcat has a [complex](https://www.mulesoft.com/tcat/tomcat-classpath) classloading system, and is less likely to work, but you might get lucky.

You can handle custom ClassLoaders without modifying FastClasspathScanner by writing a [ClassLoaderHandler](https://github.com/lukehutch/fast-classpath-scanner/tree/master/src/main/java/io/github/lukehutch/fastclasspathscanner/classloaderhandler/ClassLoaderHandler.java) and registering it using the `ServiceLoader` mechanism. Patches to add or improve support for non-standard ClassLoaders would be appreciated.

If the `ServiceLoader` framework cannot find your `ClassLoaderHandler`, or you want to write your own `ClassLoaderHandler` without registering it using the `ServiceLoader` mechanism, you can call the following after calling the constructor, and before calling .scan(): 

```java
FastClasspathScanner FastClasspathScanner#registerClassLoaderHandler(
        ClassLoaderHandler extraClassLoaderHandler)
```

Note that you can always override the system classpath with your own path, using the following call after calling the constructor, and before calling .scan():

```java
FastClasspathScanner FastClasspathScanner#overrideClasspath(String classpath)
```

### Getting generic class references for parameterized classes

A problem arises when using class-based matchers with parameterized classes, e.g. `Widget<K>`. Because of type erasure, The expression `Widget<K>.class` is not defined, and therefore it is impossible to cast `Class<Widget>` to `Class<Widget<K>>`. More specifically:

* `Widget.class` has the type `Class<Widget>`, not `Class<Widget<?>>` 
* `new Widget<Integer>().getClass()` has the type `Class<? extends Widget>`, not `Class<? extends Widget<?>>`.

The code below compiles and runs fine, but `SubclassMatchProcessor` must be parameterized with the bare type `Widget` in order to match the reference `Widget.class`. This gives rise to three type safety warnings: `Test.Widget is a raw type. References to generic type Test.Widget<K> should be parameterized` on `new SubclassMatchProcessor<Widget>()` and `Class<? extends Widget> widgetClass`; and `Type safety: Unchecked cast from Class<capture#1-of ? extends Test.Widget> to Class<Test.Widget<?>>` on the type cast `(Class<? extends Widget<?>>)`.

```java
public class Test {
    public static class Widget<K> {
        K id;
    }

    public static class WidgetSubclass<K> extends Widget<K> {
    }  

    public static void registerSubclass(Class<? extends Widget<?>> widgetClass) {
        System.out.println("Found widget subclass " + widgetClass.getName());
    }
    
    public static void main(String[] args) {
        new FastClasspathScanner("com.xyz.widget")
            // Have to use Widget.class and not Widget<?>.class as type parameter,
            // which constrains all the other types to bare class references
            .matchSubclassesOf(Widget.class, new SubclassMatchProcessor<Widget>() {
                @Override
                public void processMatch(Class<? extends Widget> widgetClass) {
                    registerSubclass((Class<? extends Widget<?>>) widgetClass);
                }
            })
            .scan();
    }
}
``` 

**Solution:** You can't cast from `Class<Widget>` to `Class<Widget<?>>`, but you can cast from `Class<Widget>` to `Class<? extends Widget<?>>` with only an `unchecked conversion` warning, which can be suppressed.

[The gory details: The type `Class<? extends Widget<?>>` is unifiable with the type `Class<Widget<?>>`, so for the method `matchSubclassesOf(Class<T> superclass, SubclassMatchProcessor<T> subclassMatchProcessor)`, you can use `<? extends Widget<?>>` for the type parameter `<T>` of `superclass`, and type  `<Widget<?>>` for the type parameter `<T>` of `subclassMatchProcessor`.]

Note that with this cast, `SubclassMatchProcessor<Widget<?>>` can be properly parameterized to match the type of `widgetClassRef`, and no cast is needed in the function call `registerSubclass(widgetClass)`.

(Also note that it is valid to replace all occurrences of the generic type parameter `<?>` in this example with a concrete type parameter, e.g. `<Integer>`.) 

```java
public static void main(String[] args) {
    // Declare the type as a variable so you can suppress the warning
    @SuppressWarnings("unchecked")
    Class<? extends Widget<?>> widgetClassRef = 
        (Class<? extends Widget<?>>) Widget.class;
    new FastClasspathScanner("com.xyz.widget").matchSubclassesOf(
                widgetClassRef, new SubclassMatchProcessor<Widget<?>>() {
            @Override
            public void processMatch(Class<? extends Widget<?>> widgetClass) {
                registerSubclass(widgetClass);
            }
        })
        .scan();
}
```

**Alternative solution 1:** Create an object of the desired type, call getClass(), and cast the result to the generic parameterized class type.

```java
public static void main(String[] args) {
    @SuppressWarnings("unchecked")
    Class<Widget<?>> widgetClass = 
        (Class<Widget<?>>) new Widget<Object>().getClass();
    new FastClasspathScanner("com.xyz.widget") //
        .matchSubclassesOf(widgetClass, new SubclassMatchProcessor<Widget<?>>() {
            @Override
            public void processMatch(Class<? extends Widget<?>> widgetClass) {
                registerSubclass(widgetClass);
            }
        })
        .scan();
}
``` 

**Alternative solution 2:** Get a class reference for a subclass of the desired class, then get the generic type of its superclass:

```java
public static void main(String[] args) {
    @SuppressWarnings("unchecked")
    Class<Widget<?>> widgetClass =
            (Class<Widget<?>>) ((ParameterizedType) WidgetSubclass.class
                .getGenericSuperclass()).getRawType();
    new FastClasspathScanner("com.xyz.widget") //
        .matchSubclassesOf(widgetClass, new SubclassMatchProcessor<Widget<?>>() {
            @Override
            public void processMatch(Class<? extends Widget<?>> widgetClass) {
                registerSubclass(widgetClass);
            }
        })
        .scan();
}
``` 

## Downloading

You can get a pre-built JAR (usable in JRE 1.7 or later) from [Sonatype](https://oss.sonatype.org/#nexus-search;quick~fast-classpath-scanner), or add the following Maven Central dependency:

```xml
<dependency>
    <groupId>io.github.lukehutch</groupId>
    <artifactId>fast-classpath-scanner</artifactId>
    <version>LATEST</version>
</dependency>
```

Or use the "Clone or download" button at the top right of this page.

## License

The MIT License (MIT)

Copyright (c) 2016 Luke Hutchison
 
Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 
The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Classfile format documentation

See Oracle's documentation on the [classfile format](http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html).

## Alternatives

* [Reflections](https://github.com/ronmamo/reflections)
* [annotation-detector](https://github.com/rmuller/infomas-asl/tree/master/annotation-detector)

## Author

Fast Classpath Scanner was written by Luke Hutchison -- https://github.com/lukehutch / [@LH](http://twitter.com/LH) on Twitter

Please donate if this library makes your life easier:

[![](https://www.paypalobjects.com/en_US/i/btn/btn_donateCC_LG.gif)](https://www.paypal.com/cgi-bin/webscr?cmd=_donations&business=luke.hutch@gmail.com&lc=US&item_name=Luke%20Hutchison&item_number=FastClasspathScanner&no_note=0&currency_code=USD&bn=PP-DonationsBF:btn_donateCC_LG.gif:NonHostedGuest)
