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
package io.github.classgraph.classpath;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.base.internal.utils.JarUtils;
import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.classpath.internal.ClassLoaderProbe;
import io.github.classgraph.classpath.internal.spec.ClassLoaderAndModuleLayerSpec;
import io.github.classgraph.classpath.internal.spec.ClasspathSpec;
import io.github.classgraph.vfs.internal.spec.VfsScanSpec;
import io.github.classgraph.vfs.internal.zip.NestedJarHandler;

/**
 * Finds the classpath and the module path of the running JVM: where its classes and resources would be loaded from,
 * including the locations that a container's custom classloaders load from.
 *
 * <pre>
 * Classpath classpath = new ClasspathFinder().find();
 * </pre>
 *
 * <p>
 * By default, the classloaders found in the environment are searched: the context classloader of the calling
 * thread, the classloader of the caller's own class, the system classloader, and their parents, in the order in
 * which they would be asked to load a class. The methods of this class narrow or widen that search.
 *
 * <p>
 * An instance is not thread-safe while it is being configured, but {@link #find()} may be called any number of
 * times, and returns a new {@link Classpath} each time.
 */
public class ClasspathFinder {
    /** Everything except the classloaders and module layers the caller named. */
    private final ClasspathSpec classpathSpec = new ClasspathSpec();

    /** How the jarfiles on the classpath are read, in order to find the classpath elements they declare. */
    private final VfsScanSpec vfsScanSpec = new VfsScanSpec();

    /**
     * The classloaders and module layers the caller named. These are held separately from the {@link ClasspathSpec}
     * so that they can be dropped as soon as the classpath has been found, rather than being kept alive by the
     * {@link Classpath}.
     */
    private final ClassLoaderAndModuleLayerSpec classLoaderAndModuleLayerSpec = new ClassLoaderAndModuleLayerSpec();

    /** If true, log what is found to the {@code io.github.classgraph.ClassGraph} logger, at {@code INFO} level. */
    private boolean verbose;

