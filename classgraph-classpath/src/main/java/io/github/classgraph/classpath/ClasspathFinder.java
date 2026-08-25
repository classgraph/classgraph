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
import io.github.classgraph.classpath.internal.ClassLoaderProbe;
import io.github.classgraph.classpath.internal.ClasspathSpec;
import io.github.classgraph.classpath.internal.ScanSourceSpec;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsSpec;

/**
 * Finds the classpath and the module path of the running JVM: where its classes and resources would be loaded from,
 * including the locations that a container's custom classloaders load from.
 *
 * <pre>
 * Classpath classpath = new ClasspathFinder().enableModules().enableClasspath().find();
 * </pre>
 *
 * <p>
 * Nothing is searched until it is enabled, so at least one of the {@code enable} methods has to be called for
 * anything to be found. They come in pairs: the method with no arguments enables the sources found in the
 * environment ({@link #enableClasspath()}, {@link #enableModules()}), and the method that takes varargs enables
 * exactly the sources it is given ({@link #enableClassLoaders(ClassLoader...)},
 * {@link #enableModuleLayers(ModuleLayer...)}, {@link #enableClasspathEntries(Object...)}). Calling only the
 * varargs method searches only what it names, which is how the environment's own sources are left out.
 *
 * <p>
 * The classpath sources are searched in the order they were enabled in, and the modules are searched before all of
 * them, since that is the order in which the JVM resolves a class.
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

    /** Everything except the places that classpath elements and modules are looked for. */
    private final ClasspathSpec classpathSpec = new ClasspathSpec();

    /** How the jarfiles on the classpath are read, in order to find the classpath elements they declare. */
    private final VfsSpec vfsSpec = new VfsSpec();

    /**
     * The places that classpath elements and modules are looked for. These are held separately from the
     * {@link ClasspathSpec} so that they can be dropped as soon as the classpath has been found, rather than being
     * kept alive by the {@link Classpath}.
     */
    private final ScanSourceSpec scanSourceSpec = new ScanSourceSpec();

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
     * Find every classpath element of every classloader that can be found in the current runtime environment.
     *
     * <p>
     * A classloader is found if it is any of the following:
     *
     * <ul>
     * <li>the context classloader of the calling thread, as returned by {@link Thread#getContextClassLoader()
     * Thread.currentThread().getContextClassLoader()};</li>
     * <li>the classloader that loaded ClassGraph itself;</li>
     * <li>the system classloader, as returned by {@link ClassLoader#getSystemClassLoader()} -- this is the
     * application classloader, unless the JVM was launched with {@code -Djava.system.class.loader};</li>
     * <li>the classloader of the class in any frame of the current call stack, so that the classloader of the code
     * that called ClassGraph is searched even when it is none of the above; or</li>
     * <li>an ancestor of any of those, reached through {@link ClassLoader#getParent()}.</li>
     * </ul>
     *
     * <p>
     * Every classpath element that every one of those classloaders loads classes from is found, whether or not the
     * classloader exposes it publicly -- see
     * <a href="https://github.com/classgraph/classgraph/wiki/Classpath-Specification-Mechanisms">Classpath
     * specification mechanisms</a> for how each supported classloader is read. Classpath elements are returned in
     * the order in which the classloaders that declared them would be asked to load a class, so a classpath element
     * that appears more than once is reported at the position the JVM would actually load it from, and each
     * classpath element is reported only once, however many of the classloaders declare it.
     *
     * <p>
     * The application classloader is normally one of them, so its own classpath entries -- the ones that the
     * {@code java.class.path} system property lists -- are found too, at the position the application classloader
     * takes in that order.
     *
     * <p>
     * The application classloader is one of the JPMS builtin classloaders, and none of those exposes the locations
     * it loads from through any public API, so its classpath entries are read from its private
     * {@code jdk.internal.loader.URLClassPath ucp} field where that is possible, and from the
     * {@code java.class.path} system property otherwise. The {@code jdk.internal.loader} package is exported to
     * only three modules and is never opened, so the field can be read only if
     * <a href="https://github.com/toolfactory/narcissus">Narcissus</a> is on the classpath, or the JVM was launched
     * with {@code --add-opens java.base/jdk.internal.loader=ALL-UNNAMED}. Two kinds of classpath entry are
     * therefore missed when the field cannot be read, since neither is listed in any system property the
     * application can read:
     * <ul>
     * <li>the jars a Java agent appended by calling
     * {@code Instrumentation.appendToSystemClassLoaderSearch(JarFile)}, which is specified not to change the value
     * of {@code java.class.path}; and</li>
     * <li>the entries appended to the boot classpath with {@code -Xbootclasspath/a}, or with the
     * {@code Boot-Class-Path} attribute of a Java agent's manifest, which the bootstrap classloader holds in a
     * {@code URLClassPath} of its own.</li>
     * </ul>
     *
     * <p>
     * This method takes no arguments, because it searches what is in the environment. To search specific
     * classloaders or specific classpath elements instead, call {@link #enableClassLoaders(ClassLoader...)} or
     * {@link #enableClasspathEntries(Object...)} and do not call this method. Calling both searches the environment
     * as well as what you named.
     *
     * <p>
     * This method does not enable the module path. Modules are found by {@link #enableModules()},
     * {@link #enableSystemModules()}, {@link #enableNonSystemModules()} or
     * {@link #enableModuleLayers(ModuleLayer...)}, and modules always precede the classpath.
     *
     * @return this (for method chaining).
     */
    public ClasspathFinder enableClasspath() {
        scanSourceSpec.enableClasspath();
        return this;
    }

    /**
     * Search the given classloaders for classpath elements, and their parents, rather than the classloaders found
     * in the environment. Call {@link #enableClasspath()} as well to search both.
     *
     * @param classLoaders
     *            the classloaders to search.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if no classloader is given.
     */
    public ClasspathFinder enableClassLoaders(final ClassLoader... classLoaders) {
        scanSourceSpec.enableClassLoaders(classLoaders);
        return this;
    }

    /**
     * Search the given classpath, with the elements separated by {@link java.io.File#pathSeparatorChar}. No
     * classloader is asked for it, so nothing else is searched unless it is enabled as well.
     *
     * @param classpath
     *            the classpath to search, with elements separated by {@link java.io.File#pathSeparatorChar}.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code classpath} is empty.
     */
    public ClasspathFinder enableClasspathEntries(final String classpath) {
        Assert.notNull(classpath, "classpath");
        scanSourceSpec.enableClasspathEntries(List.of(PathList.split(classpath, classpathSpec.allowedURLSchemes)));
        return this;
    }

    /**
     * Search the given classpath elements. No classloader is asked for them, so nothing else is searched unless it
     * is enabled as well.
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
     *             {@link #enableClassLoaders(ClassLoader...)} instead).
     */
    public ClasspathFinder enableClasspathEntries(final Object... classpathElements) {
        Assert.notNullElements(classpathElements, "classpathElements");
        scanSourceSpec.enableClasspathEntries(List.of(classpathElements));
        return this;
    }

    /**
     * Search the given classpath elements. No classloader is asked for them, so nothing else is searched unless it
     * is enabled as well.
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
     *             {@link #enableClassLoaders(ClassLoader...)} instead).
     */
    public ClasspathFinder enableClasspathEntries(final Iterable<?> classpathElements) {
        Assert.notNull(classpathElements, "classpathElements");
        if (classpathElements instanceof Path) {
            // A Path is an Iterable of its own name elements, so passing a single Path binds to this overload
            // rather than to the Object... overload. The name elements of a path are never classpath entries in
            // their own right, so a Path is added as a single classpath entry.
            scanSourceSpec.enableClasspathEntries(List.of(classpathElements));
            return this;
        }
        final List<Object> classpathElementList = new ArrayList<>();
        for (final Object classpathElement : classpathElements) {
            classpathElementList.add(classpathElement);
        }
        scanSourceSpec.enableClasspathEntries(classpathElementList);
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
     * offered each classloader before the built-in handlers are, in the order they were registered, and are never
     * dropped, so a registered handler can also override a built-in one. Of the built-in handlers, only those that
     * name the most specific classloader class are used, so a handler for a subclass of
     * {@link java.net.URLClassLoader} takes the place of the built-in {@code URLClassLoader} handler rather than
     * running alongside it, and has to add the classloader's own URLs itself. A classloader or classpath entry that
     * has already been placed keeps the position the first handler to place it gave it.
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
     * Search the module layers that are visible from the caller -- the layers of the classes on the call stack, and
     * the boot layer -- for the system modules ({@code java.*}, {@code jdk.*}, {@code javafx.*}, {@code oracle.*}).
     *
     * @return this (for method chaining).
     */
    public ClasspathFinder enableSystemModules() {
        scanSourceSpec.enableDetectedModuleLayers();
        classpathSpec.scanSystemModules = true;
        return this;
    }

    /**
     * Search the module layers that are visible from the caller -- the layers of the classes on the call stack, and
     * the boot layer -- for the non-system modules.
     *
     * @return this (for method chaining).
     */
    public ClasspathFinder enableNonSystemModules() {
        scanSourceSpec.enableDetectedModuleLayers();
        classpathSpec.scanNonSystemModules = true;
        return this;
    }

    /**
     * Search the module layers that are visible from the caller -- the layers of the classes on the call stack, and
     * the boot layer -- for modules of both kinds, system and non-system.
     *
     * @return this (for method chaining).
     */
    public ClasspathFinder enableModules() {
        return enableSystemModules().enableNonSystemModules();
    }

    /**
     * Search the given module layers, and their parent layers, for non-system modules, rather than the module
     * layers that are visible from the caller. Use this if the code calling this library does not itself run within
     * the module layer whose modules are wanted. Call {@link #enableModules()} as well to search both, or
     * {@link #enableSystemModules()} as well to find the system modules of the given layers too.
     *
     * @param moduleLayers
     *            the module layers to search.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if no module layer is given.
     */
    public ClasspathFinder enableModuleLayers(final ModuleLayer... moduleLayers) {
        scanSourceSpec.enableModuleLayers(moduleLayers);
        classpathSpec.scanNonSystemModules = true;
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
            final var classLoaderProbe = new ClassLoaderProbe(classpathSpec, scanSourceSpec, log);

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
