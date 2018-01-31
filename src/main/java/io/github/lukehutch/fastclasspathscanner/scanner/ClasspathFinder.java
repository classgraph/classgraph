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

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;

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
    static final String currDirPathStr = FileUtils.getCurrDirPathStr();

    private final List<RelativePath> rawClasspathElements;
    private final ClassLoader[] envClassLoaderOrder;

    // -------------------------------------------------------------------------------------------------------------

    /** Add a ClassLoaderHandler, and recurse to parent classloader. */
    private boolean addClassLoaderHandler(final ScanSpec scanSpec, final ClassLoader classLoader,
            final ClassLoaderHandlerRegistryEntry classLoaderHandlerRegistryEntry,
            final List<ClassLoaderHandlerRegistryEntry> allClassLoaderHandlerEntries,
            final AdditionOrderedSet<ClassLoader> foundClassLoaders,
            final List<SimpleEntry<ClassLoader, ClassLoaderHandler>> classLoaderAndHandlerOrderOut,
            final List<SimpleEntry<ClassLoader, ClassLoaderHandler>> ignoredClassLoaderAndHandlerOrderOut,
            final LogNode log) {
        ClassLoaderHandler classLoaderHandler = null;
        try {
            // Instantiate a ClassLoaderHandler for each ClassLoader, in case the ClassLoaderHandler has state
            classLoaderHandler = classLoaderHandlerRegistryEntry.classLoaderHandlerClass.getDeclaredConstructor()
                    .newInstance();
        } catch (final Exception e) {
            if (log != null) {
                log.log("Could not instantiate "
                        + classLoaderHandlerRegistryEntry.classLoaderHandlerClass.getName(), e);
            }
        }
        if (classLoaderHandler != null) {
            if (log != null) {
                log.log("ClassLoader " + classLoader + " will be handled by " + classLoaderHandler);
            }
            final DelegationOrder delegationOrder = classLoaderHandler.getDelegationOrder(classLoader);
            final ClassLoader parent = classLoader.getParent();
            if (log != null && parent != null) {
                log.log(classLoader + " delegates to parent " + parent + " with order " + delegationOrder);
            }
            switch (delegationOrder) {
            case PARENT_FIRST:
                // Recurse to parent first, then add this ClassLoader to order
                if (parent != null) {
                    findClassLoaderHandlerForClassLoaderAndParents(scanSpec, parent, allClassLoaderHandlerEntries,
                            foundClassLoaders,
                            scanSpec.ignoreParentClassLoaders ? ignoredClassLoaderAndHandlerOrderOut
                                    : classLoaderAndHandlerOrderOut,
                            ignoredClassLoaderAndHandlerOrderOut, log);
                }
                classLoaderAndHandlerOrderOut.add(new SimpleEntry<>(classLoader, classLoaderHandler));
                return true;
            case PARENT_LAST:
                // Add this ClassLoader to order, then recurse to parent
                classLoaderAndHandlerOrderOut.add(new SimpleEntry<>(classLoader, classLoaderHandler));
                if (parent != null) {
                    findClassLoaderHandlerForClassLoaderAndParents(scanSpec, parent, allClassLoaderHandlerEntries,
                            foundClassLoaders,
                            scanSpec.ignoreParentClassLoaders ? ignoredClassLoaderAndHandlerOrderOut
                                    : classLoaderAndHandlerOrderOut,
                            ignoredClassLoaderAndHandlerOrderOut, log);
                }
                return true;
            default:
                throw new RuntimeException("Unknown delegation order");
            }
        }
        return false;
    }

    /**
     * Recursively find the ClassLoaderHandler that can handle each ClassLoader and its parent(s), correctly
     * observing parent delegation order (PARENT_FIRST or PARENT_LAST).
     */
    private void findClassLoaderHandlerForClassLoaderAndParents(final ScanSpec scanSpec,
            final ClassLoader classLoader, final List<ClassLoaderHandlerRegistryEntry> allClassLoaderHandlerEntries,
            final AdditionOrderedSet<ClassLoader> foundClassLoaders,
            final List<SimpleEntry<ClassLoader, ClassLoaderHandler>> classLoaderAndHandlerOrderOut,
            final List<SimpleEntry<ClassLoader, ClassLoaderHandler>> ignoredClassLoaderAndHandlerOrderOut,
            final LogNode log) {
        // Don't handle ClassLoaders twice (so that any shared parent ClassLoaders get handled only once)
        if (foundClassLoaders.add(classLoader)) {
            boolean foundMatch = false;
            // Iterate through each ClassLoader superclass name
            for (Class<?> c = classLoader.getClass(); c != null; c = c.getSuperclass()) {
                // Compare against the class names handled by each ClassLoaderHandler
                for (final ClassLoaderHandlerRegistryEntry classLoaderHandlerRegistryEntry : allClassLoaderHandlerEntries) {
                    for (final String handledClassLoaderName : classLoaderHandlerRegistryEntry.handledClassLoaderNames) {
                        if (handledClassLoaderName.equals(c.getName())) {
                            // This ClassLoaderHandler can handle this class Instantiate the ClassLoaderHandler for
                            // this ClassLoader
                            if (addClassLoaderHandler(scanSpec, classLoader, classLoaderHandlerRegistryEntry,
                                    allClassLoaderHandlerEntries, foundClassLoaders, classLoaderAndHandlerOrderOut,
                                    ignoredClassLoaderAndHandlerOrderOut, log)) {
                                foundMatch = true;
                            }
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
            if (!foundMatch) {
                if (log != null) {
                    log.log("Could not find a ClassLoaderHandler that can handle " + classLoader + " , trying "
                            + ClassLoaderHandlerRegistry.FALLBACK_CLASS_LOADER_HANDLER.classLoaderHandlerClass
                                    .getName()
                            + " instead. Please report this at: "
                            + "https://github.com/lukehutch/fast-classpath-scanner/issues");
                }
                addClassLoaderHandler(scanSpec, classLoader,
                        ClassLoaderHandlerRegistry.FALLBACK_CLASS_LOADER_HANDLER, allClassLoaderHandlerEntries,
                        foundClassLoaders, classLoaderAndHandlerOrderOut, ignoredClassLoaderAndHandlerOrderOut,
                        log);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A class to find the unique ordered classpath elements. */
    ClasspathFinder(final ScanSpec scanSpec, final NestedJarHandler nestedJarHandler, final LogNode log) {
        final LogNode classpathFinderLog = log == null ? null : log.log("Finding ClassLoaders");

        // Get environment ClassLoader order
        envClassLoaderOrder = ClassLoaderFinder.findEnvClassLoaders(scanSpec, classpathFinderLog);

        final ClasspathOrder classpathOrder = new ClasspathOrder(nestedJarHandler);
        final ClasspathOrder ignoredClasspathOrder = new ClasspathOrder(nestedJarHandler);

        if (scanSpec.overrideClasspath != null) {
            // Manual classpath override
            if (scanSpec.overrideClassLoaders != null) {
                if (classpathFinderLog != null) {
                    classpathFinderLog
                            .log("It is not possible to override both the classpath and the ClassLoaders -- "
                                    + "ignoring the ClassLoader override");
                }
            }
            final LogNode overrideLog = classpathFinderLog == null ? null
                    : classpathFinderLog.log("Overriding classpath with: " + scanSpec.overrideClasspath);
            classpathOrder.addClasspathElements(scanSpec.overrideClasspath, envClassLoaderOrder, overrideLog);
            if (overrideLog != null) {
                classpathFinderLog
                        .log("WARNING: when the classpath is overridden, there is no guarantee that the classes "
                                + "found by classpath scanning will be the same as the classes loaded by the "
                                + "context classloader");
            }
        } else {
            // If system jars are not blacklisted, need to manually add rt.jar at the beginning of the classpath,
            // because it is included implicitly by the JVM. TODO: this is handled differently in Java 9.
            if (!scanSpec.blacklistSystemJars()) {
                // There should only be zero or one of these.
                final String rtJarPath = JarUtils.getRtJarPath();
                if (log != null) {
                    log.log(rtJarPath == null ? "Could not find path for rt.jar"
                            : "Adding rt.jar as first classpath element to scan: " + rtJarPath);
                }
                if (rtJarPath != null) {
                    // Insert rt.jar as the first entry in the classpath.
                    classpathOrder.addClasspathElement(rtJarPath, envClassLoaderOrder, classpathFinderLog);
                }
            }

            // Get all manually-added ClassLoaderHandlers (these are added before the default ClassLoaderHandlers,
            // so that the behavior of the defaults can be overridden)
            List<ClassLoaderHandlerRegistryEntry> allClassLoaderHandlerEntries;
            if (scanSpec.extraClassLoaderHandlers.isEmpty()) {
                allClassLoaderHandlerEntries = ClassLoaderHandlerRegistry.DEFAULT_CLASS_LOADER_HANDLERS;
            } else {
                allClassLoaderHandlerEntries = new ArrayList<>(scanSpec.extraClassLoaderHandlers);
                allClassLoaderHandlerEntries.addAll(ClassLoaderHandlerRegistry.DEFAULT_CLASS_LOADER_HANDLERS);
            }
            if (classpathFinderLog != null) {
                final LogNode classLoaderHandlerLog = classpathFinderLog.log("ClassLoaderHandlers:");
                for (final ClassLoaderHandlerRegistryEntry classLoaderHandlerEntry : allClassLoaderHandlerEntries) {
                    classLoaderHandlerLog.log(classLoaderHandlerEntry.classLoaderHandlerClass.getName());
                }
            }

            // Find all unique parent ClassLoaders, and put all ClassLoaders into a single order, according to the
            // delegation order (PARENT_FIRST or PARENT_LAST)
            final List<SimpleEntry<ClassLoader, ClassLoaderHandler>> classLoaderAndHandlerOrder = new ArrayList<>();
            final List<SimpleEntry<ClassLoader, ClassLoaderHandler>> ignoredClassLoaderAndHandlerOrder = //
                    new ArrayList<>();
            for (final ClassLoader envClassLoader : envClassLoaderOrder) {
                if (!scanSpec.blacklistSystemJars()
                        || !envClassLoader.getClass().getName().startsWith("sun.misc.Launcher$ExtClassLoader")) {
                    findClassLoaderHandlerForClassLoaderAndParents(scanSpec, envClassLoader,
                            allClassLoaderHandlerEntries,
                            /* foundClassLoaders = */ new AdditionOrderedSet<ClassLoader>(),
                            classLoaderAndHandlerOrder, ignoredClassLoaderAndHandlerOrder, classpathFinderLog);
                } else if (classpathFinderLog != null) {
                    classpathFinderLog.log("Skipping system classloader " + envClassLoader.getClass().getName());
                }
            }

            // Call each ClassLoaderHandler on its corresponding ClassLoader to get the classpath URLs or paths
            for (final SimpleEntry<ClassLoader, ClassLoaderHandler> classLoaderAndHandler : //
            classLoaderAndHandlerOrder) {
                final ClassLoader classLoader = classLoaderAndHandler.getKey();
                final ClassLoaderHandler classLoaderHandler = classLoaderAndHandler.getValue();
                final LogNode classLoaderClasspathLog = classpathFinderLog == null ? null
                        : classpathFinderLog.log("Finding classpath elements in ClassLoader " + classLoader);
                try {
                    classLoaderHandler.handle(scanSpec, classLoader, classpathOrder, classLoaderClasspathLog);
                } catch (final Throwable e) {
                    if (classLoaderClasspathLog != null) {
                        classLoaderClasspathLog.log("Exception in ClassLoaderHandler", e);
                    }
                }
            }
            // Repeat the process for ignored parent ClassLoaders
            for (final SimpleEntry<ClassLoader, ClassLoaderHandler> classLoaderAndHandler : //
            ignoredClassLoaderAndHandlerOrder) {
                final ClassLoader classLoader = classLoaderAndHandler.getKey();
                final ClassLoaderHandler classLoaderHandler = classLoaderAndHandler.getValue();
                final LogNode classLoaderClasspathLog = classpathFinderLog == null ? null
                        : classpathFinderLog
                                .log("Will not scan the following classpath elements from ignored ClassLoader "
                                        + classLoader);
                try {
                    classLoaderHandler.handle(scanSpec, classLoader, ignoredClasspathOrder,
                            classLoaderClasspathLog);
                } catch (final Throwable e) {
                    if (classLoaderClasspathLog != null) {
                        classLoaderClasspathLog.log("Exception in ClassLoaderHandler", e);
                    }
                }
            }

            // Get classpath elements from java.class.path, but don't add them if the element is in an ignored
            // parent classloader and not in a child classloader (and don't use java.class.path at all if
            // overrideClassLoaders is true or overrideClasspath is set)
            if (scanSpec.overrideClassLoaders == null && scanSpec.overrideClasspath == null) {
                final String[] pathElements = JarUtils.smartPathSplit(System.getProperty("java.class.path"));
                if (pathElements.length > 0) {
                    final LogNode sysPropLog = classpathFinderLog == null ? null
                            : classpathFinderLog.log("Getting classpath entries from java.class.path");
                    for (final String pathElement : pathElements) {
                        if (!ignoredClasspathOrder.get().contains(new RelativePath(currDirPathStr, pathElement,
                                envClassLoaderOrder, nestedJarHandler, log))) {
                            // pathElement is not also listed in an ignored parent classloader
                            classpathOrder.addClasspathElement(pathElement, envClassLoaderOrder, sysPropLog);
                        } else {
                            // pathElement is also listed in an ignored parent classloader, ignore it (Issue #169)
                            if (sysPropLog != null) {
                                sysPropLog.log("Found classpath element in java.class.path that will be ignored, "
                                        + "since it is also found in an ignored parent classloader: "
                                        + pathElement);
                            }
                        }
                    }
                }
            }
        }
        rawClasspathElements = classpathOrder.get().toList();
    }

    /** Get the raw classpath elements obtained from ClassLoaders. */
    public List<RelativePath> getRawClasspathElements() {
        return rawClasspathElements;
    }

    /**
     * Get the order in which ClassLoaders are called to load classes. (Usually consists of one element only.)
     */
    public ClassLoader[] getClassLoaderOrder() {
        return envClassLoaderOrder;
    }
}
