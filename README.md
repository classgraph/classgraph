# ClassGraph

ClassGraph is an uber-fast, ultra-lightweight classpath scanner, module scanner, and annotation processor for Java, Scala, Kotlin and other JVM languages. Classpath and module path scanning offers the inverse of the Java class API and/or reflection: for example, the Java class API can tell you the superclass of a given class, or give you the list of annotations on a class; classpath scanning can find all subclasses of a given class, or find all classes that are annotated with a given annotation.

ClassGraph handles a large number of [classpath specification mechanisms](https://github.com/classgraph/classgraph/wiki/Classpath-specification-mechanisms) found in the wild. ClassGraph can scan the classpath and module path either at runtime or [at build time](https://github.com/classgraph/classgraph/wiki/Build-Time-Scanning) (e.g. to implement annotation processing for Android).

ClassGraph reads the classfile bytecode format directly for speed, to avoid the overhead of loading classes, and to avoid the overhead and side effects of initializing classes (causing static initializer blocks to be run). After scanning, you get a collection of wrapper objects representing each class, method, field, and annotation found during the scan. These can be queried in a range of ways to find classes matching given criteria, without ever loading the classes. You can even generate a [GraphViz visualization of the class graph](https://raw.githubusercontent.com/classgraph/classgraph/master/src/test/java/com/xyz/classgraph-fig.png).

ClassGraph can scan both the traditional classpath and the visible Java modules (Project Jigsaw / JDK 9+), but is also backwards and forwards compatible with JDK 7 and JDK 8. ClassGraph has been [carefully optimized](#how-fast-is-classgraph).

## Documentation

[See the wiki for complete documentation and usage information.](https://github.com/classgraph/classgraph/wiki)

## Status

**Version 4.0.0-beta-1 has been released**, with a completely revamped API. See the [release notes](https://github.com/classgraph/classgraph/releases/tag/classgraph-4.0.0-beta-1) for information on porting from the older API.

In particular, the Maven group id has changed from `io.github.lukehutch.fast-classpath-scanner` to **`io.github.classgraph`** in version 4. Please see the new [Maven dependency rule](https://github.com/classgraph/classgraph/wiki) in the Wiki page.

[![Build Status](https://travis-ci.org/classgraph/classgraph.png?branch=master)](https://travis-ci.org/classgraph/classgraph)

## How fast is ClassGraph?

ClassGraph is the fastest classpath and module path scanning mechanism:

* ClassGraph parses the classfile binary format directly to determine the class graph. This is significantly faster than reflection-based methods, because no classloading needs to be performed to determine how classes are related, and additionally, class static initializer blocks don't need to be called (which can be time consuming, and can cause side effects).
* ClassGraph has been carefully profiled, tuned and parallelized so that multiple threads are concurrently engaged in reading from disk/SSD, decompressing jarfiles, and parsing classfiles. Consequently, ClassGraph runs at close to the theoretical maximum possible speed for a classpath scanner, and scanning speed is primarily limited by raw filesystem bandwidth.
* Wherever possible, lock-free datastructures are used to eliminate thread contention, and shared caches are used to avoid duplicating work. Additionally, ClassGraph opens multiple `java.lang.ZipFile` instances for a given jarfile, up to one per thread, in order to circumvent a per-instance thread lock in the JRE `ZipFile` implementation.
* ClassGraph includes comprehensive mechanisms for whitelisting and blacklisting, so that only the necessary resources are scanned.
* ClassGraph uses memory-mapped files wherever possible (when scanning directories and modules) for extra speed.  

In particular, ClassGraph is typically several times faster at scanning large classpaths consisting of many directories or jarfiles than the widely-used library [Reflections](https://github.com/ronmamo/reflections). If ClassGraph is slower than Reflections for your usecase, that is because it has discovered a [larger set of classpath elements to scan](https://github.com/classgraph/classgraph/wiki/Classpath-specification-mechanisms) than Reflections. You can limit what is scanned using whitelist / blacklist criteria (see the [wiki](https://github.com/classgraph/classgraph/wiki) for more info).

## Mailing List

* Feel free to subscribe to the [ClassGraph-Users](https://groups.google.com/d/forum/classgraph-users) email list for updates, or to ask questions.
* There is also a [Gitter room](https://gitter.im/classgraph/Lobby) for discussion of FCS.

## Author

ClassGraph was written by Luke Hutchison:

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
