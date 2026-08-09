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

import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry.ClassLoaderHandlerRegistryEntry;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.VersionFinder;
import org.jspecify.annotations.Nullable;

/** A class to find the unique ordered classpath elements. */
public class ClasspathFinder {
    /** The classpath order. */
    private final ClasspathOrder classpathOrder;

    /** The {@link ModuleFinder}, if modules are to be scanned. */
    private final @Nullable ModuleFinder moduleFinder;

    /**
     * The default order in which ClassLoaders are called to load classes,
     * respecting parent-first/parent-last delegation order.
     */
    private ClassLoader @Nullable [] classLoaderOrderRespectingParentDelegation;

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
     * @return The {@link ModuleFinder}, or null if modules are not being scanned.
     */
    public @Nullable ModuleFinder getModuleFinder() {
        return moduleFinder;
    }

    /**
     * Get the ClassLoader order, respecting parent-first/parent-last delegation
     * order.
     *
     * @return the class loader order, or null if the classpath was overridden.
     */
    public ClassLoader @Nullable [] getClassLoaderOrderRespectingParentDelegation() {
        return classLoaderOrderRespectingParentDelegation;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Determine whether a classloader is the JDK's application classloader, which
     * loads the classes on the {@code java.class.path} classpath, and the
     * application's own (non-system) modules.
     *
     * @param classLoader the classloader
     * @return true if the classloader is the application classloader
     */
    private static boolean isApplicationClassLoader(final ClassLoader classLoader) {
        return "jdk.internal.loader.ClassLoaders$AppClassLoader".equals(classLoader.getClass().getName());
    }

    /**
     * Determine whether a classloader is the JDK's platform classloader, which
     * loads only system modules.
     *
     * @param classLoader the classloader
     * @return true if the classloader is the platform classloader
     */
    private static boolean isPlatformClassLoader(final ClassLoader classLoader) {
        return "jdk.internal.loader.ClassLoaders$PlatformClassLoader".equals(classLoader.getClass().getName());
    }

    /**
     * Neither the application classloader nor the platform classloader exposes the
     * locations it loads classes from, so neither of them can be scanned as a
     * classloader. If the user names one of them, scan instead using the mechanism
     * that can reach the classes that classloader loads: for the platform
     * classloader, that is the system jars and modules, so
     * {@code enableSystemJarsAndModules()} is applied here; for the application
     * classloader, it is the {@code java.class.path} classpath and the non-system
     * modules, which the caller enables.
     *
     * @param classLoader the classloader
     * @param scanSpec    the {@link ScanSpec}
     * @param methodName  the name of the API method the classloader was passed to,
     *                    for logging
     * @param log         the log
     */
    // #639, #795
    private static void mapSystemClassLoaderToScanningMechanism(final ClassLoader classLoader,
            final ScanSpec scanSpec, final String methodName, final @Nullable LogNode log) {
        // Neither of these classloaders can be instantiated, so if one was passed in, it
        // must have been obtained from Thread.currentThread().getContextClassLoader()
        // [.getParent()], ClassLoader.getSystemClassLoader(), or similar
        if (isApplicationClassLoader(classLoader)) {
            if (log != null) {
                log.log(methodName + " was called with an instance of " + classLoader.getClass().getName()
                        + ", which does not expose the locations it loads from, so the java.class.path "
                        + "classpath and the non-system modules will be scanned instead, since these are "
                        + "what it loads from");
            }
        } else if (isPlatformClassLoader(classLoader)) {
            if (log != null) {
                log.log(methodName + " was called with an instance of " + classLoader.getClass().getName()
                        + ", which does not expose the locations it loads from, so "
                        + "enableSystemJarsAndModules() was called automatically, since the system jars "
                        + "and modules are what it loads from");
            }
            scanSpec.enableSystemJarsAndModules = true;
        }
    }

    /**
     * A class to find the unique ordered classpath elements.
     *
     * @param scanSpec        The {@link ScanSpec}.
     * @param reflectionUtils The reflection utils instance.
     * @param log             The log.
     */
    public ClasspathFinder(final ScanSpec scanSpec, final ReflectionUtils reflectionUtils, final @Nullable LogNode log) {
        final var classpathFinderLog = log == null ? null : log.log("Finding classpath and modules");

        // Set to true if java.class.path has to be scanned even though it would not
        // otherwise be, because a named classloader can only be reached that way
        // #639, #795
        var forceScanJavaClassPath = false;

        boolean scanNonSystemModules;
        if (scanSpec.overrideClasspath != null) {
            // Don't scan non-system modules if classpath is overridden
            scanNonSystemModules = false;
        } else if (scanSpec.overrideClassLoaders != null) {
            // If classloaders are overridden, scan only what the named classloaders can
            // load -- so non-system modules are scanned only if the application
            // classloader is one of the override classloaders
            scanNonSystemModules = false;
            for (final ClassLoader classLoader : scanSpec.overrideClassLoaders) {
                mapSystemClassLoaderToScanningMechanism(classLoader, scanSpec, "overrideClassLoaders()",
                        classpathFinderLog);
                if (isApplicationClassLoader(classLoader)) {
                    forceScanJavaClassPath = true;
                    scanNonSystemModules = true;
                }
            }
        } else {
            // If classloaders are not overridden and classpath is not overridden, only scan
            // non-system modules
            // if module scanning is enabled
            scanNonSystemModules = scanSpec.scanModules;
            if (scanSpec.addedClassLoaders != null) {
                // The environment classloaders are scanned as well as the added classloaders,
                // so an added classloader can only widen what is scanned, never narrow it
                for (final ClassLoader classLoader : scanSpec.addedClassLoaders) {
                    mapSystemClassLoaderToScanningMechanism(classLoader, scanSpec, "addClassLoader()",
                            classpathFinderLog);
                    if (isApplicationClassLoader(classLoader)) {
                        forceScanJavaClassPath = true;
                    }
                }
            }
        }

        // Also look for system modules if any module was specifically accepted by name --
        // a module that was asked for by name is scanned whether or not it is a system
        // module, and only the specifically-accepted system modules are scanned, so the
        // cost of scanning the (large) system modules is not incurred for the others
        // #658
        final var scanSystemModules = scanSpec.enableSystemJarsAndModules
                || !scanSpec.moduleAcceptReject.acceptIsEmpty();

        // Only instantiate a module finder if requested
        moduleFinder = scanNonSystemModules || scanSystemModules
                ? new ModuleFinder(new CallStackReader(reflectionUtils).getClassContext(), scanSpec,
                        scanNonSystemModules, scanSystemModules, classpathFinderLog)
                : null;

        classpathOrder = new ClasspathOrder(scanSpec, reflectionUtils);

        // Only look for environment classloaders if classpath and classloaders are not
        // overridden
        final var classLoaderFinder = scanSpec.overrideClasspath == null && scanSpec.overrideClassLoaders == null
                ? new ClassLoaderFinder(scanSpec, reflectionUtils, classpathFinderLog)
                : null;
        final var contextClassLoaders = classLoaderFinder == null ? new ClassLoader[0]
                : classLoaderFinder.getContextClassLoaders();
        final var defaultClassLoader = contextClassLoaders.length > 0 ? contextClassLoaders[0] : null;
        if (scanSpec.overrideClasspath != null) {
            // Manual classpath override
            if (scanSpec.overrideClassLoaders != null && classpathFinderLog != null) {
                classpathFinderLog.log("It is not possible to override both the classpath and the ClassLoaders -- "
                        + "ignoring the ClassLoader override");
            }
            final var overrideLog = classpathFinderLog == null ? null
                    : classpathFinderLog.log("Overriding classpath with: " + scanSpec.overrideClasspath);
            // The classloader is only recorded for each classpath entry, it is not used to
            // find the entries, so just use defaultClassLoader as a placeholder here
            classpathOrder.addClasspathEntries(scanSpec.overrideClasspath, defaultClassLoader, scanSpec, overrideLog);
            if (overrideLog != null) {
                overrideLog.log("WARNING: when the classpath is overridden, there is no guarantee that the classes "
                        + "found by classpath scanning will be the same as the classes loaded by the "
                        + "context classloader");
            }
            classLoaderOrderRespectingParentDelegation = contextClassLoaders;
        }

        // If system jars and modules are enabled, add the JRE lib and ext jars to the
        // beginning of the classpath
        if (scanSpec.enableSystemJarsAndModules) {
            final var systemJarsLog = classpathFinderLog == null ? null : classpathFinderLog.log("System jars:");
            for (final String libOrExtJarPath : SystemJarFinder.getJreLibOrExtJars()) {
                // If no lib or ext jar accept/reject criteria were added, all lib and ext jars
                // are accepted;
                // if only reject criteria were added, all but the rejected jars are accepted;
                // if accept criteria
                // were added, only the specifically-accepted jars are accepted (#813)
                if (scanSpec.libOrExtJarAcceptReject.isAcceptedAndNotRejected(libOrExtJarPath)) {
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
                final var classLoaderHandlerLog = classpathFinderLog.log("ClassLoaderHandlers:");
                for (final ClassLoaderHandlerRegistryEntry classLoaderHandlerEntry : //
                ClassLoaderHandlerRegistry.CLASS_LOADER_HANDLERS) {
                    classLoaderHandlerLog.log(classLoaderHandlerEntry.getHandlerName());
                }
            }

            // Find all unique classloaders, in delegation order
            final var classloaderOrderLog = classpathFinderLog == null ? null
                    : classpathFinderLog.log("Finding unique classloaders in delegation order");
            final ClassLoaderOrder classLoaderOrder = new ClassLoaderOrder(reflectionUtils);
            final var origClassLoaderOrder = scanSpec.overrideClassLoaders != null
                    ? scanSpec.overrideClassLoaders.toArray(new ClassLoader[0])
                    : contextClassLoaders;
            if (origClassLoaderOrder != null) {
                for (final ClassLoader classLoader : origClassLoaderOrder) {
                    classLoaderOrder.delegateTo(classLoader, /* isParent = */ false, classloaderOrderLog);
                }
            }

            // Get all parent classloaders
            final var allParentClassLoaders = classLoaderOrder.getAllParentClassLoaders();

            // Get the classpath URLs from each ClassLoader
            final var classloaderURLLog = classpathFinderLog == null ? null
                    : classpathFinderLog.log("Obtaining URLs from classloaders in delegation order");
            final List<ClassLoader> finalClassLoaderOrder = new ArrayList<>();
            for (final Entry<ClassLoader, List<ClassLoaderHandlerRegistryEntry>> ent : classLoaderOrder
                    .getClassLoaderOrder()) {
                final var classLoader = ent.getKey();
                for (final ClassLoaderHandlerRegistryEntry classLoaderHandlerRegistryEntry : ent.getValue()) {
                    // Add classpath entries to ignoredClasspathOrder or classpathOrder
                    if (!scanSpec.ignoreParentClassLoaders || !allParentClassLoaders.contains(classLoader)) {
                        // Otherwise add classpath entries to classpathOrder, and add the classloader to
                        // the
                        // final classloader ordering
                        final var classloaderHandlerLog = classloaderURLLog == null ? null
                                : classloaderURLLog.log("Classloader " + classLoader.getClass().getName()
                                        + " is handled by " + classLoaderHandlerRegistryEntry.getHandlerName());
                        // Record the package roots that this ClassLoaderHandler's classpath elements
                        // can have,
                        // so that only the package roots that are applicable to each classpath element
                        // are
                        // looked for and stripped when it is scanned (#929)
                        classpathOrder.setPackageRootPrefixes(classLoaderHandlerRegistryEntry.getPackageRootPrefixes());
                        try {
                            classLoaderHandlerRegistryEntry.findClasspathOrder(classLoader, classpathOrder, scanSpec,
                                    classloaderHandlerLog);
                        } finally {
                            classpathOrder.setPackageRootPrefixes(null);
                        }
                        finalClassLoaderOrder.add(classLoader);
                    } else if (classloaderURLLog != null) {
                        classloaderURLLog.log("Ignoring parent classloader " + classLoader + ", normally handled by "
                                + classLoaderHandlerRegistryEntry.getHandlerName());
                    }
                }
            }

            // Need to record the classloader delegation order, in particular to respect
            // parent-last delegation
            // order, since this is not the default (issue #267).
            classLoaderOrderRespectingParentDelegation = finalClassLoaderOrder.toArray(new ClassLoader[0]);
        }

        // Only scan java.class.path if parent classloaders are not ignored,
        // classloaders are not overridden,
        // and the classpath is not overridden, unless only module scanning was enabled,
        // and an unnamed module
        // layer was encountered -- in this case, have to forcibly scan java.class.path,
        // since the ModuleLayer
        // API doesn't allow for the opening of unnamed modules.
        if (forceScanJavaClassPath
                || (!scanSpec.ignoreParentClassLoaders && scanSpec.overrideClassLoaders == null
                        && scanSpec.overrideClasspath == null)
                || (moduleFinder != null && moduleFinder.forceScanJavaClassPath())) {
            final var pathElements = JarUtils.smartPathSplit(VersionFinder.getProperty("java.class.path"), scanSpec);
            if (pathElements.length > 0) {
                final var sysPropLog = classpathFinderLog == null ? null
                        : classpathFinderLog.log("Getting classpath entries from java.class.path");
                for (final String pathElement : pathElements) {
                    // pathElement is not also listed in an ignored parent classloader
                    final var pathElementResolved = FastPathResolver.resolve(FileUtils.currDirPath(), pathElement);
                    classpathOrder.addClasspathEntry(pathElementResolved, defaultClassLoader, scanSpec, sysPropLog);
                }
            }
        }
    }
}
