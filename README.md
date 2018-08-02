# ClassGraph

[![Build Status](https://travis-ci.org/classgraph/classgraph.png?branch=master)](https://travis-ci.org/classgraph/classgraph)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.github.classgraph/classgraph/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.github.classgraph/classgraph)
[![Javadocs](http://www.javadoc.io/badge/io.github.classgraph/classgraph.svg)](https://javadoc.io/doc/io.github.classgraph/classgraph)
[![Gitter](https://img.shields.io/badge/GITTER-join%20chat-green.svg)](https://gitter.im/classgraph/Lobby)

<img alt="ClassGraph Logo" width="320" height="320" src="https://github.com/classgraph/classgraph/wiki/ClassGraphLogo.png">

ClassGraph (formerly **FastClasspathScanner**) is an uber-fast, ultra-lightweight classpath scanner, module scanner, and annotation processor for Java, Scala, Kotlin and other JVM languages. 

ClassGraph has the ability to "invert" the Java class and/or reflection API: for example, the Java class and reflection API can tell you the interfaces implemented by a given class, or can give you the list of annotations on a class; ClassGraph can find **all classes that implement a given interface**, or can find **all classes that are annotated with a given annotation**.

ClassGraph provides a number of important capabilities to the JVM ecosystem:

* ClassGraph has the ability to build a model in memory of the entire relatedness graph of all classes, annotations, interfaces, methods and fields that are visible to the JVM. This graph can be [queried in a wide range of ways](https://github.com/classgraph/classgraph/wiki/Code-examples).
* ClassGraph reads the classfile bytecode format directly, so it can read all information about classes without loading or initializing them.
* ClassGraph is fully compatible with the new JPMS module system (Project Jigsaw / JDK 9+), i.e. it can scan both the traditional classpath and the visible Java modules. However, the code is also fully backwards compatible with JDK 7 and JDK 8 (i.e. it is compiled to be callable from a JDK 7 project).
* ClassGraph scans the classpath or module path using [carefully optimized multithreaded code](https://github.com/classgraph/classgraph/wiki/How-fast-is-ClassGraph%3F) for the shortest possible scan times, and it runs as close as possible to I/O bandwidth limits, even on a fast SSD.
* ClassGraph handles more [classpath specification mechanisms](https://github.com/classgraph/classgraph/wiki/Classpath-specification-mechanisms) found in the wild than any other classpath scanner, making code that depends upon ClassGraph maximally portable.
* ClassGraph can scan the classpath and module path either at runtime or [at build time](https://github.com/classgraph/classgraph/wiki/Build-Time-Scanning) (e.g. to implement annotation processing for Android).
* ClassGraph can create GraphViz visualizations of the class graph structure, which can help with code understanding: (click to enlarge | [see graph legend here](https://github.com/lukehutch/fast-classpath-scanner/blob/master/src/test/java/com/xyz/classgraph-fig-legend.png))

<p align="center">
  <a href="https://raw.githubusercontent.com/lukehutch/fast-classpath-scanner/master/src/test/java/com/xyz/classgraph-fig.png"><img src="https://github.com/lukehutch/fast-classpath-scanner/blob/master/src/test/java/com/xyz/classgraph-fig.png" width="898" height="685" alt="Class graph visualization"/></a>
</p>

## Documentation

[See the wiki for complete documentation and usage information.](https://github.com/classgraph/classgraph/wiki)

See the [code examples](https://github.com/classgraph/classgraph/wiki/Code-examples) page for examples of how to use the ClassGraph API.


## Status

**FastClasspathScanner was renamed to ClassGraph, and released as version 4**.

ClassGraph has a completely revamped API. See the [porting notes](https://github.com/classgraph/classgraph/wiki/Porting-FastClasspathScanner-code-to-ClassGraph) for information on porting from the older FastClasspathScanner version 3 API.

In particular, the Maven group id has changed from `io.github.lukehutch.fast-classpath-scanner` to **`io.github.classgraph`** in version 4. Please see the new [Maven dependency rule](https://github.com/classgraph/classgraph/wiki) and module "requires" line in the Wiki documentation.

## Mailing List

* Feel free to subscribe to the [ClassGraph-Users](https://groups.google.com/d/forum/classgraph-users) email list for updates, or to ask questions.
* There is also a [Gitter room](https://gitter.im/classgraph/Lobby) for discussion of ClassGraph.

## Author

ClassGraph was written by Luke Hutchison, with contributions from a number of other people:

* https://github.com/lukehutch
* [@LH](http://twitter.com/LH) on Twitter

Please donate if this library makes your life easier:

[![](https://www.paypalobjects.com/en_US/i/btn/btn_donateCC_LG.gif)](https://www.paypal.com/cgi-bin/webscr?cmd=_donations&business=luke.hutch@gmail.com&lc=US&item_name=Luke%20Hutchison&item_number=ClassGraph&no_note=0&currency_code=USD&bn=PP-DonationsBF:btn_donateCC_LG.gif:NonHostedGuest)

### Alternatives

Some other classpath scanning mechanisms include:

* [Reflections](https://github.com/ronmamo/reflections)
* [Corn Classpath Scanner](https://sites.google.com/site/javacornproject/corn-cps)
* [annotation-detector](https://github.com/rmuller/infomas-asl/tree/master/annotation-detector)
* [Scannotation](http://scannotation.sourceforge.net/)
* [Sclasner](https://github.com/xitrum-framework/sclasner)
* [Annovention](https://github.com/ngocdaothanh/annovention)
* [ClassIndex](https://github.com/atteo/classindex) (compiletime annotation scanner/processor)
* [Jandex](https://github.com/wildfly/Jandex) (Java annotation indexer, part of Wildfly)
* [Spring](http://spring.io/) has built-in classpath scanning
* [Hibernate](http://hibernate.org/) has the class [`org.hibernate.ejb.packaging.Scanner`](https://www.programcreek.com/java-api-examples/index.php?api=org.hibernate.ejb.packaging.Scanner).
* [extcos -- the Extended Component Scanner](https://sourceforge.net/projects/extcos/)
* [Javassist](http://jboss-javassist.github.io/javassist/)
* [ObjectWeb ASM](http://asm.ow2.org/)
* [QDox](https://github.com/paul-hammant/qdox), a fast Java source parser and indexer
* [bndtools](https://github.com/bndtools/bnd), which is able to ["crawl"/parse the bytecode of class files](https://github.com/bndtools/bnd/blob/master/biz.aQute.bndlib/src/aQute/bnd/osgi/Clazz.java) to find all imports/dependencies, among other things. 
* [coffea](https://github.com/sbilinski/coffea), a command line tool and Python library for analyzing static dependences in Java bytecode

## License

**The MIT License (MIT)**

**Copyright (c) 2018 Luke Hutchison**
 
Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 
The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
