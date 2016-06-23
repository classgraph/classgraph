/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Luke Hutchison
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.lukehutch.fastclasspathscanner.classpath;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.jar.Manifest;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.utils.AdditionOrderedSet;
import io.github.lukehutch.fastclasspathscanner.utils.Log;

public class ClasspathFinder {
    /** The unique elements of the classpath, as an ordered list. */
    private final ArrayList<File> classpathElements = new ArrayList<>();

    /** The unique elements of the classpath, as a set. */
    private final HashSet<String> classpathElementsSet = new HashSet<>();

    /** The set of JRE paths found so far in the classpath, cached for speed. */
    private final HashSet<String> knownJREPaths = new HashSet<>();

    /** Manually-registered ClassLoaderHandlers. */
    private final HashSet<ClassLoaderHandler> extraClassLoaderHandlers = new HashSet<>();

    /** Whether or not classpath has been read (supporting lazy reading of classpath). */
    private boolean initialized = false;

    /** Clear the classpath. */
    private void clearClasspath() {
        classpathElements.clear();
        classpathElementsSet.clear();
        initialized = false;
    }

    /** Returns true if the path ends with a JAR extension */
    public static boolean isJar(final String path) {
        final String pathLower = path.toLowerCase();
        return pathLower.endsWith(".jar") || pathLower.endsWith(".zip") || pathLower.endsWith(".war")
                || pathLower.endsWith(".car");
    }

    /**
     * Strip away any "jar:" prefix from a filename URI, and convert it to a file path, handling possibly-broken
     * mixes of filesystem and URI conventions. Follows symbolic links, and resolves any relative paths relative to
     * resolveBase.
     */
    private static Path urlToPath(final Path resolveBasePath, final String pathElementStr) {
        if (pathElementStr.isEmpty()) {
            return null;
        }
        // Ignore "jar:", we look for ".jar" on the end of filenames instead
        String pathStr = pathElementStr;
        if (pathStr.startsWith("jar:")) {
            // Convert "jar:" to "file:" URL, so that URL -> URI -> Path works
            pathStr = pathStr.substring(4);
            if (!pathStr.startsWith("file:") && !pathStr.startsWith("http:") && !pathStr.startsWith("https:")) {
                pathStr = "file:" + pathStr;
            }
        }
        // We don't fetch remote classpath entries, although they are theoretically valid if using
        // a URLClassLoader, such as used to resolve the Class-Path field in a jarfile's manifest file.
        if (pathStr.startsWith("http:") || pathStr.startsWith("https:")) {
            if (FastClasspathScanner.verbose) {
                Log.log("Ignoring remote entry in classpath: " + pathStr);
            }
            return null;
        }
        try {
            // Try parsing the path element as a URL, then as a URI, then as a filesystem path.
            // Need to deal with possibly-broken mixes of file:// URLs and system-dependent path formats -- see:
            // https://weblogs.java.net/blog/kohsuke/archive/2007/04/how_to_convert.html
            // http://stackoverflow.com/a/17870390/3950982
            // i.e. the recommended way to do this is URL -> URI -> Path, especially to handle weirdness on Windows.
            // N.B. we need NOFOLLOW_LINKS, because otherwise resolved paths may no longer appear as a child of
            // a classpath element, and/or the path tree may no longer conform to the package tree. 
            return resolveBasePath.resolve(Paths.get(new URL(pathStr).toURI())) //
                    .toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (final Exception e) {
            try {
                return resolveBasePath.resolve(pathStr).toRealPath(LinkOption.NOFOLLOW_LINKS);
            } catch (final Exception e2) {
                try {
                    final File file = new File(pathElementStr);
                    if (file.exists()) {
                        return file.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS);
                    }
                } catch (final Exception e3) {
                    // One of the above should have worked, so if we got here, the path element is junk.
                    if (FastClasspathScanner.verbose) {
                        Log.log("Exception while trying to read classpath element " + pathStr + " : "
                                + e.getMessage());
                    }
                }
            }
        }
        return null;
    }

