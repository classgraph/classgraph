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
 * Copyright (c) 2026 Luke Hutchison
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
package io.github.classgraph.classpath.internal;

import java.util.List;
import java.util.Map.Entry;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.path.PathList;
import io.github.classgraph.base.internal.utils.VersionFinder;
import io.github.classgraph.classpath.internal.ScanSourceSpec.ClasspathSource;
import io.github.classgraph.classpath.internal.ScanSourceSpec.NamedClassLoaders;
import io.github.classgraph.classpath.internal.ScanSourceSpec.NamedClasspathEntries;
import io.github.classgraph.classpath.internal.classloaderhandler.ClassLoaderHandlerRegistry.ClassLoaderHandlerRegistryEntry;
import io.github.classgraph.classpath.internal.classloaderhandler.ClassLoaderHandlerRegistry;
import org.jspecify.annotations.Nullable;

/** A class to find the unique ordered classpath elements. */
public class ClassLoaderProbe {
    /** The classpath order. */
    private final ClasspathOrderBuilder classpathOrder;

    /** The {@link ModuleFinder}, if any module layer is being searched. */
    private final @Nullable ModuleFinder moduleFinder;

    /**
     * The first of the classloaders found in the environment, which is the classloader that is recorded for a
     * classpath entry that no classloader declared, or null if no classloader was found.
     */
    private final @Nullable ClassLoader defaultClassLoader;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the classpath order.
     *
     * @return The order of raw classpath elements obtained from ClassLoaders.
     */
    public ClasspathOrderBuilder getClasspathOrder() {
        return classpathOrder;
    }

    /**
     * Get the {@link ModuleFinder}.
     *
     * @return The {@link ModuleFinder}, or null if no module layer is being searched.
     */
    public @Nullable ModuleFinder getModuleFinder() {
        return moduleFinder;
    }

