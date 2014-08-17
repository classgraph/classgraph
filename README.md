fast-classpath-scanner
======================

Uber-fast classpath scanner. Scans the classpath by reading the classfile binary format directly to avoid calling the classloader. (Calling the classloader for all classes on classpath to probe the classes with reflection can take five times longer.)

This classpath scanner is able to find:
* classes that subclass a given class or one of its subclasses
* classes that implement an interface or one of its subinterfaces, and
* classes that have a given annotation.

Usage example (uses Java 8 FunctionalInterface / lambda):

```java
    new ClasspathScanner(new String[]
          { "com.xyz.widget", "com.xyz.gizmo" })  // Whitelisted packages to scan
      .matchSubclassesOf(DBModel.class,
          // c is the matched class
          c -> System.out.println("Found subclass of DBModel: " + c.getName()))
      .matchClassesImplementing(Runnable.class,
          c -> System.out.println("Found Runnable: " + c.getName()))
      .matchClassesWithAnnotation(RestHandler.class,
          c -> System.out.println("Found RestHandler annotation on class: " + c.getName()))
      .scan();  // Actually perform the scan
```

Note that you need to pass a whitelist of packages to scan into the constructor, and the ability to detect that one class or interface extends another depends upon the entire ancestral path between the two classes or interfaces being within the whitelisted packages.

Inspired by: https://github.com/rmuller/infomas-asl/tree/master/annotation-detector

See also: http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.4

Let me know if you find this useful!

Author: Luke Hutchison (luke .dot. hutch .at. gmail .dot. com)

License: MIT