    /** Add a classpath element. */
    public void addClasspathElement(final String pathElement) {
        final Path currDirPath = Paths.get("").toAbsolutePath();
        final Path path = urlToPath(currDirPath, pathElement);
        if (path != null) {
            // Everything after '!' in a "jar:" URL refers to a path within the jar.
            final String pathStr = path.toString();
            final int bangPos = pathStr.indexOf('!');
            final String suffix;
            final File pathFile;
            if (bangPos > 0) {
                // If present, remove the '!' path suffix so that the .exists() test below won't fail
                pathFile = Paths.get(pathStr.substring(0, bangPos)).toFile();
                suffix = pathStr.substring(bangPos);
            } else {
                pathFile = path.toFile();
                suffix = "";
            }
            if (pathFile.exists()) {
                if (classpathElementsSet.add(pathStr)) {
                    // This is the first time this classpath element has been encountered
                    boolean isValidClasspathElement = true;

                    // If this classpath element is a jar or zipfile, look for Class-Path entries in the manifest
                    // file. OpenJDK scans manifest-defined classpath elements after the jar that listed them, so
                    // we recursively call addClasspathElement if needed each time a jar is encountered. 
                    if (pathFile.isFile() && isJar(pathStr)) {
                        if (isJREJar(pathFile, /* ancestralScanDepth = */2)) {
                            // Don't scan system jars
                            isValidClasspathElement = false;
                            if (FastClasspathScanner.verbose) {
                                Log.log("Skipping JRE jar: " + pathStr);
                            }
                        } else {
                            // Recursively check for Class-Path entries in jar manifests
                            final String manifestUrlStr = "jar:" + pathFile.toURI() + "!/META-INF/MANIFEST.MF";
                            try (InputStream stream = new URL(manifestUrlStr).openStream()) {
                                // Look for Class-Path keys within manifest files
                                final Manifest manifest = new Manifest(stream);
                                final String manifestClassPath = manifest.getMainAttributes()
                                        .getValue("Class-Path");
                                if (manifestClassPath != null && !manifestClassPath.isEmpty()) {
                                    if (FastClasspathScanner.verbose) {
                                        Log.log("Found Class-Path entry in " + manifestUrlStr + ": "
                                                + manifestClassPath);
                                    }
                                    // Class-Path entries in the manifest file should be resolved relative to the
                                    // directory the manifest's jarfile is contained in (i.e. path.getParent()).
                                    final Path parentPath = path.getParent();
                                    // Class-Path entries in manifest files are a space-delimited list of URIs.
                                    for (final String manifestClassPathElement : manifestClassPath.split(" ")) {
                                        final Path manifestEltPath = urlToPath(parentPath, //
                                                manifestClassPathElement);
                                        if (manifestEltPath != null) {
                                            addClasspathElement(manifestEltPath.toString());
                                        }
                                    }
                                }
                            } catch (final IOException e) {
                                // Jar does not contain a manifest
                            }
                        }
                    }

                    if (isValidClasspathElement) {
                        // Add the classpath element to the ordered list
                        if (FastClasspathScanner.verbose) {
                            Log.log("Found classpath element: " + path);
                        }
                        if (suffix.isEmpty()) {
                            // For regular files, add the File object to classpathElements.
                            classpathElements.add(pathFile);
                        } else {
                            // For jar-internal paths, add the '!' path suffix to the end of the path,
                            // and create a new (non-existent) File object to hold the path.
                            classpathElements.add(new File(pathFile.getPath() + suffix));
                        }
                    }
                }
            } else if (FastClasspathScanner.verbose) {
                Log.log("Classpath element does not exist: " + path);
            }
        }
    }

    /** Add classpath elements, separated by the system path separator character. */
    public void addClasspathElements(final String pathStr) {
        if (pathStr != null && !pathStr.isEmpty()) {
            for (final String pathElement : pathStr.split(File.pathSeparator)) {
                addClasspathElement(pathElement);
            }
        }
    }

    /**
     * Recursively search within ancestral directories of a jarfile to see if rt.jar is present, in order to
     * determine if the given jarfile is part of the JRE. This would typically be called with an initial
     * ancestralScandepth of 2, since JRE jarfiles can be in the lib or lib/ext directories of the JRE.
     */
    private boolean isJREJar(final File file, final int ancestralScanDepth) {
        if (ancestralScanDepth == 0) {
            return false;
        } else {
            final File parent = file.getParentFile();
            if (parent == null) {
                return false;
            }
            if (knownJREPaths.contains(parent.getPath())) {
                return true;
            }
            final File rt = new File(parent, "rt.jar");
            if (rt.exists()) {
                // Found rt.jar; check its manifest file to make sure it's the JRE's rt.jar and not something else 
                final String manifestUrlStr = "jar:" + rt.toURI() + "!/META-INF/MANIFEST.MF";
                try (InputStream stream = new URL(manifestUrlStr).openStream()) {
                    // Look for Class-Path keys within manifest files
                    final Manifest manifest = new Manifest(stream);
                    if ("Java Runtime Environment".equals( //
                            manifest.getMainAttributes().getValue("Implementation-Title"))
                            || "Java Platform API Specification".equals( //
                                    manifest.getMainAttributes().getValue("Specification-Title"))) {
                        // Found the JRE's rt.jar
                        knownJREPaths.add(parent.getPath());
                        return true;
                    }
                } catch (final IOException e) {
                    // Jar does not contain a manifest
                }
            }
            return isJREJar(parent, ancestralScanDepth - 1);
        }
    }

    /** Add all parent classloaders of a class in top-down order, the same as in the JRE. */
    private static void addAllParentClassloaders(final Class<?> klass,
            final AdditionOrderedSet<ClassLoader> classLoadersSetOut) {
        final ArrayList<ClassLoader> callerClassLoaders = new ArrayList<>();
        for (ClassLoader cl = klass.getClassLoader(); cl != null; cl = cl.getParent()) {
            callerClassLoaders.add(cl);
        }
        // OpenJDK calls classloaders in a top-down order
        for (int i = callerClassLoaders.size() - 1; i >= 0; --i) {
            classLoadersSetOut.add(callerClassLoaders.get(i));
        }
    }

