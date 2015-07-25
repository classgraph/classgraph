fast-classpath-scanner
======================

Uber-fast, ultra-lightweight Java classpath scanner. Scans the classpath by parsing the classfile binary format directly rather than by using reflection. (Reflection causes the classloader to load each class, which can take an order of magnitude more time than parsing the classfile directly, and can lead to unexpected behavior due to static initializer blocks of classes being called on class load.)

FastClasspathScanner is able to scan directories and jar/zip files on the classpath to:
* find classes that subclass a given class or one of its subclasses;
* find interfaces that extend a given interface or one of its subinterfaces;
* find classes that implement an interface or one of its subinterfaces, or whose superclasses implement the interface or one of its subinterfaces;
* find classes that have a given annotation;
* find the constant literal initializer value in a classfile's constant pool for a specified static final field;
* find files (even non-classfiles) anywhere on the classpath that have a path that matches a given regular expression;
* detect changes to the files within the classpath since the first time the classpath was scanned;
* return a list of all directories and files on the classpath (i.e. all classpath elements) as a list of File objects, with the list deduplicated and filtered to include only classpath directories and files that actually exist, saving you the trouble of parsing and filtering the classpath; and
* return a list of the names of all classes and interfaces on the classpath (after whitelist and blacklist filtering).

Usage is as follows:
 
```java
// Whitelisted package prefixes to scan are listed in the constructor
new FastClasspathScanner("com.xyz.widget", "com.xyz.gizmo")  
  
    .matchSubclassesOf(DBModel.class,
        // c is a subclass of DBModel or a descendant subclass
        c -> System.out.println("Subclass of DBModel: " + c.getName()))

    .matchSubinterfacesOf(Role.class,
        // c is an interface that extends the interface Role
        c -> System.out.println("Subinterface of Role: " + c.getName()))

    .matchClassesImplementing(Runnable.class,
        // c is a class that implements the interface Runnable; more precisely,
        // c or one of its superclasses implements the interface Runnable, or
        // implements an interface that is a descendant of Runnable
        c -> System.out.println("Implements Runnable: " + c.getName()))
  
    .matchClassesWithAnnotation(RestHandler.class,
        // c is a class annotated with RestHandler
        c -> System.out.println("Has a RestHandler class annotation: " + c.getName()))
 
    .matchStaticFinalFieldNames("com.xyz.Config.LOG_LEVEL",
        // The following method is called when any static final fields with
        // names matching one of the above fully-qualified names are
        // encountered, as long as those fields are initialized to constant
        // values. The value returned is the value in the classfile, not the
        // value that would be returned by reflection, so this can be useful
        // in hot-swapping of changes.
        (String className, String fieldName, Object fieldConstantValue) ->
            System.out.println("Static field " + fieldName + " in class "
            + className + " " + " currently has constant literal value "
            + fieldConstantValue + " in the classfile"))

    .matchFilenamePattern("^template/.*\\.html",
        // templatePath is a path on the classpath that matches the above pattern;
        // inputStream is a stream opened on the file or zipfile entry.
        // No need to close inputStream before exiting, it is closed by caller.
        (absolutePath, relativePath, inputStream) -> {
            try {
                String template = IOUtils.toString(inputStream, "UTF-8");
                System.out.println("Found template: " + absolutePath
                    + " (size " + template.length() + ")");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        })
    
    .scan();  // Actually perform the scan


// [...Some time later...]

// See if any timestamps on the classpath are more recent than the time of the
// previous scan. (Even faster than classpath scanning, because classfiles
// don't have to be opened.)   
boolean classpathContentsModified =
    fastClassPathScanner.classpathContentsModifiedSinceScan();
```

