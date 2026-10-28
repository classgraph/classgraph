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
import java.util.function.Predicate;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.classpath.internal.ClassLoaderProbe;
import io.github.classgraph.classpath.internal.ClasspathSpec;
import io.github.classgraph.classpath.internal.ScanSourceSpec;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsSpec;

/**
 * Configures and runs classpath and module path discovery for the running JVM.
 *
 * <pre>
 * Classpath classpath = new ClasspathFinder().enableSystemModules().enableNonSystemModules().enableClasspath()
 *         .find();
 * </pre>
 *
 * <p>
 * At least one {@code enable*} method must be called before {@link #find()}:
 * <ul>
 * <li>{@link #enableClasspath()} searches classloaders discovered in the runtime environment.</li>
 * <li>{@link #enableClassLoaders(ClassLoader...)} and {@link #enableClasspathEntries(Object...)} search explicit
 * classloaders or classpath entries.</li>
 * <li>{@link #enableSystemModules()} and {@link #enableNonSystemModules()} search modules in the caller's visible
 * module layers (or in layers specified via {@link #enableModuleLayers(ModuleLayer...)}).</li>
 * </ul>
 *
 * <p>
 * Options that refine another setting (such as {@link #enableRealtimeLogging()} without {@link #verbose()}, or
 * {@link #enableModuleLayers(ModuleLayer...)} without {@link #enableSystemModules()} or
 * {@link #enableNonSystemModules()}) require their companion option to be enabled as well; otherwise
 * {@link #find()} throws {@link IllegalArgumentException}.
 *
 * <p>
 * Instances are not thread-safe during configuration, but {@link #find()} may be called repeatedly to produce new
 * {@link Classpath} instances.
 */
public final class ClasspathFinder {
    /** Classpath and module discovery configuration. */
    private final ClasspathSpec classpathSpec = new ClasspathSpec();

    /** Virtual filesystem configuration for reading jarfiles. */
    private final VfsSpec vfsSpec = new VfsSpec();

    /** Configured classloader, classpath entry, and module layer sources. */
    private final ScanSourceSpec scanSourceSpec = new ScanSourceSpec();

    /** Whether verbose logging to {@code io.github.classgraph.ClassGraph} at {@code INFO} level is enabled. */
    private boolean verbose;

    /** Whether log entries are emitted immediately as they are recorded. */
    private boolean realtimeLoggingEnabled;

    /** Filters added via {@link #filterClasspathEntries(Predicate)}, in registration order. */
    private final List<Predicate<? super ClasspathEntry>> entryFilters = new ArrayList<>();

