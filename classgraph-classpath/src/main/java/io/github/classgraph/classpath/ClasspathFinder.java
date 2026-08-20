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

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.base.internal.path.PathList;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.classpath.internal.ClassLoaderAndModuleLayerSpec;
import io.github.classgraph.classpath.internal.ClassLoaderProbe;
import io.github.classgraph.classpath.internal.ClasspathSpec;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsSpec;

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
public final class ClasspathFinder {
    /**
     * The URL schemes a jarfile is not fetched from unless asked for. These are the schemes that every JVM can
     * already fetch over a network, so a classpath element naming one is read from the network by default, which is
     * not something a classpath walk should do with a path it was merely handed.
     */
    private static final String[] DENIED_URL_SCHEMES = { "http", "https", "ftp", "mailto" };

    /** Everything except the classloaders and module layers the caller named. */
    private final ClasspathSpec classpathSpec = new ClasspathSpec();

    /** How the jarfiles on the classpath are read, in order to find the classpath elements they declare. */
    private final VfsSpec vfsSpec = new VfsSpec();

    /**
     * The classloaders and module layers the caller named. These are held separately from the {@link ClasspathSpec}
     * so that they can be dropped as soon as the classpath has been found, rather than being kept alive by the
     * {@link Classpath}.
     */
    private final ClassLoaderAndModuleLayerSpec classLoaderAndModuleLayerSpec = new ClassLoaderAndModuleLayerSpec();

    /** If true, log what is found to the {@code io.github.classgraph.ClassGraph} logger, at {@code INFO} level. */
    private boolean verbose;

    /**
     * Constructor.
     *
     * <p>
     * A classpath is not always something the caller wrote, so the URL schemes that every JVM can fetch over a
     * network are denied to begin with: a jarfile is not downloaded from an {@code http:}, {@code https:},
     * {@code ftp:} or {@code mailto:} URL unless {@link #enableURLScheme(String)} asks for it. Every other scheme
     * is read as found.
     */
    public ClasspathFinder() {
        for (final String scheme : DENIED_URL_SCHEMES) {
            vfsSpec.disableURLScheme(scheme);
        }
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
     * Allow a jarfile to be fetched from a classpath element named by a URL with the given scheme, e.g.
     * {@code "http"}.
     *
     * <p>
     * Only {@code http}, {@code https}, {@code ftp} and {@code mailto} have to be enabled this way -- see
     * {@link #ClasspathFinder()}. A scheme that the JVM can open only because an application registered a
     * {@link java.net.URLStreamHandler} or a {@link java.nio.file.spi.FileSystemProvider} for it is already read as
     * found. Naming one here is still worth doing if classpath elements with that scheme arrive in a
     * {@code ':'}-separated classpath string such as {@code java.class.path}, since the scheme's own {@code ':'}
     * would otherwise be read as a separator and split the path element in two.
     *
     * @param scheme
     *            the URL scheme, without the {@code ':'}, e.g. {@code "http"}.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if the scheme is shorter than two characters (a one-character scheme cannot be told apart from a
     *             Windows drive letter), or is not a valid URL scheme.
     */
    public ClasspathFinder enableURLScheme(final String scheme) {
        classpathSpec.enableURLScheme(scheme);
        vfsSpec.enableURLScheme(scheme);
        return this;
    }

    /**
     * Refuse to fetch a jarfile from a classpath element named by a URL with the given scheme. The classpath
     * element is still reported, but the jarfile it names is not read, so the classpath elements that it declares
     * are not found.
     *
     * <p>
     * {@code http}, {@code https}, {@code ftp} and {@code mailto} are refused already -- see
     * {@link #ClasspathFinder()}. This adds a scheme to those.
     *
     * @param scheme
     *            the URL scheme, without the {@code ':'}, e.g. {@code "s3"}.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if the scheme is shorter than two characters (a one-character scheme cannot be told apart from a
     *             Windows drive letter), or is not a valid URL scheme.
     */
    public ClasspathFinder disableURLScheme(final String scheme) {
        vfsSpec.disableURLScheme(scheme);
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
        for (final String classpathElement : PathList.split(classpath, classpathSpec.allowedURLSchemes)) {
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

    /**
     * Register a {@link ClassLoaderHandler}, which teaches this library how to read the classpath out of a
     * {@link ClassLoader} that it does not already know about.
     *
     * <p>
     * There are built-in handlers for the classloaders of the common application servers, build tools and
     * frameworks, so this is only needed for a classloader that none of those handle. Registered handlers are
     * offered each classloader before the built-in handlers are, in the order they were registered, so a registered
     * handler can also override a built-in one: the built-in handlers still run afterwards, but a classloader or
     * classpath entry that has already been placed keeps the position the registered handler gave it.
     *
     * @param classLoaderHandler
     *            the {@link ClassLoaderHandler} to register.
     * @return this (for method chaining).
     */
    public ClasspathFinder registerClassLoaderHandler(final ClassLoaderHandler classLoaderHandler) {
        Assert.notNull(classLoaderHandler, "classLoaderHandler");
        classpathSpec.classLoaderHandlers.add(classLoaderHandler);
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
    public ClasspathFinder disableModuleScanning() {
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
                classLoaderEntries.add(ClasspathEntry.of(entry.classpathEntryObj, entry.location,
                        entry.getClassLoaderString(), entry.packageRootPrefixes, entry.libDirPrefixes));
            }

            // Add the classpath elements that those in turn declare, by reading their manifests and their lib dirs.
            // The virtual filesystem owns the classpath elements that are opened to do that, and outlives this
            // method: the returned Classpath hands it to the caller, so that a classpath element that was opened
            // here is not opened a second time when the caller reads it.
            final var vfs = new Vfs(vfsSpec, new InterruptionChecker());
            var classpath = (Classpath) null;
            try {
                final var expandedEntries = TransitiveClasspath.expand(classLoaderEntries, vfs, vfsSpec, log);
                classpath = new Classpath(expandedEntries, classLoaderProbe, classpathSpec.modulePathInfo, vfs);
                return classpath;
            } finally {
                if (classpath == null) {
                    // Ownership of the open jarfiles was not passed to a Classpath, so close them here
                    vfs.close(log);
                }
            }
        } finally {
            if (log != null) {
                log.flush();
            }
        }
    }
}
