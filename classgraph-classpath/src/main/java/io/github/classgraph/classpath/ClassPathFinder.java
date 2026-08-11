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
package io.github.classgraph.classpath;

import java.nio.file.Path;

import nonapi.io.github.classgraph.classpath.ClasspathFinder;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.classpathspec.ClassLoaderAndModuleLayerSpec;
import nonapi.io.github.classgraph.classpathspec.ClassPathSpec;
import nonapi.io.github.classgraph.utils.Assert;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/**
 * Finds the classpath and the module path of the running JVM: where its classes and resources would be loaded from,
 * including the locations that a container's custom classloaders load from.
 *
 * <pre>
 * ClassPath classPath = new ClassPathFinder().find();
 * </pre>
 *
 * <p>
 * By default, the classloaders found in the environment are searched: the context classloader of the calling
 * thread, the classloader of the caller's own class, the system classloader, and their parents, in the order in
 * which they would be asked to load a class. The methods of this class narrow or widen that search.
 *
 * <p>
 * An instance is not thread-safe while it is being configured, but {@link #find()} may be called any number of
 * times, and returns a new {@link ClassPath} each time.
 */
public class ClassPathFinder {
    /** Everything except the classloaders and module layers the caller named. */
    private final ClassPathSpec classPathSpec = new ClassPathSpec();

    /**
     * The classloaders and module layers the caller named. These are held separately from the {@link ClassPathSpec}
     * so that they can be dropped as soon as the classpath has been found, rather than being kept alive by the
     * {@link ClassPath}.
     */
    private final ClassLoaderAndModuleLayerSpec classLoaderAndModuleLayerSpec = new ClassLoaderAndModuleLayerSpec();

    /** If true, log what is found to the {@code io.github.classgraph.ClassGraph} logger, at {@code INFO} level. */
    private boolean verbose;

    /** Constructor. */
    public ClassPathFinder() {
        // Intentionally empty
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Log what is found to the {@code io.github.classgraph.ClassGraph} logger, at {@code INFO} level. This is
     * intended for working out why a classpath element is or is not being found, and is not a stable output format.
     *
     * @return this (for method chaining).
     */
    public ClassPathFinder verbose() {
        this.verbose = true;
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
    public ClassPathFinder overrideClasspath(final String classpath) {
        Assert.notNull(classpath, "classpath");
        if (classpath.isEmpty()) {
            throw new IllegalArgumentException("Can't override classpath with an empty path");
        }
        for (final String classpathElement : JarUtils.smartPathSplit(classpath, classPathSpec)) {
            classPathSpec.addClasspathOverride(classpathElement);
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
    public ClassPathFinder overrideClasspath(final Object... classpathElements) {
        Assert.notNullElements(classpathElements, "classpathElements");
        if (classpathElements.length == 0) {
            throw new IllegalArgumentException("Can't override classpath with an empty path");
        }
        for (final Object classpathElement : classpathElements) {
            classPathSpec.addClasspathOverride(classpathElement);
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
    public ClassPathFinder overrideClasspath(final Iterable<?> classpathElements) {
        Assert.notNull(classpathElements, "classpathElements");
        if (classpathElements instanceof Path) {
            // A Path is an Iterable of its own name elements, so passing a single Path binds to this overload
            // rather than to the Object... overload. The name elements of a path are never classpath entries in
            // their own right, so a Path is added as a single classpath entry.
            classPathSpec.addClasspathOverride(classpathElements);
            return this;
        }
        if (!classpathElements.iterator().hasNext()) {
            throw new IllegalArgumentException("Can't override classpath with an empty path");
        }
        for (final Object classpathElement : classpathElements) {
            Assert.notNull(classpathElement, "classpathElements element");
            classPathSpec.addClasspathOverride(classpathElement);
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
    public ClassPathFinder overrideClassLoaders(final ClassLoader... classLoaders) {
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
    public ClassPathFinder addClassLoader(final ClassLoader classLoader) {
        classLoaderAndModuleLayerSpec.addClassLoader(classLoader);
        return this;
    }

    /**
     * Do not search the parents of the classloaders that are searched. The classpath elements that only a parent
     * classloader declares are left out of the result.
     *
     * @return this (for method chaining).
     */
    public ClassPathFinder ignoreParentClassLoaders() {
        classPathSpec.ignoreParentClassLoaders = true;
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
    public ClassPathFinder overrideModuleLayers(final ModuleLayer... moduleLayers) {
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
    public ClassPathFinder addModuleLayer(final ModuleLayer moduleLayer) {
        classLoaderAndModuleLayerSpec.addModuleLayer(moduleLayer);
        return this;
    }

    /**
     * Do not look for modules at all, so that {@link ClassPath#getModules()} is empty and only the classpath
     * elements are reported.
     *
     * @return this (for method chaining).
     */
    public ClassPathFinder ignoreModules() {
        classPathSpec.scanModules = false;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the classpath elements and the modules.
     *
     * <p>
     * Nothing that is found is opened or checked for existence, so this is fast, and a returned classpath element
     * may name a jarfile or directory that is not there.
     *
     * @return the classpath.
     */
    public ClassPath find() {
        final var log = verbose ? new LogNode() : null;
        try {
            final var classpathFinder = new ClasspathFinder(classPathSpec, classLoaderAndModuleLayerSpec,
                    new ReflectionUtils(), log);
            return new ClassPath(classpathFinder, classPathSpec.modulePathInfo);
        } finally {
            if (log != null) {
                log.flush();
            }
        }
    }
}
