# FastClasspathScanner

FastClasspathScanner is an uber-fast, ultra-lightweight classpath scanner for Java, Scala and other JVM languages.

**What is classpath scanning?** Classpath scanning involves scanning directories and jar/zip files on the classpath to find files (especially classfiles) that meet certain criteria. In many ways, classpath scanning offers the *inverse of the Java reflection API:*

* The Java reflection API can tell you the superclass of a given class, but classpath scanning can find all classes that extend a given superclass.
* The Java reflection API can give you the list of annotations on a given class, but classpath scanning can find all classes that are annotated with a given annotation.
* etc. (Many other classpath scanning objectives are listed below.)

**FastClasspathScanner is able to:**

1. [find classes that subclass/extend a given class](https://github.com/lukehutch/fast-classpath-scanner/wiki/3.1.-Finding-subclasses-or-superclasses);
2. [find classes that implement an interface](https://github.com/lukehutch/fast-classpath-scanner/wiki/3.2.-Finding-classes-that-implement-an-interface) or one of its subinterfaces, or whose superclasses implement the interface or one of its subinterfaces;
3. [find interfaces that extend a given interface](https://github.com/lukehutch/fast-classpath-scanner/wiki/3.3.-Finding-subinterfaces-or-superinterfaces) or one of its subinterfaces;
4. [find classes that have a specific class annotation or meta-annotation](https://github.com/lukehutch/fast-classpath-scanner/wiki/3.4.-Finding-classes-with-specific-annotations-or-meta-annotations);
5. read the constant literal initializer value in a classfile's constant pool for a [specified static final field](https://github.com/lukehutch/fast-classpath-scanner/wiki/3.5.-Reading-constant-initializer-values-of-static-final-fields);
6. [find all classes that contain a field of a given type](https://github.com/lukehutch/fast-classpath-scanner/wiki/3.6.-Finding-classes-with-fields-of-a-given-type) (including identifying fields based on array element type and generic parameter type); 
7. find files (even non-classfiles) anywhere on the classpath that have a [path that matches a given string or regular expression](https://github.com/lukehutch/fast-classpath-scanner/wiki/3.7.-Finding-classpath-files-based-on-filename-pattern);
8. return a list of the [names of all classes, interfaces and/or annotations on the classpath](https://github.com/lukehutch/fast-classpath-scanner/wiki/3.8.-Enumerating-all-classes-on-the-classpath) (after whitelist and blacklist filtering);
9. [generate a GraphViz .dot file](https://github.com/lukehutch/fast-classpath-scanner/wiki/3.9.-Generating-a-GraphViz-dot-file-from-the-classgraph) from the class graph for visualization purposes, as shown below. A class graph visualizatoin depicts connections between classes, interfaces, annotations and meta-annotations, and connections between classes and the types of their fields.
10. return a list of [all directories and files on the classpath](https://github.com/lukehutch/fast-classpath-scanner/wiki/1.-Usage#listing-classpath-elements) (i.e. all classpath elements) as a list of File objects, with the list deduplicated and filtered to include only classpath directories and files that actually exist, saving you from the complexities of working with the classpath and classloaders; and
11. [detect changes](https://github.com/lukehutch/fast-classpath-scanner/wiki/1.-Usage#detecting-changes-to-classpath-contents) to the files within the classpath since the first time the classpath was scanned;

<p align="center">
  <img src="https://github.com/lukehutch/fast-classpath-scanner/blob/master/src/test/java/com/xyz/classgraph-fig.png" alt="Class graph visualization"/>
</p>

## Documentation

[See the wiki for full documentation.](https://github.com/lukehutch/fast-classpath-scanner/wiki)

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