    /**
     * Constructs a {@link ClasspathFinder} with network URL schemes ({@code http}, {@code https}, {@code ftp}, and
     * {@code mailto}) denied by default unless explicitly permitted via {@link #allowURLScheme(String)}.
     */
    public ClasspathFinder() {
        for (final String scheme : ClasspathSpec.NETWORK_URL_SCHEMES) {
            vfsSpec.denyURLScheme(scheme);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Enables verbose logging to the {@code io.github.classgraph.ClassGraph} logger at {@code INFO} level.
     *
     * @return this (for method chaining).
     */
    public ClasspathFinder verbose() {
        this.verbose = true;
        return this;
    }

    /**
     * Emits log entries immediately as they are recorded rather than flushing them at the end of {@link #find()}.
     * Requires {@link #verbose()} to be enabled as well.
     *
     * @return this (for method chaining).
     */
    public ClasspathFinder enableRealtimeLogging() {
        this.realtimeLoggingEnabled = true;
        return this;
    }

    /**
     * Permits classpath elements with the given URL scheme (such as {@code "http"}) to be opened and read, and
     * recognizes the scheme when splitting {@code ':'}-delimited classpath strings.
     *
     * <p>
     * By default, {@code http}, {@code https}, {@code ftp}, and {@code mailto} are denied, while all other schemes
     * supported by an installed {@link java.net.URLStreamHandler} or {@link java.nio.file.spi.FileSystemProvider}
     * are allowed.
     *
     * @param scheme
     *            the URL scheme without {@code ':'} (e.g. {@code "http"}).
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code scheme} is shorter than two characters or is not a valid URL scheme.
     */
    public ClasspathFinder allowURLScheme(final String scheme) {
        classpathSpec.allowURLScheme(scheme);
        vfsSpec.allowURLScheme(scheme);
        return this;
    }

    /**
     * Prevents classpath elements with the given URL or URI scheme from being opened or expanded, even if a
     * {@link java.net.URLStreamHandler} or {@link java.nio.file.spi.FileSystemProvider} is installed for the
     * scheme.
     *
     * @param scheme
     *            the URL scheme without {@code ':'} (e.g. {@code "s3"}).
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code scheme} is shorter than two characters or is not a valid URL scheme.
     */
    public ClasspathFinder denyURLScheme(final String scheme) {
        vfsSpec.denyURLScheme(scheme);
        return this;
    }

    /**
     * Disables opening jarfiles nested within other jarfiles (such as jarfiles inside {@code BOOT-INF/lib/} or
     * {@code WEB-INF/lib/}).
     *
     * @return this (for method chaining).
     */
    public ClasspathFinder disableNestedJarScanning() {
        vfsSpec.disableNestedJars();
        return this;
    }

    /**
     * Disables multi-release jarfile version resolution on {@link Classpath#getVfs()}, exposing resources under
     * their raw {@code META-INF/versions/<N>/} paths instead of resolving them for the running JVM version.
     *
     * @return this (for method chaining).
     */
    public ClasspathFinder disableMultiReleaseVersions() {
        vfsSpec.disableMultiReleaseVersions();
        return this;
    }

    /**
     * Sets the maximum in-memory buffer size in bytes per jarfile (default 64MB) before deflated nested jarfiles,
     * downloaded jarfiles, or stream-backed jarfiles are spilled to temporary files on disk.
     *
     * @param maxBufferedJarRAMSize
     *            the maximum number of bytes to buffer in RAM per jarfile.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code maxBufferedJarRAMSize} is negative.
     */
    public ClasspathFinder setMaxBufferedJarRAMSize(final int maxBufferedJarRAMSize) {
        vfsSpec.setMaxBufferedJarRAMSize(maxBufferedJarRAMSize);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Enables discovery of classpath elements from classloaders in the current runtime environment: the calling
     * thread's context classloader, the classloader that loaded ClassGraph,
     * {@link ClassLoader#getSystemClassLoader()}, classloaders of classes on the call stack, and their parent
     * classloaders.
     *
     * <p>
     * Reading private {@code jdk.internal.loader.URLClassPath} fields on JPMS built-in classloaders (required to
     * discover entries added via {@code Instrumentation.appendToSystemClassLoaderSearch} or
     * {@code -Xbootclasspath/a}) requires either <a href="https://github.com/toolfactory/narcissus">Narcissus</a>
     * or {@code --add-opens java.base/jdk.internal.loader=ALL-UNNAMED} (or {@code =io.github.classgraph.base} on
     * the module path); otherwise {@code java.class.path} is used as a fallback.
     *
     * @return this (for method chaining).
     */
    public ClasspathFinder enableClasspath() {
        scanSourceSpec.enableClasspath();
        return this;
    }

    /**
     * Enables discovery of classpath elements from the specified classloaders (and their parent classloaders,
     * unless {@link #ignoreParentClassLoaders()} is called).
     *
     * @param classLoaders
     *            the classloaders to search.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code classLoaders} is empty.
     */
    public ClasspathFinder enableClassLoaders(final ClassLoader... classLoaders) {
        scanSourceSpec.enableClassLoaders(classLoaders);
        return this;
    }

    /**
     * Adds classpath elements from a path string delimited by {@link java.io.File#pathSeparatorChar}.
     *
     * @param classpath
     *            the {@link java.io.File#pathSeparatorChar}-delimited classpath string.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code classpath} contains no classpath elements.
     */
    public ClasspathFinder enableClasspathEntries(final String classpath) {
        Assert.notNull(classpath, "classpath");
        scanSourceSpec.enableClasspathString(classpath);
        return this;
    }

    /**
     * Adds explicit classpath elements. Each element may be a {@link String} (treated as a single path, not split
     * on {@link java.io.File#pathSeparatorChar}), {@link java.io.File}, {@link Path}, {@link java.net.URL},
     * {@link java.net.URI}, or a nested array or {@link Iterable} of elements.
     *
     * @param classpathElements
     *            the classpath elements to search.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code classpathElements} is empty or contains a {@link ClassLoader},
     *             {@link java.util.stream.BaseStream}, {@link java.util.Iterator}, or
     *             {@link java.util.Enumeration}.
     */
    public ClasspathFinder enableClasspathEntries(final Object... classpathElements) {
        Assert.notNullElements(classpathElements, "classpathElements");
        scanSourceSpec.enableClasspathEntries(List.of(classpathElements));
        return this;
    }

    /**
     * Adds explicit classpath elements from an {@link Iterable} (or a single {@link Path}). Each element may be a
     * {@link String} (treated as a single path, not split on {@link java.io.File#pathSeparatorChar}),
     * {@link java.io.File}, {@link Path}, {@link java.net.URL}, {@link java.net.URI}, or a nested array or
     * {@link Iterable} of elements.
     *
     * @param classpathElements
     *            the classpath elements to search.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code classpathElements} is empty or contains a {@link ClassLoader},
     *             {@link java.util.stream.BaseStream}, {@link java.util.Iterator}, or
     *             {@link java.util.Enumeration}.
     */
    public ClasspathFinder enableClasspathEntries(final Iterable<?> classpathElements) {
        Assert.notNull(classpathElements, "classpathElements");
        // A Path is an Iterable of its own name elements, so a single Path is passed to this overload rather than
        // to the Object... one. The Iterable is handed over as one element, which keeps a Path as one classpath
        // element and replaces any other Iterable by its elements
        scanSourceSpec.enableClasspathEntries(List.of(classpathElements));
        return this;
    }

    /**
     * Excludes parent classloaders of searched classloaders. Requires {@link #enableClasspath()} or
     * {@link #enableClassLoaders(ClassLoader...)}.
     *
     * @return this (for method chaining).
     */
    public ClasspathFinder ignoreParentClassLoaders() {
        classpathSpec.ignoreParentClassLoaders();
        return this;
    }

    /**
     * Registers a custom {@link ClassLoaderHandler} to extract classpath entries from a custom {@link ClassLoader}.
     * Requires {@link #enableClasspath()} or {@link #enableClassLoaders(ClassLoader...)}.
     *
     * @param classLoaderHandler
     *            the {@link ClassLoaderHandler} to register.
     * @return this (for method chaining).
     */
    public ClasspathFinder registerClassLoaderHandler(final ClassLoaderHandler classLoaderHandler) {
        classpathSpec.addClassLoaderHandler(classLoaderHandler);
        return this;
    }

    /**
     * Retains only classpath entries accepted by {@code filter}. Rejected entries are excluded from the result and
     * are not opened to expand their transitive manifest or lib directory entries. If called multiple times, all
     * filters must match. Requires {@link #enableClasspath()}, {@link #enableClassLoaders(ClassLoader...)}, or
     * {@link #enableClasspathEntries(Object...)}.
     *
     * @param filter
     *            predicate returning true for classpath entries to keep.
     * @return this (for method chaining).
     */
    public ClasspathFinder filterClasspathEntries(final Predicate<? super ClasspathEntry> filter) {
        Assert.notNull(filter, "filter");
        entryFilters.add(filter);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Enables discovery of system modules ({@code java.*}, {@code jdk.*}, {@code javafx.*}, and {@code oracle.*}
     * modules present at the same location in {@link java.lang.module.ModuleFinder#ofSystem()}) in the searched
     * module layers.
     *
     * @return this (for method chaining).
     */
    public ClasspathFinder enableSystemModules() {
        classpathSpec.enableSystemModules();
        return this;
    }

    /**
     * Enables discovery of non-system modules in the searched module layers.
     *
     * @return this (for method chaining).
     */
    public ClasspathFinder enableNonSystemModules() {
        classpathSpec.enableNonSystemModules();
        return this;
    }

    /**
     * Searches the specified {@link ModuleLayer} instances (and their parent layers, unless
     * {@link #ignoreParentModuleLayers()} is called) instead of the module layers visible from the call stack. Call
     * {@link #enableDetectedModuleLayers()} to include visible call-stack layers as well. Requires
     * {@link #enableSystemModules()} or {@link #enableNonSystemModules()}.
     *
     * @param moduleLayers
     *            the module layers to search.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code moduleLayers} is empty.
     */
    public ClasspathFinder enableModuleLayers(final ModuleLayer... moduleLayers) {
        scanSourceSpec.enableModuleLayers(moduleLayers);
        return this;
    }

    /**
     * Includes module layers visible from the call stack and boot layer in addition to layers passed to
     * {@link #enableModuleLayers(ModuleLayer...)}. Requires {@link #enableSystemModules()} or
     * {@link #enableNonSystemModules()}.
     *
     * @return this (for method chaining).
     */
    public ClasspathFinder enableDetectedModuleLayers() {
        scanSourceSpec.enableDetectedModuleLayers();
        return this;
    }

    /**
     * Excludes parent module layers of searched module layers. Requires {@link #enableSystemModules()} or
     * {@link #enableNonSystemModules()}.
     *
     * @return this (for method chaining).
     */
    public ClasspathFinder ignoreParentModuleLayers() {
        classpathSpec.ignoreParentModuleLayers();
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Discovers and returns the configured {@link Classpath}, recursively expanding jarfiles with entries from
     * their automatic lib directories and manifest {@code Class-Path} and {@code Bundle-ClassPath} attributes.
     *
     * @return the discovered {@link Classpath}.
     * @throws IllegalArgumentException
     *             if no source was enabled, or if a dependent option was enabled without its companion option.
     * @throws IllegalStateException
     *             if the thread was interrupted while reading jarfiles.
     */
    public Classpath find() {
        checkConfigIsExplicit();
        final var log = verbose ? new LogNode(realtimeLoggingEnabled) : null;
        try {
            final var classLoaderProbe = new ClassLoaderProbe(classpathSpec, scanSourceSpec, log);

            // The classpath elements that the classloaders declared
            final List<ClasspathEntry> classLoaderEntries = new ArrayList<>();
            for (final var entry : classLoaderProbe.getClasspathOrder().getOrder()) {
                classLoaderEntries.add(ClasspathEntry.of(entry.classpathEntryObj(), entry.location(),
                        entry.classLoaderName(), entry.packageRootPrefixes(), entry.libDirPrefixes()));
            }

            // Add the classpath elements that those in turn declare, by reading their manifests and their lib dirs.
            // The virtual filesystem owns the classpath elements that are opened to do that, and outlives this
            // method: the returned Classpath hands it to the caller, so that a classpath element that was opened
            // here is not opened a second time when the caller reads it.
            final var vfs = new Vfs(vfsSpec);
            Classpath classpath = null;
            try {
                final var expandedEntries = TransitiveClasspath.expand(classLoaderEntries,
                        entry -> entryFilters.stream().allMatch(filter -> filter.test(entry)), vfs, log);
                classpath = new Classpath(expandedEntries, classLoaderProbe, classpathSpec, vfs);
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

    /**
     * Verifies that all configured options have their required companion options enabled.
     *
     * @throws IllegalArgumentException
     *             if a config option was enabled without its required companion option.
     */
    private void checkConfigIsExplicit() {
        Assert.companionIsCalled(realtimeLoggingEnabled, "ClasspathFinder", "enableRealtimeLogging", verbose,
                "verbose");
        Assert.companionIsCalled(!entryFilters.isEmpty(), "ClasspathFinder", "filterClasspathEntries",
                !scanSourceSpec.getClasspathSources().isEmpty(), "enableClasspath", "enableClassLoaders",
                "enableClasspathEntries");
        scanSourceSpec.checkSourcesAreExplicit(classpathSpec, "ClasspathFinder");
    }
}
