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
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.classloaderhandler.ClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.classloaderhandler.ClassLoaderHandler.DelegationOrder;
import io.github.lukehutch.fastclasspathscanner.classloaderhandler.ClassLoaderHandlerRegistry;
import io.github.lukehutch.fastclasspathscanner.classloaderhandler.ClassLoaderHandlerRegistry.ClassLoaderHandlerRegistryEntry;
import io.github.lukehutch.fastclasspathscanner.utils.AdditionOrderedSet;
import io.github.lukehutch.fastclasspathscanner.utils.FileUtils;
import io.github.lukehutch.fastclasspathscanner.utils.JarUtils;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.NestedJarHandler;

/** A class to find the unique ordered classpath elements. */
public class ClasspathFinder {
    private final NestedJarHandler nestedJarHandler;
    private static String currDirPathStr = FileUtils.getCurrDirPathStr();

    /** The ClassLoader order of the calling environment. */
    private final ClassLoader[] envClassLoaderOrder;

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
        return addClasspathElement(pathElement, new ClassLoader[] { classLoader }, log);
    }

    /**
     * Add a classpath element relative to a base file. May be called by a ClassLoaderHandler to add classpath
     * elements that it knows about. ClassLoaders will be called in order.
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
    private boolean addClasspathElement(final String pathElement, final ClassLoader[] classLoaders,
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
    private boolean addClasspathElements(final String pathStr, final ClassLoader[] classLoaders,
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

    /**
     * Recursively find the ClassLoaderHandler that can handle each ClassLoader and its parent(s), correctly
     * observing parent delegation order (PARENT_FIRST or PARENT_LAST).
     */
    private void findClassLoaderHandlerForClassLoaderAndParents(final ClassLoader classLoader,
            final List<ClassLoaderHandlerRegistryEntry> allClassLoaderHandlerEntries,
            final AdditionOrderedSet<ClassLoader> foundClassLoaders,
            final List<SimpleEntry<ClassLoader, ClassLoaderHandler>> classLoaderAndHandlerOrderOut,
            final ScanSpec scanSpec, final LogNode log) {
        // Don't add ClassLoaders twice (some environment ClassLoaders may share a parent)
        if (foundClassLoaders.add(classLoader)) {
            boolean foundMatch = false;
            // Iterate through each ClassLoader superclass name
            for (Class<?> c = classLoader.getClass(); c != null; c = c.getSuperclass()) {
                // Compare against the class names handled by each ClassLoaderHandler
                for (final ClassLoaderHandlerRegistryEntry classLoaderHandlerRegistryEntry : allClassLoaderHandlerEntries) {
                    for (final String handledClassLoaderName : classLoaderHandlerRegistryEntry.handledClassLoaderNames) {
                        if (handledClassLoaderName.equals(c.getName())) {
                            // This ClassLoaderHandler can handle this class
                            // Instantiate the ClassLoaderHandler for this ClassLoader
                            ClassLoaderHandler classLoaderHandler = null;
                            try {
                                classLoaderHandler = classLoaderHandlerRegistryEntry.classLoaderHandlerClass
                                        .newInstance();
                            } catch (InstantiationException | IllegalAccessException e) {
                                if (log != null) {
                                    log.log("Could not instantiate "
                                            + classLoaderHandlerRegistryEntry.classLoaderHandlerClass.getName(), e);
                                }
                            }
                            if (classLoaderHandler != null) {
                                final DelegationOrder delegationOrder = classLoaderHandler
                                        .getDelegationOrder(classLoader);
                                final ClassLoader parent = classLoader.getParent();
                                if (log != null && parent != null) {
                                    log.log(classLoader + " delegates to parent " + parent + " with order "
                                            + delegationOrder);
                                }
                                switch (delegationOrder) {
                                case PARENT_FIRST:
                                    // Recurse to parent first, then add this ClassLoader to order
                                    if (parent != null && !scanSpec.ignoreParentClassLoaders) {
                                        findClassLoaderHandlerForClassLoaderAndParents(parent,
                                                allClassLoaderHandlerEntries, foundClassLoaders,
                                                classLoaderAndHandlerOrderOut, scanSpec, log);
                                    }
                                    classLoaderAndHandlerOrderOut
                                            .add(new SimpleEntry<>(classLoader, classLoaderHandler));
                                    break;
                                case PARENT_LAST:
                                    // Add this ClassLoader to order, then recurse to parent
                                    classLoaderAndHandlerOrderOut
                                            .add(new SimpleEntry<>(classLoader, classLoaderHandler));
                                    if (parent != null && !scanSpec.ignoreParentClassLoaders) {
                                        findClassLoaderHandlerForClassLoaderAndParents(parent,
                                                allClassLoaderHandlerEntries, foundClassLoaders,
                                                classLoaderAndHandlerOrderOut, scanSpec, log);
                                    }
                                    break;
                                default:
                                    throw new RuntimeException("Unknown delegation order");
                                }
                                if (log != null) {
                                    log.log("ClassLoader " + classLoader + " will be handled by "
                                            + classLoaderHandler);
                                }
                            }
                            foundMatch = true;
                            break;
                        }
                    }
                    if (foundMatch) {
                        break;
                    }
                }
                if (foundMatch) {
                    break;
                }
            }
            if (!foundMatch && log != null) {
                log.log("Could not find a ClassLoaderHandler that can handle " + classLoader
                        + " -- please report this at https://github.com/lukehutch/fast-classpath-scanner/issues");
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A class to find the unique ordered classpath elements. */
    ClasspathFinder(final ScanSpec scanSpec, final NestedJarHandler nestedJarHandler, final LogNode log) {
        // Get environment ClassLoader order
        envClassLoaderOrder = ClassLoaderFinder.findEnvClassLoaders(scanSpec,
                log == null ? null : log.log("Finding ClassLoaders"));

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
            addClasspathElements(scanSpec.overrideClasspath, envClassLoaderOrder, overrideLog);
            if (overrideLog != null) {
                log.log("WARNING: when the classpath is overridden, there is no guarantee that the classes "
                        + "found by classpath scanning will be the same as the classes loaded by the context "
                        + "classloader");
            }
        } else {
            // If system jars are not blacklisted, need to manually add rt.jar at the beginning of the classpath,
            // because it is included implicitly by the JVM.
            // TODO: this is handled differently in Java 9.
            if (!scanSpec.blacklistSystemJars()) {
                // There should only be zero or one of these.
                final String rtJarPath = JarUtils.getRtJarPath();
                if (rtJarPath != null) {
                    // Insert rt.jar as the first entry in the classpath.
                    addClasspathElement(rtJarPath, envClassLoaderOrder, log);
                }
            }

            // Get all manually-added ClassLoaderHandlers
            // (these are added before the default ClassLoaderHandlers, so that the behavior of the defaults
            // can be overridden)
            List<ClassLoaderHandlerRegistryEntry> allClassLoaderHandlerEntries;
            if (scanSpec.extraClassLoaderHandlers.isEmpty()) {
                allClassLoaderHandlerEntries = ClassLoaderHandlerRegistry.DEFAULT_CLASS_LOADER_HANDLERS;
            } else {
                allClassLoaderHandlerEntries = new ArrayList<>(scanSpec.extraClassLoaderHandlers);
                allClassLoaderHandlerEntries.addAll(ClassLoaderHandlerRegistry.DEFAULT_CLASS_LOADER_HANDLERS);
            }
            if (log != null) {
                final LogNode classLoaderHandlerLog = log.log("ClassLoaderHandlers:");
                for (final ClassLoaderHandlerRegistryEntry classLoaderHandlerEntry : allClassLoaderHandlerEntries) {
                    classLoaderHandlerLog.log(classLoaderHandlerEntry.classLoaderHandlerClass.getName());
                }
            }

            // Find all unique parent ClassLoaders, and put all ClassLoaders into a single order,
            // according to the delegation order (PARENT_FIRST or PARENT_LAST)
            final List<SimpleEntry<ClassLoader, ClassLoaderHandler>> classLoaderAndHandlerOrder = new ArrayList<>();
            for (final ClassLoader envClassLoader : envClassLoaderOrder) {
                if (!scanSpec.blacklistSystemJars()
                        || !envClassLoader.getClass().getName().startsWith("sun.misc.Launcher$ExtClassLoader")) {
                    findClassLoaderHandlerForClassLoaderAndParents(envClassLoader, allClassLoaderHandlerEntries,
                            /* foundClassLoaders = */ new AdditionOrderedSet<>(), classLoaderAndHandlerOrder,
                            scanSpec, log);
                } else if (log != null) {
                    log.log("Skipping system classloader " + envClassLoader.getClass().getName());
                }
            }

            // Call each ClassLoaderHandler on its corresponding ClassLoader to get the classpath URLs or paths
            for (final SimpleEntry<ClassLoader, ClassLoaderHandler> classLoaderAndHandler : classLoaderAndHandlerOrder) {
                final ClassLoader classLoader = classLoaderAndHandler.getKey();

                final ClassLoaderHandler classLoaderHandler = classLoaderAndHandler.getValue();
                final LogNode classLoaderClasspathLog = log == null ? null
                        : log.log("Finding classpath elements in ClassLoader " + classLoader);
                try {
                    classLoaderHandler.handle(classLoader, /* classpathFinder = */ this, scanSpec,
                            classLoaderClasspathLog);
                } catch (final Throwable e) {
                    if (classLoaderClasspathLog != null) {
                        classLoaderClasspathLog.log("Exception in ClassLoaderHandler", e);
                    }
                }
            }

            if (scanSpec.overrideClassLoaders == null) {
                // Add entries found in java.class.path, in case those entries were missed above due to some
                // non-standard classloader that uses this property
                final LogNode sysPropLog = log == null ? null
                        : log.log("Getting classpath entries from java.class.path");
                addClasspathElements(System.getProperty("java.class.path"), envClassLoaderOrder, sysPropLog);
            }
        }
    }

    /** Get the raw classpath elements obtained from ClassLoaders. */
    public List<ClasspathRelativePath> getRawClasspathElements() {
        return rawClasspathElements;
    }

    /** Get the order in which ClassLoaders are called to load classes. (Usually consists of one element only.) */
    public ClassLoader[] getClassLoaderOrder() {
        return envClassLoaderOrder;
    }
}