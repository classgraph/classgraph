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

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.BaseStream;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.PathList;
import io.github.classgraph.base.internal.utils.Assert;

/**
 * Holds the configured sources from which classpath entries and modules are discovered.
 */
public class ScanSourceSpec {
    /** A configured source of classpath elements. */
    public sealed interface ClasspathSource {
    }

    /** Classloaders discovered in the caller's runtime environment and their parent classloaders. */
    public record DetectedClassLoaders() implements ClasspathSource {
    }

    /**
     * Explicit classloaders and their parent classloaders.
     *
     * @param classLoaders
     *            the classloaders to search.
     */
    public record NamedClassLoaders(List<ClassLoader> classLoaders) implements ClasspathSource {
    }

    /**
     * Explicit classpath elements supplied directly by the caller.
     *
     * @param classpathEntries
     *            the normalized classpath element objects.
     */
    public record NamedClasspathEntries(List<Object> classpathEntries) implements ClasspathSource {
    }

    /**
     * A {@link File#pathSeparatorChar}-delimited classpath string to be split when classpath discovery runs.
     *
     * @param classpath
     *            the delimited classpath string.
     */
    public record ClasspathString(String classpath) implements ClasspathSource {
    }

    /** Configured classpath sources, in the order they were enabled. */
    private final List<ClasspathSource> classpathSources = new ArrayList<>();

    /** Whether {@link #enableDetectedModuleLayers()} was explicitly called. */
    private boolean detectedModuleLayersRequested;

    /** Explicit module layers added via {@link #enableModuleLayers(ModuleLayer...)}, or null if none. */
    private @Nullable List<ModuleLayer> namedModuleLayers;

    // -----------------------------------------------------------------------------------------------------------

    /** Constructor. */
    public ScanSourceSpec() {
        // Intentionally empty
    }

    // -----------------------------------------------------------------------------------------------------------

    /** Enables discovery of classloaders in the runtime environment. */
    public void enableClasspath() {
        classpathSources.add(new DetectedClassLoaders());
    }

    /**
     * Adds explicit classloaders to search for classpath elements.
     *
     * @param classLoaders
     *            the classloaders to search.
     * @throws IllegalArgumentException
     *             if {@code classLoaders} is empty.
     */
    public void enableClassLoaders(final ClassLoader... classLoaders) {
        Assert.notNullElements(classLoaders, "classLoaders");
        if (classLoaders.length == 0) {
            throw new IllegalArgumentException("At least one ClassLoader must be provided");
        }
        final List<ClassLoader> classLoaderList = new ArrayList<>(classLoaders.length);
        Collections.addAll(classLoaderList, classLoaders);
        classpathSources.add(new NamedClassLoaders(classLoaderList));
    }

