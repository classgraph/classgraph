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

import io.github.classgraph.ClassGraphClassLoader;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry.ClassLoaderHandlerRegistryEntry;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** A class to find the unique ordered classpath elements. */
public class ClasspathFinder {
    /** The classpath order. */
    private final ClasspathOrder classpathOrder;

    /** The {@link ModuleFinder}, if modules are to be scanned. */
    private final ModuleFinder moduleFinder;

    /**
     * The default order in which ClassLoaders are called to load classes, respecting parent-first/parent-last
     * delegation order.
     */
    private ClassLoader[] classLoaderOrderRespectingParentDelegation;

    /**
     * If one of the classloaders that was found was an existing instance of {@link ClassGraphClassLoader}, then
     * delegate to that classloader first rather than trying to load from the {@link ClassGraphClassLoader} of the
     * current scan, so that classes are compatible between nested scans (#485).
     */
    private ClassGraphClassLoader delegateClassGraphClassLoader;

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
     * Get the {@link ModuleFinder}.
     *
     * @return The {@link ModuleFinder}.
     */
    public ModuleFinder getModuleFinder() {
        return moduleFinder;
    }

    /**
     * Get the ClassLoader order, respecting parent-first/parent-last delegation order.
     *
     * @return the class loader order.
     */
    public ClassLoader[] getClassLoaderOrderRespectingParentDelegation() {
        return classLoaderOrderRespectingParentDelegation;
    }