**Blacklisting and whitelisting package prefixes in the constructor:** The constructor specifies whitelisted package prefixes to scan, e.g. `new FastClasspathScanner("com.xyz.widget")` will scan inside package `com.xyz.widget` as well as any child packages like `com.xyz.widget.button`. If no whitelisted packages are specified (i.e. if the constructor is called without arguments), or if one of the whitelisted package names is "" or "/", all classfiles in the classpath will be scanned. If a package name is prefixed with "-", e.g. "-com.xyz.otherthing", then that package is blacklisted, rather than whitelisted. The final list of packages scanned is the set of whitelisted packages minus the set of blacklisted packages.

**Using Java 8 method references:** The `.match...()` methods (e.g. .matchSubclassesOf()) take [MatchProcessors](https://github.com/lukehutch/fast-classpath-scanner/tree/master/src/main/java/io/github/lukehutch/fastclasspathscanner/matchprocessor) as one of their arguments, which are single-method classes (i.e. FunctionalInterfaces). Java 8 method references may also be used as FunctionalInterfaces, e.g. List::add:

```java
List<Class<? extends Node>> collector = new ArrayList<>();
new FastClasspathScanner("com.xyz.widget")
    .matchSubclassesOf(Node.class, collector::add)
    .scan();
```

**Calling from Java 7 and below:** The pre-Java-8 mechanism for adding a MatchProcessor is as follows (note that there is a different [MatchProcessor class](https://github.com/lukehutch/fast-classpath-scanner/tree/master/src/main/java/io/github/lukehutch/fastclasspathscanner/matchprocessor) corresponding to each .match*() method):

```java
new FastClasspathScanner("com.xyz.widget")  
    .matchSubclassesOf(DBModel.class, new SubclassMatchProcessor<DBModel>() {
        @Override
        public void processMatch(Class<? extends DBModel> matchingClass) {
            System.out.println("Subclass of DBModel: " + matchingClass))
        }
    })
    .scan();
```

**Avoiding calling the classloader on matching classes:** You can also get a list of matching fully-qualified names of interfaces and classes matching required criteria without adding any MatchProcessors (i.e. without calling any `.match...()` methods on the FastClasspathScanner instance) by calling a `.getNamesOf...()` method. These methods return lists of strings, rather than lists of `Class<?>` references, which means that the classloader does not need to be called on the matching classes, and the static initializer blocks of the matching classes will never be executed. This can be useful if the static initializer blocks of classes trigger unwanted side effects if run before the expected time. (i.e. the class hierarchy is parsed and stored during the `.scan()` call whether or not there are any MatchProcessors added to the FastClasspathScanner instance, but the classloader is only called on a class if there is a MatchProcessor that matches the class, in order to be able to pass a `Class<?>` reference to the MatchProcessor.)

As a result of not calling the classloader, you get a `List<String>` list of matching classnames, rather than a list of `Class<?>` references:

```java
List<String> subclassesOfWidget = new FastClasspathScanner("com.xyz.widget")
    // No need to add any MatchProcessors, just create a new scanner and then call
    // .scan() to parse the class hierarchy of all classfiles on the classpath.
    .scan()
    // Get the names of all subclasses of Widget on the classpath,
    // again without calling the classloader:
    .getNamesOfSubclassesOf("com.xyz.widget.Widget");
```

See also [Usage Caveats](#usage-caveats) below.

# API

Note that most of the methods in the API return *this* (of type FastClasspathScanner), so that you can use the [method chaining](http://en.wikipedia.org/wiki/Method_chaining) calling style, as shown above.

### Whitelisting package prefixes in the call to the constructor

Calling the constructor does not actually start the scan. The constructor takes a whitelist of package prefixes that should be scanned. Whitelisting package prefixes of interest can dramatically speed up classpath scanning, because it limits the number of classfiles that need to be opened and read.

```java
/**
 * Constructs a FastClasspathScanner instance.
 * 
 * @param packagesToScan
 *            the whitelist of package prefixes to scan, e.g.
 *            "com.xyz.widget", "com.xyz.gizmo". If no whitelisted
 *            packages are given (i.e. if the constructor is called
 *            with zero arguments), then all packages on the
 *            classpath will be scanned.
 */
public FastClasspathScanner(String... pacakagesToScan) {
    /*...*/
}
```

Note that if you don't specify any whitelisted package prefixes, i.e. `new FastClasspathScanner()`, all packages on the classpath will be scanned. ("Scanning" involves parsing the classfile binary format to determine class and interface relationships.)

### Matching the subclasses (or finding the superclasses) of a class

FastClasspathScanner can find all classes on the classpath within whitelisted package prefixes that extend a given superclass.

*Important note:* the ability to detect that a class extends another depends upon the entire ancestral path between the two classes being within one of the whitelisted package prefixes.

There are also methods `List<String> getNamesOfSubclassesOf(String superclassName)` and `List<String> getNamesOfSubclassesOf(Class<T> superclass)` that can be called after `.scan()` to find the names of the subclasses of a given class (whether or not a corresponding match processor was added to detect this). These methods will return the matching classes without calling the classloader, whereas if a match processor is used, the classloader is called first (using Class.forName()) so that a class reference can be passed into the match processor.

Furthermore, the methods `List<String> getNamesOfSuperclassesOf(String subclassName)` and `List<String> getNamesOfSuperclassesOf(Class<T> subclass)` are able to return all superclasses of a given class after a call to `.scan()`. (Note that there is not currently a SuperclassMatchProcessor or .matchSuperclassesOf().)

```java
/** The method to run when a subclass of a specific class is found on the classpath. */
@FunctionalInterface
public interface SubclassMatchProcessor<T> {
    public void processMatch(Class<? extends T> matchingClass);
}

/**
 * Call the provided SubclassMatchProcessor if classes are found on the classpath that
 * extend the specified superclass.
 * 
 * @param superclass
 *            The superclass to match (i.e. the class that subclasses need to extend)
 * @param subclassMatchProcessor
 *            the SubclassMatchProcessor to call when a match is found.
 */
public <T> FastClasspathScanner matchSubclassesOf(
        Class<T> superclass,
        SubclassMatchProcessor<T> subclassMatchProcessor) {
    /* ... */
}

/**
 * Returns the list of classes on the classpath that extend the specified superclass.
 * Should be called after .scan(), and returns matching classes whether or not a
 * SubclassMatchProcessor was added to the scanner before the call to .scan().
 * Does not call the classloader on the matching classes, just returns their names.
 * 
 * @param superclass
 *            The superclass to match (i.e. the class that subclasses need to extend
 *            to match).
 * @return A list of matching classes, or the empty list if none.
 */
public <T> List<String> getNamesOfSubclassesOf(final Class<T> superclass) {
    /* ... */
}

// Alternatively:
public List<String> getNamesOfSubclassesOf(String superclassName) {
    /* ... */
}

// Note that there are also methods for finding the names of all superclasses of a
// given class (even though there is no corresponding SuperclassMatchProcessor):
public <T> List<String> getNamesOfSuperclassesOf(final Class<T> subclass) {
    /* ... */
}

// Alternatively:
public List<String> getNamesOfSuperclassesOf(String subclassName) {
    /* ... */
}
```

### Matching the subinterfaces (or finding the superinterfaces) of an interface

FastClasspathScanner can find all interfaces on the classpath within whitelisted package prefixes that that extend a given interface or its subinterfaces.

*Important note:* The ability to detect that an interface extends another interface depends upon the entire ancestral path between the two interfaces being within one of the whitelisted package prefixes.

There are also methods `List<String> getNamesOfSubinterfacesOf(String ifaceName)` and `List<String> getNamesOfSubinterfacesOf(Class<T> iface)` that can be called after `.scan()` to find the names of the subinterfaces of a given interface (whether or not a corresponding match processor was added to detect this). These methods will return the matching interfaces without calling the classloader, whereas if a match processor is used, the classloader is called first (using Class.forName()) so that a class reference for the matching interface can be passed into the match processor.

Furthermore, the methods `List<String> getNamesOfSuperinterfacesOf(String ifaceName)` and `List<String> getNamesOfSuperinterfacesOf(Class<T> iface)` are able to return all superinterfaces of a given interface after a call to `.scan()`. (Note that there is not currently a SuperinterfaceMatchProcessor or .matchSuperinterfacesOf().)
 
```java
/**
 * The method to run when an interface that extends another specific interface
 * is found on the classpath.
 */
@FunctionalInterface
public interface SubinterfaceMatchProcessor<T> {
    public void processMatch(Class<? extends T> matchingInterface);
}

/**
 * Call the provided SubinterfaceMatchProcessor if an interface that extends a
 * given superinterface is found on the classpath.
 * 
 * @param superInterface
 *            The superinterface to match (i.e. the interface that subinterfaces
 *            need to extend).
 * @param subinterfaceMatchProcessor
 *            the SubinterfaceMatchProcessor to call when a match is found.
 */
@SuppressWarnings("unchecked")
public <T> FastClasspathScanner matchSubinterfacesOf(final Class<T> superInterface,
        final SubinterfaceMatchProcessor<T> subinterfaceMatchProcessor) {
    /* ... */
}

/**
 * Returns the list of interfaces on the classpath that extend a given
 * superinterface. Should be called after .scan(), and returns matching interfaces
 * whether or not a SubinterfaceMatchProcessor was added to the scanner before the
 * call to .scan(). Does not call the classloader the matching interfaces, just
 * returns their names.
 * 
 * @param superInterface
 *            The superinterface to match (i.e. the interface that subinterfaces
 *            need to extend to match).
 * @return A list of matching interfaces, or the empty list if none.
 */
public <T> List<String> getNamesOfSubinterfacesOf(final Class<T> superInterface) {
    /* ... */
}

// Alternatively:
public List<String> getNamesOfSubinterfacesOf(final String superInterfaceName) {
    /* ... */
}

// Note that there are also methods for finding the names of all superinterfaces of a
// given class (even though there is no corresponding SuperinterfaceMatchProcessor):
public <T> List<String> getNamesOfSuperinterfacesOf(final Class<T> subinterface) {
    /* ... */
}

// Alternatively:
public List<String> getNamesOfSuperinterfacesOf(String subinterfaceName) {
    /* ... */
}
```

### Matching the classes that implement an interface

FastClasspathScanner can find all classes on the classpath within whitelisted package prefixes that that implement a given interface. The matching logic here is trickier than it would seem, because FastClassPathScanner also has to match classes whose superclasses implement the target interface, or classes that implement a sub-interface (descendant interface) of the target interface, or classes whose superclasses implement a sub-interface of the target interface.

*Important note:* The ability to detect that a class implements an interface depends upon the entire ancestral path between the class and the interface (and any relevant sub-interfaces or superclasses along the path between the two) being within one of the whitelisted package prefixes.

There are also methods `List<String> getNamesOfClassesImplementing(String ifaceName)` and `List<String> getNamesOfClassesImplementing(Class<T> iface)` that can be called after `.scan()` to find the names of the classes implementing a given interface (whether or not a corresponding match processor was added to detect this). These methods will return the matching classes without calling the classloader, whereas if a match processor is used, the classloader is called first (using Class.forName()) so that a class reference can be passed into the match processor.

```java
/**
 * The method to run when a class implementing a specific interface is found on the
 * classpath.
 */
@FunctionalInterface
public interface InterfaceMatchProcessor<T> {
    public void processMatch(Class<? extends T> implementingClass);
}

/**
 * Call the provided InterfaceMatchProcessor for classes on the classpath that
 * implement the specified interface or a sub-interface, or whose superclasses
 * implement the specified interface or a sub-interface.
 * 
 * @param implementedInterface
 *            The interface that classes need to implement.
 * @param interfaceMatchProcessor
 *            the ClassMatchProcessor to call when a match is found.
 */
public <T> FastClasspathScanner matchClassesImplementing(
        Class<T> implementedInterface,
        InterfaceMatchProcessor<T> interfaceMatchProcessor) {
    /* ... */
}

/**
 * Returns a list of classes on the classpath that implement the specified
 * interface or a subinterface, or whose superclasses implement the specified
 * interface or a sub-interface. Should be called after .scan(), and returns
 * matching interfaces whether or not an InterfaceMatchProcessor was added
 * to the scanner before the call to .scan(). Does not call the classloader on
 * the matching classes, just returns their names.
 * 
 * @param implementedInterface
 *            The interface that classes need to implement to match.
 * @return A list of matching classes, or the empty list if none.
 */
public <T> List<String> getNamesOfClassesImplementing(final Class<T> implementedInterface) {
    /* ... */
}

// Alternatively:
public List<String> getNamesOfClassesImplementing(final String implementedInterfaceName) {
    /* ... */
}
```

### Matching classes with a specific annotation

FastClassPathScanner can detect classes that have a class annotation that matches a given annotation. 

There are also methods `List<String> getNamesOfClassesWithAnnotation(String annotationClassName)` and `List<String> getNamesOfClassesWithAnnotation(Class<T> annotationClass)` that can be called after `.scan()` to find the names of the classes that have a given annotation (whether or not a corresponding match processor was added to detect this). These methods will return the matching classes without calling the classloader, whereas if a match processor is used, the classloader is called first (using Class.forName()) so that a class reference can be passed into the match processor.

```java
/**
 * The method to run when a class having a specified annotation is found on the
 * classpath.
 */
@FunctionalInterface
public interface ClassAnnotationMatchProcessor {
    public void processMatch(Class<?> matchingClass);
}

/**
 * Call the provided ClassAnnotationMatchProcessor if classes are found on the
 * classpath that have the specified annotation.
 * 
 * @param annotation
 *            The class annotation that classes need to have in order to match.
 * @param classAnnotationMatchProcessor
 *            the ClassAnnotationMatchProcessor to call when a match is found.
 */
public FastClasspathScanner matchClassesWithAnnotation(
       Class<?> annotation,
       ClassAnnotationMatchProcessor classAnnotationMatchProcessor) {
    /* ... */
}

/**
 * Returns a list of classes on the classpath that have the specified
 * annotation. Should be called after .scan(), and returns matching classes
 * whether or not a ClassAnnotationMatchProcessor was added to the scanner
 * before the call to .scan(). Does not call the classloader on the matching
 * classes, just returns their names.
 * 
 * @param annotation
 *            The class annotation.
 * @return A list of classes with the class annotation, or the empty list if none.
 */
public <T> List<String> getNamesOfClassesWithAnnotation(final Class<?> annotation) {
    /* ... */
}

// Alternatively:
public List<String> getNamesOfClassesWithAnnotation(final String annotationName) {
    /* ... */
}
```

### Fetching the constant initializer values of static final fields

FastClassPathScanner is able to scan the classpath for matching fully-qualified static final fields, e.g. for the fully-qualified field name "com.xyz.Config.POLL_INTERVAL", FastClassPathScanner will look in the class com.xyz.Config for the static final field POLL_INTERVAL, and if it is found, and if it has a constant literal initializer value, that value will be read directly from the classfile and passed into a provided StaticFinalFieldMatchProcessor.

Field values are obtained directly from the constant pool in a classfile, not from a loaded class using reflection. This allows you to detect changes to the classpath and then run another scan that picks up the new values of selected static constants without reloading the class. [(Class reloading is fraught with issues.)](http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html)

This can be useful in hot-swapping of changes to static constants in classfiles if the constant value is changed and the class is re-compiled while the code is running. (Neither the JVM nor the Eclipse debugger will hot-replace static constant initializer values if you change them while running code, so you can pick up changes this way instead). 

**Note:** The visibility of the fields is not checked; the value of the field in the classfile is returned whether or not it should be visible to the caller. Therefore you should probably only use this method with public static final fields (not just static final fields) to coincide with Java's own semantics.

```java
/**
 * The method to run when a class with the matching class name and with a final static
 * field with the matching field name is found on the classpath. The constant value of
 * the final static field is obtained directly from the constant pool of the classfile.
 * 
 * @param className
 *            The class name, e.g. "com.package.ClassName".
 * @param fieldName
 *            The field name, e.g. "STATIC_FIELD_NAME".
 * @param fieldConstantValue
 *            The field's constant literal value, read directly from the classfile's 
 *            constant pool.
 */
@FunctionalInterface
public interface StaticFinalFieldMatchProcessor {
    public void processMatch(String className,
                             String fieldName,
                             Object fieldConstantValue);
}

/**
 * Call the given StaticFinalFieldMatchProcessor if classes are found on the classpath
 * that contain static final fields that match one of a set of fully-qualified field
 * names, e.g. "com.package.ClassName.STATIC_FIELD_NAME".
 * 
 * @param fullyQualifiedStaticFinalFieldNames
 *            The set of fully-qualified static field names to match.
 * @param staticFinalFieldMatchProcessor
 *            the StaticFinalFieldMatchProcessor to call when a match is found.
 */
public FastClasspathScanner matchStaticFinalFieldNames(
        HashSet<String> fullyQualifiedStaticFinalFieldNames,
        StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
    /* ... */
}

/**
 * (Convenience method if you're only looking to match a single field name)
 */
public FastClasspathScanner matchStaticFinalFieldNames(
        final String fullyQualifiedStaticFinalFieldName,
        final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
    /* ... */
}

/**
 * (Convenience method that allows you to list static field names in a varargs
 * parameter list. The parameters are reversed in this method, because the
 * varargs parameter must come last.)
 */
public FastClasspathScanner matchStaticFinalFieldNames(
        final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor,
        final String... fullyQualifiedStaticFinalFieldNames) {
    /* ... */
}
```

*Note:* Only static final fields with constant-valued literals are matched, not fields with initializer values that are the result of an expression or reference, except for cases where the compiler is able to simplify an expression into a single constant at compiletime, [such as in the case of string concatenation](https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-5.html#jvms-5.1). The following are examples of constant static final fields:

```java
public static final int w = 5;
public static final String x = "a";
static final String y = "a" + "b";  // Referentially equal to the interned String "ab"
private static final int z = 1;     // Private field values are also returned 
static final byte b = 0x7f;         // StaticFinalFieldMatchProcessor is passed a Byte
```

whereas the following fields are non-constant, non-static and/or non-final, so these fields cannot be matched:

```java
public static final Integer w = 5;  // Non-constant due to autoboxing
static final String y = "a" + w;    // Non-constant expression, because w is non-const
static final int[] arr = {1, 2, 3}; // Arrays are non-constant
static int n = 100;                 // Non-final
final int q = 5;                    // Non-static 
```

Primitive types (int, long, short, float, double, boolean, char, byte) are wrapped in the corresponding wrapper class (Integer, Long etc.) before being passed to the provided StaticFinalFieldMatchProcessor.

### Finding files (even non-classfiles) anywhere on the classpath whose path matches a given regular expression

This can be useful for detecting changes to non-classfile resources on the classpath, for example a web server's template engine can hot-reload HTML templates when they change by including the template directory in the classpath and then detecting changes to files that are in the template directory and have the extension ".html".

```java
/** The method to run when a file with a matching path is found on the classpath. */
@FunctionalInterface
public interface FileMatchProcessor {
    /**
     * Process a matching file.
     * 
     * @param absolutePath
     *            The path of the matching file on the filesystem.
     * @param relativePath
     *            The path of the matching file relative to the classpath entry that
     *            contained the match.
     * @param inputStream
     *            An InputStream (either a FileInputStream or a ZipEntry InputStream)
     *            opened on the file. You do not need to close this InputStream before
     *            returning, it is closed by the caller.
     */
    public void processMatch(String absolutePath,
                             String relativePath,
                             InputStream inputStream);
}

/**
 * Call the given FileMatchProcessor if files are found on the classpath with the given
 * regexp pattern in their path.
 * 
 * @param filenameMatchPattern
 *            The regexp to match, e.g. "app/templates/.*\\.html"
 * @param fileMatchProcessor
 *            The FileMatchProcessor to call when each match is found.
 */
public FastClasspathScanner matchFilenamePattern(
        String filenameMatchPattern,
        FileMatchProcessor fileMatchProcessor) {
    /* ... */
}
```

### Performing the actual scan

The `.scan()` method performs the actual scan. This method may be called multiple times after the initialization steps shown above, although there is usually no point performing additional scans unless `classpathContentsModifiedSinceScan()` returns true.

```java
/**
 * Scan classpath for matching files. Call this after all match processors have been
 * added.
 */
public void scan() { /* ... */ }
```

As the scan proceeds, for all match processors that deal with classfiles (i.e. for all but FileMatchProcessor), if the same fully-qualified class name is encountered more than once on the classpath, the second and subsequent definitions of the class are ignored, in order to follow Java's class masking behavior.

### Detecting changes to classpath contents after the scan

When the classpath is scanned using `.scan()`, the "latest last modified timestamp" found anywhere on the classpath is recorded (i.e. the latest timestamp out of all last modified timestamps of all files found within the whitelisted package prefixes on the classpath).

After a call to `.scan()`, it is possible to later call `.classpathContentsModifiedSinceScan()` at any point to check if something within the classpath has changed. This method does not look inside classfiles and does not call any match processors, but merely looks at the last modified timestamps of all files and zip/jarfiles within the whitelisted package prefixes of the classpath, updating the latest last modified timestamp if anything has changed. If the latest last modified timestamp increases, this method will return true.  

Since `.classpathContentsModifiedSinceScan()` only checks file modification timestamps, it works several times faster than the original call to `.scan()`. It is therefore a very lightweight operation that can be called in a polling loop to detect changes to classpath contents for hot reloading of resources.

```java
/**
 * Returns true if the classpath contents have been changed since .scan() was
 * last called.
 */
public boolean classpathContentsModifiedSinceScan() { /* ... */ }
```

### Get a list of all whitelisted (and non-blacklisted) classes and interfaces on the classpath

The names of all classes and interfaces reached during the scan, after taking into account whitelist and blacklist criteria, can be returned by calling the method `.getAllScannedClasses()` after calling `.scan()`. This can be helpful for debugging purposes.

```java
/**
 * Returns the names of all classes and interfaces processed during the scan, i.e. all classes
 * reachable after taking into account the package whitelist and blacklist criteria.
 */
public <T> Set<String> getNamesOfAllClasses() { /* ... */ }
```

### Get all unique directories and files on the classpath

The list of all directories and files on the classpath is returned by the following method. The list is filtered to include only unique classpath elements (duplicates are eliminated), and to include only directories and files that actually exist. The elements in the list are in classpath order.

```java
/**
 * Get a list of unique elements on the classpath (files and directories)
 * as File objects, preserving order. Classpath elements that do not exist
 * are not included in the list.
 */
public static ArrayList<File> getUniqueClasspathElements() { /* ... */ }
```

## Usage Caveats

### (1) Startup overhead of Java 8 Streams and lambda expressions

The usage examples above use lambda expressions (functional interfaces) and Stream patterns from Java 8 for simplicity. However, at least as of JDK 1.8.0 r20, lambda expressions and Streams each incur a one-time startup penalty of 30-40ms the first time they are used. If this overhead is prohibitive, use the pre-Java-8 form (explicitly creating a MatchProcessor, potentially using an inner class, but without lambda expressions, as shown in the introduction above).

### (2) Getting generic class references for parameterized classes

A problem arises when using class-based matchers with parameterized classes, e.g. `Widget<K>`. Because of type erasure, The expression `Widget<K>.class` is not defined, and therefore it is impossible to cast `Class<Widget>` to `Class<Widget<K>>`. More specifically:

* `Widget.class` has the type `Class<Widget>`, not `Class<Widget<?>>` 
* `new Widget<Integer>().getClass()` has the type `Class<? extends Widget>`, not `Class<? extends Widget<?>>`. The type `Class<? extends Widget>` can be cast to `Class<Widget<?>>` with an unchecked conversion warning.

The following code compiles and runs fine, but `SubclassMatchProcessor` must be parameterized with the bare type `Widget` in order to match the reference `Widget.class`. This causes the warning `Test.Widget is a raw type. References to generic type Test.Widget<K> should be parameterized` on `SubclassMatchProcessor<Widget>` and `Type safety: Unchecked cast from Class<capture#1-of ? extends Test.Widget> to Class<Test.Widget<?>>` on `(Class<? extends Widget<?>>)`. 

```java
public class Test {
    public static class Widget<K> {
        K id;
    }

    public static class WidgetSubclass<K> extends Widget<K> {
    }  

    public static void registerWidgetSubclass(Class<? extends Widget<?>> widgetClass) {
        System.out.println("Found widget subclass " + widgetClass.getName());
    }
    
    public static void main(String[] args) {
        new FastClasspathScanner("com.xyz.widget") //
            .matchSubclassesOf(Widget.class, new SubclassMatchProcessor<Widget>() {
                @Override
                public void processMatch(Class<? extends Widget> widgetClass) {
                    registerWidgetSubclass((Class<? extends Widget<?>>) widgetClass);
                }
            })
            .scan();
    }
}
``` 

**Solution 1:** Create an object of the desired type, call getClass(), and cast the result to the generic parameterized class type. (Note that `SubclassMatchProcessor<Widget<?>>` is now properly parameterized, and no cast is needed in the function call `registerWidgetSubclass(widgetClass)`.)

```java
public static void main(String[] args) {
    @SuppressWarnings("unchecked")
    Class<Widget<?>> widgetClass = (Class<Widget<?>>) new Widget<Object>().getClass();
        
    new FastClasspathScanner("com.xyz.widget") //
        .matchSubclassesOf(widgetClass, new SubclassMatchProcessor<Widget<?>>() {
            @Override
            public void processMatch(Class<? extends Widget<?>> widgetClass) {
                registerWidgetSubclass(widgetClass);
            }
        })
        .scan();
}
``` 

**Solution 2:** Get a class reference for a subclass of the desired class, then get the generic type of its superclass:

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
                registerWidgetSubclass(widgetClass);
            }
        })
        .scan();
}
``` 

## License

The MIT License (MIT)

Copyright (c) 2015 Luke Hutchison
 
Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 
The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Downloading

You can get a pre-built JAR from [Sonatype](https://oss.sonatype.org/#nexus-search;quick~fast-classpath-scanner), or add the following Maven Central dependency:

```xml
<dependency>
    <groupId>io.github.lukehutch</groupId>
    <artifactId>fast-classpath-scanner</artifactId>
    <version>latest_version</version>
</dependency>
```

## Credits

### Classfile format documentation

See Oracle's documentation on the [classfile format](http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html).

### Inspiration

FastClasspathScanner was inspired by Ronald Muller's [annotation-detector](https://github.com/rmuller/infomas-asl/tree/master/annotation-detector).

### Alternatives

[Reflections](https://github.com/ronmamo/reflections) could be a good alternative if Fast Classpath Scanner doesn't meet your needs.

## Author

Fast Classpath Scanner was written by Luke Hutchison -- https://github.com/lukehutch

Please [![Donate](https://www.paypalobjects.com/en_US/i/btn/btn_donate_SM.gif)](https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=8XNBMBYA8EC4Y) if this library makes your life easier.
