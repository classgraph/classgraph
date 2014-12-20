fast-classpath-scanner
======================

Uber-fast, ultra-lightweight Java classpath scanner. Scans the classpath by parsing the classfile binary format directly rather than by using reflection. (Reflection causes the classloader to load each class, which can take an order of magnitude more time than parsing the classfile directly.)

FastClasspathScanner is able to scan directories and jar/zip files on the classpath to:
* find classes that subclass a given class or one of its subclasses;
* find classes that implement an interface or one of its subinterfaces, or whose superclasses implement the interface or one of its subinterfaces;
* find classes that have a given annotation;
* find the constant literal initializer value in a classfile's constant pool for a specified static final field;
* find files (even non-classfiles) anywhere on the classpath that have a path that matches a given regular expression; and
* detect changes to the files within the classpath since the first time the classpath was scanned.

Usage examples below use lambda expressions and Stream patterns from Java 8.

```java

new FastClasspathScanner(
  // Whitelisted package prefixes to scan
  new String[] { "com.xyz.widget", "com.xyz.gizmo" })  
  
  .matchSubclassesOf(DBModel.class,
  // c is a subclass of DBModel or a descendant subclass
  c -> System.out.println("Subclasses DBModel: " + c.getName()))
  
  .matchClassesImplementing(Runnable.class,
  // c is a class that implements the interface Runnable; more precisely,
  // c or one of its superclasses implements the interface Runnable, or
  // implements an interface that is a descendant of Runnable
  c -> System.out.println("Implements Runnable: " + c.getName()))
  
  .matchClassesWithAnnotation(RestHandler.class,
  // c is a class annotated with @RestHandler
  c -> System.out.println("Has @RestHandler class annotation: " + c.getName()))
 
  .matchStaticFinalFieldNames(
  Stream.of("com.xyz.Config.POLL_INTERVAL", "com.xyz.Config.LOG_LEVEL")
           .collect(Collectors.toCollection(HashSet::new)),
      // The following method is called when any static final fields with
      // names matching one of the above fully-qualified names are
      // encountered, as long as those fields are initialized to constant
          // values. The value returned is the value in the classfile, not the
          // value that would be returned by reflection, so this can be useful
          // in hot-swapping of changes to static constants in classfiles if
          // the constant value is changed and the class is re-compiled while
          // the code is running. (Eclipse doesn't hot-replace static constant
          // initializer values if you change them while running code in the
          // debugger, so you can pick up changes this way instead). 
          // Note that the visibility of the fields is not checked; the value
          // of the field in the classfile is returned whether or not it
          // should be visible. 
          (String className, String fieldName, Object fieldConstantValue) ->
              System.out.println("Static field " + fieldName + " of class "
                  + className + " " + " has constant literal value "
                  + fieldConstantValue + " in classfile"))

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

# API

Note that most of the methods in the API return *this* (of type FastClasspathScanner), so that you can use the [method chaining](http://en.wikipedia.org/wiki/Method_chaining) calling style, as shown above.

### Whitelisting package prefixes in the call to the constructor

Calling the constructor does not actually start the scan. The constructor takes a whitelist of package prefixes that should be scanned. Whitelisting package prefixes of interest can dramatically speed up classpath scanning, because it limits the number of classfiles that need to be opened and read.

```java

public FastClasspathScanner(String[] pacakagesToScan) { /*...*/ }

```

### Matching the subclasses of a class

FastClasspathScanner can find all classes on the classpath within whitelisted package prefixes that extend a given superclass.

*Important note:* the ability to detect that a class or interface extends another depends upon the entire ancestral path between the two classes or interfaces being within one of the whitelisted package prefixes.

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
 *            The superclass to match (i.e. the class that subclasses need to extend
 *            in order to match).
 * @param subclassMatchProcessor
 *            the SubclassMatchProcessor to call when a match is found.
 */
public <T> FastClasspathScanner matchSubclassesOf(final Class<T> superclass,
        final SubclassMatchProcessor<T> subclassMatchProcessor) { /* ... */ }

```

Note that this method does not yet implement the detection of interfaces that extend other interfaces, only classes that extend other classes.

### Matching the classes that implement an interface

FastClasspathScanner can find all classes on the classpath within whitelisted package prefixes that that implement a given target interface. The matching logic here is trickier than it would seem, because FastClassPathScanner also has to match classes whose superclasses implement the target interface, or classes that implement a sub-interface (descendant interface) of the target interface, or classes whose superclasses implement a sub-interface of the target interface.

Note again that the ability to detect that a class or interface extends or implements another depends upon the entire ancestral path between the two classes or interfaces being within one of the whitelisted package prefixes.

