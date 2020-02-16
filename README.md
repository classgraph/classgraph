# ClassGraph

<img alt="ClassGraph Logo" height="320" width = "320" src="https://github.com/classgraph/classgraph/wiki/ClassGraphLogo.png">&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<img alt="Duke Award Logo" height="320" src="https://github.com/classgraph/classgraph/wiki/Duke-noborder.png">

ClassGraph is an uber-fast, ultra-lightweight, parallelized classpath scanner and module scanner for Java, Scala, Kotlin and other JVM languages.

| _ClassGraph won a Duke's Choice Award (a recognition of the most useful and/or innovative software in the Java ecosystem) at Oracle Code One 2018._ Thanks to all the users who have reported bugs, requested features, offered suggestions, and submitted pull requests to help get ClassGraph to where it is today. |
|-----------------------------|

[![Platforms: Windows, Mac OS X, Linux, Android (build-time)](https://img.shields.io/badge/platforms-Windows,_Mac_OS_X,_Linux,_Android_(build--time)-blue.svg)](#)
[![Languages: Java, Scala, Kotlin, etc.](https://img.shields.io/badge/languages-Java,_Scala,_Kotlin,_etc.-blue.svg)](#)
[![JDK compatibility: 7, 8, 9+ (JPMS)](https://img.shields.io/badge/JDK_compatibility-7,_8,_9+_(JPMS)-blue.svg)](#)
<br>
[![Build Status](https://travis-ci.org/classgraph/classgraph.png?branch=master)](https://travis-ci.org/classgraph/classgraph)
[![GitHub issues](https://img.shields.io/github/issues/classgraph/classgraph.svg)](https://github.com/classgraph/classgraph/issues/)
[![lgtm alerts](https://img.shields.io/lgtm/alerts/g/classgraph/classgraph.svg?logo=lgtm&logoWidth=18)](https://lgtm.com/projects/g/classgraph/classgraph/alerts/)
[![lgtm code quality](https://img.shields.io/lgtm/grade/java/g/classgraph/classgraph.svg?logo=lgtm&logoWidth=18)](https://lgtm.com/projects/g/classgraph/classgraph/context:java)
[![Codacy Badge](https://img.shields.io/codacy/grade/31d639dd3874454c917f80ea2bff8155.svg?style=flat)](https://www.codacy.com/app/lukehutch/classgraph)
<br>
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.github.classgraph/classgraph/badge.svg)](https://mvnrepository.com/artifact/io.github.classgraph/classgraph)
[![Javadocs](http://www.javadoc.io/badge/io.github.classgraph/classgraph.svg)](https://javadoc.io/doc/io.github.classgraph/classgraph)
<br>
[![License: MIT](https://img.shields.io/badge/license-MIT-lightgrey.svg)](https://github.com/classgraph/classgraph/blob/master/LICENSE)
[![Dependencies: none](https://img.shields.io/badge/dependencies-none-lightgrey.svg)](#)
<br>
[![GitHub stars chart](https://img.shields.io/badge/github%20stars-chart-yellow.svg)](https://seladb.github.io/StarTrack-js/#/preload?r=classgraph,classgraph)
[![Gitter chat](https://img.shields.io/badge/gitter-join%20chat-yellow.svg)](https://gitter.im/classgraph/Lobby)

| ClassGraph is now fully stable. This project adheres to the **[Zero Bugs Commitment](https://github.com/classgraph/classgraph/blob/master/Zero-Bugs-Commitment.md)**. |
|-----------------------------|

### ClassGraph vs. Java Introspection

ClassGraph has the ability to "invert" the Java class and/or reflection API, or has the ability to index classes and resources. For example, the Java class and reflection API can tell you the superclass of a given class, or the interfaces implemented by a given class, or can give you the list of annotations on a class; ClassGraph can find **all classes that extend a given class** (all subclasses of a given class), or **all classes that implement a given interface**, or **all classes that are annotated with a given annotation**. The Java API can load the content of a resource file with a specific path in a specific ClassLoader, but ClassGraph can find and load **all resources in all classloaders with paths matching a given pattern**.

### Examples

The following code prints the name of all classes in the package `com.xyz` or its subpackages, anywhere on the classpath or module path, that are annotated with an annotation of the form `@com.xyz.Route("/pages/home.html")`, along with the annotation parameter value. This is accomplished without loading or initializing any of the scanned classes.

```java
String pkg = "com.xyz";
String routeAnnotation = pkg + ".Route";
try (ScanResult scanResult =
        new ClassGraph()
            .verbose()                   // Log to stderr
            .enableAllInfo()             // Scan classes, methods, fields, annotations
            .whitelistPackages(pkg)      // Scan com.xyz and subpackages (omit to scan all packages)
            .scan()) {                   // Start the scan
    for (ClassInfo routeClassInfo : scanResult.getClassesWithAnnotation(routeAnnotation)) {
        AnnotationInfo routeAnnotationInfo = routeClassInfo.getAnnotationInfo(routeAnnotation);
        List<AnnotationParameterValue> routeParamVals = routeAnnotationInfo.getParameterValues();
        // @com.xyz.Route has one required parameter
        String route = (String) routeParamVals.get(0).getValue();
        System.out.println(routeClassInfo.getName() + " is annotated with route " + route);
    }
}
```

The following code finds all JSON files in `META-INF/config` in all ClassLoaders or modules, and calls the method `readJson(String path, String content)` with the path and content of each file.

```java
try (ScanResult scanResult = new ClassGraph().whitelistPathsNonRecursive("META-INF/config").scan()) {
    scanResult.getResourcesWithExtension("json").forEachByteArray((Resource res, byte[] content) -> {
        readJson(res.getPath(), new String(content, StandardCharsets.UTF_8));
    });
}
```

See the [code examples](https://github.com/classgraph/classgraph/wiki/Code-examples) page for more examples of how to use the ClassGraph API.

### Capabilities

ClassGraph provides a number of important capabilities to the JVM ecosystem:

* ClassGraph has the ability to build a model in memory of the entire relatedness graph of all classes, annotations, interfaces, methods and fields that are visible to the JVM. This graph can be [queried in a wide range of ways](https://github.com/classgraph/classgraph/wiki/Code-examples), enabling some degree of *metaprogramming* in JVM languages -- the ability to write code that analyzes or responds to the properties of other code.
* ClassGraph reads the classfile bytecode format directly, so it can read all information about classes without loading or initializing them.
* ClassGraph is fully compatible with the new JPMS module system (Project Jigsaw / JDK 9+), i.e. it can scan both the traditional classpath and the module path. However, the code is also fully backwards compatible with JDK 7 and JDK 8 (i.e. the code is compiled in Java 7 compatibility mode, and all interaction with the module system is implemented via reflection for backwards compatibility).
* ClassGraph scans the classpath or module path using [carefully optimized multithreaded code](https://github.com/classgraph/classgraph/wiki/How-fast-is-ClassGraph%3F) for the shortest possible scan times, and it runs as close as possible to I/O bandwidth limits, even on a fast SSD.
* ClassGraph handles more [classpath specification mechanisms](https://github.com/classgraph/classgraph/wiki/Classpath-specification-mechanisms) found in the wild than any other classpath scanner, making code that depends upon ClassGraph maximally portable.
* ClassGraph can scan the classpath and module path either at runtime or [at build time](https://github.com/classgraph/classgraph/wiki/Build-Time-Scanning) (e.g. to implement annotation processing for Android).
* ClassGraph can [find classes that are duplicated or defined more than once in the classpath or module path](https://github.com/classgraph/classgraph/wiki/Code-examples#find-all-duplicate-class-definitions-in-the-classpath-or-module-path), which can help find the cause of strange class resolution behaviors.
* ClassGraph can [create GraphViz visualizations of the class graph structure](https://github.com/classgraph/classgraph/wiki/API:-ClassInfo#generating-a-graphviz-dot-file-for-class-graph-visualization), which can help with code understanding: (click to enlarge | [see graph legend here](https://github.com/classgraph/classgraph/blob/master/src/test/java/com/xyz/classgraph-fig-legend.png))

<p align="center">
  <a href="https://raw.githubusercontent.com/classgraph/classgraph/master/src/test/java/com/xyz/classgraph-fig.png"><img src="https://github.com/classgraph/classgraph/blob/master/src/test/java/com/xyz/classgraph-fig.png" width="898" height="685" alt="Class graph visualization"/></a>
</p>

## Downloading

### Maven dependency

Replace `X.Y.Z` below with the latest [release number](https://github.com/classgraph/classgraph/releases). (Alternatively, you could use `LATEST` in place of `X.Y.Z` instead if you just want to grab the latest version -- although be aware that that may lead to non-reproducible builds, since the ClassGraph version number could increase at any time. You could use [dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html) to address this.)

```xml
<dependency>
    <groupId>io.github.classgraph</groupId>
    <artifactId>classgraph</artifactId>
    <version>X.Y.Z</version>
</dependency>
```

See instructions for [use as a module](https://github.com/classgraph/classgraph/wiki#use-as-a-module).

### Pre-built JARs

You can get pre-built JARs (usable on JRE 7 or newer) from [Sonatype](https://oss.sonatype.org/#nexus-search;quick~io.github.classgraph).

### Building from source

ClassGraph must be built on JDK 8 or newer (due to the presence of `@FunctionalInterface` annotations on some interfaces), but is built using `-target 1.7` for backwards compatibility with JRE 7.

The following commands will build the most recent version of ClassGraph from git master. The compiled package will then be in the "classgraph/target" directory.

```bash
git clone https://github.com/classgraph/classgraph.git
cd classgraph
export JAVA_HOME=/usr/java/default   # Or similar -- Maven needs JAVA_HOME
./mvnw -Dmaven.test.skip=true package
```

This will allow you to build a local SNAPSHOT jar in `target/`. Alternatively, use `./mvnw -Dmaven.test.skip=true install` to build a SNAPSHOT jar and then copy it into your local repository, so that you can use it in your Maven projects. Note that may need to do `./mvnw dependency:resolve` in your project if you overwrite an older snapshot with a newer one.

`./mvnw -U` updates from remote repositories an may overwrite your local artifact. But you can always change the `artifactId` or the `groupId` of your local ClassGraph build to place your local build artifact in another location within your local repository.

## Documentation

[See the wiki for complete documentation and usage information.](https://github.com/classgraph/classgraph/wiki)

**ClassGraph was known as FastClasspathScanner prior to version 4**.  See the [porting notes](https://github.com/classgraph/classgraph/wiki/Porting-FastClasspathScanner-code-to-ClassGraph) for information on porting from the older FastClasspathScanner API.

## Mailing List

* Feel free to subscribe to the [ClassGraph-Users](https://groups.google.com/d/forum/classgraph-users) email list for updates, or to ask questions.
* There is also a [Gitter room](https://gitter.im/classgraph/Lobby) for discussion of ClassGraph.

## Sponsorship

ClassGraph was written by Luke Hutchison ([@LH](http://twitter.com/LH) on Twitter).

If ClassGraph is critical to your work, you can help fund further development through the [GitHub Sponsors Program](https://github.com/sponsors/lukehutch).

<a href="https://github.com/sponsors/lukehutch"><img src="https://github.blog/wp-content/uploads/2019/05/mona-heart-featured.png" height="140"></a>

## Acknowledgments

ClassGraph would not be possible without contributions from numerous users, including in the form of bug reports, feature requests, code contributions, and assistance with testing.

## Alternatives

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
* [org.clapper.classutil.ClassFinder](https://github.com/bmc/classutil/blob/master/src/main/scala/org/clapper/classutil/ClassFinder.scala)
* [com.google.common.reflect.ClassPath](https://github.com/google/guava/blob/master/guava/src/com/google/common/reflect/ClassPath.java)

## License

**The MIT License (MIT)**

**Copyright (c) 2019 Luke Hutchison**

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
