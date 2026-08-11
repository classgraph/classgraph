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
import nonapi.io.github.classgraph.classpathspec.ClassLoaderAndModuleLayerSpec;
import nonapi.io.github.classgraph.classpathspec.ClassPathSpec;
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
     * The default order in which ClassLoaders are called to load classes, respecting parent-first/parent-last
     * delegation order.
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
     * Get the ClassLoader order, respecting parent-first/parent-last delegation order.
     *
     * @return the class loader order, or null if the classpath was overridden.
     */
    public ClassLoader @Nullable [] getClassLoaderOrderRespectingParentDelegation() {
        return classLoaderOrderRespectingParentDelegation;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Determine whether a classloader is the JDK's application classloader, which loads the classes on the
     * {@code java.class.path} classpath, and the application's own (non-system) modules.
     *
     * @param classLoader
     *            the classloader
     * @return true if the classloader is the application classloader
     */
    private static boolean isApplicationClassLoader(final ClassLoader classLoader) {
        return "jdk.internal.loader.ClassLoaders$AppClassLoader".equals(classLoader.getClass().getName());
    }

    /**
     * Determine whether a classloader is the JDK's platform classloader, which loads only system modules.
     *
     * @param classLoader
     *            the classloader
     * @return true if the classloader is the platform classloader
     */
    private static boolean isPlatformClassLoader(final ClassLoader classLoader) {
        return "jdk.internal.loader.ClassLoaders$PlatformClassLoader".equals(classLoader.getClass().getName());
    }

    /**
     * Neither the application classloader nor the platform classloader exposes the locations it loads classes from,
     * so neither of them can be scanned as a classloader. If the user names one of them, scan instead using the
     * mechanism that can reach the classes that classloader loads: for the platform classloader, that is the system
     * jars and modules, so {@code enableSystemJarsAndModules()} is applied here; for the application classloader,
     * it is the {@code java.class.path} classpath and the non-system modules, which the caller enables.
     *
     * @param classLoader
     *            the classloader
     * @param classPathSpec
     *            the {@link ClassPathSpec}
     * @param methodName
     *            the name of the API method the classloader was passed to, for logging
     * @param log
     *            the log node, or null to skip logging
     */
    // #639, #795
    private static void mapSystemClassLoaderToScanningMechanism(final ClassLoader classLoader,
            final ClassPathSpec classPathSpec, final String methodName, final @Nullable LogNode log) {
        // Neither of these classloaders can be instantiated, so if one was passed in, it must have been obtained
        // from Thread.currentThread().getContextClassLoader() [.getParent()], ClassLoader.getSystemClassLoader(),
        // or similar
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
            classPathSpec.enableSystemJarsAndModules = true;
        }
    }

    /**
     * Which of the mechanisms that are not enabled by the scan options have to be used anyway, to reach everything
     * the named classloaders can load.
     *
     * @param scanNonSystemModules
     *            whether the non-system modules should be scanned
     * @param forceScanJavaClassPath
     *            whether the {@code java.class.path} classpath has to be scanned even though it would not otherwise
     *            be, because a named classloader can only be reached that way
     */
    // #639, #795
    private record ScanTargets(boolean scanNonSystemModules, boolean forceScanJavaClassPath) {
    }

    /**
     * Work out what has to be scanned to reach everything that the classloaders named by the scan options can load.
     *
     * @param classPathSpec
     *            the {@link ClassPathSpec}
     * @param classLoaderAndModuleLayerSpec
     *            the classloaders and module layers the caller asked to be scanned
     * @param log
     *            the log node, or null to skip logging
     * @return what has to be scanned
     */
    private static ScanTargets findScanTargets(final ClassPathSpec classPathSpec,
            final ClassLoaderAndModuleLayerSpec classLoaderAndModuleLayerSpec, final @Nullable LogNode log) {
        if (classPathSpec.overrideClasspath != null) {
            // Don't scan non-system modules if classpath is overridden
            return new ScanTargets(/* scanNonSystemModules = */ false, /* forceScanJavaClassPath = */ false);
        }
        if (classLoaderAndModuleLayerSpec.overrideClassLoaders != null) {
            // If classloaders are overridden, scan only what the named classloaders can load -- so non-system
            // modules are scanned only if the application classloader is one of the override classloaders
            var isApplicationClassLoaderNamed = false;
            for (final ClassLoader classLoader : classLoaderAndModuleLayerSpec.overrideClassLoaders) {
                mapSystemClassLoaderToScanningMechanism(classLoader, classPathSpec, "overrideClassLoaders()", log);
                if (isApplicationClassLoader(classLoader)) {
                    isApplicationClassLoaderNamed = true;
                }
            }
            return new ScanTargets(/* scanNonSystemModules = */ isApplicationClassLoaderNamed,
                    /* forceScanJavaClassPath = */ isApplicationClassLoaderNamed);
        }
        // If classloaders are not overridden and classpath is not overridden, only scan non-system modules if
        // module scanning is enabled
        var forceScanJavaClassPath = false;
        if (classLoaderAndModuleLayerSpec.addedClassLoaders != null) {
            // The environment classloaders are scanned as well as the added classloaders, so an added classloader
            // can only widen what is scanned, never narrow it
            for (final ClassLoader classLoader : classLoaderAndModuleLayerSpec.addedClassLoaders) {
                mapSystemClassLoaderToScanningMechanism(classLoader, classPathSpec, "addClassLoader()", log);
                if (isApplicationClassLoader(classLoader)) {
                    forceScanJavaClassPath = true;
                }
            }
        }
        return new ScanTargets(classPathSpec.scanModules, forceScanJavaClassPath);
    }

    /**
     * A class to find the unique ordered classpath elements.
     *
     * @param classPathSpec
     *            The {@link ClassPathSpec}.
     * @param classLoaderAndModuleLayerSpec
     *            The classloaders and module layers the caller asked to be scanned.
     * @param reflectionUtils
     *            The reflection utils instance.
     * @param log
     *            The log.
     */
    public ClasspathFinder(final ClassPathSpec classPathSpec,
            final ClassLoaderAndModuleLayerSpec classLoaderAndModuleLayerSpec,
            final ReflectionUtils reflectionUtils, final @Nullable LogNode log) {
        final var classpathFinderLog = log == null ? null : log.log("Finding classpath and modules");

        final var scanTargets = findScanTargets(classPathSpec, classLoaderAndModuleLayerSpec, classpathFinderLog);

        // Also look for system modules if any module was specifically accepted by name -- a module that was asked
        // for by name is scanned whether or not it is a system module, and only the specifically-accepted system
        // modules are scanned, so the cost of scanning the (large) system modules is not incurred for the others
        // #658
        final var scanSystemModules = classPathSpec.enableSystemJarsAndModules
                || !classPathSpec.moduleAcceptReject.acceptIsEmpty();

        // Only instantiate a module finder if requested
        moduleFinder = scanTargets.scanNonSystemModules() || scanSystemModules
                ? new ModuleFinder(CallStackReader.getClassContext(), classPathSpec, classLoaderAndModuleLayerSpec,
                        scanTargets.scanNonSystemModules(), scanSystemModules, classpathFinderLog)
                : null;

        classpathOrder = new ClasspathOrder(classPathSpec, reflectionUtils);

        // Only look for environment classloaders if classpath and classloaders are not overridden
        final var classLoaderFinder = classPathSpec.overrideClasspath == null
                && classLoaderAndModuleLayerSpec.overrideClassLoaders == null
                        ? new ClassLoaderFinder(classLoaderAndModuleLayerSpec, reflectionUtils, classpathFinderLog)
                        : null;
        final var contextClassLoaders = classLoaderFinder == null ? new ClassLoader[0]
                : classLoaderFinder.getContextClassLoaders();
        final var defaultClassLoader = contextClassLoaders.length > 0 ? contextClassLoaders[0] : null;

        final var overrideClasspath = classPathSpec.overrideClasspath;
        if (overrideClasspath != null) {
            addOverriddenClasspathEntries(overrideClasspath, classPathSpec, classLoaderAndModuleLayerSpec,
                    defaultClassLoader, classpathFinderLog);
            classLoaderOrderRespectingParentDelegation = contextClassLoaders;
        }

        if (overrideClasspath == null) {
            // Need to record the classloader delegation order, in particular to respect parent-last delegation
            // order, since this is not the default (issue #267).
            classLoaderOrderRespectingParentDelegation = addClassLoaderClasspathEntries(classPathSpec,
                    classLoaderAndModuleLayerSpec, reflectionUtils, contextClassLoaders, classpathFinderLog);
        }

        // Only scan java.class.path if parent classloaders are not ignored, classloaders are not overridden, and
        // the classpath is not overridden, unless only module scanning was enabled, and an unnamed module layer was
        // encountered -- in this case, have to forcibly scan java.class.path, since the ModuleLayer API doesn't
        // allow for the opening of unnamed modules.
        if (scanTargets.forceScanJavaClassPath()
                || (!classPathSpec.ignoreParentClassLoaders
                        && classLoaderAndModuleLayerSpec.overrideClassLoaders == null && overrideClasspath == null)
                || (moduleFinder != null && moduleFinder.forceScanJavaClassPath())) {
            addJavaClassPathEntries(classPathSpec, defaultClassLoader, classpathFinderLog);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add the classpath entries that the classpath was overridden with.
     *
     * @param overrideClasspath
     *            the classpath entries the classpath was overridden with
     * @param classPathSpec
     *            the {@link ClassPathSpec}
     * @param classLoaderAndModuleLayerSpec
     *            the classloaders and module layers the caller asked to be scanned
     * @param defaultClassLoader
     *            the classloader to record for each classpath entry, or null if there is none
     * @param log
     *            the log node, or null to skip logging
     */
    private void addOverriddenClasspathEntries(final List<Object> overrideClasspath,
            final ClassPathSpec classPathSpec, final ClassLoaderAndModuleLayerSpec classLoaderAndModuleLayerSpec,
            final @Nullable ClassLoader defaultClassLoader, final @Nullable LogNode log) {
        if (classLoaderAndModuleLayerSpec.overrideClassLoaders != null && log != null) {
            log.log("It is not possible to override both the classpath and the ClassLoaders -- "
                    + "ignoring the ClassLoader override");
        }
        final var overrideLog = log == null ? null : log.log("Overriding classpath with: " + overrideClasspath);
        // The classloader is only recorded for each classpath entry, it is not used to find the entries, so just
        // use defaultClassLoader as a placeholder here
        classpathOrder.addClasspathEntries(overrideClasspath, defaultClassLoader, classPathSpec, overrideLog);
        if (overrideLog != null) {
            overrideLog.log("WARNING: when the classpath is overridden, there is no guarantee that the classes "
                    + "found by classpath scanning will be the same as the classes loaded by the "
                    + "context classloader");
        }
    }

    /**
     * Find the unique classloaders in delegation order, then add the classpath entries that each of them loads
     * from, using the {@code ClassLoaderHandler} registered for the classloader.
     *
     * @param classPathSpec
     *            the {@link ClassPathSpec}
     * @param classLoaderAndModuleLayerSpec
     *            the classloaders and module layers the caller asked to be scanned
     * @param reflectionUtils
     *            the reflection utils instance
     * @param contextClassLoaders
     *            the environment classloaders, which are used unless the classloaders were overridden
     * @param log
     *            the log node, or null to skip logging
     * @return the classloaders whose classpath entries were added, in delegation order
     */
    private ClassLoader[] addClassLoaderClasspathEntries(final ClassPathSpec classPathSpec,
            final ClassLoaderAndModuleLayerSpec classLoaderAndModuleLayerSpec,
            final ReflectionUtils reflectionUtils, final ClassLoader[] contextClassLoaders,
            final @Nullable LogNode log) {
        // List ClassLoaderHandlers
        if (log != null) {
            final var classLoaderHandlerLog = log.log("ClassLoaderHandlers:");
            for (final ClassLoaderHandlerRegistryEntry classLoaderHandlerEntry : //
            ClassLoaderHandlerRegistry.CLASS_LOADER_HANDLERS) {
                classLoaderHandlerLog.log(classLoaderHandlerEntry.getHandlerName());
            }
        }

        // Find all unique classloaders, in delegation order
        final var classloaderOrderLog = log == null ? null
                : log.log("Finding unique classloaders in delegation order");
        final ClassLoaderOrder classLoaderOrder = new ClassLoaderOrder(reflectionUtils);
        final var overrideClassLoaders = classLoaderAndModuleLayerSpec.overrideClassLoaders;
        final var origClassLoaderOrder = overrideClassLoaders != null
                ? overrideClassLoaders.toArray(ClassLoader[]::new)
                : contextClassLoaders;
        for (final ClassLoader classLoader : origClassLoaderOrder) {
            classLoaderOrder.delegateTo(classLoader, /* isParent = */ false, classloaderOrderLog);
        }

        // Get all parent classloaders
        final var allParentClassLoaders = classLoaderOrder.getAllParentClassLoaders();

        // Get the classpath URLs from each ClassLoader
        final var classloaderURLLog = log == null ? null
                : log.log("Obtaining URLs from classloaders in delegation order");
        final List<ClassLoader> finalClassLoaderOrder = new ArrayList<>();
        for (final Entry<ClassLoader, List<ClassLoaderHandlerRegistryEntry>> ent : classLoaderOrder
                .getClassLoaderOrder()) {
            final var classLoader = ent.getKey();
            for (final ClassLoaderHandlerRegistryEntry classLoaderHandlerRegistryEntry : ent.getValue()) {
                if (classPathSpec.ignoreParentClassLoaders && allParentClassLoaders.contains(classLoader)) {
                    if (classloaderURLLog != null) {
                        classloaderURLLog.log("Ignoring parent classloader " + classLoader
                                + ", normally handled by " + classLoaderHandlerRegistryEntry.getHandlerName());
                    }
                } else {
                    // Add the classpath entries to classpathOrder, and add the classloader to the final classloader
                    // ordering
                    final var classloaderHandlerLog = classloaderURLLog == null ? null
                            : classloaderURLLog.log("Classloader " + classLoader.getClass().getName()
                                    + " is handled by " + classLoaderHandlerRegistryEntry.getHandlerName());
                    // Record the package roots that this ClassLoaderHandler's classpath elements can have, so that
                    // only the package roots that are applicable to each classpath element are looked for and
                    // stripped when it is scanned (#929)
                    classpathOrder.setPackageRootPrefixes(classLoaderHandlerRegistryEntry.getPackageRootPrefixes());
                    try {
                        classLoaderHandlerRegistryEntry.findClasspathOrder(classLoader, classpathOrder,
                                classPathSpec, classloaderHandlerLog);
                    } finally {
                        classpathOrder.setPackageRootPrefixes(null);
                    }
                    finalClassLoaderOrder.add(classLoader);
                }
            }
        }
        return finalClassLoaderOrder.toArray(ClassLoader[]::new);
    }

    /**
     * Add the classpath entries listed in the {@code java.class.path} system property.
     *
     * @param classPathSpec
     *            the {@link ClassPathSpec}
     * @param defaultClassLoader
     *            the classloader to record for each classpath entry, or null if there is none
     * @param log
     *            the log node, or null to skip logging
     */
    private void addJavaClassPathEntries(final ClassPathSpec classPathSpec,
            final @Nullable ClassLoader defaultClassLoader, final @Nullable LogNode log) {
        final var pathElements = JarUtils.smartPathSplit(VersionFinder.getProperty("java.class.path"),
                classPathSpec);
        if (pathElements.length > 0) {
            final var sysPropLog = log == null ? null : log.log("Getting classpath entries from java.class.path");
            for (final String pathElement : pathElements) {
                // pathElement is not also listed in an ignored parent classloader
                final var pathElementResolved = FastPathResolver.resolve(FileUtils.currDirPath(), pathElement);
                classpathOrder.addClasspathEntry(pathElementResolved, defaultClassLoader, classPathSpec,
                        sysPropLog);
            }
        }
    }
}