```java

/**
 * The method to run when a class implementing a specific interface is found on the
 * classpath.
 */
@FunctionalInterface
public interface InterfaceMatchProcessor<T> {
    public void processMatch(Class<? extends T> matchingClass);
}

/**
 * Call the provided InterfaceMatchProcessor for classes on the classpath that
 * implement the specified interface or a sub-interface, or whose superclasses
 * implement the specified interface or a sub-interface.
 * 
 * @param iface
 *            The interface to match (i.e. the interface that classes need to implement
 *            to match).
 * @param interfaceMatchProcessor
 *            the ClassMatchProcessor to call when a match is found.
 */
public <T> FastClasspathScanner matchClassesImplementing(final Class<T> iface,
        final InterfaceMatchProcessor<T> interfaceMatchProcessor) { /* ... */ }

```

### Matching classes with a specific annotation

FastClassPathScanner can detect classes that have a class annotation that matches a given annotation. 

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
 *            The class annotation to match.
 * @param classAnnotationMatchProcessor
 *            the ClassAnnotationMatchProcessor to call when a match is found.
 */
public FastClasspathScanner matchClassesWithAnnotation(final Class<?> annotation,
       final ClassAnnotationMatchProcessor classAnnotationMatchProcessor) { /* ... */ }

```

### Fetching the constant initializer values of static final fields

FastClassPathScanner is able to scan the classpath for matching fully-qualified static final fields, e.g. for the fully-qualified field name "com.xyz.Config.POLL_INTERVAL", FastClassPathScanner will look in the class com.xyz.Config for the static final field POLL_INTERVAL, and if it is found, and if it has a constant literal initializer value, that value will be read directly from the classfile and passed into a provided StaticFinalFieldMatchProcessor.

Field values are obtained directly from the constant pool in a classfile, not from a loaded class using reflection. This allows you to detect changes to the classpath and then run another scan that picks up the new values of selected static constants without reloading the class. [(Class reloading is fraught with issues.)](http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html)

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
    public void processMatch(String className, String fieldName,
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
        final HashSet<String> fullyQualifiedStaticFinalFieldNames,
        final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
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
static final String y = "a" + w;    // Non-constant expression, because x is non-const
static final int[] arr = {1, 2, 3}; // Arrays are non-constant
static int n = 100;                 // Non-final
final int q = 5;                    // Non-static 

```

Primitive types (int, long, short, float, double, boolean, char, byte) are wrapped in the corresponding wrapper class (Integer, Long etc.) before being passed to the provided StaticFinalFieldMatchProcessor.

**Note:** Visibility modifiers of matching fields are not checked, so the constant literal initializer value of matching fields will be returned even in cases where fields are private, package-private or protected.  

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
    public void processMatch(String absolutePath, String relativePath,
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
public FastClasspathScanner matchFilenamePattern(final String filenameMatchPattern,
        final FileMatchProcessor fileMatchProcessor) { /* ... */ }

```

### Detecting changes to classpath contents

When the classpath is scanned using scan(), the "latest last modified timestamp" found anywhere on the classpath is recorded (i.e. the latest timestamp out of all last modified timestamps of all files found within the whitelisted package prefixes on the classpath).

After a call to scan(), it is possible to later call classpathContentsModifiedSinceScan() at any point to check if something within the classpath has changed. This method does not look inside classfiles and does not call any match processors, but merely looks at the last modified timestamps of all files and zip/jarfiles within the whitelisted package prefixes of the classpath, updating the latest last modified timestamp if anything has changed. If the latest last modified timestamp increases, this method will return true.  

Since classpathContentsModifiedSinceScan() only checks file modification timestamps, it works several times faster than the original call to scan(). It is therefore a very lightweight operation that can be called in a polling loop to detect changes to classpath contents for hot reloading of resources.

```java

/**
 * Returns true if the classpath contents have been changed since scan() was
 * last called.
 */
public boolean classpathContentsModifiedSinceScan() { /* ... */ }

```

### Performing the actual scan

The scan() method performs the actual scan. This method may be called multiple times after the initialization steps shown above, although there is usually no point performing additional scans unless classpathContentsModifiedSinceScan() returns true.

```java

/**
 * Scan classpath for matching files. Call this after all match processors have been
 * added.
 */
public void scan() { /* ... */ }

```

As the scan proceeds, for all match processors that deal with classfiles (i.e. for all but FileMatchProcessor), if the same fully-qualified class name is encountered more than once on the classpath, the second and subsequent definitions of the class are ignored, in order to follow Java's class masking behavior.

## Credits

### Inspiration

FastClasspathScanner was inspired by Ronald Muller's [annotation-detector](https://github.com/rmuller/infomas-asl/tree/master/annotation-detector).

### Author

Luke Hutchison (luke .dot. hutch .at. gmail .dot. com)

**Please let me know if you find FastClasspathScanner useful!**

### Classfile format documentation

See Oracle's documentation on the [classfile format](http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html).

### License

The MIT License (MIT)

Copyright (c) 2014 Luke Hutchison
 
Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 
The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
