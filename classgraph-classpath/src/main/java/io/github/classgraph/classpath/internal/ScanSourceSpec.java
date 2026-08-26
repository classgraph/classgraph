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
import java.util.List;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.PathList;
import io.github.classgraph.base.internal.utils.Assert;
import org.jspecify.annotations.Nullable;

/**
 * Where the classpath elements and modules are looked for. Nothing is looked for until something here is enabled.
 *
 * <p>
 * The classpath sources are kept in the order they were enabled in, and searched in that order. The module sources
 * are searched before all of them, because that is the order in which the JVM resolves a class: a builtin
 * classloader looks the class's package up among the modules before it delegates to its parent or falls back to its
 * classpath.
 *
 * <p>
 * This is deliberately not part of {@link ClasspathSpec}: a {@code ScanResult} holds the specs the scan was run
 * with for as long as the caller holds the {@code ScanResult}, and a scan must not keep a {@link ClassLoader} or a
 * {@link ModuleLayer} alive. This object is held only by the {@code ClassGraph} instance the caller built the scan
 * with, and by the scan itself while it runs; it is unreachable from the {@code ScanResult}.
 */
public class ScanSourceSpec {
    /** A place that classpath elements are looked for. */
    public sealed interface ClasspathSource {
    }

    /**
     * The classpath elements declared by the classloaders found in the environment: the context classloader of the
     * calling thread, the classloader of the caller's own class, the system classloader, the classloaders of the
     * classes on the call stack, and the parents of all of those.
     */
    public record DetectedClassLoaders() implements ClasspathSource {
    }

    /**
     * The classpath elements declared by the given classloaders, and by their parents.
     *
     * @param classLoaders
     *            the classloaders.
     */
    public record NamedClassLoaders(List<ClassLoader> classLoaders) implements ClasspathSource {
    }

    /**
     * Classpath elements given directly, rather than found by asking a classloader.
     *
     * @param classpathEntries
     *            the classpath elements, one entry per element.
     */
    public record NamedClasspathEntries(List<Object> classpathEntries) implements ClasspathSource {
    }

    /**
     * A whole classpath given directly, still to be split at {@link File#pathSeparatorChar}. The split is left
     * until the classpath is found, because which URL schemes' own {@code ':'} must not be read as a separator is
     * not settled until then: the caller may register a scheme after handing over the classpath that names it.
     *
     * @param classpath
     *            the classpath, with the elements separated by {@link File#pathSeparatorChar}.
     */
    public record ClasspathString(String classpath) implements ClasspathSource {
    }

    /** The places classpath elements are looked for, in the order they were enabled in. */
    public final List<ClasspathSource> classpathSources = new ArrayList<>();

    /** If true, search the module layers that are visible from the caller, and the boot layer. */
    public boolean searchDetectedModuleLayers;

    /** If non-null, module layers to search as well as any that {@link #searchDetectedModuleLayers} asks for. */
    public @Nullable List<ModuleLayer> namedModuleLayers;

    // -----------------------------------------------------------------------------------------------------------

    /** Constructor. */
    public ScanSourceSpec() {
        // Intentionally empty
    }

    // -----------------------------------------------------------------------------------------------------------

    /** Search the classloaders found in the environment for classpath elements. */
    public void enableClasspath() {
        classpathSources.add(new DetectedClassLoaders());
    }

    /**
     * Search the given classloaders for classpath elements.
     *
     * @param classLoaders
     *            the classloaders to search.
     * @throws IllegalArgumentException
     *             if no classloader is given.
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
     * Search the given classpath elements. Each element is one classpath entry, and is not split on
     * {@link File#pathSeparatorChar}.
     *
     * @param classpathEntries
     *            the classpath elements, one entry per element.
     * @throws IllegalArgumentException
     *             if no classpath element is given, or if any element is a {@link ClassLoader}.
     */
    public void enableClasspathEntries(final List<?> classpathEntries) {
        if (classpathEntries.isEmpty()) {
            throw new IllegalArgumentException("At least one classpath entry must be provided");
        }
        final List<Object> normalized = new ArrayList<>(classpathEntries.size());
        for (final Object classpathEntry : classpathEntries) {
            Assert.notNull(classpathEntry, "classpathEntries element");
            if (classpathEntry instanceof ClassLoader) {
                throw new IllegalArgumentException(
                        "Need to pass ClassLoader instances to enableClassLoaders, not enableClasspathEntries");
            }
            // A classpath element of a type the classpath order understands is kept as it is, so that the filesystem
            // of a Path and the scheme of a URL or URI are not lost. Anything else is read by its string form, which
            // is taken now rather than at scan time, so that a mutable object cannot change what it names in between
            normalized.add(classpathEntry instanceof String || classpathEntry instanceof URL
                    || classpathEntry instanceof URI || classpathEntry instanceof File
                    || classpathEntry instanceof Path ? classpathEntry : classpathEntry.toString());
        }
        classpathSources.add(new NamedClasspathEntries(normalized));
    }

    /**
     * Search the classpath elements of the given classpath, which is split at {@link File#pathSeparatorChar} when
     * the classpath is found rather than now.
     *
     * @param classpath
     *            the classpath, with the elements separated by {@link File#pathSeparatorChar}.
     * @throws IllegalArgumentException
     *             if the classpath holds no classpath element.
     */
    public void enableClasspathString(final String classpath) {
        // Only the emptiness of the classpath is tested here, which no set of registered URL schemes can change:
        // splitting for real is left until the classpath is found
        if (PathList.split(classpath, /* allowedURLSchemes = */ null).length == 0) {
            throw new IllegalArgumentException("At least one classpath entry must be provided");
        }
        classpathSources.add(new ClasspathString(classpath));
    }

    /** Search the module layers that are visible from the caller, and the boot layer. */
    public void enableDetectedModuleLayers() {
        searchDetectedModuleLayers = true;
    }

    /**
     * Search the given module layers, as well as any that {@link #enableDetectedModuleLayers()} asks for.
     *
     * @param moduleLayers
     *            the module layers to search.
     * @throws IllegalArgumentException
     *             if no module layer is given.
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
     * Log the sources that the caller enabled.
     *
     * @param log
     *            the log node, or null to skip logging
     */
    public void log(final @Nullable LogNode log) {
        if (log != null) {
            final var subLog = log.log("ScanSourceSpec:");
            subLog.log("classpathSources: " + classpathSources);
            subLog.log("searchDetectedModuleLayers: " + searchDetectedModuleLayers);
            subLog.log("namedModuleLayers: " + namedModuleLayers);
        }
    }
}
