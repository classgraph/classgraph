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
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.PathList;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.internal.ScanSourceSpec.ClasspathSource;
import io.github.classgraph.classpath.internal.ScanSourceSpec.ClasspathString;
import io.github.classgraph.classpath.internal.ScanSourceSpec.DetectedClassLoaders;
import io.github.classgraph.classpath.internal.ScanSourceSpec.NamedClassLoaders;
import io.github.classgraph.classpath.internal.ScanSourceSpec.NamedClasspathEntries;
import io.github.classgraph.classpath.internal.classloaderhandler.ClassLoaderHandlerRegistry;

/**
 * Discovers classpath entries and modules from the configured {@link ScanSourceSpec} and {@link ClasspathSpec}.
 */
public class ClassLoaderProbe {
    /** The accumulated classpath entries in resolution order. */
    private final ClasspathOrderBuilder classpathOrder;

    /** The {@link ModuleFinder}, or null if no module layers are searched. */
    private final @Nullable ModuleFinder moduleFinder;

    /** The primary environment {@link ClassLoader}, or null if none was found. */
    private final @Nullable ClassLoader defaultClassLoader;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the {@link ClasspathOrderBuilder} containing the discovered raw classpath entries.
     *
     * @return the {@link ClasspathOrderBuilder}.
     */
    public ClasspathOrderBuilder getClasspathOrder() {
        return classpathOrder;
    }

    /**
     * Returns the {@link ModuleFinder}, or null if no module layers were searched.
     *
     * @return the {@link ModuleFinder}, or null.
     */
    public @Nullable ModuleFinder getModuleFinder() {
        return moduleFinder;
    }

