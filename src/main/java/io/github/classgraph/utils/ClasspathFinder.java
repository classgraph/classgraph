/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.classgraph.utils;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.classgraph.ScanSpec;
import io.github.classgraph.classloaderhandler.ClassLoaderHandler;
import io.github.classgraph.classloaderhandler.ClassLoaderHandler.DelegationOrder;
import io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry.ClassLoaderHandlerRegistryEntry;

/** A class to find the unique ordered classpath elements. */
public class ClasspathFinder {
    private final List<ClasspathOrModulePathEntry> rawClasspathElements;
    private final ClassLoaderAndModuleFinder classLoaderAndModuleFinder;

    // -------------------------------------------------------------------------------------------------------------

    /** Add a ClassLoaderHandler, and recurse to parent classloader. */
    private boolean addClassLoaderHandler(final ScanSpec scanSpec, final ClassLoader classLoader,
            final ClassLoaderHandlerRegistryEntry classLoaderHandlerRegistryEntry,
            final LinkedHashSet<ClassLoader> foundClassLoaders,
            final List<ClassLoaderHandlerRegistryEntry> allClassLoaderHandlerRegistryEntries,
            final List<SimpleEntry<ClassLoader, ClassLoaderHandler>> classLoaderAndHandlerOrderOut,
            final List<SimpleEntry<ClassLoader, ClassLoaderHandler>> ignoredClassLoaderAndHandlerOrderOut,
            final Set<ClassLoader> visited, final LogNode log) {
        // Instantiate a ClassLoaderHandler for each ClassLoader, in case the ClassLoaderHandler has state
        final ClassLoaderHandler classLoaderHandler = classLoaderHandlerRegistryEntry.instantiate(log);
        if (classLoaderHandler != null) {
            if (log != null) {
                log.log("ClassLoader " + classLoader + " will be handled by " + classLoaderHandler);
            }
            final ClassLoader embeddedClassLoader = classLoaderHandler.getEmbeddedClassLoader(classLoader);
            if (embeddedClassLoader != null) {
                if (visited.add(embeddedClassLoader)) {
                    if (log != null) {
                        log.log("Delegating from " + classLoader + " to embedded ClassLoader "
                                + embeddedClassLoader);
                    }
                    return addClassLoaderHandler(scanSpec, embeddedClassLoader, classLoaderHandlerRegistryEntry,
                            foundClassLoaders, allClassLoaderHandlerRegistryEntries, classLoaderAndHandlerOrderOut,
                            ignoredClassLoaderAndHandlerOrderOut, visited, log);
                } else {
                    if (log != null) {
                        log.log("Hit infinite loop when delegating from " + classLoader
                                + " to embedded ClassLoader " + embeddedClassLoader);
                    }
                    return false;
                }
            } else {
                final DelegationOrder delegationOrder = classLoaderHandler.getDelegationOrder(classLoader);
                final ClassLoader parent = classLoader.getParent();
                if (log != null && parent != null) {
                    log.log(classLoader + " delegates to parent " + parent + " with order " + delegationOrder);
                }
                switch (delegationOrder) {
                case PARENT_FIRST:
                    // Recurse to parent first, then add this ClassLoader to order
                    if (parent != null) {
                        findClassLoaderHandlerForClassLoaderAndParents(scanSpec, parent, foundClassLoaders,
                                allClassLoaderHandlerRegistryEntries,
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
                        findClassLoaderHandlerForClassLoaderAndParents(scanSpec, parent, foundClassLoaders,
                                allClassLoaderHandlerRegistryEntries,
                                scanSpec.ignoreParentClassLoaders ? ignoredClassLoaderAndHandlerOrderOut
                                        : classLoaderAndHandlerOrderOut,
                                ignoredClassLoaderAndHandlerOrderOut, log);
                    }
                    return true;
                default:
                    throw new RuntimeException("Unknown delegation order");
                }
            }
        }
        return false;
    }

    /**
     * Recursively find the ClassLoaderHandler that can handle each ClassLoader and its parent(s), correctly
     * observing parent delegation order (PARENT_FIRST or PARENT_LAST).
     */
    private void findClassLoaderHandlerForClassLoaderAndParents(final ScanSpec scanSpec,
            final ClassLoader classLoader, final LinkedHashSet<ClassLoader> foundClassLoaders,
            final List<ClassLoaderHandlerRegistryEntry> allClassLoaderHandlerRegistryEntries,
            final List<SimpleEntry<ClassLoader, ClassLoaderHandler>> classLoaderAndHandlerOrderOut,
            final List<SimpleEntry<ClassLoader, ClassLoaderHandler>> ignoredClassLoaderAndHandlerOrderOut,
            final LogNode log) {
        // Don't handle ClassLoaders twice (so that any shared parent ClassLoaders get handled only once)
        if (foundClassLoaders.add(classLoader)) {
            boolean foundMatch = false;
            // Iterate through each ClassLoader superclass name
            for (Class<?> c = classLoader.getClass(); c != null; c = c.getSuperclass()) {
                // Compare against the class names handled by each ClassLoaderHandler
                for (final ClassLoaderHandlerRegistryEntry classLoaderHandlerRegistryEntry : //
                allClassLoaderHandlerRegistryEntries) {
                    for (final String handledClassLoaderName : //
                    classLoaderHandlerRegistryEntry.handledClassLoaderNames) {
                        if (handledClassLoaderName.equals(c.getName())) {
                            // This ClassLoaderHandler can handle this class -- instantiate it
                            if (addClassLoaderHandler(scanSpec, classLoader, classLoaderHandlerRegistryEntry,
                                    foundClassLoaders, allClassLoaderHandlerRegistryEntries,
                                    classLoaderAndHandlerOrderOut, ignoredClassLoaderAndHandlerOrderOut,
                                    new HashSet<ClassLoader>(), log)) {
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
                            + "https://github.com/classgraph/classgraph/issues");
                }
                addClassLoaderHandler(scanSpec, classLoader,
                        ClassLoaderHandlerRegistry.FALLBACK_CLASS_LOADER_HANDLER, foundClassLoaders,
                        allClassLoaderHandlerRegistryEntries, classLoaderAndHandlerOrderOut,
                        ignoredClassLoaderAndHandlerOrderOut, new HashSet<ClassLoader>(), log);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A class to find the unique ordered classpath elements.
     * 
     * @param scanSpec
     *            The {@link ScanSpec}.
     * @param nestedJarHandler
     *            The {@link NestedJarHandler}.
     * @param log
     *            The log.
     */
    public ClasspathFinder(final ScanSpec scanSpec, final NestedJarHandler nestedJarHandler, final LogNode log) {
        final LogNode classpathFinderLog = log == null ? null : log.log("Finding ClassLoaders and modules");

        // Get environment ClassLoader order
        classLoaderAndModuleFinder = new ClassLoaderAndModuleFinder(scanSpec, classpathFinderLog);

        final ClasspathOrder classpathOrder = new ClasspathOrder(scanSpec, nestedJarHandler);
        final ClasspathOrder ignoredClasspathOrder = new ClasspathOrder(scanSpec, nestedJarHandler);

        final ClassLoader[] classLoaders = classLoaderAndModuleFinder.getClassLoaders();
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
            classpathOrder.addClasspathElements(scanSpec.overrideClasspath, classLoaders, overrideLog);
            if (overrideLog != null) {
                overrideLog.log("WARNING: when the classpath is overridden, there is no guarantee that the classes "
                        + "found by classpath scanning will be the same as the classes loaded by the "
                        + "context classloader");
            }
        } else {
            // If system jars are not blacklisted, add JRE jars to the beginning of the classpath
            if (!scanSpec.blacklistSystemJarsOrModules) {
                final List<String> jreJarPaths = JarUtils.getJreJarPaths();
                if (log != null) {
                    log.log("Adding JRE/JDK jars to classpath:").log(jreJarPaths);
                }
                for (final String jreJarPath : jreJarPaths) {
                    classpathOrder.addClasspathElement(jreJarPath, classLoaders, scanSpec, classpathFinderLog);
                }
            }

            // List ClassLoaderHandlers
            if (classpathFinderLog != null) {
                final LogNode classLoaderHandlerLog = classpathFinderLog.log("ClassLoaderHandlers:");
                for (final ClassLoaderHandlerRegistryEntry classLoaderHandlerEntry : //
                ClassLoaderHandlerRegistry.CLASS_LOADER_HANDLERS) {
                    classLoaderHandlerLog.log(classLoaderHandlerEntry.classLoaderHandlerClass.getName());
                }
            }

            // Find all unique parent ClassLoaders, and put all ClassLoaders into a single order, according to the
            // delegation order (PARENT_FIRST or PARENT_LAST)
            final List<SimpleEntry<ClassLoader, ClassLoaderHandler>> classLoaderAndHandlerOrder = new ArrayList<>();
            final List<SimpleEntry<ClassLoader, ClassLoaderHandler>> ignoredClassLoaderAndHandlerOrder = //
                    new ArrayList<>();
            for (final ClassLoader envClassLoader : classLoaders) {
                findClassLoaderHandlerForClassLoaderAndParents(scanSpec, envClassLoader,
                        /* foundClassLoaders = */ new LinkedHashSet<ClassLoader>(),
                        ClassLoaderHandlerRegistry.CLASS_LOADER_HANDLERS, classLoaderAndHandlerOrder,
                        ignoredClassLoaderAndHandlerOrder, classpathFinderLog);
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
                        if (!ignoredClasspathOrder.get()
                                .contains(new ClasspathOrModulePathEntry(FileUtils.CURR_DIR_PATH, pathElement,
                                        classLoaders, nestedJarHandler, scanSpec, log))) {
                            // pathElement is not also listed in an ignored parent classloader
                            classpathOrder.addClasspathElement(pathElement, classLoaders, scanSpec, sysPropLog);
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
        rawClasspathElements = new ArrayList<>(classpathOrder.get());
    }

    /**
     * @return The raw classpath elements obtained from ClassLoaders.
     */
    public List<ClasspathOrModulePathEntry> getRawClasspathElements() {
        return rawClasspathElements;
    }

    /**
     * @return The {@link ClassLoaderAndModuleFinder}.
     */
    public ClassLoaderAndModuleFinder getClassLoaderAndModuleFinder() {
        return classLoaderAndModuleFinder;
    }
}
