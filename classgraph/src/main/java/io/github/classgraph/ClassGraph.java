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
package io.github.classgraph;

import java.io.File;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.base.internal.path.PathList;
import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.base.internal.utils.VersionFinder;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ModulePathInfo;
import io.github.classgraph.classpath.internal.CallStackInfo;
import io.github.classgraph.classpath.internal.ClasspathSpec;
import io.github.classgraph.classpath.internal.ScanSourceSpec;

/**
 * Uber-fast, ultra-lightweight Java classpath and module path scanner. Scans classfiles in the classpath and/or
 * module path by parsing the classfile binary format directly rather than by using reflection.
 *
 * <p>
 * Nothing is scanned until it is enabled, so at least one of the {@code enable} methods has to be called for
 * anything to be found:
 *
 * <pre>
 * new ClassGraph().enableNonSystemModules().enableClasspath().acceptPackages("com.xyz").scan()
 * </pre>
 *
 * <p>
 * {@link #enableClasspath()} enables the classpath sources found in the runtime environment;
 * {@link #enableClassLoaders(ClassLoader...)} and {@link #enableClasspathEntries(Object...)} name classpath sources
 * explicitly. Calling both scans both kinds of source. Module kinds are enabled separately with
 * {@link #enableSystemModules()} and {@link #enableNonSystemModules()}. {@link #enableModuleLayers(ModuleLayer...)}
 * selects layers instead of the caller's layers, but must be accompanied by a module-kind method. Add
 * {@link #enableDetectedModuleLayers()} to search the caller's layers as well as the named ones.
 *
 * <p>
 * The classpath sources are scanned in the order they were enabled in, and the modules are scanned before all of
 * them, since that is the order in which the JVM resolves a class. Narrow what is reached from an enabled source
 * with {@link #ignoreParentClassLoaders()}, {@link #ignoreParentModuleLayers()}, {@link #disableJarScanning()} and
 * {@link #disableDirScanning()}. After a scan, {@link ScanResult#getClasspathURIs()} and
 * {@link ScanResult#getModuleReferences()} report exactly which classpath elements and modules were scanned.
 *
 * <p>
 * The {@code accept} and {@code reject} methods only narrow what the {@code enable} methods enabled; neither adds
 * new scan sources. For example, {@code acceptPackages("com.xyz").scan()} on its own scans nothing because no
 * source of classes was enabled, and {@code acceptModules("java.base")} only scans {@code java.base} if
 * {@link #enableSystemModules()} was also called.
 *
 * <p>
 * All configuration is explicit: no configuration method implicitly enables another. Enabling an option without its
 * required companion option causes {@link #scan()} to throw {@link IllegalArgumentException} (for example, calling
 * {@link #enableMethodInfo()} without {@link #enableClassInfo()}, or {@link #ignoreMethodVisibility()} without
 * {@link #enableMethodInfo()}).
 *
 * <p>
 * Documentation: <a href= "https://github.com/classgraph/classgraph/wiki">
 * https://github.com/classgraph/classgraph/wiki</a>
 */
public final class ClassGraph {
    /** The scanning specification. */
    ScanSpec scanSpec = new ScanSpec();

    /** The sources from which classpath elements and modules are discovered. */
    final ScanSourceSpec scanSourceSpec = new ScanSourceSpec();

    /** The default number of worker threads to use while scanning. */
    static final int DEFAULT_NUM_WORKER_THREADS = Math.max(
            // Always scan with at least 2 threads
            2, //
            Math.min(
                    // Top out at 16 threads
                    16, //
                    (int) Math.ceil(
                            // Num IO threads (top out at 4, since most I/O devices won't scale better than this)
                            Math.min(4.0, Runtime.getRuntime().availableProcessors() * 0.75) +
                            // Num scanning threads (higher than available processors, because some threads can be
                            // blocked)
                                    Runtime.getRuntime().availableProcessors() * 1.25)) //
    );

    /** The default worker timeout (see {@link #setWorkerTimeout(Duration)}). */
    static final Duration DEFAULT_WORKER_TIMEOUT = Duration.ofMinutes(1);

    /** The Maven {@code groupId} of the artifact this class is packaged in. */
    private static final String MAVEN_GROUP_ID = "io.github.classgraph";

    /** The Maven {@code artifactId} of the artifact this class is packaged in. */
    private static final String MAVEN_ARTIFACT_ID = "classgraph";

    /** If true, log while scanning. Each scan writes a log of its own. */
    private boolean verbose;

