fast-classpath-scanner
======================

Uber-fast, ultra-lightweight Java classpath scanner. Scans the classpath by parsing the classfile binary format directly rather than by using reflection. (Reflection causes the classloader to load each class, which can take an order of magnitude more time than parsing the classfile directly.)

This classpath scanner is able to scan directories and jar/zip files on the classpath to locate:
* classes that subclass a given class or one of its subclasses
* classes that implement an interface or one of its subinterfaces
* classes that have a given annotation
* file paths (even for non-classfiles) anywhere on the classpath that match a given regexp.

Usage example (with Java 8 lambda expressions):

```java

    new FastClasspathScanner(
          // Whitelisted package prefixes to scan
          new String[] { "com.xyz.widget", "com.xyz.gizmo" })  
          
      .matchSubclassesOf(DBModel.class,
          // c is a subclass of DBModel
          c -> System.out.println("Subclasses DBModel: " + c.getName()))
          
      .matchClassesImplementing(Runnable.class,
          // c is a class that implements Runnable
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

```

*Important note:* you need to pass a whitelist of package prefixes to scan into the constructor, and the ability to detect that a class or interface extends another depends upon the entire ancestral path between the two classes or interfaces having one of the whitelisted package prefixes.

When matching involves classfiles (i.e. in all cases except FastClasspathScanner#matchFilenamePattern, which deals with arbitrary files on the classpath), if the same fully-qualified class name is encountered more than once on the classpath, the second and subsequent definitions of the class are ignored.

The scanner also records the latest last-modified timestamp of any file or directory encountered, and you can see if that latest last-modified timestamp has increased (indicating that something on the classpath has been updated) by calling

```java
    boolean classpathContentsModified =
        fastClassPathScanner.classpathContentsModifiedSinceScan();
```

This can be used to enable dynamic class-reloading if something on the classpath is updated, for example to support hot-replace of route handler classes in a webserver. The above call is several times faster than the original call to scan(), since only modification timestamps need to be checked.

Inspired by: https://github.com/rmuller/infomas-asl/tree/master/annotation-detector

See also: http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.4

Please let me know if you find this useful!

Author: Luke Hutchison (luke .dot. hutch .at. gmail .dot. com)

License: MIT
