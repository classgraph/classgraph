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
package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.classloaderhandler.ClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.classloaderhandler.ClassLoaderHandlerRegistry;
import io.github.lukehutch.fastclasspathscanner.utils.AdditionOrderedSet;
import io.github.lukehutch.fastclasspathscanner.utils.FileUtils;
import io.github.lukehutch.fastclasspathscanner.utils.JarUtils;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.NestedJarHandler;

/** A class to find the unique ordered classpath elements. */
public class ClasspathFinder {
    private final NestedJarHandler nestedJarHandler;
    private static String currDirPathStr = FileUtils.getCurrDirPathStr();

    /** The ClassLoader order. */
    private final List<ClassLoader> classLoaderOrder;

    /** The list of raw classpath elements. */
    private final List<ClasspathRelativePath> rawClasspathElements = new ArrayList<>();
    private final Set<ClasspathRelativePath> rawClasspathElementsSet = new HashSet<>();

    /**
     * Add a classpath element relative to a base file. May be called by a ClassLoaderHandler to add classpath
     * elements that it knows about.
     */
    private void addClasspathElement(final ClasspathRelativePath classpathEltPath, final LogNode log) {
        if (rawClasspathElementsSet.add(classpathEltPath)) {
            rawClasspathElements.add(classpathEltPath);
            if (log != null) {
                log.log("Found classpath element: " + classpathEltPath);
            }
        } else {
            if (log != null) {
                log.log("Ignoring duplicate classpath element: " + classpathEltPath);
            }
        }
    }

