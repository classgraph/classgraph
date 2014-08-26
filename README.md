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
    new FastClasspathScanner(new String[]
          { "com.xyz.widget", "com.xyz.gizmo" })  // Whitelisted package prefixes to scan
          
      .matchSubclassesOf(DBModel.class,
          // c is a subclass of DBModel
          c -> System.out.println("Subclasses DBModel: " + c.getName()))
          
      .matchClassesImplementing(Runnable.class,
          // c is a class that implements Runnable
          c -> System.out.println("Implements Runnable: " + c.getName()))
          
      .matchClassesWithAnnotation(RestHandler.class,
          // c is a class annotated with @RestHandler
          c -> System.out.println("Has @RestHandler class annotation: " + c.getName()))
          
      .matchFilenamePattern("^template/.*\\.html",
          // templatePath is a path on the classpath that matches the above pattern;
          // inputStream is a stream opened on the file or zipfile entry.
          // No need to close inputStream before exiting, it is closed by caller.
          (templatePath, inputStream) -> {
              try {
                  BufferedReader reader = new BufferedReader(
                          new InputStreamReader(inputStream, "UTF-8"));
                  StringBuilder buf = new StringBuilder();
                  for (String line; (line = reader.readLine()) != null;) {
                      buf.append(line);
                      buf.append('\n');
                  }
                  System.out.println("Found template: " + templatePath
                          + " (size " + buf.length() + ")");
              } catch (IOException e) {
                  throw new RuntimeException(e);
              }
          })

      .scan();  // Actually perform the scan
```

Note that you need to pass a whitelist of package prefixes to scan into the constructor, and the ability to detect that a class or interface extends another depends upon the entire ancestral path between the two classes or interfaces having one of the whitelisted package prefixes.

The scanner also records the latest last-modified timestamp of any file or directory encountered, and you can see if that latest last-modified timestamp has increased (indicating that something on the classpath has been updated) by calling

```java
    boolean classpathContentsModified =
        fastClassPathScanner.classpathContentsModifiedSinceScan();
```

This can be used to enable dynamic class-reloading if something on the classpath is updated, for example to support hot-replace of route handler classes in a webserver. The above call is several times faster than the original call to scan(), since only modification timestamps need to be checked.

Inspired by: https://github.com/rmuller/infomas-asl/tree/master/annotation-detector

See also: http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.4

Let me know if you find this useful!

Author: Luke Hutchison (luke .dot. hutch .at. gmail .dot. com)

License: MIT