    /** Constructor. */
    public ClasspathFinder() {
        // Intentionally empty
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Log what is found to the {@code io.github.classgraph.ClassGraph} logger, at {@code INFO} level. This is
     * intended for working out why a classpath element is or is not being found, and is not a stable output format.
     *
     * @return this (for method chaining).
     */
    public ClasspathFinder verbose() {
        this.verbose = true;
        return this;
    }

    /**
     * Allow classpath elements to be named by a URL with the given scheme, e.g. {@code "http"}. Only {@code file:}
     * URLs are allowed by default. A classpath element with a scheme that has not been enabled is still reported,
     * but the jarfile it names is not read, so the classpath elements that it declares are not found.
     *
     * @param scheme
     *            the URL scheme, without the {@code ':'}, e.g. {@code "http"}.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if the scheme is empty, contains a {@code ':'}, or is {@code "jrt"} or {@code "file"} (which are
     *             always handled).
     */
    public ClasspathFinder enableURLScheme(final String scheme) {
        classpathSpec.enableURLScheme(scheme);
        vfsScanSpec.enableURLScheme(scheme);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Search the given classpath instead of the one the environment declares, with the elements separated by
     * {@link java.io.File#pathSeparatorChar}. The classloaders, the {@code java.class.path} system property and the
     * modules are all ignored.
     *
     * @param classpath
     *            the classpath to search, with elements separated by {@link java.io.File#pathSeparatorChar}.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code classpath} is empty.
     */
    public ClasspathFinder overrideClasspath(final String classpath) {
        Assert.notNull(classpath, "classpath");
        if (classpath.isEmpty()) {
            throw new IllegalArgumentException("Can't override classpath with an empty path");
        }
        for (final String classpathElement : JarUtils.smartPathSplit(classpath, classpathSpec.allowedURLSchemes)) {
            classpathSpec.addClasspathOverride(classpathElement);
        }
        return this;
    }

    /**
     * Search the given classpath elements instead of the ones the environment declares. The classloaders, the
     * {@code java.class.path} system property and the modules are all ignored.
     *
     * <p>
     * Each element is one classpath entry, and is not split on {@link java.io.File#pathSeparatorChar} -- pass the
     * {@link String} overload for a path that needs splitting. Elements may be of any type whose
     * {@link Object#toString()} is a classpath element location, e.g. {@link String}, {@link java.io.File} or
     * {@link Path}.
     *
     * @param classpathElements
     *            the classpath elements to search, one entry per element.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code classpathElements} is empty, or if any element is a {@link ClassLoader} (pass those to
     *             {@link #overrideClassLoaders(ClassLoader...)} instead).
     */
    public ClasspathFinder overrideClasspath(final Object... classpathElements) {
        Assert.notNullElements(classpathElements, "classpathElements");
        if (classpathElements.length == 0) {
            throw new IllegalArgumentException("Can't override classpath with an empty path");
        }
        for (final Object classpathElement : classpathElements) {
            classpathSpec.addClasspathOverride(classpathElement);
        }
        return this;
    }

    /**
     * Search the given classpath elements instead of the ones the environment declares. The classloaders, the
     * {@code java.class.path} system property and the modules are all ignored.
     *
     * <p>
     * Each element is one classpath entry, and is not split on {@link java.io.File#pathSeparatorChar} -- pass the
     * {@link String} overload for a path that needs splitting. Elements may be of any type whose
     * {@link Object#toString()} is a classpath element location, e.g. {@link String}, {@link java.io.File} or
     * {@link Path}.
     *
     * <p>
     * A single {@link Path} is treated as one classpath entry, not as a sequence of its name elements.
     *
     * @param classpathElements
     *            the classpath elements to search, one entry per element.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code classpathElements} is empty, or if any element is a {@link ClassLoader} (pass those to
     *             {@link #overrideClassLoaders(ClassLoader...)} instead).
     */
    public ClasspathFinder overrideClasspath(final Iterable<?> classpathElements) {
        Assert.notNull(classpathElements, "classpathElements");
        if (classpathElements instanceof Path) {
            // A Path is an Iterable of its own name elements, so passing a single Path binds to this overload
            // rather than to the Object... overload. The name elements of a path are never classpath entries in
            // their own right, so a Path is added as a single classpath entry.
            classpathSpec.addClasspathOverride(classpathElements);
            return this;
        }
        if (!classpathElements.iterator().hasNext()) {
            throw new IllegalArgumentException("Can't override classpath with an empty path");
        }
        for (final Object classpathElement : classpathElements) {
            Assert.notNull(classpathElement, "classpathElements element");
            classpathSpec.addClasspathOverride(classpathElement);
        }
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Search the given classloaders instead of the ones found in the environment. This also causes the
     * {@code java.class.path} system property to be ignored.
     *
     * @param classLoaders
     *            the classloaders to search.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if no classloader is given.
     */
    public ClasspathFinder overrideClassLoaders(final ClassLoader... classLoaders) {
        classLoaderAndModuleLayerSpec.overrideClassLoaders(classLoaders);
        return this;
    }

    /**
     * Search the given classloader as well as the ones found in the environment. This can only widen what is
     * searched, never narrow it, and has no effect if the classpath is overridden.
     *
     * @param classLoader
     *            the extra classloader to search.
     * @return this (for method chaining).
     */
    public ClasspathFinder addClassLoader(final ClassLoader classLoader) {
        classLoaderAndModuleLayerSpec.addClassLoader(classLoader);
        return this;
    }

    /**
     * Do not search the parents of the classloaders that are searched. The classpath elements that only a parent
     * classloader declares are left out of the result.
     *
     * @return this (for method chaining).
     */
    public ClasspathFinder ignoreParentClassLoaders() {
        classpathSpec.ignoreParentClassLoaders = true;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Search the given module layers instead of the ones that are visible from the caller. Use this if the code
     * calling this library does not itself run within the module layer whose modules are wanted.
     *
     * @param moduleLayers
     *            the module layers to search.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if no module layer is given.
     */
    public ClasspathFinder overrideModuleLayers(final ModuleLayer... moduleLayers) {
        classLoaderAndModuleLayerSpec.overrideModuleLayers(moduleLayers);
        return this;
    }

    /**
     * Search the given module layer as well as the ones that are visible from the caller. Use this if you define
     * your own module layer, but the code calling this library does not run within it.
     *
     * @param moduleLayer
     *            the extra module layer to search.
     * @return this (for method chaining).
     */
    public ClasspathFinder addModuleLayer(final ModuleLayer moduleLayer) {
        classLoaderAndModuleLayerSpec.addModuleLayer(moduleLayer);
        return this;
    }

    /**
     * Do not look for modules at all, so that {@link Classpath#getModules()} is empty and only the classpath
     * elements are reported.
     *
     * @return this (for method chaining).
     */
    public ClasspathFinder ignoreModules() {
        classpathSpec.scanModules = false;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the classpath elements and the modules.
     *
     * <p>
     * The jarfiles on the classpath are opened, so that the classpath elements they declare can be added to the
     * result: the jarfiles in their automatic lib dirs, and the entries of their manifests' {@code Class-Path} and
     * {@code Bundle-ClassPath} attributes. Each of those can declare more of them, so this is recursive. Close the
     * returned {@link Classpath} to close the jarfiles again.
     *
     * <p>
     * A classpath element that could not be opened, or that is not a jarfile, is still reported -- a classloader
     * may name a jarfile or directory that is not there.
     *
     * @return the classpath.
     * @throws IllegalStateException
     *             if the thread was interrupted while the jarfiles were being read.
     */
    public Classpath find() {
        final var log = verbose ? new LogNode() : null;
        try {
            final var classLoaderProbe = new ClassLoaderProbe(classpathSpec, classLoaderAndModuleLayerSpec, log);

            // The classpath elements that the classloaders declared
            final List<ClasspathEntry> classLoaderEntries = new ArrayList<>();
            for (final var entry : classLoaderProbe.getClasspathOrder().getOrder()) {
                classLoaderEntries.add(new ClasspathEntry(entry.location, entry.getClassLoaderString(),
                        List.of(entry.packageRootPrefixes)));
            }

            // Add the classpath elements that those in turn declare, by reading their manifests
            final var nestedJarHandler = new NestedJarHandler(vfsScanSpec, new InterruptionChecker());
            var classpath = (Classpath) null;
            try {
                final var expandedEntries = ClasspathExpansion.expand(classLoaderEntries, vfsScanSpec,
                        nestedJarHandler, log);
                classpath = new Classpath(expandedEntries, classLoaderProbe, classpathSpec.modulePathInfo,
                        nestedJarHandler);
                return classpath;
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while reading the jarfiles on the classpath", e);
            } finally {
                if (classpath == null) {
                    // Ownership of the open jarfiles was not passed to a Classpath, so close them here
                    nestedJarHandler.close(log);
                }
            }
        } finally {
            if (log != null) {
                log.flush();
            }
        }
    }
}
