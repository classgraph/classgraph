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
 * Copyright (c) 2019 Luke Hutchison
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
package nonapi.io.github.classgraph.classpath;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry.ClassLoaderHandlerRegistryEntry;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** A class to find the unique ordered classpath elements. */
public class ClasspathFinder {
    /** The classpath order. */
    private final ClasspathOrder classpathOrder;

    /** The classloader and module finder. */
    private final ClassLoaderAndModuleFinder classLoaderAndModuleFinder;

    /**
     * The default order in which ClassLoaders are called to load classes, respecting parent-first/parent-last
     * delegation order.
     */
    private ClassLoader[] classLoaderOrderRespectingParentDelegation;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the classpath order.
     *
     * @return The order of raw classpath elements obtained from ClassLoaders.
     */
    public ClasspathOrder getClasspathOrder() {
        return classpathOrder;
    }

    /**
     * Get the classloader and module finder.
     *
     * @return The {@link ClassLoaderAndModuleFinder}.
     */
    public ClassLoaderAndModuleFinder getClassLoaderAndModuleFinder() {
        return classLoaderAndModuleFinder;
    }

    /**
     * Get the ClassLoader order, respecting parent-first/parent-last delegation order.
     *
     * @return the class loader order.
     */
    public ClassLoader[] getClassLoaderOrderRespectingParentDelegation() {
        return classLoaderOrderRespectingParentDelegation;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A class to find the unique ordered classpath elements.
     * 
     * @param scanSpec
     *            The {@link ScanSpec}.
     * @param log
     *            The log.
     */
    public ClasspathFinder(final ScanSpec scanSpec, final LogNode log) {
        final LogNode classpathFinderLog = log == null ? null : log.log("Finding classpath and modules");

        // If system jars are not blacklisted, add JRE rt.jar to the beginning of the classpath
        final String jreRtJar = SystemJarFinder.getJreRtJarPath();
        final boolean scanAllLibOrExtJars = !scanSpec.libOrExtJarWhiteBlackList.whitelistAndBlacklistAreEmpty();
        final LogNode systemJarsLog = classpathFinderLog == null ? null : classpathFinderLog.log("System jars:");

        classLoaderAndModuleFinder = new ClassLoaderAndModuleFinder(scanSpec, classpathFinderLog);

        classpathOrder = new ClasspathOrder(scanSpec);
        final ClasspathOrder ignoredClasspathOrder = new ClasspathOrder(scanSpec);

        final ClassLoader[] contextClassLoaders = classLoaderAndModuleFinder.getContextClassLoaders();
        final ClassLoader defaultClassLoader = contextClassLoaders != null && contextClassLoaders.length > 0
                ? contextClassLoaders[0]
                : null;
        if (scanSpec.overrideClasspath != null) {
            // Manual classpath override
            if (scanSpec.overrideClassLoaders != null && classpathFinderLog != null) {
                classpathFinderLog.log("It is not possible to override both the classpath and the ClassLoaders -- "
                        + "ignoring the ClassLoader override");
            }
            final LogNode overrideLog = classpathFinderLog == null ? null
                    : classpathFinderLog.log("Overriding classpath with: " + scanSpec.overrideClasspath);
            classpathOrder.addClasspathEntries(scanSpec.overrideClasspath, defaultClassLoader, scanSpec,
                    overrideLog);
            if (overrideLog != null) {
                overrideLog.log("WARNING: when the classpath is overridden, there is no guarantee that the classes "
                        + "found by classpath scanning will be the same as the classes loaded by the "
                        + "context classloader");
            }
            classLoaderOrderRespectingParentDelegation = contextClassLoaders;

        } else {
            // Add rt.jar and/or lib/ext jars to beginning of classpath, if enabled
            if (jreRtJar != null) {
                if (scanSpec.enableSystemJarsAndModules) {
                    classpathOrder.addSystemClasspathEntry(jreRtJar, defaultClassLoader);
                    if (systemJarsLog != null) {
                        systemJarsLog.log("Found rt.jar: " + jreRtJar);
                    }
                } else if (systemJarsLog != null) {
                    systemJarsLog.log((scanSpec.enableSystemJarsAndModules ? "" : "Scanning disabled for rt.jar: ")
                            + jreRtJar);
                }
            }
            for (final String libOrExtJarPath : SystemJarFinder.getJreLibOrExtJars()) {
                if (scanAllLibOrExtJars || scanSpec.libOrExtJarWhiteBlackList
                        .isSpecificallyWhitelistedAndNotBlacklisted(libOrExtJarPath)) {
                    classpathOrder.addSystemClasspathEntry(libOrExtJarPath, defaultClassLoader);
                    if (systemJarsLog != null) {
                        systemJarsLog.log("Found lib or ext jar: " + libOrExtJarPath);
                    }
                } else if (systemJarsLog != null) {
                    systemJarsLog.log("Scanning disabled for lib or ext jar: " + libOrExtJarPath);
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

            // Find all unique classloaders, in delegation order
            final ClassLoaderOrder classLoaderOrder = new ClassLoaderOrder();
            if (contextClassLoaders != null) {
                for (final ClassLoader classLoader : contextClassLoaders) {
                    classLoaderOrder.delegateTo(classLoader, /* isParent = */ false);
                }
            }

            // Get all parent classloaders
            final Set<ClassLoader> allParentClassLoaders = classLoaderOrder.getAllParentClassLoaders();

            // Get the classpath URLs from each ClassLoader
            final List<ClassLoader> finalClassLoaderOrder = new ArrayList<>();
            for (final Entry<ClassLoader, ClassLoaderHandlerRegistryEntry> ent : classLoaderOrder
                    .getClassLoaderOrder()) {
                final ClassLoader classLoader = ent.getKey();
                final ClassLoaderHandlerRegistryEntry classLoaderHandlerRegistryEntry = ent.getValue();
                // Add classpath entries to ignoredClasspathOrder or classpathOrder
                if (scanSpec.ignoreParentClassLoaders && allParentClassLoaders.contains(classLoader)) {
                    // If this is a parent and parent classloaders are being ignored, add classpath entries
                    // to ignoredClasspathOrder
                    classLoaderHandlerRegistryEntry.findClasspathOrder(classLoader, ignoredClasspathOrder, scanSpec,
                            classpathFinderLog);
                } else {
                    // Otherwise add classpath entries to classpathOrder, and add the classloader to the
                    // final classloader ordering
                    classLoaderHandlerRegistryEntry.findClasspathOrder(classLoader, classpathOrder, scanSpec,
                            classpathFinderLog);
                    finalClassLoaderOrder.add(classLoader);
                }
            }

            // Need to record the classloader delegation order, in particular to respect parent-last delegation
            // order, since this is not the default (issue #267).
            classLoaderOrderRespectingParentDelegation = finalClassLoaderOrder.toArray(new ClassLoader[0]);

            // Get classpath elements from java.class.path, but don't add them if the element is in an ignored
            // parent classloader and not in a child classloader (and don't use java.class.path at all if
            // overrideClassLoaders is true or overrideClasspath is set)
            if (scanSpec.overrideClassLoaders == null && scanSpec.overrideClasspath == null) {
                final String[] pathElements = JarUtils.smartPathSplit(System.getProperty("java.class.path"));
                if (pathElements.length > 0) {
                    final LogNode sysPropLog = classpathFinderLog == null ? null
                            : classpathFinderLog.log("Getting classpath entries from java.class.path");
                    for (final String pathElement : pathElements) {
                        final String pathElementResolved = FastPathResolver.resolve(FileUtils.CURR_DIR_PATH,
                                pathElement);
                        if (!ignoredClasspathOrder.getClasspathEntryUniqueResolvedPaths()
                                .contains(pathElementResolved)) {
                            // pathElement is not also listed in an ignored parent classloader
                            classpathOrder.addClasspathEntry(pathElement, defaultClassLoader, scanSpec, sysPropLog);
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
    }
}
