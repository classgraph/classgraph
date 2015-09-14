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
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.jar.Manifest;

class ClasspathFinder {
    /** The unique elements of the classpath, as an ordered list. */
    private final ArrayList<File> classpathElements = new ArrayList<>();

    /** The unique elements of the classpath, as a set. */
    private final HashSet<String> classpathElementsSet = new HashSet<>();

    private boolean initialized = false;

    /** Clear the classpath. */
    private void clearClasspath() {
        classpathElements.clear();
        classpathElementsSet.clear();
        initialized = false;
    }

    /** Add a classpath element. */
    private void addClasspathElement(final String pathElement) {
        if (!pathElement.isEmpty()) {
            final File pathElementFile = new File(pathElement);
            if (pathElementFile.exists()) {
                // Canonicalize path so that we don't get stuck in a redirect loop due to softlinks
                String canonicalPath;
                try {
                    canonicalPath = pathElementFile.getCanonicalPath();
                } catch (IOException | SecurityException e) {
                    canonicalPath = pathElement;
                }
                if (classpathElementsSet.add(canonicalPath)) {
                    // This is the first time this classpath element has been encountered
                    if (FastClasspathScanner.verbose) {
                        Log.log("Found classpath element: " + pathElement);
                    }
                    classpathElements.add(pathElementFile);

                    // If this classpath element is a jar or zipfile, look for Class-Path entries in the manifest
                    // file. OpenJDK scans manifest-defined classpath elements after the jar that listed them, so
                    // we recursively call addClasspathElement if needed each time a jar is encountered. 
                    if (pathElementFile.isFile() && Utils.isJar(pathElement)) {
                        final String manifestUrlStr = "jar:file:" + pathElement + "!/META-INF/MANIFEST.MF";
                        try (InputStream stream = new URL(manifestUrlStr).openStream()) {
                            // Look for Class-Path keys within manifest files
                            final Manifest manifest = new Manifest(stream);
                            final String manifestClassPath = manifest.getMainAttributes().getValue("Class-Path");
                            if (manifestClassPath != null && !manifestClassPath.isEmpty()) {
                                if (FastClasspathScanner.verbose) {
                                    Log.log("Found Class-Path entry in " + manifestUrlStr + ": "
                                            + manifestClassPath);
                                }
                                // Class-Path elements are space-delimited
                                for (final String manifestClassPathElement : manifestClassPath.split(" ")) {
                                    // Resolve Class-Path elements relative to the parent jar's containing directory
                                    final String manifestClassPathElementAbsolute = new File(
                                            pathElementFile.getParent(), manifestClassPathElement).getPath();
                                    addClasspathElement(manifestClassPathElementAbsolute);
                                }
                            }
                        } catch (final IOException e) {
                            // Jar does not contain a manifest
                        }
                    }
                }
            } else if (FastClasspathScanner.verbose) {
                Log.log("Classpath element does not exist: " + pathElement);
            }
        }
    }

    /** Parse the system classpath. */
    private void parseSystemClasspath() {
        // Start with java.class.path (Maven sets this, but doesn't seem to add all classpath URLs to class loaders)
        String sysClassPath = System.getProperty("java.class.path");
        if (sysClassPath == null || sysClassPath.isEmpty()) {
            // Should never need this, but just in case java.class.path is empty, use current dir
            sysClassPath = ".";
        }
        overrideClasspath(sysClassPath);

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
                // Add the classloader from the calling class
                final StackTraceElement caller = stacktrace[2];
                final ClassLoader cl = caller.getClass().getClassLoader();
                if (classLoadersSet.add(cl)) {
                    classLoaders.add(cl);
                }

                // The following is for reference only: it adds the classloader for the Java extension classes
                // (which is at caller.getClass().getClassLoader().getParent()). Under most circumstances,
                // the user should not need to scan extension classes. See:
                // https://docs.oracle.com/javase/8/docs/technotes/tools/findingclasses.html

                //    ArrayList<ClassLoader> callerClassLoaders = new ArrayList<>();
                //    for (ClassLoader cl = caller.getClass().getClassLoader(); cl != null; cl = cl.getParent()) {
                //        callerClassLoaders.add(cl);
                //    }
                //    // OpenJDK calls classloaders in a top-down order
                //    for (int i = callerClassLoaders.size() - 1; i >= 0; --i) {
                //        ClassLoader cl = callerClassLoaders.get(i);
                //        if (classLoadersSet.add(cl)) {
                //            classLoaders.add(cl);
                //        }
                //    }
            }
        }
        if (classLoadersSet.add(Thread.currentThread().getContextClassLoader())) {
            classLoaders.add(Thread.currentThread().getContextClassLoader());
        }

        // Get file paths for URLs of each classloader.
        for (final ClassLoader cl : classLoaders) {
            if (cl != null) {
                for (final URL url : ((URLClassLoader) cl).getURLs()) {
                    final String protocol = url.getProtocol();
                    if (protocol == null || protocol.equalsIgnoreCase("file")) {
                        // "file:" URL found in classpath
                        addClasspathElement(url.getFile());
                    }
                }
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