    /**
     * Add a classpath element relative to a base file. May be called by a ClassLoaderHandler to add classpath
     * elements that it knows about.
     * 
     * @param pathElement
     *            the URL or path of the classpath element.
     * @param classLoader
     *            the ClassLoader that this classpath element was obtained from.
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * 
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    public boolean addClasspathElement(final String pathElement, final ClassLoader classLoader, final LogNode log) {
        if (pathElement == null || pathElement.isEmpty()) {
            return false;
        } else {
            addClasspathElement(new ClasspathRelativePath(currDirPathStr, pathElement, Arrays.asList(classLoader),
                    nestedJarHandler), log);
            return true;
        }
    }

    /**
     * Add a classpath element relative to a base file. May be called by a ClassLoaderHandler to add classpath
     * elements that it knows about.
     * 
     * @param pathElement
     *            the URL or path of the classpath element.
     * @param classLoaders
     *            the ClassLoader(s) that this classpath element was obtained from.
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * 
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    public boolean addClasspathElement(final String pathElement, final List<ClassLoader> classLoaders,
            final LogNode log) {
        if (pathElement == null || pathElement.isEmpty()) {
            return false;
        } else {
            addClasspathElement(
                    new ClasspathRelativePath(currDirPathStr, pathElement, classLoaders, nestedJarHandler), log);
            return true;
        }
    }

    /**
     * Add classpath elements, separated by the system path separator character. May be called by a
     * ClassLoaderHandler to add a path string that it knows about.
     * 
     * @param pathStr
     *            the delimited string of URLs or paths of the classpath.
     * @param classLoader
     *            the ClassLoader that this classpath was obtained from.
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * 
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    public boolean addClasspathElements(final String pathStr, final ClassLoader classLoader, final LogNode log) {
        if (pathStr == null || pathStr.isEmpty()) {
            return false;
        } else {
            for (final String pathElement : pathStr.split(File.pathSeparator)) {
                addClasspathElement(pathElement, classLoader, log);
            }
            return true;
        }
    }

    /**
     * Add classpath elements, separated by the system path separator character. May be called by a
     * ClassLoaderHandler to add a path string that it knows about.
     * 
     * @param pathStr
     *            the delimited string of URLs or paths of the classpath.
     * @param classLoaders
     *            the ClassLoader(s) that this classpath was obtained from.
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * 
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    public boolean addClasspathElements(final String pathStr, final List<ClassLoader> classLoaders,
            final LogNode log) {
        if (pathStr == null || pathStr.isEmpty()) {
            return false;
        } else {
            for (final String pathElement : pathStr.split(File.pathSeparator)) {
                addClasspathElement(pathElement, classLoaders, log);
            }
            return true;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A class to find the unique ordered classpath elements. */
    ClasspathFinder(final ScanSpec scanSpec, final NestedJarHandler nestedJarHandler, final LogNode log) {
        // Get ClassLoaders
        this.classLoaderOrder = ClassLoaderFinder.findClassLoaders(scanSpec,
                log == null ? null : log.log("Finding ClassLoaders"));

        // Get parent classloaders, in classpath resolution order, deduplicating
        final LogNode parentClassLoaderLog = log == null ? null : log.log("Finding parent ClassLoaders");
        final AdditionOrderedSet<ClassLoader> allClassLoaders = new AdditionOrderedSet<>();
        for (final ClassLoader classLoader : classLoaderOrder) {
            final ArrayList<ClassLoader> parentClassLoaders = new ArrayList<>();
            for (ClassLoader cl = classLoader; cl != null; cl = cl.getParent()) {
                parentClassLoaders.add(cl);
            }
            // OpenJDK calls classloaders in a top-down order
            for (int i = parentClassLoaders.size() - 1; i >= 0; --i) {
                final ClassLoader cl = parentClassLoaders.get(i);
                if (parentClassLoaderLog != null && i > 0) {
                    parentClassLoaderLog
                            .log("ClassLoader " + cl + " is parent of ClassLoader" + parentClassLoaders.get(i - 1));
                }
                allClassLoaders.add(cl);
            }
        }
        final List<ClassLoader> allClassLoadersOrdered = allClassLoaders.getList();

        this.nestedJarHandler = nestedJarHandler;
        if (scanSpec.overrideClasspath != null) {
            // Manual classpath override
            if (scanSpec.overrideClassLoaders != null) {
                if (log != null) {
                    log.log("It is not possible to override both the classpath and the ClassLoaders -- "
                            + "ignoring the ClassLoader override");
                }
            }
            final LogNode overrideLog = log == null ? null : log.log("Overriding classpath");
            addClasspathElements(scanSpec.overrideClasspath, allClassLoadersOrdered, overrideLog);
            if (overrideLog != null) {
                log.log("WARNING: when the classpath is overridden, there is no guarantee that the classes "
                        + "found by classpath scanning will be the same as the classes loaded by the context "
                        + "classloader");
            }
        } else {
            // If system jars are not blacklisted, need to manually add rt.jar at the beginning of the classpath,
            // because it is included implicitly by the JVM.
            if (!scanSpec.blacklistSystemJars()) {
                // There should only be zero or one of these.
                final String rtJarPath = JarUtils.getRtJarPath();
                if (rtJarPath != null) {
                    // Insert rt.jar as the first entry in the classpath.
                    addClasspathElement(rtJarPath, allClassLoadersOrdered, log);
                }
            }
            // Get all manually-added ClassLoaderHandlers
            final List<ClassLoaderHandler> classLoaderHandlers = new ArrayList<>();
            for (final Class<? extends ClassLoaderHandler> classLoaderHandler : scanSpec.extraClassLoaderHandlers) {
                try {
                    classLoaderHandlers.add(classLoaderHandler.newInstance());
                } catch (InstantiationException | IllegalAccessException e) {
                    if (log != null) {
                        log.log("Could not instantiate " + classLoaderHandler.getName(), e);
                    }
                }
            }
            // Add all default ClassLoaderHandlers after manually-added ClassLoaderHandlers
            for (final Class<? extends ClassLoaderHandler> classLoaderHandlerClass : //
            ClassLoaderHandlerRegistry.DEFAULT_CLASS_LOADER_HANDLERS) {
                try {
                    classLoaderHandlers.add(classLoaderHandlerClass.newInstance());
                } catch (InstantiationException | IllegalAccessException e) {
                    if (log != null) {
                        log.log("Could not instantiate " + classLoaderHandlerClass.getName(), e);
                    }
                }
            }
            if (log != null) {
                final LogNode classLoaderHandlerLog = log.log("ClassLoaderHandlers loaded:");
                for (final ClassLoaderHandler classLoaderHandler : classLoaderHandlers) {
                    classLoaderHandlerLog.log(classLoaderHandler.getClass().getName());
                }
            }

            // Try finding a handler for each of the classloaders discovered above
            for (final ClassLoader classLoader : allClassLoadersOrdered) {
                // Skip system classloaders for efficiency if system jars are not going to be scanned.
                // TODO: Update to include JDK9 system classloader names.
                if (!scanSpec.blacklistSystemJars()
                        || !classLoader.getClass().getName().startsWith("sun.misc.Launcher$ExtClassLoader")) {
                    final LogNode classLoaderClasspathLog = log == null ? null
                            : log.log("Finding classpath elements in ClassLoader " + classLoader);
                    // Iterate through registered ClassLoaderHandlers
                    boolean classloaderFound = false;
                    for (final ClassLoaderHandler handler : classLoaderHandlers) {
                        try {
                            if (handler.handle(classLoader, this, classLoaderClasspathLog)) {
                                classloaderFound = true;
                                break;
                            }
                        } catch (final Exception e) {
                            if (classLoaderClasspathLog != null) {
                                classLoaderClasspathLog.log("Exception in ClassLoaderHandler", e);
                            }
                        }
                    }
                    if (!classloaderFound) {
                        if (classLoaderClasspathLog != null) {
                            classLoaderClasspathLog.log("Unknown ClassLoader type, cannot scan classes");
                        }
                    }
                }
            }

            if (scanSpec.overrideClassLoaders == null) {
                // Add entries found in java.class.path, in case those entries were missed above due to some
                // non-standard classloader that uses this property
                final LogNode sysPropLog = log == null ? null
                        : log.log("Getting classpath entries from java.class.path");
                addClasspathElements(System.getProperty("java.class.path"), allClassLoadersOrdered, sysPropLog);
            }
        }
    }

    /** Get the raw classpath elements obtained from ClassLoaders. */
    public List<ClasspathRelativePath> getRawClasspathElements() {
        return rawClasspathElements;
    }

    /** Get the order in which ClassLoaders are called to load classes. (Usually consists of one element only.) */
    public List<ClassLoader> getClassLoaderOrder() {
        return classLoaderOrder;
    }
}