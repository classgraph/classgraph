/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison <luke .dot. hutch .at. gmail .dot. com>
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.scanner;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.utils.Log;
import io.github.lukehutch.fastclasspathscanner.utils.Utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.Manifest;

public class ClasspathFinder {
    /** The unique elements of the classpath, as an ordered list. */
    private final ArrayList<File> classpathElements = new ArrayList<>();

    /** The unique elements of the classpath, as a set. */
    private final HashSet<String> classpathElementsSet = new HashSet<>();

    /** The set of JRE paths found so far in the classpath, cached for speed. */
    private final HashSet<String> knownJREPaths = new HashSet<>();

    private boolean initialized = false;

    /** Clear the classpath. */
    private void clearClasspath() {
        classpathElements.clear();
        classpathElementsSet.clear();
        initialized = false;
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
            // Everything after '!' in a "jar:" URL refers to a path within the jar.
            // Don't allow this for classpath scanning.
            if (pathStr.indexOf('!') >= 0) {
                if (FastClasspathScanner.verbose) {
                    Log.log("Ignoring direct jar-internal URL reference in classpath: " + pathStr);
                }
                return null;
            }
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
            return resolveBasePath.resolve(Paths.get(new URL(pathStr).toURI())).toRealPath();
        } catch (final Exception e) {
            try {
                return resolveBasePath.resolve(pathStr).toRealPath();
            } catch (final Exception e2) {
                try {
                    final File file = new File(pathElementStr);
                    if (file.exists()) {
                        return file.toPath().toRealPath();
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
    private void addClasspathElement(final String pathElement) {
        final Path currDirPath = Paths.get("").toAbsolutePath();
        final Path path = urlToPath(currDirPath, pathElement);
        if (path != null) {
            final File pathFile = path.toFile();
            if (pathFile.exists()) {
                final String pathStr = path.toString();
                if (classpathElementsSet.add(pathStr)) {
                    // This is the first time this classpath element has been encountered
                    boolean isValidClasspathElement = true;

                    // If this classpath element is a jar or zipfile, look for Class-Path entries in the manifest
                    // file. OpenJDK scans manifest-defined classpath elements after the jar that listed them, so
                    // we recursively call addClasspathElement if needed each time a jar is encountered. 
                    if (pathFile.isFile() && Utils.isJar(pathStr)) {
                        // Don't scan system jars
                        if (isJREJar(pathFile, /* ancestralScanDepth = */2)) {
                            isValidClasspathElement = false;
                            if (FastClasspathScanner.verbose) {
                                Log.log("Skipping JRE jar: " + pathStr);
                            }
                        } else {
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
                        if (FastClasspathScanner.verbose) {
                            Log.log("Found classpath element: " + path);
                        }
                        classpathElements.add(pathFile);
                    }
                }
            } else if (FastClasspathScanner.verbose) {
                Log.log("Classpath element does not exist: " + path);
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

    /** Parse the system classpath. */
    private void parseSystemClasspath() {
        clearClasspath();

        // Look for all unique classloaders.
        // Keep them in an order that (hopefully) reflects the order in which the JDK calls classloaders.
        final ArrayList<ClassLoader> classLoaders = new ArrayList<>();
        final HashSet<ClassLoader> classLoadersSet = new HashSet<>();
        classLoadersSet.add(ClassLoader.getSystemClassLoader());
        classLoaders.add(ClassLoader.getSystemClassLoader());
        // Dirty method for looking for other classloaders on the call stack
        try {
            // Generate stacktrace
            throw new Exception();
        } catch (final Exception e) {
            final StackTraceElement[] stacktrace = e.getStackTrace();
            if (stacktrace.length >= 3) {
                // Visit parent classloaders in top-down order, the same as in the JRE
                final ArrayList<ClassLoader> callerClassLoaders = new ArrayList<>();
                final StackTraceElement caller = stacktrace[2];
                for (ClassLoader cl = caller.getClass().getClassLoader(); cl != null; cl = cl.getParent()) {
                    callerClassLoaders.add(cl);
                }
                // OpenJDK calls classloaders in a top-down order
                for (int i = callerClassLoaders.size() - 1; i >= 0; --i) {
                    final ClassLoader cl = callerClassLoaders.get(i);
                    if (classLoadersSet.add(cl)) {
                        classLoaders.add(cl);
                    }
                }

                // Simpler version of this block that does not look up parent classloaders (in most runtime
                // environments, the only parent classloader will be the one that loads the Java extension
                // classes, and those are not scanned anyway), see:
                // https://docs.oracle.com/javase/8/docs/technotes/tools/findingclasses.html

                //    // Add the classloader from the calling class
                //    final StackTraceElement caller = stacktrace[2];
                //    final ClassLoader cl = caller.getClass().getClassLoader();
                //    if (classLoadersSet.add(cl)) {
                //        classLoaders.add(cl);
                //    }
            }
        }
        if (classLoadersSet.add(Thread.currentThread().getContextClassLoader())) {
            classLoaders.add(Thread.currentThread().getContextClassLoader());
        }

        // Get file paths for URLs of each classloader.
        for (final ClassLoader cl : classLoaders) {
            if (cl != null) {
                if (cl instanceof URLClassLoader) {
                    for (final URL url : ((URLClassLoader) cl).getURLs()) {
                        final String protocol = url.getProtocol();
                        if (protocol == null || protocol.equalsIgnoreCase("file")) {
                            // "file:" URL found in classpath
                            addClasspathElement(url.getFile());
                        }
                    }
                } else if (cl.getClass().getName().equals("org.jboss.modules.ModuleClassLoader")) {
                    // This is brittle, but it's the simplest way to support the JBoss ModuleClassLoader. See:
                    // https://github.com/jboss-modules/jboss-modules/blob/master/src/ ...
                    // main/java/org/jboss/modules/ModuleClassLoader.java
                    try {
                        final Method getPaths = cl.getClass().getDeclaredMethod("getPaths");
                        getPaths.setAccessible(true);
                        @SuppressWarnings("unchecked")
                        final Set<String> paths = (Set<String>) getPaths.invoke(cl);
                        for (final String path : paths) {
                            addClasspathElement(path);
                        }
                    } catch (final Exception e) {
                        Log.log("Was not able to call getPaths() in " + cl.getClass().getName() + ": "
                                + e.getMessage());
                    }
                } else if (cl.getClass().getName().equals("weblogic.utils.classloaders.ChangeAwareClassLoader")) {
                  try {
                    final Method getPaths = cl.getClass().getDeclaredMethod("getClassPath");
                    @SuppressWarnings("unchecked")
                    final String paths = (String) getPaths.invoke(cl);
                    for (final String path : paths.split(";")) {
                      addClasspathElement(path);
                    }
                  } catch (final Exception e) {
                    Log.log("Was not able to call getClassPath() in " + cl.getClass().getName() + ": "
                        + e.getMessage());
                  }
                } else {
                    Log.log("Found unknown ClassLoader type, cannot scan classes: " + cl.getClass().getName());
                }
            }
        }

        // Add entries found in java.class.path
        final String classpathProperty = System.getProperty("java.class.path");
        if (classpathProperty == null || classpathProperty.isEmpty()) {
            for (final String pathElement : classpathProperty.split(File.pathSeparator)) {
                addClasspathElement(pathElement);
            }
        }

        initialized = true;
    }

    /** Override the system classpath with a custom classpath to search. */
    public void overrideClasspath(final String classpath) {
        clearClasspath();
        for (final String pathElement : classpath.split(File.pathSeparator)) {
            addClasspathElement(pathElement);
        }
        initialized = true;
    }

    /**
     * Returns the list of all unique File objects representing directories or zip/jarfiles on the classpath, in
     * classloader resolution order. Classpath elements that do not exist are not included in the list.
     */
    public ArrayList<File> getUniqueClasspathElements() {
        if (!initialized) {
            parseSystemClasspath();
        }
        return classpathElements;
    }

}