    /**
     * Get the classloader to record for anything that no classloader declared, which is the first of the
     * classloaders found in the environment: the context classloader of the calling thread, if it has one.
     *
     * @return the classloader, or null if no classloader was found in the environment.
     */
    public @Nullable ClassLoader getDefaultClassLoader() {
        return defaultClassLoader;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the classpath elements and modules of the sources that the caller enabled, reading the call stack of the
     * calling thread in order to find the classloaders and module layers that the caller can see.
     *
     * <p>
     * Call this only from the thread that asked for the classpath. If the caller has already read its own call
     * stack -- as ClassGraph has, since it checks the stack for a class loading lock before it starts a scan (#933)
     * -- then pass that call stack to
     * {@link #ClassLoaderProbe(CallStackInfo, ClasspathSpec, ScanSourceSpec, LogNode)} instead, rather than walking
     * the stack a second time.
     *
     * @param classpathSpec
     *            The {@link ClasspathSpec}.
     * @param scanSourceSpec
     *            The places to look for classpath elements and modules.
     * @param log
     *            The log.
     */
    public ClassLoaderProbe(final ClasspathSpec classpathSpec, final ScanSourceSpec scanSourceSpec,
            final @Nullable LogNode log) {
        this(CallStackInfo.read(), classpathSpec, scanSourceSpec, log);
    }

    /**
     * Find the classpath elements and modules of the sources that the caller enabled, using a call stack that has
     * already been read.
     *
     * @param callStackInfo
     *            The call stack of the thread that asked for the classpath, which names the classloaders and module
     *            layers that the caller can see.
     * @param classpathSpec
     *            The {@link ClasspathSpec}.
     * @param scanSourceSpec
     *            The places to look for classpath elements and modules.
     * @param log
     *            The log.
     */
    public ClassLoaderProbe(final CallStackInfo callStackInfo, final ClasspathSpec classpathSpec,
            final ScanSourceSpec scanSourceSpec, final @Nullable LogNode log) {
        final var classLoaderProbeLog = log == null ? null : log.log("Finding classpath and modules");

        // The modules are searched before the classpath, whatever order the sources were enabled in, because that is
        // the order in which the JVM resolves a class: a builtin classloader looks the class's package up among the
        // modules before it delegates to its parent or falls back to its classpath
        moduleFinder = scanSourceSpec.searchDetectedModuleLayers || scanSourceSpec.namedModuleLayers != null
                ? new ModuleFinder(callStackInfo, classpathSpec, scanSourceSpec, classLoaderProbeLog)
                : null;

        classpathOrder = new ClasspathOrderBuilder(classpathSpec);

        // The classloaders in the environment are found whether or not they are one of the sources to search, since
        // a classpath entry that the caller named directly still has to record a classloader, and since this is the
        // classloader that the scan falls back to when it has to load a class
        final var contextClassLoaders = new ClassLoaderFinder(callStackInfo, classLoaderProbeLog)
                .getContextClassLoaders();
        defaultClassLoader = contextClassLoaders.length == 0 ? null : contextClassLoaders[0];

        // Wrap the ClassLoaderHandlers the user registered. These are offered each classloader before the built-in
        // handlers are, so that a user handler can override a built-in one.
        final List<ClassLoaderHandlerRegistryEntry> userClassLoaderHandlers = classpathSpec.classLoaderHandlers
                .stream().map(ClassLoaderHandlerRegistryEntry::new).toList();
        if (classLoaderProbeLog != null) {
            final var classLoaderHandlerLog = classLoaderProbeLog.log("ClassLoaderHandlers:");
            for (final ClassLoaderHandlerRegistryEntry classLoaderHandlerEntry : userClassLoaderHandlers) {
                classLoaderHandlerLog.log(classLoaderHandlerEntry.getHandlerName() + " (registered by the caller)");
            }
            for (final ClassLoaderHandlerRegistryEntry classLoaderHandlerEntry : //
            ClassLoaderHandlerRegistry.CLASS_LOADER_HANDLERS) {
                classLoaderHandlerLog.log(classLoaderHandlerEntry.getHandlerName());
            }
        }

        // Every classloader source shares one classloader order, so that a classloader that more than one source
        // reaches is only searched once, at the first position it is reached at
        final var classLoaderOrder = new ClassLoaderOrderBuilder(userClassLoaderHandlers);
        var numClassLoadersSearched = 0;

        // Search the classpath sources in the order the caller enabled them in
        for (final ClasspathSource classpathSource : scanSourceSpec.classpathSources) {
            if (classpathSource instanceof final NamedClasspathEntries namedClasspathEntries) {
                addNamedClasspathEntries(namedClasspathEntries.classpathEntries(), classLoaderProbeLog);
            } else {
                final var classLoaders = classpathSource instanceof final NamedClassLoaders namedClassLoaders
                        ? namedClassLoaders.classLoaders()
                        : List.of(contextClassLoaders);
                numClassLoadersSearched = addClassLoaderClasspathEntries(classLoaders, classpathSpec,
                        classLoaderOrder, numClassLoadersSearched, classLoaderProbeLog);
            }
        }

        // The application classloader's own classpath entries are added by its ClassLoaderHandler, at the position
        // the application classloader takes in the delegation order. The only case that handler cannot cover is an
        // unnamed module layer: the ModuleLayer API does not allow an unnamed module to be opened, so the classes
        // in it can only be reached through java.class.path, whether or not the application classloader is being
        // scanned. Anything added here that the handler already added is dropped as a duplicate.
        if (moduleFinder != null && moduleFinder.forceScanJavaClassPath()) {
            addJavaClassPathEntries(classpathSpec, classLoaderProbeLog);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add the classpath entries that the caller named directly.
     *
     * @param classpathEntries
     *            the classpath entries
     * @param log
     *            the log node, or null to skip logging
     */
    private void addNamedClasspathEntries(final List<Object> classpathEntries, final @Nullable LogNode log) {
        final var subLog = log == null ? null
                : log.log("Adding the classpath entries given by the caller: " + classpathEntries);
        // No classloader is recorded for an entry the caller named: the caller named a location to read, not a
        // classloader to read it through, and a scan that was never asked to look at a classloader must not pin one
        classpathOrder.addClasspathEntries(classpathEntries, /* classLoader = */ null, subLog);
        if (subLog != null) {
            subLog.log("WARNING: when the classpath entries are given directly, there is no guarantee that the "
                    + "classes found by classpath scanning will be the same as the classes loaded by the "
                    + "context classloader");
        }
    }

    /**
     * Find the unique classloaders that the given classloaders delegate to, in delegation order, then add the
     * classpath entries that each of them loads from, using the {@code ClassLoaderHandler} registered for the
     * classloader.
     *
     * @param classLoaders
     *            the classloaders to search
     * @param classpathSpec
     *            the {@link ClasspathSpec}
     * @param classLoaderOrder
     *            the classloader order, which is shared by all the classloader sources
     * @param numClassLoadersSearched
     *            the number of classloaders in {@code classLoaderOrder} that an earlier source already searched
     * @param log
     *            the log node, or null to skip logging
     * @return the new number of classloaders in {@code classLoaderOrder} that have been searched
     */
    private int addClassLoaderClasspathEntries(final List<ClassLoader> classLoaders,
            final ClasspathSpec classpathSpec, final ClassLoaderOrderBuilder classLoaderOrder,
            final int numClassLoadersSearched, final @Nullable LogNode log) {
        // Find the unique classloaders these classloaders delegate to, in delegation order. This appends to the
        // shared classloader order, so a classloader an earlier source already reached is not listed again.
        final var classloaderOrderLog = log == null ? null
                : log.log("Finding unique classloaders in delegation order");
        for (final ClassLoader classLoader : classLoaders) {
            classLoaderOrder.delegateTo(classLoader, /* isParent = */ false, classloaderOrderLog);
        }

        // Get all parent classloaders
        final var allParentClassLoaders = classLoaderOrder.getAllParentClassLoaders();

        // Get the classpath entries from each of the classloaders this source added to the order
        final var classLoaderOrderEntries = classLoaderOrder.getClassLoaderOrder();
        final var classloaderURLLog = log == null ? null
                : log.log("Obtaining URLs from classloaders in delegation order");
        for (final Entry<ClassLoader, List<ClassLoaderHandlerRegistryEntry>> ent : classLoaderOrderEntries
                .subList(numClassLoadersSearched, classLoaderOrderEntries.size())) {
            final var classLoader = ent.getKey();
            for (final ClassLoaderHandlerRegistryEntry classLoaderHandlerRegistryEntry : ent.getValue()) {
                if (classpathSpec.ignoreParentClassLoaders && allParentClassLoaders.contains(classLoader)) {
                    if (classloaderURLLog != null) {
                        classloaderURLLog.log("Ignoring parent classloader " + classLoader
                                + ", normally handled by " + classLoaderHandlerRegistryEntry.getHandlerName());
                    }
                } else {
                    // Add the classpath entries to classpathOrder
                    final var classloaderHandlerLog = classloaderURLLog == null ? null
                            : classloaderURLLog.log("Classloader " + classLoader.getClass().getName()
                                    + " is handled by " + classLoaderHandlerRegistryEntry.getHandlerName());
                    // Record the package roots that this ClassLoaderHandler's classpath elements can have, so that
                    // only the package roots that are applicable to each classpath element are looked for and
                    // stripped when it is scanned (#929), and likewise the lib dirs that this ClassLoaderHandler
                    // loads jarfiles from without listing them as classpath elements
                    classpathOrder.setPackageRootPrefixes(classLoaderHandlerRegistryEntry.getPackageRootPrefixes());
                    classpathOrder.setLibDirPrefixes(classLoaderHandlerRegistryEntry.getLibDirPrefixes());
                    try {
                        classLoaderHandlerRegistryEntry.findClasspathOrder(classLoader, classpathOrder,
                                classloaderHandlerLog);
                    } finally {
                        classpathOrder.setPackageRootPrefixes(null);
                        classpathOrder.setLibDirPrefixes(null);
                    }
                }
            }
        }
        return classLoaderOrderEntries.size();
    }

    /**
     * Add the classpath entries listed in the {@code java.class.path} system property.
     *
     * @param classpathSpec
     *            the {@link ClasspathSpec}
     * @param log
     *            the log node, or null to skip logging
     */
    private void addJavaClassPathEntries(final ClasspathSpec classpathSpec, final @Nullable LogNode log) {
        final var pathElements = PathList.split(VersionFinder.getProperty("java.class.path"),
                classpathSpec.allowedURLSchemes);
        if (pathElements.length > 0) {
            final var sysPropLog = log == null ? null : log.log("Getting classpath entries from java.class.path");
            for (final String pathElement : pathElements) {
                final var pathElementResolved = FastPathResolver.resolveFilePath(FileUtils.currDirPath(),
                        pathElement);
                classpathOrder.addClasspathEntry(pathElementResolved, defaultClassLoader, sysPropLog);
            }
        }
    }
}