    /**
     * If one of the classloaders that was found was an existing instance of {@link ClassGraphClassLoader}, then
     * delegate to that classloader first rather than trying to load from the {@link ClassGraphClassLoader} of the
     * current scan, so that classes are compatible between nested scans (#485).
     * 
     * @return the {@link ClassGraphClassLoader} to delegate to before loading classes with this scan's own
     *         {@link ClassGraphClassLoader} (or null if none).
     */
    public ClassGraphClassLoader getDelegateClassGraphClassLoader() {
        return delegateClassGraphClassLoader;
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
    public ClasspathFinder(final ScanSpec scanSpec, final ReflectionUtils reflectionUtils, final LogNode log) {
        final LogNode classpathFinderLog = log == null ? null : log.log("Finding classpath and modules");

        // Require scanning traditional classpath if an override classloader is AppClassLoader (#639)
        boolean forceScanJavaClassPath = false;

        // If classloaders are overridden, check if the override classloader(s) is/are JPMS classloaders.
        // If so, need to enable non-system module scanning.
        boolean scanNonSystemModules;
        if (scanSpec.overrideClasspath != null) {
            // Don't scan non-system modules if classpath is overridden
            scanNonSystemModules = false;
        } else if (scanSpec.overrideClassLoaders != null) {
            // If classloaders are overridden, only scan non-system modules if an override classloader is a JPMS
            // AppClassLoader or PlatformClassLoader
            scanNonSystemModules = false;
            for (final ClassLoader classLoader : scanSpec.overrideClassLoaders) {
                final String classLoaderClassName = classLoader.getClass().getName();
                // It's not possible to instantiate AppClassLoader or PlatformClassLoader, so if these are
                // passed in as override classloaders, they must have been obtained using
                // Thread.currentThread().getContextClassLoader() [.getParent()] or similar
                if (!scanSpec.enableSystemJarsAndModules) {
                    if (classpathFinderLog != null && classLoaderClassName
                            .equals("jdk.internal.loader.ClassLoaders$PlatformClassLoader")) {
                        classpathFinderLog
                                .log("overrideClassLoaders() was called with an instance of " + classLoaderClassName
                                        + ", so enableSystemJarsAndModules() was called automatically");
                        scanSpec.enableSystemJarsAndModules = true;
                    }
                }
                if (classLoaderClassName.equals("jdk.internal.loader.ClassLoaders$AppClassLoader")
                        || classLoaderClassName.equals("jdk.internal.loader.ClassLoaders$PlatformClassLoader")) {
                    if (classpathFinderLog != null) {
                        classpathFinderLog
                                .log("overrideClassLoaders() was called with an instance of " + classLoaderClassName
                                        + ", so the `java.class.path` classpath will also be scanned");
                    }
                    forceScanJavaClassPath = true;
                }
            }
        } else {
            // If classloaders are not overridden and classpath is not overridden, only scan non-system modules
            // if module scanning is enabled
            scanNonSystemModules = scanSpec.scanModules;
        }

        // Only instantiate a module finder if requested
        moduleFinder = scanNonSystemModules || scanSpec.enableSystemJarsAndModules
                ? new ModuleFinder(new CallStackReader(reflectionUtils).getClassContext(classpathFinderLog),
                        scanSpec, scanNonSystemModules,
                        /* scanSystemModules = */ scanSpec.enableSystemJarsAndModules, reflectionUtils,
                        classpathFinderLog)
                : null;

        classpathOrder = new ClasspathOrder(scanSpec, reflectionUtils);

        // Only look for environment classloaders if classpath and classloaders are not overridden
        final ClassLoaderFinder classLoaderFinder = scanSpec.overrideClasspath == null
                && scanSpec.overrideClassLoaders == null
                        ? new ClassLoaderFinder(scanSpec, reflectionUtils, classpathFinderLog)
                        : null;
        final ClassLoader[] contextClassLoaders = classLoaderFinder == null ? new ClassLoader[0]
                : classLoaderFinder.getContextClassLoaders();
        final ClassLoader defaultClassLoader = contextClassLoaders.length > 0 ? contextClassLoaders[0] : null;
        if (scanSpec.overrideClasspath != null) {
            // Manual classpath override
            if (scanSpec.overrideClassLoaders != null && classpathFinderLog != null) {
                classpathFinderLog.log("It is not possible to override both the classpath and the ClassLoaders -- "
                        + "ignoring the ClassLoader override");
            }
            final LogNode overrideLog = classpathFinderLog == null ? null
                    : classpathFinderLog.log("Overriding classpath with: " + scanSpec.overrideClasspath);
            classpathOrder.addClasspathEntries(scanSpec.overrideClasspath,
                    // If the classpath is overridden, the classloader used to load classes is overridden in
                    // ClassGraphClassLoader by a custom URLClassLoader that loads from the override classpath.
                    // Just use defaultClassLoader as a placeholder here.
                    defaultClassLoader, scanSpec, overrideLog);
            if (overrideLog != null) {
                overrideLog.log("WARNING: when the classpath is overridden, there is no guarantee that the classes "
                        + "found by classpath scanning will be the same as the classes loaded by the "
                        + "context classloader");
            }
            classLoaderOrderRespectingParentDelegation = contextClassLoaders;
        }

        // If system jars and modules are enabled, add JRE rt.jar to the beginning of the classpath
        if (scanSpec.enableSystemJarsAndModules) {
            final String jreRtJar = SystemJarFinder.getJreRtJarPath();

            // Add rt.jar and/or lib/ext jars to beginning of classpath, if enabled
            final LogNode systemJarsLog = classpathFinderLog == null ? null
                    : classpathFinderLog.log("System jars:");
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
            final boolean scanAllLibOrExtJars = !scanSpec.libOrExtJarAcceptReject.acceptAndRejectAreEmpty();
            for (final String libOrExtJarPath : SystemJarFinder.getJreLibOrExtJars()) {
                if (scanAllLibOrExtJars
                        || scanSpec.libOrExtJarAcceptReject.isSpecificallyAcceptedAndNotRejected(libOrExtJarPath)) {
                    classpathOrder.addSystemClasspathEntry(libOrExtJarPath, defaultClassLoader);
                    if (systemJarsLog != null) {
                        systemJarsLog.log("Found lib or ext jar: " + libOrExtJarPath);
                    }
                } else if (systemJarsLog != null) {
                    systemJarsLog.log("Scanning disabled for lib or ext jar: " + libOrExtJarPath);
                }
            }
        }

        if (scanSpec.overrideClasspath == null) {
            // List ClassLoaderHandlers
            if (classpathFinderLog != null) {
                final LogNode classLoaderHandlerLog = classpathFinderLog.log("ClassLoaderHandlers:");
                for (final ClassLoaderHandlerRegistryEntry classLoaderHandlerEntry : //
                ClassLoaderHandlerRegistry.CLASS_LOADER_HANDLERS) {
                    classLoaderHandlerLog.log(classLoaderHandlerEntry.classLoaderHandlerClass.getName());
                }
            }

            // Find all unique classloaders, in delegation order
            final LogNode classloaderOrderLog = classpathFinderLog == null ? null
                    : classpathFinderLog.log("Finding unique classloaders in delegation order");
            final ClassLoaderOrder classLoaderOrder = new ClassLoaderOrder(reflectionUtils);
            final ClassLoader[] origClassLoaderOrder = scanSpec.overrideClassLoaders != null
                    ? scanSpec.overrideClassLoaders.toArray(new ClassLoader[0])
                    : contextClassLoaders;
            if (origClassLoaderOrder != null) {
                for (final ClassLoader classLoader : origClassLoaderOrder) {
                    classLoaderOrder.delegateTo(classLoader, /* isParent = */ false, classloaderOrderLog);
                }
            }

            // Get all parent classloaders
            final Set<ClassLoader> allParentClassLoaders = classLoaderOrder.getAllParentClassLoaders();

            // Get the classpath URLs from each ClassLoader
            final LogNode classloaderURLLog = classpathFinderLog == null ? null
                    : classpathFinderLog.log("Obtaining URLs from classloaders in delegation order");
            final List<ClassLoader> finalClassLoaderOrder = new ArrayList<>();
            for (final Entry<ClassLoader, ClassLoaderHandlerRegistryEntry> ent : classLoaderOrder
                    .getClassLoaderOrder()) {
                final ClassLoader classLoader = ent.getKey();
                final ClassLoaderHandlerRegistryEntry classLoaderHandlerRegistryEntry = ent.getValue();
                // Add classpath entries to ignoredClasspathOrder or classpathOrder
                if (!scanSpec.ignoreParentClassLoaders || !allParentClassLoaders.contains(classLoader)) {
                    // Otherwise add classpath entries to classpathOrder, and add the classloader to the
                    // final classloader ordering
                    final LogNode classloaderHandlerLog = classloaderURLLog == null ? null
                            : classloaderURLLog
                                    .log("Classloader " + classLoader.getClass().getName() + " is handled by "
                                            + classLoaderHandlerRegistryEntry.classLoaderHandlerClass.getName());
                    classLoaderHandlerRegistryEntry.findClasspathOrder(classLoader, classpathOrder, scanSpec,
                            classloaderHandlerLog);
                    finalClassLoaderOrder.add(classLoader);
                } else if (classloaderURLLog != null) {
                    classloaderURLLog.log("Ignoring parent classloader " + classLoader + ", normally handled by "
                            + classLoaderHandlerRegistryEntry.classLoaderHandlerClass.getName());
                }
                // See if a previous scan's ClassGraphClassLoader should be delegated to first
                if (classLoader instanceof ClassGraphClassLoader) {
                    delegateClassGraphClassLoader = (ClassGraphClassLoader) classLoader;
                }
            }

            // Need to record the classloader delegation order, in particular to respect parent-last delegation
            // order, since this is not the default (issue #267).
            classLoaderOrderRespectingParentDelegation = finalClassLoaderOrder.toArray(new ClassLoader[0]);
        }

        // Only scan java.class.path if parent classloaders are not ignored, classloaders are not overridden,
        // and the classpath is not overridden, unless only module scanning was enabled, and an unnamed module
        // layer was encountered -- in this case, have to forcibly scan java.class.path, since the ModuleLayer
        // API doesn't allow for the opening of unnamed modules.
        if (forceScanJavaClassPath
                || (!scanSpec.ignoreParentClassLoaders && scanSpec.overrideClassLoaders == null
                        && scanSpec.overrideClasspath == null)
                || (moduleFinder != null && moduleFinder.forceScanJavaClassPath())) {
            final String[] pathElements = JarUtils.smartPathSplit(System.getProperty("java.class.path"), scanSpec);
            if (pathElements.length > 0) {
                final LogNode sysPropLog = classpathFinderLog == null ? null
                        : classpathFinderLog.log("Getting classpath entries from java.class.path");
                for (final String pathElement : pathElements) {
                    // pathElement is not also listed in an ignored parent classloader
                    final String pathElementResolved = FastPathResolver.resolve(FileUtils.currDirPath(),
                            pathElement);
                    classpathOrder.addClasspathEntry(pathElementResolved, defaultClassLoader, scanSpec, sysPropLog);
                }
            }
        }
    }
}