    /**
     * Returns the primary environment {@link ClassLoader} (such as the thread context classloader), or null if none
     * was found.
     *
     * @return the default {@link ClassLoader}, or null.
     */
    public @Nullable ClassLoader getDefaultClassLoader() {
        return defaultClassLoader;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Discovers classpath entries and modules for the configured sources, reading the call stack of the current
     * thread.
     *
     * @param classpathSpec
     *            the {@link ClasspathSpec}.
     * @param scanSourceSpec
     *            the configured classpath and module layer sources.
     * @param log
     *            the log node, or null to skip logging.
     */
    public ClassLoaderProbe(final ClasspathSpec classpathSpec, final ScanSourceSpec scanSourceSpec,
            final @Nullable LogNode log) {
        this(CallStackInfo.read(), classpathSpec, scanSourceSpec, log);
    }

    /**
     * Discovers classpath entries and modules for the configured sources using a pre-captured
     * {@link CallStackInfo}.
     *
     * @param callStackInfo
     *            the call stack info of the calling thread.
     * @param classpathSpec
     *            the {@link ClasspathSpec}.
     * @param scanSourceSpec
     *            the configured classpath and module layer sources.
     * @param log
     *            the log node, or null to skip logging.
     */
    public ClassLoaderProbe(final CallStackInfo callStackInfo, final ClasspathSpec classpathSpec,
            final ScanSourceSpec scanSourceSpec, final @Nullable LogNode log) {
        final var classLoaderProbeLog = log == null ? null : log.log("Finding classpath and modules");

        // The modules are searched before the classpath, whatever order the sources were enabled in, because that
        // is the order in which the JVM resolves a class: a builtin classloader looks the class's package up among
        // the modules before it delegates to its parent or falls back to its classpath
        moduleFinder = scanSourceSpec.searchesDetectedModuleLayers(classpathSpec)
                || scanSourceSpec.getNamedModuleLayers() != null
                        ? new ModuleFinder(callStackInfo, classpathSpec, scanSourceSpec, classLoaderProbeLog)
                        : null;

        classpathOrder = new ClasspathOrderBuilder(classpathSpec);

        // The classloaders in the environment are found whether or not they are one of the sources to search, since
        // the first of them is the classloader that the scan falls back to when it has to load a class, and the one
        // that the entries of java.class.path are recorded against
        final var environmentClassLoaders = new ClassLoaderFinder(callStackInfo, classLoaderProbeLog)
                .getClassLoaders();
        defaultClassLoader = environmentClassLoaders.isEmpty() ? null : environmentClassLoaders.get(0);

        // The ClassLoaderHandlers the user registered. These are offered each classloader before the built-in
        // handlers are, so that a user handler can override a built-in one.
        final var userClassLoaderHandlers = classpathSpec.getClassLoaderHandlers();
        if (classLoaderProbeLog != null) {
            final var classLoaderHandlerLog = classLoaderProbeLog.log("ClassLoaderHandlers:");
            for (final ClassLoaderHandler classLoaderHandler : userClassLoaderHandlers) {
                classLoaderHandlerLog.log(classLoaderHandler.getClass().getName() + " (registered by the caller)");
            }
            for (final ClassLoaderHandler classLoaderHandler : ClassLoaderHandlerRegistry.CLASS_LOADER_HANDLERS) {
                classLoaderHandlerLog.log(classLoaderHandler.getClass().getName());
            }
        }

        // Every classloader source shares one classloader order, so that a classloader that more than one source
        // reaches is only searched once, at the first position it is reached at
        final var classLoaderOrder = new ClassLoaderOrderBuilder(userClassLoaderHandlers);

        // Find the classloader order of every classloader source before the classpath entries of any of them are
        // added, so that a classloader one source names and a later source reaches as a parent is known to be a
        // parent when ignoreParentClassLoaders() is applied, however the classloaders were split between calls.
        // classLoaderOrderEnd[i] is the number of classloaders in the order once source i has been searched. A
        // source that is not a classloader source adds nothing to the order.
        final var classpathSources = scanSourceSpec.getClasspathSources();
        final var classLoaderOrderEnd = new int[classpathSources.size()];
        for (var i = 0; i < classpathSources.size(); i++) {
            final ClasspathSource classpathSource = classpathSources.get(i);
            if (classpathSource instanceof final NamedClassLoaders namedClassLoaders) {
                findClassLoaderOrder(namedClassLoaders.classLoaders(), classLoaderOrder, classLoaderProbeLog);
            } else if (classpathSource instanceof DetectedClassLoaders) {
                findClassLoaderOrder(environmentClassLoaders, classLoaderOrder, classLoaderProbeLog);
            }
            classLoaderOrderEnd[i] = classLoaderOrder.getClassLoaderOrder().size();
        }
        final var classLoaderOrderEntries = classLoaderOrder.getClassLoaderOrder();
        final var allParentClassLoaders = classLoaderOrder.getAllParentClassLoaders();

        // Search the classpath sources in the order the caller enabled them in. Each classloader source adds the
        // classpath entries of the classloaders it added to the classloader order. Each kind of source has its own
        // branch, and the last one throws, so a new kind of source fails until it is given a branch here.
        for (var i = 0; i < classpathSources.size(); i++) {
            final ClasspathSource classpathSource = classpathSources.get(i);
            if (classpathSource instanceof final NamedClasspathEntries namedClasspathEntries) {
                addNamedClasspathEntries(namedClasspathEntries.classpathEntries(), classLoaderProbeLog);
            } else if (classpathSource instanceof final ClasspathString classpathString) {
                // The classpath is split here rather than when the caller handed it over, so that a URL scheme the
                // caller registered afterwards still keeps its own ':' from being read as a separator
                addNamedClasspathEntries(
                        List.of(PathList.split(classpathString.classpath(), classpathSpec.getAllowedURLSchemes())),
                        classLoaderProbeLog);
            } else if (classpathSource instanceof NamedClassLoaders
                    || classpathSource instanceof DetectedClassLoaders) {
                addClassLoaderClasspathEntries(classLoaderOrderEntries
                        .subList(i == 0 ? 0 : classLoaderOrderEnd[i - 1], classLoaderOrderEnd[i]), classpathSpec,
                        allParentClassLoaders, classLoaderProbeLog);
            } else {
                throw new AssertionError(classpathSource);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Adds classpath entries supplied directly by the caller.
     *
     * @param classpathEntries
     *            the classpath entries.
     * @param log
     *            the log node, or null to skip logging.
     */
    private void addNamedClasspathEntries(final List<?> classpathEntries, final @Nullable LogNode log) {
        final var subLog = log == null ? null
                : log.log("Adding the classpath entries given by the caller: " + classpathEntries);
        // No classloader is recorded for an entry the caller named: the caller named a location to read, not a
        // classloader to read it through, and a scan that was never asked to look at a classloader must not pin one
        classpathOrder.addClasspathEntry(classpathEntries, /* classLoader = */ null, subLog);
        if (subLog != null) {
            subLog.log("WARNING: when the classpath entries are given directly, there is no guarantee that the "
                    + "classes found by classpath scanning will be the same as the classes loaded by the "
                    + "context classloader");
        }
    }

    /**
     * Traverses {@code classLoaders} in delegation order and appends them to {@code classLoaderOrder}.
     *
     * @param classLoaders
     *            the classloaders to traverse.
     * @param classLoaderOrder
     *            the shared classloader order builder.
     * @param log
     *            the log node, or null to skip logging.
     */
    private static void findClassLoaderOrder(final List<ClassLoader> classLoaders,
            final ClassLoaderOrderBuilder classLoaderOrder, final @Nullable LogNode log) {
        final var classloaderOrderLog = log == null ? null
                : log.log("Finding unique classloaders in delegation order");
        for (final ClassLoader classLoader : classLoaders) {
            classLoaderOrder.delegateToPeer(classLoader, classloaderOrderLog);
        }
    }

    /**
     * Extracts classpath entries from each classloader in {@code handledClassLoaders} using its selected
     * {@link ClassLoaderHandler} instances.
     *
     * @param handledClassLoaders
     *            the classloaders and their selected handlers.
     * @param classpathSpec
     *            the {@link ClasspathSpec}.
     * @param allParentClassLoaders
     *            all classloaders reached as parent classloaders.
     * @param log
     *            the log node, or null to skip logging.
     */
    private void addClassLoaderClasspathEntries(
            final List<ClassLoaderOrderBuilder.HandledClassLoader> handledClassLoaders,
            final ClasspathSpec classpathSpec, final Set<ClassLoader> allParentClassLoaders,
            final @Nullable LogNode log) {
        final var classloaderURLLog = log == null ? null
                : log.log("Obtaining URLs from classloaders in delegation order");
        for (final var handledClassLoader : handledClassLoaders) {
            final var classLoader = handledClassLoader.classLoader();
            for (final ClassLoaderHandler classLoaderHandler : handledClassLoader.handlers()) {
                if (classpathSpec.isParentClassLoadersIgnored() && allParentClassLoaders.contains(classLoader)) {
                    if (classloaderURLLog != null) {
                        classloaderURLLog.log("Ignoring parent classloader " + classLoader
                                + ", normally handled by " + classLoaderHandler.getClass().getName());
                    }
                } else {
                    // Add the classpath entries to classpathOrder
                    final var classloaderHandlerLog = classloaderURLLog == null ? null
                            : classloaderURLLog.log("Classloader " + classLoader.getClass().getName()
                                    + " is handled by " + classLoaderHandler.getClass().getName());
                    // Record the package roots that this ClassLoaderHandler's classpath elements can have, so that
                    // only the package roots that are applicable to each classpath element are looked for and
                    // stripped when it is scanned (#929), and likewise the lib dirs that this ClassLoaderHandler
                    // loads jarfiles from without listing them as classpath elements. They are normalized and
                    // copied here, so a handler that spells them loosely, or that refills one list for each
                    // classloader, still gives each classpath element the prefixes that were declared for it
                    try {
                        classpathOrder.setPackageRootPrefixes(classLoaderHandler.getPackageRootPrefixes(),
                                classloaderHandlerLog);
                        classpathOrder.setLibDirPrefixes(classLoaderHandler.getLibDirPrefixes(),
                                classloaderHandlerLog);
                        classpathOrder.setCurrentHandlerClass(classLoaderHandler.getClass());
                        classLoaderHandler.findClasspathOrder(classLoader, classpathOrder, classloaderHandlerLog);
                    } catch (final RuntimeException | LinkageError e) {
                        // One failing handler, for example one that meets a container version whose internals
                        // have changed, must not stop every other classloader from being read. The classpath
                        // entries the handler added before it failed are kept.
                        if (classloaderHandlerLog != null) {
                            classloaderHandlerLog.log("ClassLoaderHandler "
                                    + classLoaderHandler.getClass().getName() + " failed on classloader "
                                    + classLoader.getClass().getName() + ", so it is skipped", e);
                        }
                    } finally {
                        classpathOrder.setPackageRootPrefixes(null, null);
                        classpathOrder.setLibDirPrefixes(null, null);
                        classpathOrder.setCurrentHandlerClass(null);
                    }
                }
            }
        }
    }
}