    /**
     * Flattens, validates, and adds explicit classpath elements.
     *
     * @param classpathEntries
     *            the classpath elements (or nested arrays/iterables of elements).
     * @throws IllegalArgumentException
     *             if no elements are provided, or if an element is a {@link ClassLoader}, {@link BaseStream},
     *             {@link Iterator}, or {@link Enumeration}.
     */
    public void enableClasspathEntries(final List<?> classpathEntries) {
        final List<Object> normalized = new ArrayList<>(classpathEntries.size());
        for (final Object classpathEntry : classpathEntries) {
            // The containers are expanded now rather than at scan time, so that a mutable collection cannot change
            // what it names in between
            ClasspathEntryFlattener.forEachEntry(classpathEntry, entry -> {
                // A caller that runs no null checker of its own can still pass a null element, at any depth
                Objects.requireNonNull(entry, "classpathEntries element must not be null");
                if (entry instanceof ClassLoader) {
                    throw new IllegalArgumentException(
                            "Need to pass ClassLoader instances to enableClassLoaders, not enableClasspathEntries");
                }
                if (!(entry instanceof Iterable<?>) && (entry instanceof BaseStream<?, ?>
                        || entry instanceof Iterator<?> || entry instanceof Enumeration<?>)) {
                    // A one-shot sequence that is not an Iterable would be read by its string form, which names no
                    // file, so the classpath elements it holds would be silently left out
                    throw new IllegalArgumentException("Need to collect a " + entry.getClass().getName()
                            + " into a collection before passing it to enableClasspathEntries");
                }
                // A classpath element of a type the classpath order understands is kept as it is, so that the
                // filesystem of a Path and the scheme of a URL or URI are not lost. Anything else, including an
                // Iterable that yields itself, as an Ant Resource does, is read by its string form, which is taken
                // now rather than at scan time, so that a mutable object cannot change what it names in between
                return normalized.add(entry instanceof String || entry instanceof URL || entry instanceof URI
                        || entry instanceof File || entry instanceof Path ? entry : entry.toString());
            });
        }
        // Tested after expanding, so that collections and arrays that hold no classpath element are rejected too
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("At least one classpath entry must be provided");
        }
        classpathSources.add(new NamedClasspathEntries(normalized));
    }

    /**
     * Adds a {@link File#pathSeparatorChar}-delimited classpath string.
     *
     * @param classpath
     *            the delimited classpath string.
     * @throws IllegalArgumentException
     *             if {@code classpath} contains no elements.
     */
    public void enableClasspathString(final String classpath) {
        // Only the emptiness of the classpath is tested here, which no set of registered URL schemes can change:
        // splitting for real is left until the classpath is found
        if (PathList.split(classpath, /* allowedURLSchemes = */ null).length == 0) {
            throw new IllegalArgumentException("At least one classpath entry must be provided");
        }
        classpathSources.add(new ClasspathString(classpath));
    }

    /** Enables searching module layers visible from the call stack and boot layer. */
    public void enableDetectedModuleLayers() {
        detectedModuleLayersRequested = true;
    }

    /**
     * Returns whether module layers detected in the environment should be searched.
     *
     * @param classpathSpec
     *            the classpath configuration.
     * @return true if detected module layers should be searched.
     */
    public boolean searchesDetectedModuleLayers(final ClasspathSpec classpathSpec) {
        return detectedModuleLayersRequested
                || classpathSpec.isModuleScanningEnabled() && namedModuleLayers == null;
    }

    /**
     * Returns whether any classloader source (detected or named) is enabled.
     *
     * @return true if classloaders are searched.
     */
    public boolean searchesClassLoaders() {
        return classpathSources.stream().anyMatch(source -> switch (source) {
        case DetectedClassLoaders _,NamedClassLoaders _ -> true;
        case NamedClasspathEntries _,ClasspathString _ -> false;
        });
    }

    /**
     * Adds explicit module layers to search.
     *
     * @param moduleLayers
     *            the module layers to search.
     * @throws IllegalArgumentException
     *             if {@code moduleLayers} is empty.
     */
    public void enableModuleLayers(final ModuleLayer... moduleLayers) {
        Assert.notNullElements(moduleLayers, "moduleLayers");
        if (moduleLayers.length == 0) {
            throw new IllegalArgumentException("At least one ModuleLayer must be provided");
        }
        if (this.namedModuleLayers == null) {
            this.namedModuleLayers = new ArrayList<>();
        }
        Collections.addAll(this.namedModuleLayers, moduleLayers);
    }

    // -----------------------------------------------------------------------------------------------------------

    /**
     * Returns the enabled classpath sources in registration order.
     *
     * @return the classpath sources, as an unmodifiable list.
     */
    public List<ClasspathSource> getClasspathSources() {
        return Collections.unmodifiableList(classpathSources);
    }

    /**
     * Returns the explicit module layers added via {@link #enableModuleLayers(ModuleLayer...)}, or null if none.
     *
     * @return the named module layers, or null.
     */
    public @Nullable List<ModuleLayer> getNamedModuleLayers() {
        final var moduleLayers = namedModuleLayers;
        return moduleLayers == null ? null : Collections.unmodifiableList(moduleLayers);
    }

    /**
     * Verifies that any configured module layer or classloader refinement options have a corresponding module or
     * classloader source enabled.
     *
     * @param classpathSpec
     *            the classpath configuration.
     * @param className
     *            the simple name of the caller-facing configuration class for error messages.
     * @throws IllegalArgumentException
     *             if a refinement option was enabled without a corresponding source.
     */
    public void checkSourcesAreExplicit(final ClasspathSpec classpathSpec, final String className) {
        // The module layer options say which layers to look in, so they need a kind of module to look for
        final var moduleScanningEnabled = classpathSpec.isModuleScanningEnabled();
        Assert.companionIsCalled(namedModuleLayers != null, className, "enableModuleLayers", moduleScanningEnabled,
                "enableSystemModules", "enableNonSystemModules");
        Assert.companionIsCalled(detectedModuleLayersRequested, className, "enableDetectedModuleLayers",
                moduleScanningEnabled, "enableSystemModules", "enableNonSystemModules");
        Assert.companionIsCalled(classpathSpec.isParentModuleLayersIgnored(), className, "ignoreParentModuleLayers",
                moduleScanningEnabled, "enableSystemModules", "enableNonSystemModules");

        // The classloader options apply only to the classloaders that are searched, so a classloader has to be
        // searched. Named classpath entries and modules are found without asking any classloader.
        final var classLoadersSearched = searchesClassLoaders();
        Assert.companionIsCalled(classpathSpec.isParentClassLoadersIgnored(), className, "ignoreParentClassLoaders",
                classLoadersSearched, "enableClasspath", "enableClassLoaders");
        Assert.companionIsCalled(!classpathSpec.getClassLoaderHandlers().isEmpty(), className,
                "registerClassLoaderHandler", classLoadersSearched, "enableClasspath", "enableClassLoaders");
    }

    // -----------------------------------------------------------------------------------------------------------

    /**
     * Logs the configured sources.
     *
     * @param classpathSpec
     *            the classpath configuration.
     * @param log
     *            the log node, or null to skip logging.
     */
    public void log(final ClasspathSpec classpathSpec, final @Nullable LogNode log) {
        if (log != null) {
            final var subLog = log.log("ScanSourceSpec:");
            subLog.log("classpathSources: " + classpathSources);
            subLog.log("searchesDetectedModuleLayers: " + searchesDetectedModuleLayers(classpathSpec));
            subLog.log("namedModuleLayers: " + namedModuleLayers);
        }
    }
}