    /** Parse the system classpath. */
    private void parseSystemClasspath() {
        clearClasspath();

        // Look for all unique classloaders.
        // Need both a set and a list so we can keep them unique, but in an order that (hopefully) reflects
        // the order in which the JDK calls classloaders.
        //
        // See:
        // https://docs.oracle.com/javase/8/docs/technotes/tools/findingclasses.html
        //
        final AdditionOrderedSet<ClassLoader> classLoadersSet = new AdditionOrderedSet<>();
        classLoadersSet.add(ClassLoader.getSystemClassLoader());
        // Look for classloaders on the call stack
        try {
            // Generate stacktrace
            throw new Exception();
        } catch (final Exception e) {
            final StackTraceElement[] stacktrace = e.getStackTrace();
            if (stacktrace.length >= 1) {
                for (final StackTraceElement ste : stacktrace) {
                    try {
                        addAllParentClassloaders(Class.forName(ste.getClassName()), classLoadersSet);
                    } catch (final ClassNotFoundException e1) {
                    }
                }
            }
        }
        classLoadersSet.add(Thread.currentThread().getContextClassLoader());
        addAllParentClassloaders(ClasspathFinder.class, classLoadersSet);
        final List<ClassLoader> classLoaders = classLoadersSet.getList();
        classLoaders.remove(null);

        // Always include a ClassLoaderHandler for URLClassLoader subclasses as a default, so that we can handle
        // URLClassLoaders (the most common form of ClassLoader) even if ServiceLoader can't find other
        // ClassLoaderHandlers (this can happen if FastClasspathScanner's package is renamed using Maven Shade).
        final HashSet<ClassLoaderHandler> classLoaderHandlers = new HashSet<>();
        classLoaderHandlers.add(new ClassLoaderHandler() {
            @Override
            public boolean handle(final ClassLoader classloader, final ClasspathFinder classpathFinder) {
                if (classloader instanceof URLClassLoader) {
                    for (final URL url : ((URLClassLoader) classloader).getURLs()) {
                        classpathFinder.addClasspathElement(url.toString());
                    }
                    return true;
                }
                return false;
            }
        });

        // Find all ClassLoaderHandlers registered using ServiceLoader, given known ClassLoaders. 
        // FastClasspathScanner ships with several of these, registered in:
        // src/main/resources/META-INF/services
        for (final ClassLoader classLoader : classLoaders) {
            // Use ServiceLoader to find registered ClassLoaderHandlers, see:
            // https://docs.oracle.com/javase/6/docs/api/java/util/ServiceLoader.html
            final ServiceLoader<ClassLoaderHandler> classLoaderHandlerLoader = ServiceLoader
                    .load(ClassLoaderHandler.class, classLoader);
            // Iterate through registered ClassLoaderHandlers
            for (final ClassLoaderHandler handler : classLoaderHandlerLoader) {
                classLoaderHandlers.add(handler);
            }
        }
        // Add manually-added ClassLoaderHandlers
        classLoaderHandlers.addAll(extraClassLoaderHandlers);

        // Try finding a handler for each of the classloaders discovered above
        boolean classloaderFound = false;
        for (final ClassLoader classLoader : classLoaders) {
            // Iterate through registered ClassLoaderHandlers
            for (final ClassLoaderHandler handler : classLoaderHandlers) {
                try {
                    if (handler.handle(classLoader, this)) {
                        // Sucessfully handled
                        if (FastClasspathScanner.verbose) {
                            Log.log("Classpath elements from ClassLoader " + classLoader.getClass().getName()
                                    + " were extracted by ClassLoaderHandler " + handler.getClass().getName());
                        }
                        classloaderFound = true;
                        break;
                    }
                } catch (final Exception e) {
                    Log.log("Was not able to call getPaths() in " + classLoader.getClass().getName() + ": "
                            + e.toString());
                }
            }
            if (!classloaderFound) {
                Log.log("Found unknown ClassLoader type, cannot scan classes: " + classLoader.getClass().getName());
            }
        }

        // Add entries found in java.class.path, in case those entries were missed above due to some
        // non-standard classloader that uses this property
        addClasspathElements(System.getProperty("java.class.path"));

        initialized = true;
    }

    /**
     * Add an extra ClassLoaderHandler. Needed if the ServiceLoader framework is not able to find the
     * ClassLoaderHandler for your specific ClassLoader, or if you want to manually register your own
     * ClassLoaderHandler rather than using the ServiceLoader framework.
     */
    public void registerClassLoaderHandler(final ClassLoaderHandler extraClassLoaderHandler) {
        extraClassLoaderHandlers.add(extraClassLoaderHandler);
    }

    /** Override the system classpath with a custom classpath to search. */
    public void overrideClasspath(final String classpath) {
        clearClasspath();
        addClasspathElements(classpath);
        initialized = true;
    }

    /**
     * Returns the list of all unique File objects representing directories or zip/jarfiles on the classpath, in
     * classloader resolution order. Classpath elements that do not exist are not included in the list.
     */
    public List<File> getUniqueClasspathElements() {
        if (!initialized) {
            parseSystemClasspath();
        }
        return classpathElements;
    }
}