    /** If true, write out each log entry of a scan as soon as it is made. */
    private boolean realtimeLoggingEnabled;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Construct a {@link ClassGraph} instance.
     *
     * <p>
     * Network URL schemes ({@code http}, {@code https}, {@code ftp}, and {@code mailto}) are denied by default;
     * call {@link #allowURLScheme(String)} or {@link #enableRemoteJarScanning()} to allow them. All other schemes
     * (including custom {@link java.net.URLStreamHandler} or {@link java.nio.file.spi.FileSystemProvider} schemes)
     * are allowed by default unless denied with {@link #denyURLScheme(String)}.
     */
    public ClassGraph() {
        for (final String scheme : ClasspathSpec.NETWORK_URL_SCHEMES) {
            scanSpec.vfsSpec.denyURLScheme(scheme);
        }
    }

    /**
     * Get the version number of ClassGraph.
     *
     * @return the ClassGraph version, or "unknown" if it could not be determined.
     */
    public static String getVersion() {
        return VersionFinder.getVersion(ClassGraph.class, MAVEN_GROUP_ID, MAVEN_ARTIFACT_ID);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Switches on verbose logging. The log is written to the {@code io.github.classgraph.ClassGraph}
     * {@link java.util.logging.Logger} at {@code INFO} level, which by default writes to System.err.
     *
     * @return this (for method chaining).
     */
    public ClassGraph verbose() {
        this.verbose = true;
        return this;
    }

    /**
     * Switches verbose logging on if verbose is true, and off if it is false. See {@link #verbose()}.
     *
     * @param verbose
     *            if true, enable verbose logging; if false, disable it.
     * @return this (for method chaining).
     */
    public ClassGraph verbose(final boolean verbose) {
        this.verbose = verbose;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Enables the scanning of classfiles, producing {@link ClassInfo} objects in the {@link ScanResult}.
     *
     * <p>
     * Requires at least one scan source (such as {@link #enableClasspath()}, {@link #enableSystemModules()}, or
     * {@link #enableNonSystemModules()}) to be enabled as well.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableClassInfo() {
        scanSpec.enableClassInfo = true;
        return this;
    }

    /**
     * Causes class visibility to be ignored, enabling private, package-private and protected classes to be scanned.
     * By default, only public classes are scanned.
     *
     * <p>
     * Requires {@link #enableClassInfo()}, or the scan throws {@link IllegalArgumentException}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph ignoreClassVisibility() {
        scanSpec.ignoreClassVisibility = true;
        return this;
    }

    /**
     * Enables the saving of method info during the scan. This information can be obtained using
     * {@link ClassInfo#getMethodInfo()} etc. By default, method info is not scanned.
     *
     * <p>
     * Requires {@link #enableClassInfo()}, or the scan throws {@link IllegalArgumentException}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableMethodInfo() {
        scanSpec.enableMethodInfo = true;
        return this;
    }

    /**
     * Causes method visibility to be ignored, enabling private, package-private and protected methods to be
     * scanned. By default, only public methods are scanned.
     *
     * <p>
     * Requires {@link #enableMethodInfo()}, or the scan throws {@link IllegalArgumentException}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph ignoreMethodVisibility() {
        scanSpec.ignoreMethodVisibility = true;
        return this;
    }

    /**
     * Enables the saving of field info during the scan. This information can be obtained using
     * {@link ClassInfo#getFieldInfo()}. By default, field info is not scanned.
     *
     * <p>
     * Requires {@link #enableClassInfo()}, or the scan throws {@link IllegalArgumentException}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableFieldInfo() {
        scanSpec.enableFieldInfo = true;
        return this;
    }

    /**
     * Causes field visibility to be ignored, enabling private, package-private and protected fields to be scanned.
     * By default, only public fields are scanned.
     *
     * <p>
     * Requires {@link #enableFieldInfo()}, or the scan throws {@link IllegalArgumentException}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph ignoreFieldVisibility() {
        scanSpec.ignoreFieldVisibility = true;
        return this;
    }

    /**
     * Enables the saving of static final field constant initializer values, which can be obtained from
     * {@link FieldInfo#getConstantInitializerValue()}. By default, constant initializer values are not scanned.
     *
     * <p>
     * Constant initializer values in classfiles are limited to primitives and {@link String} constants, and only
     * when the compiler emits a {@code ConstantValue} attribute on the field rather than initializing the field in
     * {@code <clinit>} or {@code <init>}. If a classfile includes a {@code ConstantValue} attribute on a non-static
     * field, that value is also recorded.
     *
     * <p>
     * Requires {@link #enableFieldInfo()}, or the scan throws {@link IllegalArgumentException}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableStaticFinalFieldConstantInitializerValues() {
        scanSpec.enableStaticFinalFieldConstantInitializerValues = true;
        return this;
    }

    /**
     * Enables the saving of annotation info (for class, field, method and method parameter annotations) during the
     * scan. This information can be obtained using {@link ClassInfo#getAllAnnotationInfo()},
     * {@link FieldInfo#getAllAnnotationInfo()}, and {@link MethodParameterInfo#getAllAnnotationInfo()}. By default,
     * annotation info is not scanned.
     *
     * <p>
     * Requires {@link #enableClassInfo()}, or the scan throws {@link IllegalArgumentException}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableAnnotationInfo() {
        scanSpec.enableAnnotationInfo = true;
        return this;
    }

    /**
     * Enables the determination of inter-class dependencies, which may be read by calling
     * {@link ClassInfo#getClassDependencies()}, {@link ScanResult#getClassDependencyMap()} or
     * {@link ScanResult#getReverseClassDependencyMap()}.
     *
     * <p>
     * Requires {@link #enableClassInfo()}, or the scan throws {@link IllegalArgumentException}. To include
     * dependencies from fields, methods, annotations, or non-public members, enable the corresponding options
     * ({@link #enableFieldInfo()}, {@link #enableMethodInfo()}, {@link #enableAnnotationInfo()},
     * {@link #ignoreClassVisibility()}, {@link #ignoreFieldVisibility()}, {@link #ignoreMethodVisibility()}).
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableInterClassDependencies() {
        scanSpec.enableInterClassDependencies = true;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Causes only runtime visible annotations to be scanned (causes runtime invisible annotations to be ignored).
     *
     * <p>
     * Requires {@link #enableAnnotationInfo()}, or the scan throws {@link IllegalArgumentException}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph disableRuntimeInvisibleAnnotations() {
        scanSpec.disableRuntimeInvisibleAnnotations = true;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Disables the scanning of jarfiles.
     *
     * @return this (for method chaining).
     */
    public ClassGraph disableJarScanning() {
        scanSpec.scanJars = false;
        return this;
    }

    /**
     * Disables the scanning of nested jarfiles (jarfiles within jarfiles).
     *
     * @return this (for method chaining).
     */
    public ClassGraph disableNestedJarScanning() {
        scanSpec.vfsSpec.disableNestedJars();
        return this;
    }

    /**
     * Disables the scanning of directories.
     *
     * @return this (for method chaining).
     */
    public ClassGraph disableDirScanning() {
        scanSpec.scanDirs = false;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Include external classes (classes outside the accepted packages/classes that are referenced by accepted
     * classes) in query results.
     *
     * <p>
     * Requires {@link #enableClassInfo()}, or the scan throws {@link IllegalArgumentException}.
     *
     * <p>
     * During a scan, ClassGraph automatically extends scanning upwards from accepted classes to their superclasses,
     * implemented interfaces, enclosing classes, annotations (when {@link #enableAnnotationInfo()} is enabled),
     * classes referenced in annotation parameter values, and method thrown exception types (when
     * {@link #enableMethodInfo()} is enabled). Without {@link #enableExternalClasses()}, these external classes are
     * used to complete the upward class hierarchy for predicates such as
     * {@link ClassInfo#extendsSuperclass(String)} and {@link ClassInfo#implementsInterface(String)}, but are
     * omitted from {@link ScanResult#getAllClasses()} and other query results. Enabling this option includes them
     * in query results (except for classes explicitly excluded by {@link #rejectClasses(String...)} or
     * {@link #rejectPackages(String...)}).
     *
     * <p>
     * External classes whose classfiles cannot be found (or {@code java.lang.Object}, or non-public classes when
     * {@link #ignoreClassVisibility()} was not called) are represented by placeholder {@link ClassInfo} objects for
     * which {@link ClassInfo#getResource()} returns null.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableExternalClasses() {
        scanSpec.enableExternalClasses = true;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan every classpath element of every ClassLoader that can be found in the current runtime environment:
     *
     * <ul>
     * <li>the context ClassLoader of the calling thread ({@link Thread#getContextClassLoader()});</li>
     * <li>the ClassLoader that loaded ClassGraph;</li>
     * <li>the system ClassLoader ({@link ClassLoader#getSystemClassLoader()});</li>
     * <li>the ClassLoader of any class on the current call stack; and</li>
     * <li>the parent ClassLoaders of any of the above (unless {@link #ignoreParentClassLoaders()} is called).</li>
     * </ul>
     *
     * <p>
     * Classpath elements are scanned in classloader delegation order. On JDK 9+, reading entries added dynamically
     * to the built-in application or bootstrap ClassLoaders (such as via
     * {@code Instrumentation.appendToSystemClassLoaderSearch(JarFile)} or {@code -Xbootclasspath/a}) requires
     * either <a href="https://github.com/toolfactory/narcissus">Narcissus</a> on the classpath/module path or
     * {@code --add-opens java.base/jdk.internal.loader=ALL-UNNAMED} (or
     * {@code --add-opens java.base/jdk.internal.loader=io.github.classgraph.base} when ClassGraph is on the module
     * path); otherwise ClassGraph falls back to {@code java.class.path}.
     *
     * <p>
     * To scan only specific ClassLoaders or classpath entries instead of the runtime environment, call
     * {@link #enableClassLoaders(ClassLoader...)} or {@link #enableClasspathEntries(Object...)} without calling
     * this method. To scan modules, call {@link #enableSystemModules()} or {@link #enableNonSystemModules()}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableClasspath() {
        scanSourceSpec.enableClasspath();
        return this;
    }

    /**
     * Scan the classpath elements declared by the given ClassLoaders (and their parent ClassLoaders, unless
     * {@link #ignoreParentClassLoaders()} is called). Call {@link #enableClasspath()} as well to scan both the
     * given ClassLoaders and the runtime environment's ClassLoaders.
     *
     * <p>
     * Note that the JDK platform ClassLoader loads only from system modules; call {@link #enableSystemModules()} to
     * scan system modules.
     *
     * @param classLoaders
     *            The ClassLoaders to scan.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if no ClassLoader is given.
     */
    public ClassGraph enableClassLoaders(final ClassLoader... classLoaders) {
        scanSourceSpec.enableClassLoaders(classLoaders);
        return this;
    }

    /**
     * Scan the given classpath string, split on {@link java.io.File#pathSeparatorChar}.
     *
     * <p>
     * This is the only overload that splits a classpath string on {@link java.io.File#pathSeparatorChar}. To
     * combine a classpath string with additional entries, chain calls (e.g.
     * {@code enableClasspathEntries(classpath).enableClasspathEntries(extraJar)}).
     *
     * @param classpath
     *            The classpath to scan, with path elements separated by {@link java.io.File#pathSeparatorChar}.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code classpath} holds no classpath entry, e.g. if it is empty.
     */
    public ClassGraph enableClasspathEntries(final String classpath) {
        Assert.notNull(classpath, "classpath");
        scanSourceSpec.enableClasspathString(classpath);
        return this;
    }

    /**
     * Scan the given classpath entries.
     *
     * <p>
     * Each {@link String}, {@link java.io.File}, {@link java.nio.file.Path}, {@link java.net.URL}, or
     * {@link java.net.URI} element is treated as a single classpath entry ({@link String} elements are not split on
     * {@link java.io.File#pathSeparatorChar}; use {@link #enableClasspathEntries(String)} for a path-separated
     * string). Nested {@link Iterable}s and arrays are flattened recursively, and any other object is converted via
     * {@code toString()}.
     *
     * @param classpathElements
     *            The classpath entries to scan.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code classpathElements} holds no classpath entry, or contains a {@link ClassLoader} (use
     *             {@link #enableClassLoaders(ClassLoader...)}), {@link java.util.stream.BaseStream},
     *             {@link java.util.Iterator}, or non-{@link Iterable} {@link java.util.Enumeration}.
     */
    public ClassGraph enableClasspathEntries(final Iterable<?> classpathElements) {
        Assert.notNull(classpathElements, "classpathElements");
        // A Path is an Iterable of its own name elements, so a single Path is passed to this overload rather than
        // to the Object... one. The Iterable is handed over as one element, which keeps a Path as one classpath
        // entry and replaces any other Iterable by its elements
        scanSourceSpec.enableClasspathEntries(List.of(classpathElements));
        return this;
    }

    /**
     * Scan the given classpath entries.
     *
     * <p>
     * Each {@link String}, {@link java.io.File}, {@link java.nio.file.Path}, {@link java.net.URL}, or
     * {@link java.net.URI} element is treated as a single classpath entry ({@link String} elements are not split on
     * {@link java.io.File#pathSeparatorChar}; use {@link #enableClasspathEntries(String)} for a path-separated
     * string). Nested {@link Iterable}s and arrays are flattened recursively, and any other object is converted via
     * {@code toString()}.
     *
     * @param classpathElements
     *            The classpath entries to scan.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code classpathElements} holds no classpath entry, or contains a {@link ClassLoader} (use
     *             {@link #enableClassLoaders(ClassLoader...)}), {@link java.util.stream.BaseStream},
     *             {@link java.util.Iterator}, or non-{@link Iterable} {@link java.util.Enumeration}.
     */
    public ClassGraph enableClasspathEntries(final Object... classpathElements) {
        Assert.notNullElements(classpathElements, "classpathElements");
        scanSourceSpec.enableClasspathEntries(List.of(classpathElements));
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add a classpath element filter. The provided filter should return true if the path string passed to it is a
     * path you want to scan. If several filters are added, a classpath element is only scanned if every filter
     * accepts it.
     *
     * <p>
     * The filter is applied to all discovered classpath elements (including those expanded from wildcard
     * directories or declared in manifest {@code Class-Path} / {@code Bundle-ClassPath} attributes or lib
     * directories), and may be invoked concurrently by multiple worker threads, so it must be thread-safe.
     *
     * @param classpathElementFilter
     *            The thread-safe filter to apply to the normalized ({@code '/'}-separated) path string of each
     *            discovered classpath element (with any leading {@code "jar:"} and/or {@code "file:"} stripped, and
     *            nested jar paths separated by {@code "!/"}); returns true if the classpath element should be
     *            scanned.
     * @return this (for method chaining).
     */
    public ClassGraph filterClasspathElements(final Predicate<String> classpathElementFilter) {
        Assert.notNull(classpathElementFilter, "classpathElementFilter");
        scanSpec.classpathSpec.filterClasspathElements(classpathElementFilter);
        return this;
    }

    /**
     * Add a classpath element {@link URL} filter. The provided filter should return true if the {@link URL} passed
     * to it is a URL you want to scan. If several filters are added, a classpath element is only scanned if every
     * filter accepts it.
     *
     * <p>
     * Applied in the same way as {@link #filterClasspathElements(Predicate)} to every classpath element whose
     * location can be represented as a {@link URL}. The filter may be invoked concurrently by multiple worker
     * threads, so it must be thread-safe.
     *
     * @param classpathElementURLFilter
     *            The thread-safe filter to apply to the {@link URL} of each discovered classpath element.
     * @return this (for method chaining).
     */
    public ClassGraph filterClasspathElementsByURL(final Predicate<URL> classpathElementURLFilter) {
        Assert.notNull(classpathElementURLFilter, "classpathElementURLFilter");
        scanSpec.classpathSpec.filterClasspathElementsByURL(classpathElementURLFilter);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Ignore parent classloaders (i.e. only obtain paths to scan from classloaders that are not the parent of
     * another classloader).
     *
     * <p>
     * Requires {@link #enableClasspath()} or {@link #enableClassLoaders(ClassLoader...)}, or the scan throws
     * {@link IllegalArgumentException}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph ignoreParentClassLoaders() {
        scanSpec.classpathSpec.ignoreParentClassLoaders();
        return this;
    }

    /**
     * Register a custom {@link ClassLoaderHandler} to extract classpath entries from a custom {@link ClassLoader}.
     *
     * <p>
     * Registered handlers are tried in registration order before built-in handlers. Requires
     * {@link #enableClasspath()} or {@link #enableClassLoaders(ClassLoader...)}, or the scan throws
     * {@link IllegalArgumentException}.
     *
     * @param classLoaderHandler
     *            the {@link ClassLoaderHandler} to register.
     * @return this (for method chaining).
     */
    public ClassGraph registerClassLoaderHandler(final ClassLoaderHandler classLoaderHandler) {
        scanSpec.classpathSpec.addClassLoaderHandler(classLoaderHandler);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan system modules ({@code java.*}, {@code jdk.*}, {@code javafx.*}, and {@code oracle.*}) in the visible
     * {@link ModuleLayer}s (or in the layers specified by {@link #enableModuleLayers(ModuleLayer...)}).
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableSystemModules() {
        scanSpec.classpathSpec.enableSystemModules();
        return this;
    }

    /**
     * Scan non-system modules in the visible {@link ModuleLayer}s (or in the layers specified by
     * {@link #enableModuleLayers(ModuleLayer...)}). Classes on the traditional classpath (the unnamed module) are
     * only scanned if {@link #enableClasspath()} is also called.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableNonSystemModules() {
        scanSpec.classpathSpec.enableNonSystemModules();
        return this;
    }

    /**
     * Scan the modules of the given {@link ModuleLayer}s (and their parent layers, unless
     * {@link #ignoreParentModuleLayers()} is called) instead of the layers visible from the caller. Call
     * {@link #enableDetectedModuleLayers()} as well to scan both.
     *
     * <p>
     * Requires {@link #enableSystemModules()} or {@link #enableNonSystemModules()}, or the scan throws
     * {@link IllegalArgumentException}.
     *
     * @param moduleLayers
     *            The ModuleLayers to scan.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if no ModuleLayer is given.
     */
    public ClassGraph enableModuleLayers(final ModuleLayer... moduleLayers) {
        scanSourceSpec.enableModuleLayers(moduleLayers);
        return this;
    }

    /**
     * Scan the {@link ModuleLayer}s visible from the caller (the layers of classes on the call stack and the boot
     * layer) in addition to any layers specified by {@link #enableModuleLayers(ModuleLayer...)}.
     *
     * <p>
     * Requires {@link #enableSystemModules()} or {@link #enableNonSystemModules()}, or the scan throws
     * {@link IllegalArgumentException}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableDetectedModuleLayers() {
        scanSourceSpec.enableDetectedModuleLayers();
        return this;
    }

    /**
     * Ignore parent module layers (i.e. only scan module layers that are not the parent of another module layer).
     *
     * <p>
     * Requires {@link #enableSystemModules()} or {@link #enableNonSystemModules()}, or the scan throws
     * {@link IllegalArgumentException}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph ignoreParentModuleLayers() {
        scanSpec.classpathSpec.ignoreParentModuleLayers();
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan one or more specific packages and their sub-packages.
     *
     * <p>
     * Narrows the scan to the given packages; requires a scan source (such as {@link #enableClasspath()}) and, to
     * scan classfiles rather than only resources, {@link #enableClassInfo()}.
     *
     * @param packageNames
     *            The fully-qualified names of packages to scan (using {@code '.'} as a separator). May include glob
     *            wildcards ({@code '*'} matches within a single package segment, and {@code "**"} as a complete
     *            segment matches zero or more package segments, e.g. {@code "com.**.internal"}). Sub-packages of a
     *            matched package are also scanned. Note that {@code "java.awt.*"} matches only sub-packages of
     *            {@code java.awt}; use {@code "java.awt"} to match {@code java.awt} and its sub-packages.
     * @return this (for method chaining).
     */
    public ClassGraph acceptPackages(final String... packageNames) {
        Assert.notNullElements(packageNames, "packageNames");
        for (final String packageName : packageNames) {
            // A trailing "**" means "and everything below", which acceptPackages() already does -- strip it
            final var packageNameNormalized = AcceptReject
                    .stripTrailingDoubleGlob(AcceptReject.normalizePackageOrClassName(packageName), '.');
            // Accept package
            scanSpec.packageAcceptReject.addToAccept(packageNameNormalized);
            final var path = ClassNames.packageNameToPath(packageNameNormalized);
            scanSpec.pathAcceptReject.addToAccept(path + "/");
            if (packageNameNormalized.isEmpty()) {
                scanSpec.pathAcceptReject.addToAccept("");
            }
            // Accept sub-packages (glob-containing package names included, since the prefix matcher can hold a glob
            // -- #870)
            if (packageNameNormalized.isEmpty()) {
                scanSpec.packagePrefixAcceptReject.addToAccept("");
                scanSpec.pathPrefixAcceptReject.addToAccept("");
            } else {
                scanSpec.packagePrefixAcceptReject.addToAccept(packageNameNormalized + ".");
                scanSpec.pathPrefixAcceptReject.addToAccept(path + "/");
            }
        }
        return this;
    }

    /**
     * Scan one or more specific paths, and their sub-directories or nested paths.
     *
     * <p>
     * Narrows the scan to the given paths; requires a scan source (such as {@link #enableClasspath()}) to be
     * enabled.
     *
     * @param paths
     *            The paths to scan, relative to the package root of the classpath element (with {@code '/'} as a
     *            separator). May include glob wildcards ({@code '*'} matches within a single path segment, and
     *            {@code "**"} as a complete segment matches zero or more path segments). Sub-directories of a
     *            matched path are also scanned.
     * @return this (for method chaining).
     */
    public ClassGraph acceptPaths(final String... paths) {
        Assert.notNullElements(paths, "paths");
        for (final String path : paths) {
            // A trailing "**" means "and everything below", which acceptPaths() already does -- strip it
            final var pathNormalized = AcceptReject.stripTrailingDoubleGlob(AcceptReject.normalizePath(path), '/');
            // Accept path
            final var packageName = AcceptReject.pathToPackageName(pathNormalized);
            scanSpec.packageAcceptReject.addToAccept(packageName);
            scanSpec.pathAcceptReject.addToAccept(pathNormalized + "/");
            if (pathNormalized.isEmpty()) {
                scanSpec.pathAcceptReject.addToAccept("");
            }
            // Accept sub-directories / nested paths (glob-containing paths included -- #870)
            if (pathNormalized.isEmpty()) {
                scanSpec.packagePrefixAcceptReject.addToAccept("");
                scanSpec.pathPrefixAcceptReject.addToAccept("");
            } else {
                scanSpec.packagePrefixAcceptReject.addToAccept(packageName + ".");
                scanSpec.pathPrefixAcceptReject.addToAccept(pathNormalized + "/");
            }
        }
        return this;
    }

    /**
     * Scan one or more specific packages, without recursively scanning sub-packages unless they are themselves
     * accepted.
     *
     * <p>
     * Narrows the scan to the given packages; requires a scan source (such as {@link #enableClasspath()}) and, to
     * scan classfiles rather than only resources, {@link #enableClassInfo()}. Useful for scanning the package root
     * ({@code ""}) without recursively scanning everything in the jar, directory, or module.
     *
     * @param packageNames
     *            The fully-qualified names of packages to scan (with {@code '.'} as a separator). May not include a
     *            glob wildcard.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if any package name contains a glob wildcard.
     */
    public ClassGraph acceptPackagesNonRecursive(final String... packageNames) {
        Assert.notNullElements(packageNames, "packageNames");
        for (final String packageName : packageNames) {
            final var packageNameNormalized = AcceptReject.normalizePackageOrClassName(packageName);
            if (AcceptReject.containsWildcard(packageNameNormalized)) {
                throw new IllegalArgumentException("Cannot use a glob wildcard here: " + packageNameNormalized);
            }
            // Accept package, but not sub-packages
            scanSpec.packageAcceptReject.addToAccept(packageNameNormalized);
            scanSpec.pathAcceptReject.addToAccept(ClassNames.packageNameToPath(packageNameNormalized) + "/");
            if (packageNameNormalized.isEmpty()) {
                scanSpec.pathAcceptReject.addToAccept("");
            }
        }
        return this;
    }

    /**
     * Scan one or more specific paths, without recursively scanning sub-directories or nested paths unless they are
     * themselves accepted.
     *
     * <p>
     * Narrows the scan to the given paths; requires a scan source (such as {@link #enableClasspath()}) to be
     * enabled. Useful for scanning the package root ({@code ""}) without recursively scanning everything in the
     * jar, directory, or module.
     *
     * @param paths
     *            The paths to scan, relative to the package root of the classpath element (with {@code '/'} as a
     *            separator). May not include a glob wildcard.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if any path contains a glob wildcard.
     */
    public ClassGraph acceptPathsNonRecursive(final String... paths) {
        Assert.notNullElements(paths, "paths");
        for (final String path : paths) {
            final var pathNormalized = AcceptReject.normalizePath(path);
            if (AcceptReject.containsWildcard(pathNormalized)) {
                throw new IllegalArgumentException("Cannot use a glob wildcard here: " + pathNormalized);
            }
            // Accept path, but not sub-directories / nested paths
            scanSpec.packageAcceptReject.addToAccept(AcceptReject.pathToPackageName(pathNormalized));
            scanSpec.pathAcceptReject.addToAccept(pathNormalized + "/");
            if (pathNormalized.isEmpty()) {
                scanSpec.pathAcceptReject.addToAccept("");
            }
        }
        return this;
    }

    /**
     * Prevent the scanning of one or more specific packages and their sub-packages.
     *
     * <p>
     * Classes in a rejected package are omitted from query results, though their classfiles may still be read as
     * upward supertypes or annotations of accepted classes (see {@link #rejectClasses(String...)}).
     *
     * @param packageNames
     *            The fully-qualified names of packages to reject (using {@code '.'} as a separator). May include
     *            glob wildcards ({@code '*'} matches within a single package segment, and {@code "**"} as a
     *            complete segment matches zero or more package segments). Sub-packages of a matched package are
     *            also rejected. Note that {@code "java.awt.*"} matches only sub-packages of {@code java.awt}; use
     *            {@code "java.awt"} to reject {@code java.awt} and its sub-packages.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if any package name is the root package ({@code ""}).
     */
    public ClassGraph rejectPackages(final String... packageNames) {
        Assert.notNullElements(packageNames, "packageNames");
        for (final String packageName : packageNames) {
            final var packageNameNormalized = AcceptReject
                    .stripTrailingDoubleGlob(AcceptReject.normalizePackageOrClassName(packageName), '.');
            if (packageNameNormalized.isEmpty()) {
                throw new IllegalArgumentException(
                        "Rejecting the root package (\"\") will cause nothing to be scanned");
            }
            // Rejecting always prevents further recursion, no need to reject sub-packages
            scanSpec.packageAcceptReject.addToReject(packageNameNormalized);
            final var path = ClassNames.packageNameToPath(packageNameNormalized);
            scanSpec.pathAcceptReject.addToReject(path + "/");
            // Reject sub-packages (zipfile entries can occur in any order)
            scanSpec.packagePrefixAcceptReject.addToReject(packageNameNormalized + ".");
            scanSpec.pathPrefixAcceptReject.addToReject(path + "/");
        }
        return this;
    }

    /**
     * Prevent the scanning of one or more specific paths and their sub-directories / nested paths.
     *
     * @param paths
     *            The paths to reject (with {@code '/'} as a separator). May include glob wildcards ({@code '*'}
     *            matches within a single path segment, and {@code "**"} as a complete segment matches zero or more
     *            path segments). Sub-directories of a matched path are also rejected.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if any path is the package root ({@code ""} or {@code "/"}).
     */
    public ClassGraph rejectPaths(final String... paths) {
        Assert.notNullElements(paths, "paths");
        for (final String path : paths) {
            final var pathNormalized = AcceptReject.stripTrailingDoubleGlob(AcceptReject.normalizePath(path), '/');
            if (pathNormalized.isEmpty()) {
                throw new IllegalArgumentException(
                        "Rejecting the root package (\"\") will cause nothing to be scanned");
            }
            // Rejecting always prevents further recursion, no need to reject sub-directories / nested paths
            final var packageName = AcceptReject.pathToPackageName(pathNormalized);
            scanSpec.packageAcceptReject.addToReject(packageName);
            scanSpec.pathAcceptReject.addToReject(pathNormalized + "/");
            // Reject sub-directories / nested paths
            scanSpec.packagePrefixAcceptReject.addToReject(packageName + ".");
            scanSpec.pathPrefixAcceptReject.addToReject(pathNormalized + "/");
        }
        return this;
    }

    /**
     * Scan one or more specific classes, without scanning other classes in the same package unless the package is
     * itself accepted.
     *
     * <p>
     * Narrows the scan to the given classes; requires a scan source (such as {@link #enableClasspath()}) and, to
     * scan classfiles rather than only resources, {@link #enableClassInfo()}.
     *
     * @param classNames
     *            The fully-qualified names of classes to scan (using {@code '.'} as a separator). May contain glob
     *            wildcards ({@code '*'} matches within a single package or class name segment, {@code "**"} matches
     *            zero or more packages, and {@code '?'} matches one character; e.g. {@code "**.*Suffix"}).
     * @return this (for method chaining).
     */
    public ClassGraph acceptClasses(final String... classNames) {
        Assert.notNullElements(classNames, "classNames");
        for (final String className : classNames) {
            final var classNameNormalized = AcceptReject.normalizePackageOrClassName(className);
            // Accept the class itself
            scanSpec.classAcceptReject.addToAccept(classNameNormalized);
            scanSpec.classfilePathAcceptReject
                    .addToAccept(ClassNames.classNameToClassfilePath(classNameNormalized));
            // A class name is never empty, so getParentPackageName cannot return null
            final var packageName = Objects.requireNonNull(PackageInfo.getParentPackageName(classNameNormalized));
            // Record the package containing the class, so we can recurse to this point even if the package is not
            // itself accepted
            scanSpec.classPackageAcceptReject.addToAccept(packageName);
            scanSpec.classPackagePathAcceptReject.addToAccept(ClassNames.packageNameToPath(packageName) + "/");
        }
        return this;
    }

    /**
     * Reject one or more classes, excluding them from class query results even if they are in an accepted package.
     *
     * <p>
     * If an accepted class extends, implements, or is annotated with a rejected class, the rejected classfile is
     * still read so that hierarchy queries (such as {@link ScanResult#getAllClassesImplementing(String)}) remain
     * complete, and its {@link ClassInfo} can still be looked up directly via
     * {@link ScanResult#getClassInfo(String)}. The classfile also remains listed as a {@link Resource} unless its
     * path is rejected.
     *
     * @param classNames
     *            The fully-qualified names of classes to reject (using {@code '.'} as a separator). May contain
     *            glob wildcards ({@code '*'} matches within a single package or class name segment, {@code "**"}
     *            matches zero or more packages, and {@code '?'} matches one character; e.g. {@code "**.*Suffix"}).
     * @return this (for method chaining).
     */
    public ClassGraph rejectClasses(final String... classNames) {
        Assert.notNullElements(classNames, "classNames");
        for (final String className : classNames) {
            final var classNameNormalized = AcceptReject.normalizePackageOrClassName(className);
            scanSpec.classAcceptReject.addToReject(classNameNormalized);
            scanSpec.classfilePathAcceptReject
                    .addToReject(ClassNames.classNameToClassfilePath(classNameNormalized));
        }
        return this;
    }

    /**
     * Accept one or more jars by leafname, causing only the accepted jars to be scanned.
     *
     * <p>
     * Narrows the jars scanned from enabled classpath sources; does not add new jars to the classpath.
     *
     * @param jarLeafNames
     *            The leafnames of the jars to scan (e.g. {@code "mylib.jar"} or {@code "mylib-*.jar"}), matched
     *            case-insensitively. May contain {@code '*'} and {@code '?'} wildcards.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if any name includes a directory component rather than being a bare leafname.
     */
    public ClassGraph acceptJars(final String... jarLeafNames) {
        Assert.notNullElements(jarLeafNames, "jarLeafNames");
        for (final String jarLeafName : jarLeafNames) {
            final var leafName = PathSyntax.leafName(jarLeafName);
            if (!leafName.equals(jarLeafName)) {
                throw new IllegalArgumentException("Can only accept jars by leafname: " + jarLeafName);
            }
            scanSpec.jarAcceptReject.addToAccept(leafName);
        }
        return this;
    }

    /**
     * Reject one or more jars by leafname, preventing them from being scanned.
     *
     * @param jarLeafNames
     *            The leafnames of the jars to reject (e.g. {@code "badlib.jar"} or {@code "badlib-*.jar"}), matched
     *            case-insensitively. May contain {@code '*'} and {@code '?'} wildcards.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if any name includes a directory component rather than being a bare leafname.
     */
    public ClassGraph rejectJars(final String... jarLeafNames) {
        Assert.notNullElements(jarLeafNames, "jarLeafNames");
        for (final String jarLeafName : jarLeafNames) {
            final var leafName = PathSyntax.leafName(jarLeafName);
            if (!leafName.equals(jarLeafName)) {
                throw new IllegalArgumentException("Can only reject jars by leafname: " + jarLeafName);
            }
            scanSpec.jarAcceptReject.addToReject(leafName);
        }
        return this;
    }

    /**
     * Accept one or more modules for scanning.
     *
     * <p>
     * Narrows the modules scanned; requires {@link #enableSystemModules()} and/or {@link #enableNonSystemModules()}
     * to enable module scanning.
     *
     * @param moduleNames
     *            The names of the modules to scan. May contain glob wildcards ({@code '*'} matches within a single
     *            module name segment, {@code "**"} matches zero or more whole segments, e.g. {@code "jdk.**"}, and
     *            {@code '?'} matches one character).
     * @return this (for method chaining).
     */
    // #658
    public ClassGraph acceptModules(final String... moduleNames) {
        Assert.notNullElements(moduleNames, "moduleNames");
        for (final String moduleName : moduleNames) {
            scanSpec.moduleAcceptReject.addToAccept(AcceptReject.normalizePackageOrClassName(moduleName));
        }
        return this;
    }

    /**
     * Reject one or more modules, preventing them from being scanned.
     *
     * @param moduleNames
     *            The names of the modules to reject. May contain glob wildcards ({@code '*'} matches within a
     *            single module name segment, {@code "**"} matches zero or more whole segments, and {@code '?'}
     *            matches one character).
     * @return this (for method chaining).
     */
    // #658
    public ClassGraph rejectModules(final String... moduleNames) {
        Assert.notNullElements(moduleNames, "moduleNames");
        for (final String moduleName : moduleNames) {
            scanSpec.moduleAcceptReject.addToReject(AcceptReject.normalizePackageOrClassName(moduleName));
        }
        return this;
    }

    /**
     * Only scan classpath elements that contain at least one resource with a path matching the given resource paths
     * (checked across all resources in the classpath element, regardless of package/path filters).
     *
     * @param resourcePaths
     *            The resource paths, any of which must be present in a classpath element for it to be scanned. May
     *            contain glob wildcards ({@code '*'} matches within a single path segment, {@code "**"} matches
     *            zero or more whole path segments, e.g. {@code "META-INF/**"}, and {@code '?'} matches one
     *            character).
     * @return this (for method chaining).
     */
    public ClassGraph acceptClasspathElementsContainingResourcePath(final String... resourcePaths) {
        Assert.notNullElements(resourcePaths, "resourcePaths");
        for (final String resourcePath : resourcePaths) {
            final var resourcePathNormalized = AcceptReject.normalizePath(resourcePath);
            scanSpec.classpathElementResourcePathAcceptReject.addToAccept(resourcePathNormalized);
        }
        return this;
    }

    /**
     * Exclude classpath elements that contain any resource with a path matching the given resource paths (checked
     * across all resources in the classpath element, regardless of package/path filters).
     *
     * @param resourcePaths
     *            The resource paths that cause a classpath element to be excluded if present. May contain glob
     *            wildcards ({@code '*'} matches within a single path segment, {@code "**"} matches zero or more
     *            whole path segments, and {@code '?'} matches one character).
     * @return this (for method chaining).
     */
    public ClassGraph rejectClasspathElementsContainingResourcePath(final String... resourcePaths) {
        Assert.notNullElements(resourcePaths, "resourcePaths");
        for (final String resourcePath : resourcePaths) {
            final var resourcePathNormalized = AcceptReject.normalizePath(resourcePath);
            scanSpec.classpathElementResourcePathAcceptReject.addToReject(resourcePathNormalized);
        }
        return this;
    }

    /**
     * Enable classpath elements to be fetched from remote ({@code "http:"}/{@code "https:"}) URLs (equivalent to
     * {@code allowURLScheme("http").allowURLScheme("https")}).
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableRemoteJarScanning() {
        return allowURLScheme("http").allowURLScheme("https");
    }

    /**
     * Allow classpath elements with the specified URL scheme to be read, and recognize the scheme when splitting
     * {@code ':'}-separated classpath strings.
     *
     * <p>
     * Only {@code http}, {@code https}, {@code ftp}, and {@code mailto} are denied by default (see
     * {@link #ClassGraph()}). Custom schemes with a registered {@link java.net.URLStreamHandler} or
     * {@link java.nio.file.spi.FileSystemProvider} are already allowed, but registering them here also prevents
     * their {@code ':'} from being treated as a path separator in {@code ':'}-separated classpath strings.
     *
     * @param scheme
     *            the URL scheme name (without a trailing {@code ':'}), at least two characters long.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if the scheme is shorter than two characters or is not a valid URL scheme.
     */
    public ClassGraph allowURLScheme(final String scheme) {
        Assert.notNull(scheme, "scheme");
        scanSpec.classpathSpec.allowURLScheme(scheme);
        scanSpec.vfsSpec.allowURLScheme(scheme);
        return this;
    }

    /**
     * Deny classpath elements with the specified URL/URI scheme.
     *
     * @param scheme
     *            the URL scheme name (without a trailing {@code ':'}), at least two characters long.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if the scheme is shorter than two characters or is not a valid URL scheme.
     */
    public ClassGraph denyURLScheme(final String scheme) {
        Assert.notNull(scheme, "scheme");
        scanSpec.vfsSpec.denyURLScheme(scheme);
        return this;
    }

    /**
     * Enables the scanning of JRE {@code lib} and {@code ext} jars when found on the classpath (skipped by
     * default). To scan JPMS system modules, call {@link #enableSystemModules()} instead.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableSystemJars() {
        scanSpec.enableSystemJars = true;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Set the maximum RAM buffer size (per jar) for deflated nested jars or jars downloaded from {@code http:} /
     * {@code https:} URLs before spilling over to a temporary file on disk. (Stored nested jars are sliced directly
     * without buffering.)
     *
     * <p>
     * Default: 64MB (capped at {@code Integer.MAX_VALUE - 8}).
     *
     * @param maxBufferedJarRAMSize
     *            The maximum RAM buffer size per jar, in bytes.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code maxBufferedJarRAMSize} is negative.
     */
    public ClassGraph setMaxBufferedJarRAMSize(final int maxBufferedJarRAMSize) {
        scanSpec.vfsSpec.setMaxBufferedJarRAMSize(maxBufferedJarRAMSize);
        return this;
    }

    /**
     * Set the maximum duration a scan may go without any thread making progress before failing with
     * {@link ClassGraphException}. A scan that continues making progress is never timed out.
     *
     * <p>
     * Default: 1 minute. A zero or negative duration disables the timeout.
     *
     * @param workerTimeout
     *            how long the scan may go with no progress before failing.
     * @return this (for method chaining).
     */
    public ClassGraph setWorkerTimeout(final Duration workerTimeout) {
        Assert.notNull(workerTimeout, "workerTimeout");
        scanSpec.workerTimeout = workerTimeout;
        return this;
    }

    /**
     * Disable multi-release jar versioning, reporting all resources under their literal
     * {@code META-INF/versions/<N>/} paths rather than mapping them to the root for the running JVM version.
     *
     * @return this (for method chaining).
     */
    public ClassGraph disableMultiReleaseVersions() {
        scanSpec.vfsSpec.disableMultiReleaseVersions();
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Write each log entry to the logger immediately as it is produced, rather than after the scan completes.
     *
     * <p>
     * Requires {@link #verbose()}, or the scan throws {@link IllegalArgumentException}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableRealtimeLogging() {
        realtimeLoggingEnabled = true;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan the enabled classpath elements and modules using the given {@link ExecutorService} and degree of
     * parallelism, blocking until the scan is complete. Assign the returned {@link ScanResult} in a
     * try-with-resources statement, or close it when finished.
     *
     * @param executorService
     *            A custom {@link ExecutorService} to use for scheduling worker tasks.
     * @param numParallelTasks
     *            The maximum number of parallel tasks (including the calling thread) during the parallel stages of
     *            scanning. Must be at least 1; if 1, the scan runs entirely on the calling thread.
     * @return a {@link ScanResult} object representing the result of the scan.
     * @throws ClassGraphException
     *             if any thread of the scan throws an uncaught exception, or the scan was interrupted.
     * @throws IllegalArgumentException
     *             if {@code numParallelTasks} is less than 1, or if a config option was enabled without its
     *             required companion option.
     */
    public ScanResult scan(final ExecutorService executorService, final int numParallelTasks) {
        Assert.notNull(executorService, "executorService");
        checkNumParallelTasks(numParallelTasks);
        return scanOnThisThread(/* performScan = */ true, executorService, numParallelTasks);
    }

    /**
     * Validate the requested scan parallelism before scheduling any work.
     *
     * @param numParallelTasks
     *            The requested number of parallel scan tasks.
     */
    private static void checkNumParallelTasks(final int numParallelTasks) {
        if (numParallelTasks < 1) {
            throw new IllegalArgumentException("numParallelTasks must be at least 1");
        }
    }

    /**
     * Check that every config option that requires a companion config option was enabled alongside it.
     *
     * @throws IllegalArgumentException
     *             if a config option was enabled without its required companion option.
     */
    private void checkConfigIsExplicit() {
        checkRequires(scanSpec.enableFieldInfo, "enableFieldInfo", scanSpec.enableClassInfo, "enableClassInfo");
        checkRequires(scanSpec.enableMethodInfo, "enableMethodInfo", scanSpec.enableClassInfo, "enableClassInfo");
        checkRequires(scanSpec.enableAnnotationInfo, "enableAnnotationInfo", scanSpec.enableClassInfo,
                "enableClassInfo");
        checkRequires(scanSpec.ignoreClassVisibility, "ignoreClassVisibility", scanSpec.enableClassInfo,
                "enableClassInfo");
        checkRequires(scanSpec.enableInterClassDependencies, "enableInterClassDependencies",
                scanSpec.enableClassInfo, "enableClassInfo");
        checkRequires(scanSpec.enableExternalClasses, "enableExternalClasses", scanSpec.enableClassInfo,
                "enableClassInfo");
        checkRequires(scanSpec.ignoreFieldVisibility, "ignoreFieldVisibility", scanSpec.enableFieldInfo,
                "enableFieldInfo");
        checkRequires(scanSpec.enableStaticFinalFieldConstantInitializerValues,
                "enableStaticFinalFieldConstantInitializerValues", scanSpec.enableFieldInfo, "enableFieldInfo");
        checkRequires(scanSpec.ignoreMethodVisibility, "ignoreMethodVisibility", scanSpec.enableMethodInfo,
                "enableMethodInfo");
        checkRequires(scanSpec.disableRuntimeInvisibleAnnotations, "disableRuntimeInvisibleAnnotations",
                scanSpec.enableAnnotationInfo, "enableAnnotationInfo");
        checkRequires(realtimeLoggingEnabled, "enableRealtimeLogging", verbose, "verbose");
        scanSourceSpec.checkSourcesAreExplicit(scanSpec.classpathSpec, "ClassGraph");
    }

    /**
     * Throw if a config option was enabled without its required companion option.
     *
     * @param enabled
     *            true if the config option that needs a companion option was enabled.
     * @param methodName
     *            the name of the config method that needs a companion option.
     * @param companionEnabled
     *            true if the companion config option was enabled.
     * @param companionMethodName
     *            the name of the companion config method.
     * @throws IllegalArgumentException
     *             if {@code enabled} is true and {@code companionEnabled} is false.
     */
    private static void checkRequires(final boolean enabled, final String methodName,
            final boolean companionEnabled, final String companionMethodName) {
        Assert.companionIsCalled(enabled, "ClassGraph", methodName, companionEnabled, companionMethodName);
    }

    /**
     * Run a scan on the calling thread, blocking until it is complete.
     *
     * @param performScan
     *            If true, performing a scan. If false, only fetching the classpath.
     * @param executorService
     *            A custom {@link ExecutorService} to use for scheduling worker tasks.
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning.
     * @return a {@link ScanResult} object representing the result of the scan.
     * @throws ClassGraphException
     *             if any thread of the scan throws an uncaught exception, or the scan was interrupted.
     */
    private ScanResult scanOnThisThread(final boolean performScan, final ExecutorService executorService,
            final int numParallelTasks) {
        checkConfigIsExplicit();
        final var topLevelLog = verbose ? new LogNode(realtimeLoggingEnabled) : null;
        // Read the call stack once, on the calling thread: the scan needs it both to decide whether loading a class
        // on a worker thread could deadlock (#933) and to find the caller's classloaders and module layers
        final var callStackInfo = CallStackInfo.read();
        try {
            // Scan with a copy of the ScanSpec, since the ScanResult keeps it and reads it after the scan:
            // configuring this ClassGraph further for another scan must not change the answers of a ScanResult
            // already returned. The ScanSourceSpec is not copied, since only the Scanner's constructor reads it.
            return new Scanner(performScan, callStackInfo, scanSpec.copy(), scanSourceSpec, executorService,
                    numTasksWithoutDeadlockHazard(callStackInfo, numParallelTasks, topLevelLog), topLevelLog)
                    .call();

        } catch (final InterruptedException e) {
            // Throwing InterruptedException cleared the interrupt status, and this method reports the interruption
            // as an unchecked exception rather than rethrowing it, so restore the status, otherwise a caller that
            // catches ClassGraphException sees a thread that no longer looks interrupted
            Thread.currentThread().interrupt();
            throw new ClassGraphException("Scan interrupted", e);
        } catch (final CancellationException e) {
            throw new ClassGraphException("Scan interrupted", e);
        } catch (final ExecutionException e) {
            final var cause = InterruptionChecker.getCause(e);
            if (cause instanceof final Error error) {
                // An Error thrown on the calling thread propagates unwrapped, so do the same for one thrown on a
                // worker thread, rather than letting the thread that hit it decide the type the caller sees
                throw error;
            }
            throw new ClassGraphException("Uncaught exception during scan", cause);
        } catch (final RuntimeException e) {
            // An unchecked exception thrown by the scanner used to reach the caller wrapped in an
            // ExecutionException by the Future, and was rewrapped as the cause of a ClassGraphException, so wrap it
            // the same way here rather than letting it propagate unwrapped
            throw new ClassGraphException("Uncaught exception during scan", e);
        }
    }

    /**
     * Reduce the number of parallel tasks to 1 if the calling thread is holding a classloading lock.
     *
     * @param callStackInfo
     *            The call stack of the calling thread.
     * @param numParallelTasks
     *            The requested number of parallel tasks.
     * @param topLevelLog
     *            The log of the scan, or null if not logging.
     * @return the number of parallel tasks to use.
     */
    private static int numTasksWithoutDeadlockHazard(final CallStackInfo callStackInfo, final int numParallelTasks,
            final @Nullable LogNode topLevelLog) {
        if (numParallelTasks <= 1) {
            // The scan already runs entirely on the calling thread
            return numParallelTasks;
        }
        final var frame = callStackInfo.getFrameHoldingClassLoadingLock();
        if (frame == null) {
            return numParallelTasks;
        }
        if (topLevelLog != null) {
            topLevelLog.log("The thread that called scan() is holding a class loading lock in " + frame
                    + ", so loading a class on a worker thread could deadlock the scan (#933) -- running the "
                    + "whole scan on the calling thread instead of using " + numParallelTasks + " threads");
        }
        return 1;
    }

    /**
     * Scan the enabled classpath elements and modules with the requested parallelism, blocking until the scan is
     * complete. Assign the returned {@link ScanResult} in a try-with-resources statement, or close it when
     * finished.
     *
     * <p>
     * Passing {@code numThreads = 1} runs the entire scan on the calling thread without starting worker threads
     * (which is also done automatically if {@code scan()} is called from a static initializer or during
     * {@link ClassLoader} classloading, to avoid classloading deadlocks).
     *
     * @param numThreads
     *            The total number of parallel tasks, including the calling thread. Must be at least 1. If 1, the
     *            scan is run entirely on the calling thread.
     * @return a {@link ScanResult} object representing the result of the scan.
     * @throws ClassGraphException
     *             if any thread of the scan throws an uncaught exception, or the scan was interrupted.
     * @throws IllegalArgumentException
     *             if {@code numThreads} is less than 1, or if a config option was enabled without its required
     *             companion option.
     */
    public ScanResult scan(final int numThreads) {
        checkNumParallelTasks(numThreads);
        try (var executorService = new AutoCloseableExecutorService(numThreads)) {
            return scan(executorService, numThreads);
        }
    }

    /**
     * Scan the enabled classpath elements and modules, blocking until the scan is complete. Assign the returned
     * {@link ScanResult} in a try-with-resources statement, or close it when finished.
     *
     * @return a {@link ScanResult} object representing the result of the scan.
     * @throws ClassGraphException
     *             if any thread of the scan throws an uncaught exception, or the scan was interrupted.
     * @throws IllegalArgumentException
     *             if a config option was enabled without its required companion option.
     */
    public ScanResult scan() {
        return scan(DEFAULT_NUM_WORKER_THREADS);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get a {@link ScanResult} that can be used for determining the classpath.
     *
     * @param executorService
     *            The executor service.
     * @return a {@link ScanResult} object representing the result of the scan (can only be used for determining
     *         classpath).
     * @throws ClassGraphException
     *             if any thread of the scan throws an uncaught exception, or the scan was interrupted.
     */
    ScanResult getClasspathScanResult(final AutoCloseableExecutorService executorService) {
        return scanOnThisThread(/* performScan = */ false, executorService, DEFAULT_NUM_WORKER_THREADS);
    }

    /**
     * Find the classpath elements and modules without scanning them, then read the result with the given getter.
     *
     * @param <T>
     *            the type of the value read from the {@link ScanResult}.
     * @param getter
     *            reads the value from the {@link ScanResult}, which is closed after the getter returns.
     * @return the value returned by the getter.
     * @throws ClassGraphException
     *             if any thread of the scan throws an uncaught exception, or the scan was interrupted.
     * @throws IllegalArgumentException
     *             if a config option was enabled without its required companion option.
     */
    private <T> T withClasspathScanResult(final Function<ScanResult, T> getter) {
        try (var executorService = new AutoCloseableExecutorService(DEFAULT_NUM_WORKER_THREADS);
                var scanResult = getClasspathScanResult(executorService)) {
            return getter.apply(scanResult);
        }
    }

    /**
     * Returns the list of unique {@link File} objects representing the enabled directory and zip/jarfile classpath
     * elements, in classloader resolution order. Excludes non-existent files/directories, classpath elements that
     * have no backing {@link File} (such as nested jars or in-memory filesystems; see {@link #getClasspathURIs()}),
     * and temporary files downloaded from remote jar URLs.
     *
     * @return a {@code List<File>} consisting of the unique enabled directories and jarfiles, in classpath
     *         resolution order.
     * @throws ClassGraphException
     *             if any thread of the scan throws an uncaught exception, or the scan was interrupted.
     * @throws IllegalArgumentException
     *             if a config option was enabled without its required companion option.
     */
    public @Unmodifiable List<File> getClasspathFiles() {
        // A jarfile downloaded from a URL into a temporary file is listed by that file, which is deleted when the
        // ScanResult is closed, before this returns, so a file that is gone by then is left out
        return withClasspathScanResult(ScanResult::getClasspathFiles).stream().filter(File::exists).toList();
    }

    /**
     * Returns the unique enabled directory and zip/jarfile classpath elements, in classloader resolution order, as
     * a path-separated classpath string (see {@link #getClasspathFiles()}). Does not include package roots or
     * nested jars within jars; call {@link #getClasspathURIs()} for full URIs.
     *
     * @return a classpath path string consisting of the unique enabled directories and jarfiles, in classpath
     *         resolution order.
     * @throws ClassGraphException
     *             if any thread of the scan throws an uncaught exception, or the scan was interrupted.
     * @throws IllegalArgumentException
     *             if a config option was enabled without its required companion option.
     */
    public String getClasspath() {
        return PathList.join(getClasspathFiles());
    }

    /**
     * Returns the ordered list of unique {@link URI} objects representing the enabled existing directory/jar
     * classpath elements and modules.
     *
     * @return the unique enabled classpath elements and modules, as a list of {@link URI} objects.
     * @throws ClassGraphException
     *             if any thread of the scan throws an uncaught exception, or the scan was interrupted.
     * @throws IllegalArgumentException
     *             if a config option was enabled without its required companion option.
     */
    public @Unmodifiable List<URI> getClasspathURIs() {
        return withClasspathScanResult(ScanResult::getClasspathURIs);
    }

    /**
     * Returns the ordered list of unique {@link URL} objects representing the enabled existing directory/jar
     * classpath elements and modules (excluding modules with a null or {@code jrt:} location URI).
     *
     * @return the unique enabled classpath elements and modules, as a list of {@link URL} objects.
     * @throws ClassGraphException
     *             if any thread of the scan throws an uncaught exception, or the scan was interrupted.
     * @throws IllegalArgumentException
     *             if a config option was enabled without its required companion option.
     */
    public @Unmodifiable List<URL> getClasspathURLs() {
        return withClasspathScanResult(ScanResult::getClasspathURLs);
    }

    /**
     * Returns the {@link ModuleReference} for each enabled module that passes module accept/reject criteria.
     *
     * @return a list of the {@link ModuleReference} for each enabled module.
     * @throws ClassGraphException
     *             if any thread of the scan throws an uncaught exception, or the scan was interrupted.
     * @throws IllegalArgumentException
     *             if a config option was enabled without its required companion option.
     */
    public @Unmodifiable List<ModuleReference> getModuleReferences() {
        return withClasspathScanResult(ScanResult::getModuleReferences);
    }

    /**
     * Get the module path info provided on the command line with {@code --module-path}, {@code --add-modules},
     * {@code --patch-module}, {@code --add-exports}, {@code --add-opens}, and {@code --add-reads} (equivalent to
     * {@link ModulePathInfo#current()}). Use {@link #getModuleReferences()} to get the modules enabled for
     * scanning.
     *
     * @return The {@link ModulePathInfo}.
     */
    // An instance method, so that the module path info is reached the same way as the classpath and the modules
    @SuppressWarnings("static-method")
    public ModulePathInfo getModulePathInfo() {
        return ModulePathInfo.current();
    }
}
