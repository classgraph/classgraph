FastClasspathScanner
====================

Uber-fast, ultra-lightweight Java classpath scanner. Scans the classpath by parsing the classfile binary format directly rather than by using reflection. (Reflection causes the classloader to load each class, which can take an order of magnitude more time than parsing the classfile directly, and can lead to unexpected behavior due to static initializer blocks of classes being called on class load.)

FastClasspathScanner is able to scan directories and jar/zip files on the classpath to:

1. [find classes that subclass a given class](#1-matching-the-subclasses-or-finding-the-superclasses-of-a-class) or one of its subclasses;
2. [find interfaces that extend a given interface](#2-matching-the-subinterfaces-or-finding-the-superinterfaces-of-an-interface) or one of its subinterfaces;
3. [find classes that implement an interface](#3-matching-the-classes-that-implement-an-interface) or one of its subinterfaces, or whose superclasses implement the interface or one of its subinterfaces;
4. [find classes that have a specific class annotation](#4-matching-classes-with-a-specific-annotation);
5. find the constant literal initializer value in a classfile's constant pool for a [specified static final field](#5-fetching-the-constant-initializer-values-of-static-final-fields);
6. find files (even non-classfiles) anywhere on the classpath that have a [path that matches a given regular expression](#6-finding-files-even-non-classfiles-anywhere-on-the-classpath-whose-path-matches-a-given-regular-expression);
7. perform the actual [classpath scan](#7-performing-the-actual-scan);
8. [detect changes](#8-detecting-changes-to-classpath-contents-after-the-scan) to the files within the classpath since the first time the classpath was scanned;
9. return a list of [all directories and files on the classpath](#9-get-a-list-of-all-whitelisted-and-non-blacklisted-classes-and-interfaces-on-the-classpath) (i.e. all classpath elements) as a list of File objects, with the list deduplicated and filtered to include only classpath directories and files that actually exist, saving you the trouble of parsing and filtering the classpath; and
10. return a list of the [names of all classes and interfaces on the classpath](#10-get-all-unique-directories-and-files-on-the-classpath) (after whitelist and blacklist filtering).

### Usage

There are two different ways to use the FastClasspathScanner to match classes and interfaces. (Both mechanisms can be combined.)

**Method 1:** Create a FastClasspathScanner instance, listing package prefixes to scan within, then add one or more [`MatchProcessor`](https://github.com/lukehutch/fast-classpath-scanner/tree/master/src/main/java/io/github/lukehutch/fastclasspathscanner/matchprocessor) instances to the FastClasspathScanner by calling the FastClasspathScanner's `.match...()` methods, followed by calling `.scan()` to start the scan. This is the pattern shown in the following example, where Java 8 lambda expressions are used to implicitly create the appropriate type of MatchProcessor corresponding to each `.match...()` method:
 
```java
// Whitelisted package prefixes are listed in the constructor
// (can also blacklist packages by prefixing with "-")
new FastClasspathScanner("com.xyz.widget", "com.xyz.gizmo")  
    .matchSubclassesOf(Widget.class,
        // c is a subclass of Widget or a descendant subclass
        c -> System.out.println("Subclass of Widget: " + c.getName()))
    .matchSubinterfacesOf(Tweakable.class,
        // c is an interface that extends the interface Tweakable
        c -> System.out.println("Subinterface of Tweakable: " + c.getName()))
    .matchClassesImplementing(Changeable.class,
        // c is a class that implements the interface Changeable; more precisely,
        // c or one of its superclasses implements the interface Changeable, or
        // implements an interface that is a descendant of Changeable
        c -> System.out.println("Implements Changeable: " + c.getName()))
    .matchClassesWithAnnotation(BindTo.class,
        // c is a class annotated with BindTo
        c -> System.out.println("Has a BindTo class annotation: " + c.getName()))
    .matchStaticFinalFieldNames("com.xyz.widget.Widget.LOG_LEVEL",
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
        // absolutePath is a path on the classpath that matches the above pattern;
        // relativePath is just the section of the matching path relative to the
        // classpath element it is contained in; inputStream is a stream opened
        // on the file or zipfile entry that matched the requested filename pattern.
        // No need to close inputStream before exiting, it is closed by the caller.
        // The passed method can throw IOException.
        (absolutePath, relativePath, inputStream) -> {
            String template = IOUtils.toString(inputStream, "UTF-8");
            System.out.println("Found template: " + absolutePath
                + " (size " + template.length() + ")");
        })
    .scan();  // Actually perform the scan

// [...Some time later...]
// See if any timestamps on the classpath are more recent than the time of the
// previous scan. (Even faster than classpath scanning, because classfiles
// don't have to be opened.)   
boolean classpathContentsModified =
    fastClassPathScanner.classpathContentsModifiedSinceScan();
```

**Method 2:** Create a FastClasspathScanner instance, potentially without adding any MatchProcessors, call scan() to scan the classpath, then call `.getNamesOf...()` methods to find classes and interfaces of interest without actually calling the classloader on any matching classes. The `.getNamesOf...()` methods return lists of strings, rather than lists of `Class<?>` references, and scanning is done by reading the classfile directly, so the classloader does not need to be called for these methods to return their results. This can be useful if the static initializer code for matching classes would trigger unwanted side effects if run during a classpath scan. An example of this usage pattern is:

```java
List<String> subclassesOfWidget = new FastClasspathScanner("com.xyz.widget")
    // No need to add any MatchProcessors, just create a new scanner and then call
    // .scan() to parse the class hierarchy of all classfiles on the classpath.
    .scan()
    // Get the names of all subclasses of Widget on the classpath,
    // again without calling the classloader:
    .getNamesOfSubclassesOf("com.xyz.widget.Widget");
```

### Tips

**Using Java 8 method references:** The `.match...()` methods (e.g. `.matchSubclassesOf()`) take a [`MatchProcessor`](https://github.com/lukehutch/fast-classpath-scanner/tree/master/src/main/java/io/github/lukehutch/fastclasspathscanner/matchprocessor) as one of their arguments, which are single-method classes (i.e. FunctionalInterfaces). Java 8 method references may also be used as FunctionalInterfaces, e.g. `list::add`:

```java
List<Class<? extends Widget>> collector = new ArrayList<>();
new FastClasspathScanner("com.xyz.widget")
    .matchSubclassesOf(Widget.class, collector::add)
    .scan();
```

**Calling from Java 7 and below:** The pre-Java-8 mechanism is as follows (note that there is a different [`MatchProcessor`](https://github.com/lukehutch/fast-classpath-scanner/tree/master/src/main/java/io/github/lukehutch/fastclasspathscanner/matchprocessor) class corresponding to each `.match...()` method, e.g. `.matchSubclassesOf()` takes a `SubclassMatchProcessor`):

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

See also [Usage Caveats](#usage-caveats) below.

# API

Note that most of the methods in the API return *this* (of type FastClasspathScanner), so that you can use the [method chaining](http://en.wikipedia.org/wiki/Method_chaining) calling style, as shown in the example above.

## Constructor

Calling the constructor does not actually start the scan. The constructor takes a whitelist and/or a blacklist of package prefixes that should be scanned.

**Whitelisting package prefixes:** Whitelisted package prefixes can dramatically speed up classpath scanning, because it limits the number of classfiles that need to be opened and read, e.g. `new FastClasspathScanner("com.xyz.widget")` will scan inside package `com.xyz.widget` as well as any child packages like `com.xyz.widget.button`. If no whitelisted packages are specified (i.e. if the constructor is called without arguments), or if one of the whitelisted package names is "" or "/", all classfiles in the classpath will be scanned.

**Blacklisting package prefixes:** If a package name is listed in the constructor prefixed with the hyphen (`-`) character, e.g. `"-com.xyz.widget.slider"`, then the package name (without the leading hyphen) will be blacklisted, rather than whitelisted. The final list of packages scanned is the set of whitelisted packages minus the set of blacklisted packages. Blacklisting is useful for excluding an entire sub-tree of a whitelisted package.

```java
// Constructor for FastClasspathScanner
public FastClasspathScanner(String... pacakagesToScan)
```

Note that if you don't specify any whitelisted package prefixes, i.e. `new FastClasspathScanner()`, all packages on the classpath will be scanned. ("Scanning" involves parsing the classfile binary format to determine class and interface relationships.)

## API calls for each use case

### 1. Matching the subclasses (or finding the superclasses) of a class

FastClasspathScanner can find all classes on the classpath within whitelisted package prefixes that extend a given superclass.

*Important note:* the ability to detect that a class extends another depends upon the entire ancestral path between the two classes being within one of the whitelisted package prefixes.

There are also methods `List<String> getNamesOfSubclassesOf(String superclassName)` and `List<String> getNamesOfSubclassesOf(Class<T> superclass)` that can be called after `.scan()` to find the names of the subclasses of a given class (whether or not a corresponding match processor was added to detect this). These methods will return the matching classes without calling the classloader, whereas if a match processor is used, the classloader is called first (using Class.forName()) so that a class reference can be passed into the match processor.

Furthermore, the methods `List<String> getNamesOfSuperclassesOf(String subclassName)` and `List<String> getNamesOfSuperclassesOf(Class<T> subclass)` are able to return all superclasses of a given class after a call to `.scan()`. (Note that there is not currently a SuperclassMatchProcessor or .matchSuperclassesOf().)

#### MatchProcessor:
```java
@FunctionalInterface
public interface SubclassMatchProcessor<T> {
    public void processMatch(Class<? extends T> matchingClass);
}
```
#### Methods:
```java
public <T> FastClasspathScanner matchSubclassesOf(Class<T> superclass,
    SubclassMatchProcessor<T> subclassMatchProcessor)

public <T> List<String> getNamesOfSubclassesOf(final Class<T> superclass) 

public List<String> getNamesOfSubclassesOf(String superclassName)

public <T> List<String> getNamesOfSuperclassesOf(final Class<T> subclass)

public List<String> getNamesOfSuperclassesOf(String subclassName)
```

### 2. Matching the subinterfaces (or finding the superinterfaces) of an interface

FastClasspathScanner can find all interfaces on the classpath within whitelisted package prefixes that that extend a given interface or its subinterfaces.

*Important note:* The ability to detect that an interface extends another interface depends upon the entire ancestral path between the two interfaces being within one of the whitelisted package prefixes.

There are also methods `List<String> getNamesOfSubinterfacesOf(String ifaceName)` and `List<String> getNamesOfSubinterfacesOf(Class<T> iface)` that can be called after `.scan()` to find the names of the subinterfaces of a given interface (whether or not a corresponding match processor was added to detect this). These methods will return the matching interfaces without calling the classloader, whereas if a match processor is used, the classloader is called first (using Class.forName()) so that a class reference for the matching interface can be passed into the match processor.

Furthermore, the methods `List<String> getNamesOfSuperinterfacesOf(String ifaceName)` and `List<String> getNamesOfSuperinterfacesOf(Class<T> iface)` are able to return all superinterfaces of a given interface after a call to `.scan()`. (Note that there is not currently a SuperinterfaceMatchProcessor or .matchSuperinterfacesOf().)

#### MatchProcessor:
```java
@FunctionalInterface
public interface SubinterfaceMatchProcessor<T> {
    public void processMatch(Class<? extends T> matchingInterface);
}
```
#### Methods:
```java
public <T> FastClasspathScanner matchSubinterfacesOf(final Class<T> superInterface,
    final SubinterfaceMatchProcessor<T> subinterfaceMatchProcessor)

public <T> List<String> getNamesOfSubinterfacesOf(final Class<T> superInterface)

public List<String> getNamesOfSubinterfacesOf(final String superInterfaceName)

public <T> List<String> getNamesOfSuperinterfacesOf(final Class<T> subinterface)

public List<String> getNamesOfSuperinterfacesOf(String subinterfaceName)
```

### 3. Matching the classes that implement an interface

FastClasspathScanner can find all classes on the classpath within whitelisted package prefixes that that implement a given interface. The matching logic here is trickier than it would seem, because FastClassPathScanner also has to match classes whose superclasses implement the target interface, or classes that implement a sub-interface (descendant interface) of the target interface, or classes whose superclasses implement a sub-interface of the target interface.

*Important note:* The ability to detect that a class implements an interface depends upon the entire ancestral path between the class and the interface (and any relevant sub-interfaces or superclasses along the path between the two) being within one of the whitelisted package prefixes.

There are also methods `List<String> getNamesOfClassesImplementing(String ifaceName)` and `List<String> getNamesOfClassesImplementing(Class<T> iface)` that can be called after `.scan()` to find the names of the classes implementing a given interface (whether or not a corresponding match processor was added to detect this). These methods will return the matching classes without calling the classloader, whereas if a match processor is used, the classloader is called first (using Class.forName()) so that a class reference can be passed into the match processor.

#### MatchProcessor:
```java
@FunctionalInterface
public interface InterfaceMatchProcessor<T> {
    public void processMatch(Class<? extends T> implementingClass);
}
```
#### Methods:
```java
public <T> FastClasspathScanner matchClassesImplementing(Class<T> implementedInterface,
    InterfaceMatchProcessor<T> interfaceMatchProcessor)

public <T> List<String> getNamesOfClassesImplementing(final Class<T> implementedInterface)

public List<String> getNamesOfClassesImplementing(final String implementedInterfaceName)
```

### 4. Matching classes with a specific annotation

FastClassPathScanner can detect classes that have a class annotation that matches a given annotation. 

There are also methods `List<String> getNamesOfClassesWithAnnotation(String annotationClassName)` and `List<String> getNamesOfClassesWithAnnotation(Class<T> annotationClass)` that can be called after `.scan()` to find the names of the classes that have a given annotation (whether or not a corresponding match processor was added to detect this). These methods will return the matching classes without calling the classloader, whereas if a match processor is used, the classloader is called first (using Class.forName()) so that a class reference can be passed into the match processor.

#### MatchProcessor:
```java
@FunctionalInterface
public interface ClassAnnotationMatchProcessor {
    public void processMatch(Class<?> matchingClass);
}
```
#### Methods:
```java
public FastClasspathScanner matchClassesWithAnnotation(Class<?> annotation,
    ClassAnnotationMatchProcessor classAnnotationMatchProcessor)

public <T> List<String> getNamesOfClassesWithAnnotation(final Class<?> annotation)

public List<String> getNamesOfClassesWithAnnotation(final String annotationName)
```

### 5. Fetching the constant initializer values of static final fields

FastClassPathScanner is able to scan the classpath for matching fully-qualified static final fields, e.g. for the fully-qualified field name "com.xyz.Config.POLL_INTERVAL", FastClassPathScanner will look in the class com.xyz.Config for the static final field POLL_INTERVAL, and if it is found, and if it has a constant literal initializer value, that value will be read directly from the classfile and passed into a provided StaticFinalFieldMatchProcessor.

Field values are obtained directly from the constant pool in a classfile, not from a loaded class using reflection. This allows you to detect changes to the classpath and then run another scan that picks up the new values of selected static constants without reloading the class. [(Class reloading is fraught with issues.)](http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html)

This can be useful in hot-swapping of changes to static constants in classfiles if the constant value is changed and the class is re-compiled while the code is running. (Neither the JVM nor the Eclipse debugger will hot-replace static constant initializer values if you change them while running code, so you can pick up changes this way instead). 

**Note:** The visibility of the fields is not checked; the value of the field in the classfile is returned whether or not it should be visible to the caller. Therefore you should probably only use this method with public static final fields (not just static final fields) to coincide with Java's own semantics.

#### MatchProcessor:
```java
@FunctionalInterface
public interface StaticFinalFieldMatchProcessor {
    public void processMatch(String className, String fieldName, Object fieldConstantValue);
}
```
#### Methods:
```java
public FastClasspathScanner matchStaticFinalFieldNames(
    HashSet<String> fullyQualifiedStaticFinalFieldNames,
    StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor)

public FastClasspathScanner matchStaticFinalFieldNames(
    final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor,
    final String... fullyQualifiedStaticFinalFieldNames)
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

### 6. Finding files (even non-classfiles) anywhere on the classpath whose path matches a given regular expression

This can be useful for detecting changes to non-classfile resources on the classpath, for example a web server's template engine can hot-reload HTML templates when they change by including the template directory in the classpath and then detecting changes to files that are in the template directory and have the extension ".html".

#### MatchProcessor:

A `FileMatchProcessor` is passed the `InputStream` for any `File` or `ZipFileEntry` in the classpath that has a path matching the pattern provided in the `.matchFilenamePattern()` method. You do not need to close the passed `InputStream` if you choose to read the stream contents; the stream is closed by the caller.

The value of `relativePath` is relative to the classpath entry that contained the matching file.

```java
/** The method to run when a file with a matching path is found on the classpath. */
@FunctionalInterface
public interface FileMatchProcessor {
    public void processMatch(String absolutePath, String relativePath, InputStream inputStream)
        throws IOException;
}
```
#### Methods:
```java
public FastClasspathScanner matchFilenamePattern(String filenameMatchPattern,
        FileMatchProcessor fileMatchProcessor)
```

### 7. Performing the actual scan

The `.scan()` method performs the actual scan. This method may be called multiple times after the initialization steps shown above, although there is usually no point performing additional scans unless `classpathContentsModifiedSinceScan()` returns true.

```java
public void scan()
```

As the scan proceeds, for all match processors that deal with classfiles (i.e. for all but FileMatchProcessor), if the same fully-qualified class name is encountered more than once on the classpath, the second and subsequent definitions of the class are ignored, in order to follow Java's class masking behavior.

### 8. Detecting changes to classpath contents after the scan

When the classpath is scanned using `.scan()`, the "latest last modified timestamp" found anywhere on the classpath is recorded (i.e. the latest timestamp out of all last modified timestamps of all files found within the whitelisted package prefixes on the classpath).

After a call to `.scan()`, it is possible to later call `.classpathContentsModifiedSinceScan()` at any point to check if something within the classpath has changed. This method does not look inside classfiles and does not call any match processors, but merely looks at the last modified timestamps of all files and zip/jarfiles within the whitelisted package prefixes of the classpath, updating the latest last modified timestamp if anything has changed. If the latest last modified timestamp increases, this method will return true.  

Since `.classpathContentsModifiedSinceScan()` only checks file modification timestamps, it works several times faster than the original call to `.scan()`. It is therefore a very lightweight operation that can be called in a polling loop to detect changes to classpath contents for hot reloading of resources.

```java
public boolean classpathContentsModifiedSinceScan()
```

### 9. Get a list of all whitelisted (and non-blacklisted) classes and interfaces on the classpath

The names of all classes and interfaces reached during the scan, after taking into account whitelist and blacklist criteria, can be returned by calling the method `.getNamesOfAllClasses()` after calling `.scan()`. This can be helpful for debugging purposes.

Note that system classes (e.g. java.lang.String) do not need to be explicitly included on the classpath, so they are not typically returned in this list. However, any classes *referenced by* classes on the classpath (and, more specifically, any classes referenced by whitelisted classes), will be returned in this list. For example, any classes encountered that do not extend another class will reference java.lang.Object, so java.lang.Object will be returned in this list. 

```java
public <T> Set<String> getNamesOfAllClasses()
```

### 10. Get all unique directories and files on the classpath

The list of all directories and files on the classpath is returned by the following method. The list is filtered to include only unique classpath elements (duplicates are eliminated), and to include only directories and files that actually exist. The elements in the list are in classpath order.

```java
public static ArrayList<File> getUniqueClasspathElements()
```

## Usage Caveats

### (i) Startup overhead of Java 8 Streams and lambda expressions

The usage examples above use lambda expressions (functional interfaces) and Stream patterns from Java 8 for simplicity. However, at least as of JDK 1.8.0 r20, lambda expressions and Streams each incur a one-time startup penalty of 30-40ms the first time they are used. If this overhead is prohibitive, use the pre-Java-8 form (explicitly creating a MatchProcessor, potentially using an inner class, but without lambda expressions, as shown in the introduction above).

### (ii) Getting generic class references for parameterized classes

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
