# FastClasspathScanner

FastClasspathScanner is an uber-fast, ultra-lightweight classpath scanner, module scanner, and annotation processor for Java, Scala, Kotlin and other JVM languages. Classpath and module path scanning offers the inverse of the Java class API and/or reflection: for example, the Java class API can tell you the superclass of a given class, or give you the list of annotations on a class; classpath scanning can find all subclasses of a given class, or find all classes that are annotated with a given annotation.

FastClasspathScanner handles a [huge number](#classpath-specification-mechanisms-handled-by-fastclasspathscanner) of classpath specification mechanisms found in the wild. FastClasspathScanner can scan the classpath and module path either at build-time (e.g. to implement annotation processing for Android), or dynamically at runtime.

FastClasspathScanner reads the classfile bytecode format directly for speed, to avoid the overhead of loading classes, and to avoid the overhead and side effects of initializing classes (causing static initializer blocks to be run). After scanning, you get a collection of wrapper objects representing each class, method, field, and annotation found during the scan. These can be queried in a range of ways to find classes matching given criteria, without ever loading the classes. You can even generate a [GraphViz visualization of the class graph](https://raw.githubusercontent.com/lukehutch/fast-classpath-scanner/master/src/test/java/com/xyz/classgraph-fig.png).

FastClasspathScanner can scan both the traditional classpath and the visible Java modules (Project Jigsaw / JDK 9+), but is also backwards and forwards compatible with JDK 7 and JDK 8. FastClasspathScanner has been [carefully optimized](#how-fast-is-fastclasspathscanner).

## Status

**Version 4.0.0-beta-1 has been released**, with a completely revamped API. See the [release notes](https://github.com/lukehutch/fast-classpath-scanner/releases/tag/fast-classpath-scanner-4.0.0-beta-1) for information on porting from the older API.

[![Build Status](https://travis-ci.org/lukehutch/fast-classpath-scanner.png?branch=master)](https://travis-ci.org/lukehutch/fast-classpath-scanner)

## Documentation

* [See the wiki for documentation and usage information.](https://github.com/lukehutch/fast-classpath-scanner/wiki)
* [See the JavaDoc for full API documentation.](https://javadoc.io/doc/io.github.lukehutch/fast-classpath-scanner/)

## Classpath specification mechanisms handled by FastClasspathScanner

FastClasspathScanner handles the following classpath and module path specification mechanisms:

* The **JDK 9+ module path (Project Jigsaw)**. FastClasspathScanner uses [this mechanism](https://stackoverflow.com/questions/41932635/scanning-classpath-modulepath-in-runtime-in-java-9/45612376#45612376) to scan all visible modules, however it is entirely implemented with reflection, so that FastClasspathScanner can be compiled with JDK 7 for backwards compatibility.
* The **standard (now legacy) Java `URLClassLoader`** and subclasses.
* The **`java.class.path`** system property, supporting specification of the classpath using the `-cp` JRE commandline switch.
* Classes added to **`lib/`** or **`ext/`** directories in the JDK or JRE (this is a rare but valid way to add classes to the classpath), or any other extension directories found in the **`java.ext.dirs`** system property.
  * Note however that if you use this method to add jars to the classpath, and you want FastClasspathScanner to scan your jars, you'll have to un-blacklist the scanning of system jars, or specifically whitelist the lib/ext jars you want to scan (see the documentation for info).
* OS-specific site-wide `lib/` or `ext/` directories (i.e. directories where jarfiles may be installed such that they are accessible to all installed JREs and JDKs):
  * `/usr/java/packages` on Linux
  * `%SystemRoot%\Sun\Java` or `%SystemRoot%\Oracle\Java` on Windows
  * `/System/Library/Java` on Mac OS X
  * `/usr/jdk/packages` on Solaris
* **Jarfiles specified using `http://` or `https://` URLs**. (Some ClassLoaders allow this: if present, these remote jars are downloaded to local temporary files. Note that if your classpath contains remote jars, they will be downloaded every time the classpath is scanned. Also note that both FastClasspathScanner and the ClassLoader will separately download the same jarfiles.)
* **ZipSFX (self-extracting) jarfiles**, i.e. zipfiles with a Bash script or `.exe` section prepended before the `PK` zipfile marker. (These can be created by Spring-Boot.)
* **Wildcarded classpath entries**, e.g. `lib/*` (which includes all jars and directories in `lib/` as separate classpath entries), which is allowed as of JDK 6. (Only whole-directory globs are currently supported, so `lib/proj*` doesn't work, but this should match the JRE's behavior.)
* **[Class-Path references](https://docs.oracle.com/javase/tutorial/deployment/jar/downman.html) in a jarfile's `META-INF/MANIFEST.MF`**, whereby jarfiles may add other external jarfiles to their own classpaths. FastClasspathScanner is able to determine the transitive closure of these references, breaking cycles if necessary.
* **Jarfiles within jarfiles** (to unlimited nesting depth), e.g.  
    `project.jar!/BOOT-INF/lib/dependency.jar` , and classpath roots within jarfiles, e.g.  
    `project.jar!/BOOT-INF/classes` , as required by The Spring, JBoss, and Felix classloaders, and probably others.
* The **Spring** and **Spring-Boot** classloaders, including properly handling nested classpaths (jars within jars) in an optimal way.
  * Also handles a special case, where you want to scan a Spring-Boot jar (or WAR), but the scanner is [not itself running within that jar](https://github.com/lukehutch/fast-classpath-scanner/issues/209). In this case, there is no classloader accessible to the scanner that knows how to load classes from the jar, since Spring-Boot has its own classloader contained in the jar. The package root is at `BOOT-INF/classes` or `WEB-INF/classes`, not at the root of the jarfile, meaning that `URLClassLoader` cannot load classes from that jar (`URLClassLoader` requires the package root to be the root directory of the jar). If you try loading classes through FastClasspathScanner, the package root of the jar (e.g. `BOOT-INF/classes`) is extracted to a temporary directory by FastClasspathScanner, and a custom `URLClassLoader` is automatically created to handle loading classes from the package root.
* **lib jars within jars**, at the jar paths `lib`, `BOOT-INF/lib`, `WEB-INF/lib`, or `WEB-INF/lib-provided`. FastClasspathScanner can locate lib jars to scan within these common paths, even when these jars are not explicitly listed on the classpath in the form `file:/path/to/my-spring-project.jar!/BOOT-INF/lib/libjar.jar`. This is needed if you are trying to scan a Spring-Boot jar (or WAR), but the scanner is not running within that jar, and you need to scan the lib dependencies within the jar, or load classes from a package root (`BOOT-INF/classes`) that depend upon classes in a lib directory (`BOOT-INF/lib`). These lib dependencies are implicitly added by the Spring-Boot classloader, so if you're not running within that classloader, FastClasspathScanner will automatically construct a classloader for you that simulates the Spring-Boot classloader, but can run outside the Spring-Boot jar.
* **Bridging classloading across multiple different classloaders**: If you try to load a class from a jar that is handled by one classloader, and it depends upon a class that is defined in a jar handled by a different classloader, you will get a `ClassNotFoundException`, `UnsatisfiedLinkError`, `NoClassDefFoundError` etc. FastClasspathScanner can handle this case if you call `FastClasspathScanner#createClassLoaderForMatchingClasses()` before `#scan()`. It will create a new "bridging" `URLClassLoader` that is able to load all whitelisted classes discovered during the scan. This is helpful in cases where you have multiple classpath entries containing jars-within-jars (`BOOT-INF/lib/*.lib`) or jars with non-root package roots (`BOOT-INF/classes`), where these jars have interdependencies that span different classloaders.
* The **JBoss WildFly** classloader, including JARs and WARs nested inside EARs.
* The **WebLogic** classloader.
* The **OSGi Eclipse** DefaultClassLoader.
* The **OSGi Equinox** classloader (e.g. for Eclipse PDE).
* The **Apache Felix** (OSGi reimplementation) classloader.
* The **[traditional Websphere](http://www-01.ibm.com/support/docview.wss?uid=swg27023549&aid=1)** classloader.
* The **Websphere Liberty** classloader.
* The **Ant** classloader.
* Any unknown classloader with a predictable method such as `getClasspath()`, `getClassPath()`, `getURLs()` etc. (a number of method and field names are tried).
* FastClasspathScanner handles both **`PARENT_FIRST` and `PARENT_LAST` classloader delegation modes** (primarily used by Websphere), in order to resolve classpath elements in the correct order. (Standard Java classloaders use `PARENT_FIRST` delegation.)

## How fast is FastClasspathScanner?

FastClasspathScanner is the fastest classpath scanning mechanism:

* FastClasspathScanner parses the classfile binary format directly to determine the class graph. This is significantly faster than reflection-based methods, because no classloading needs to be performed to determine how classes are related, and additionally, class static initializer blocks don't need to be called (which can be time consuming, and can cause side effects).
* FastClasspathScanner has been carefully profiled, tuned and parallelized so that multiple threads are concurrently engaged in reading from disk/SSD, decompressing jarfiles, and parsing classfiles. Consequently, FastClasspathScanner runs at close to the theoretical maximum possible speed for a classpath scanner, and scanning speed is primarily limited by raw filesystem bandwidth.
* Wherever possible, lock-free datastructures are used to eliminate thread contention, and shared caches are used to avoid duplicating work. Additionally, FastClasspathScanner opens multiple `java.lang.ZipFile` instances for a given jarfile, up to one per thread, in order to circumvent a per-instance thread lock in the JRE `ZipFile` implementation.
* FastClasspathScanner includes comprehensive mechanisms for whitelisting and blacklisting, so that only the necessary resources are scanned.
* FastClasspathScanner uses memory-mapped files wherever possible (when scanning directories and modules) for extra speed.  

In particular, FastClasspathScanner is typically several times faster at scanning large classpaths consisting of many directories or jarfiles than the widely-used library [Reflections](https://github.com/ronmamo/reflections). If FastClasspathScanner is slower than Reflections for your usecase, that is because it has discovered a [much larger set of classpath elements to scan](#classpath-specification-mechanisms-handled-by-fastclasspathscanner) than Reflections. You can limit what is scanned using whitelist / blacklist criteria (see the documentation).

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

## Downloading

### Maven dependency

```xml
<dependency>
    <groupId>io.github.lukehutch</groupId>
    <artifactId>fast-classpath-scanner</artifactId>
    <version>LATEST</version>
</dependency>
```

### Pre-built JARs

You can get pre-built JARs (usable in JRE 1.7 or later) from [Sonatype](https://oss.sonatype.org/#nexus-search;quick~fast-classpath-scanner).

### Use as a module

To use FastClasspathScanner as a Java module, add the jar dependency to your project's module path, then add the following to your `module-info.java`: 

```
requires io.github.lukehutch.fastclasspathscanner;
```

If when trying to run your code, you get the following exception, the problem is that the code you are trying to run is modular, but the FastClasspathScanner jar was added to the regular classpath, not the module path:

```
java.lang.NoClassDefFoundError: io/github/lukehutch/fastclasspathscanner/FastClasspathScanner
```  

In this case, if you manually added the FastClasspathScanner jar to an Eclipse project, go to *Project Properties > Java Build Path > Libraries > Classpath > fast-classpath-scanner-X.Y.Z.jar*, open the dropdown, double click on *Is not modular*, and check the box at the top, *Defines one or more modules*, then *OK*, then *Apply and Save*.

If you are launching from the commandline, make sure both your project and FastClasspathScanner are on the module path, not the classpath.  

### Building from source

The following commands will build the most recent version of FastClasspathScanner from git master. The compiled package will then be in the "fast-classpath-scanner/target" directory.

```
git clone https://github.com/lukehutch/fast-classpath-scanner.git
cd fast-classpath-scanner
export JAVA_HOME=/usr/java/default   # Or similar -- Maven needs JAVA_HOME
mvn -Dmaven.test.skip=true package
```

## Mailing List

* Feel free to subscribe to the [FastClasspathScanner-Users](https://groups.google.com/d/forum/fastclasspathscanner-users) email list for updates, or to ask questions.
* There is also a [Gitter room](https://gitter.im/fast-classpath-scanner/Lobby) for discussion of FCS.

## Author

FastClasspathScanner was written by Luke Hutchison:

* https://github.com/lukehutch
* [@LH](http://twitter.com/LH) on Twitter

Please donate if this library makes your life easier:

[![](https://www.paypalobjects.com/en_US/i/btn/btn_donateCC_LG.gif)](https://www.paypal.com/cgi-bin/webscr?cmd=_donations&business=luke.hutch@gmail.com&lc=US&item_name=Luke%20Hutchison&item_number=FastClasspathScanner&no_note=0&currency_code=USD&bn=PP-DonationsBF:btn_donateCC_LG.gif:NonHostedGuest)

## License

**The MIT License (MIT)**

**Copyright (c) 2018 Luke Hutchison**
 
Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 
The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
