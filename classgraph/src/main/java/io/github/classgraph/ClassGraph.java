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
import java.io.InputStream;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Predicate;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.base.internal.filter.AcceptReject;
import io.github.classgraph.base.internal.path.PathList;
import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.base.internal.utils.VersionFinder;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ModulePathInfo;
import io.github.classgraph.classpath.internal.CallStack;
import io.github.classgraph.classpath.internal.ScanSourceSpec;
import org.jspecify.annotations.Nullable;

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
 * The methods that say where to scan come in pairs: the method with no arguments enables the sources found in the
 * current runtime environment ({@link #enableClasspath()}, {@link #enableModules()},
 * {@link #enableSystemModules()}, {@link #enableNonSystemModules()}), and the method that takes varargs enables
 * exactly the sources it is given ({@link #enableClassLoaders(ClassLoader...)},
 * {@link #enableModuleLayers(ModuleLayer...)}, {@link #enableClasspathEntries(Object...)}). Calling only the
 * varargs method scans only what it names, which is how the environment's own sources are left out.
 *
 * <p>
 * The classpath sources are scanned in the order they were enabled in, and the modules are scanned before all of
 * them, since that is the order in which the JVM resolves a class. Narrow what is reached from an enabled source
 * with {@link #ignoreParentClassLoaders()}, {@link #ignoreParentModuleLayers()}, {@link #disableJarScanning()} and
 * {@link #disableDirScanning()}. After a scan, {@link ScanResult#getClasspathURIs()} and
 * {@link ScanResult#getModuleReferences()} report exactly which classpath elements and modules were scanned.
 *
 * <p>
 * Documentation: <a href= "https://github.com/classgraph/classgraph/wiki">
 * https://github.com/classgraph/classgraph/wiki</a>
 */
public class ClassGraph {
    /** The scanning specification. */
    ScanSpec scanSpec = new ScanSpec();

    /**
     * The places that classpath elements and modules are looked for. Held separately from the {@link ScanSpec}, so
     * that a {@link ScanResult} cannot keep a classloader or a module layer alive (a {@link ScanResult} holds its
     * {@link ScanSpec}, but never this).
     */
    final ScanSourceSpec scanSourceSpec = new ScanSourceSpec();

    /**
     * The default number of worker threads to use while scanning. This number gave the best results on a relatively
     * modern laptop with SSD, while scanning a large classpath.
     */
    static final int DEFAULT_NUM_WORKER_THREADS = Math.max(
            // Always scan with at least 2 threads
            2, //
            (int) Math.ceil(
                    // Num IO threads (top out at 4, since most I/O devices won't scale better than this)
                    Math.min(4.0, Runtime.getRuntime().availableProcessors() * 0.75) +
                    // Num scanning threads (higher than available processors, because some threads can be blocked)
                            Runtime.getRuntime().availableProcessors() * 1.25) //
    );

    /**
     * The default maximum length of time to wait for a worker thread to finish. This is long enough that a healthy
     * scan will never hit it, but short enough that a scan that can never finish is reported rather than hanging
     * forever.
     */
    static final Duration DEFAULT_WORKER_TIMEOUT = Duration.ofMinutes(1);

    /** The Maven {@code groupId} of the artifact this class is packaged in. */
    private static final String MAVEN_GROUP_ID = "io.github.classgraph";

    /** The Maven {@code artifactId} of the artifact this class is packaged in. */
    private static final String MAVEN_ARTIFACT_ID = "classgraph";

    /**
     * The URL schemes a scan does not fetch a jarfile from unless asked to. These are the schemes that every JVM
     * can already fetch over a network, so a classpath element naming one is read from the network by default,
     * which is not something a scan should do with a path it was merely handed.
     */
    private static final String[] DENIED_URL_SCHEMES = { "http", "https", "ftp", "mailto" };

    /**
     * If non-null, log while scanning.
     */
    private @Nullable LogNode topLevelLog;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Construct a ClassGraph instance.
     *
     * <p>
     * A scan reads whatever the classpath names, and a classpath is not always something the caller wrote, so the
     * URL schemes that every JVM can fetch over a network are denied to begin with: a jarfile is not downloaded
     * from an {@code http:}, {@code https:}, {@code ftp:} or {@code mailto:} URL unless
     * {@link #enableURLScheme(String)} or {@link #enableRemoteJarScanning()} asks for it. Every other scheme is
     * read as found, including one that an application registered a {@link java.net.URLStreamHandler} or a
     * {@link java.nio.file.spi.FileSystemProvider} for, since registering one is what says those URLs are meant to
     * be read.
     */
    public ClassGraph() {
        for (final String scheme : DENIED_URL_SCHEMES) {
            scanSpec.vfsSpec.disableURLScheme(scheme);
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
     * Switches on verbose logging to System.err.
     *
     * @return this (for method chaining).
     */
    public ClassGraph verbose() {
        if (topLevelLog == null) {
            topLevelLog = new LogNode();
        }
        return this;
    }

    /**
     * Switches on verbose logging to System.err if verbose is true.
     *
     * @param verbose
     *            if true, enable verbose logging.
     * @return this (for method chaining).
     */
    public ClassGraph verbose(final boolean verbose) {
        if (verbose) {
            verbose();
        }
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Enables the scanning of all classes, fields, methods, annotations, and static final field constant
     * initializer values, and ignores all visibility modifiers, so that both public and non-public classes, fields
     * and methods are all scanned.
     *
     * <p>
     * Calls {@link #enableClassInfo()}, {@link #enableFieldInfo()}, {@link #enableMethodInfo()},
     * {@link #enableAnnotationInfo()}, {@link #enableStaticFinalFieldConstantInitializerValues()},
     * {@link #ignoreClassVisibility()}, {@link #ignoreFieldVisibility()}, and {@link #ignoreMethodVisibility()}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableAllInfo() {
        enableClassInfo();
        enableFieldInfo();
        enableMethodInfo();
        enableAnnotationInfo();
        enableStaticFinalFieldConstantInitializerValues();
        ignoreClassVisibility();
        ignoreFieldVisibility();
        ignoreMethodVisibility();
        return this;
    }

    /**
     * Enables the scanning of classfiles, producing {@link ClassInfo} objects in the {@link ScanResult}. Implicitly
     * disables {@link #enableMultiReleaseVersions()}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableClassInfo() {
        scanSpec.enableClassInfo = true;
        scanSpec.vfsSpec.disableMultiReleaseVersions();
        return this;
    }

    /**
     * Causes class visibility to be ignored, enabling private, package-private and protected classes to be scanned.
     * By default, only public classes are scanned. (Automatically calls {@link #enableClassInfo()}.)
     *
     * @return this (for method chaining).
     */
    public ClassGraph ignoreClassVisibility() {
        enableClassInfo();
        scanSpec.ignoreClassVisibility = true;
        return this;
    }

    /**
     * Enables the saving of method info during the scan. This information can be obtained using
     * {@link ClassInfo#getMethodInfo()} etc. By default, method info is not scanned. (Automatically calls
     * {@link #enableClassInfo()}.)
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableMethodInfo() {
        enableClassInfo();
        scanSpec.enableMethodInfo = true;
        return this;
    }

    /**
     * Causes method visibility to be ignored, enabling private, package-private and protected methods to be
     * scanned. By default, only public methods are scanned. (Automatically calls {@link #enableClassInfo()} and
     * {@link #enableMethodInfo()}.)
     *
     * @return this (for method chaining).
     */
    public ClassGraph ignoreMethodVisibility() {
        enableClassInfo();
        enableMethodInfo();
        scanSpec.ignoreMethodVisibility = true;
        return this;
    }

    /**
     * Enables the saving of field info during the scan. This information can be obtained using
     * {@link ClassInfo#getFieldInfo()}. By default, field info is not scanned. (Automatically calls
     * {@link #enableClassInfo()}.)
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableFieldInfo() {
        enableClassInfo();
        scanSpec.enableFieldInfo = true;
        return this;
    }

    /**
     * Causes field visibility to be ignored, enabling private, package-private and protected fields to be scanned.
     * By default, only public fields are scanned. (Automatically calls {@link #enableClassInfo()} and
     * {@link #enableFieldInfo()}.)
     *
     * @return this (for method chaining).
     */
    public ClassGraph ignoreFieldVisibility() {
        enableClassInfo();
        enableFieldInfo();
        scanSpec.ignoreFieldVisibility = true;
        return this;
    }

    /**
     * Enables the saving of static final field constant initializer values. By default, constant initializer values
     * are not scanned. If this is enabled, you can obtain the constant field initializer values from
     * {@link FieldInfo#getConstantInitializerValue()}.
     *
     * <p>
     * Note that constant initializer values are usually only of primitive type, or String constants (or values that
     * can be computed and reduced to one of those types at compiletime).
     *
     * <p>
     * Also note that it is up to the compiler as to whether or not a constant-valued field is assigned as a
     * constant in the field definition itself, or whether it is assigned manually in static class initializer
     * blocks -- so your mileage may vary in being able to extract constant initializer values.
     *
     * <p>
     * In fact in Kotlin, even constant initializers for non-static / non-final fields are stored in a field
     * attribute in the classfile (and so these values may be picked up by ClassGraph by calling this method),
     * although any field initializers for non-static fields are supposed to be ignored by the JVM according to the
     * classfile spec, so the Kotlin compiler may change in future to stop generating these values, and you probably
     * shouldn't rely on being able to get the initializers for non-static fields in Kotlin. (As far as non-final
     * fields, javac simply does not add constant initializer values to the field attributes list for non-final
     * fields, even if they are static, but the spec doesn't say whether or not the JVM should ignore constant
     * initializers for non-final fields.)
     *
     * <p>
     * Automatically calls {@link #enableClassInfo()} and {@link #enableFieldInfo()}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableStaticFinalFieldConstantInitializerValues() {
        enableClassInfo();
        enableFieldInfo();
        scanSpec.enableStaticFinalFieldConstantInitializerValues = true;
        return this;
    }

    /**
     * Enables the saving of annotation info (for class, field, method and method parameter annotations) during the
     * scan. This information can be obtained using {@link ClassInfo#getAllAnnotationInfo()},
     * {@link FieldInfo#getAllAnnotationInfo()}, and {@link MethodParameterInfo#getAllAnnotationInfo()}. By default,
     * annotation info is not scanned. (Automatically calls {@link #enableClassInfo()}.)
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableAnnotationInfo() {
        enableClassInfo();
        scanSpec.enableAnnotationInfo = true;
        return this;
    }

    /**
     * Enables the determination of inter-class dependencies, which may be read by calling
     * {@link ClassInfo#getClassDependencies()}, {@link ScanResult#getClassDependencyMap()} or
     * {@link ScanResult#getReverseClassDependencyMap()}. (Automatically calls {@link #enableClassInfo()},
     * {@link #enableFieldInfo()}, {@link #enableMethodInfo()}, {@link #enableAnnotationInfo()},
     * {@link #ignoreClassVisibility()}, {@link #ignoreFieldVisibility()} and {@link #ignoreMethodVisibility()}.)
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableInterClassDependencies() {
        enableClassInfo();
        enableFieldInfo();
        enableMethodInfo();
        enableAnnotationInfo();
        ignoreClassVisibility();
        ignoreFieldVisibility();
        ignoreMethodVisibility();
        scanSpec.enableInterClassDependencies = true;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Causes only runtime visible annotations to be scanned (causes runtime invisible annotations to be ignored).
     * (Automatically calls {@link #enableClassInfo()}.)
     *
     * @return this (for method chaining).
     */
    public ClassGraph disableRuntimeInvisibleAnnotations() {
        enableClassInfo();
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
     * Causes ClassGraph to return classes that are not in the accepted packages, but that are directly referred to
     * by classes within accepted packages as a superclass, implemented interface or annotation. (Automatically
     * calls {@link #enableClassInfo()}.)
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableExternalClasses() {
        enableClassInfo();
        scanSpec.enableExternalClasses = true;
        return this;
    }

    /**
     * Remove temporary files, including nested jarfiles (jarfiles within jarfiles, which have to be extracted
     * during scanning in order to be read) from their temporary directory as soon as the scan has completed. The
     * default is for temporary files to be removed when the {@link ScanResult} is closed, or failing that, on JVM
     * exit.
     *
     * <p>
     * N.B. if the scan did extract a nested jarfile to a temporary file, then removing that temporary file requires
     * closing the extracted jarfile that was read from it, so the {@link ScanResult} will not be able to read
     * resources or load classes from any nested jar after the scan returns. If no nested jars were encountered, no
     * temporary files are created, and the {@link ScanResult} remains fully usable.
     *
     * @return this (for method chaining).
     */
    // #916
    public ClassGraph removeTemporaryFilesAfterScan() {
        scanSpec.removeTemporaryFilesAfterScan = true;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan every classpath element of every ClassLoader that can be found in the current runtime environment.
     *
     * <p>
     * A ClassLoader is found if it is any of the following:
     *
     * <ul>
     * <li>the context ClassLoader of the calling thread, as returned by {@link Thread#getContextClassLoader()
     * Thread.currentThread().getContextClassLoader()};</li>
     * <li>the ClassLoader that loaded ClassGraph itself;</li>
     * <li>the system ClassLoader, as returned by {@link ClassLoader#getSystemClassLoader()} -- this is the
     * application ClassLoader, unless the JVM was launched with {@code -Djava.system.class.loader};</li>
     * <li>the ClassLoader of the class in any frame of the current call stack, so that the ClassLoader of the code
     * that called ClassGraph is scanned even when it is none of the above; or</li>
     * <li>an ancestor of any of those, reached through {@link ClassLoader#getParent()}.</li>
     * </ul>
     *
     * <p>
     * Every classpath element that every one of those ClassLoaders loads classes from is scanned, whether or not
     * the ClassLoader exposes it publicly -- see
     * <a href="https://github.com/classgraph/classgraph/wiki/Classpath-Specification-Mechanisms">Classpath
     * specification mechanisms</a> for how each supported ClassLoader is read. Classpath elements are scanned in
     * the order in which the ClassLoaders that declared them would be asked to load a class, so a class that
     * appears on the classpath more than once is reported from the copy the JVM would actually load. Each classpath
     * element is scanned only once, however many of the ClassLoaders declare it.
     *
     * <p>
     * The application ClassLoader is normally one of them, so its own classpath entries -- the ones that the
     * {@code java.class.path} system property lists -- are scanned too, at the position the application ClassLoader
     * takes in that order.
     *
     * <p>
     * This method takes no arguments, because it scans what is in the environment. To scan specific ClassLoaders or
     * specific classpath elements instead, call {@link #enableClassLoaders(ClassLoader...)} or
     * {@link #enableClasspathEntries(Object...)} and do not call this method. Calling both scans the environment as
     * well as what you named.
     *
     * <p>
     * This method does not enable the scanning of modules. Classes in modules are reached by
     * {@link #enableModules()}, {@link #enableSystemModules()}, {@link #enableNonSystemModules()} or
     * {@link #enableModuleLayers(ModuleLayer...)}, and modules are always scanned before the classpath.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableClasspath() {
        scanSourceSpec.enableClasspath();
        return this;
    }

    /**
     * Scan the classpath elements declared by the given ClassLoaders, and by their parents, rather than by the
     * ClassLoaders found in the current runtime environment. Call {@link #enableClasspath()} as well to scan both.
     *
     * <p>
     * You may want to use this together with {@link #ignoreParentClassLoaders()}, so that classpath entries are
     * obtained only from the ClassLoaders you passed in, and not from their parent ClassLoaders.
     *
     * <p>
     * The JDK's own application and platform ClassLoaders do not expose the locations they load classes from, so
     * they cannot be scanned as ClassLoaders. The application ClassLoader's own classpath entries are still found,
     * since its handler falls back to the {@code java.class.path} system property, but the platform ClassLoader
     * loads only from the system modules, so {@link #enableSystemModules()} is what reaches its classes.
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
     * Scan the given classpath, with path elements separated by {@link java.io.File#pathSeparatorChar}. No
     * ClassLoader is asked for it, so nothing else is scanned unless it is enabled as well.
     *
     * @param classpath
     *            The classpath to scan, with path elements separated by {@link java.io.File#pathSeparatorChar}.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code classpath} is empty.
     */
    public ClassGraph enableClasspathEntries(final String classpath) {
        Assert.notNull(classpath, "classpath");
        scanSourceSpec.enableClasspathEntries(
                List.of(PathList.split(classpath, scanSpec.classpathSpec.allowedURLSchemes)));
        return this;
    }

    /**
     * Scan the given classpath entries. No ClassLoader is asked for them, so nothing else is scanned unless it is
     * enabled as well.
     *
     * <p>
     * Works for Iterables of any type whose toString() method resolves to a classpath element string, e.g. String,
     * File or Path. Each element is one classpath entry, and is not split on {@link java.io.File#pathSeparatorChar}
     * -- pass the {@link String} overload for a path that needs splitting.
     *
     * <p>
     * A single {@link Path} is treated as one classpath entry, not as a sequence of its name elements.
     *
     * @param classpathElements
     *            The classpath entries to scan, one entry per element.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code classpathElements} is empty, or if any element is a {@link ClassLoader} (pass those to
     *             {@link #enableClassLoaders(ClassLoader...)} instead).
     */
    public ClassGraph enableClasspathEntries(final Iterable<?> classpathElements) {
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
     * Scan the given classpath entries. No ClassLoader is asked for them, so nothing else is scanned unless it is
     * enabled as well.
     *
     * <p>
     * Works for arrays of any member type whose toString() method resolves to a classpath element string, e.g.
     * String, File or Path. Each element is one classpath entry, and is not split on
     * {@link java.io.File#pathSeparatorChar} -- pass the {@link String} overload for a path that needs splitting.
     *
     * @param classpathElements
     *            The classpath entries to scan, one entry per element.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code classpathElements} is empty, or if any element is a {@link ClassLoader} (pass those to
     *             {@link #enableClassLoaders(ClassLoader...)} instead).
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
     * @param classpathElementFilter
     *            The filter to apply to the path string of each discovered classpath element. The path string is
     *            normalized so that the path separator is '/'. It will usually be a file path, but could be a URL,
     *            or it could be a path for a nested jar, where the jarfile is separated from the path within it by
     *            "!/", as the "jar:" URL scheme requires. "jar:" and/or "file:" will have been stripped from the
     *            beginning, if they were present in the classpath.
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
     * @param classpathElementURLFilter
     *            The filter to apply to the {@link URL} of each discovered classpath element.
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
     * @return this (for method chaining).
     */
    public ClassGraph ignoreParentClassLoaders() {
        scanSpec.classpathSpec.ignoreParentClassLoaders = true;
        return this;
    }

    /**
     * Register a {@link ClassLoaderHandler}, which teaches ClassGraph how to read the classpath out of a
     * {@link ClassLoader} that it does not already know about.
     *
     * <p>
     * ClassGraph ships with handlers for the classloaders of the common application servers, build tools and
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
    public ClassGraph registerClassLoaderHandler(final ClassLoaderHandler classLoaderHandler) {
        Assert.notNull(classLoaderHandler, "classLoaderHandler");
        scanSpec.classpathSpec.classLoaderHandlers.add(classLoaderHandler);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan the system modules ({@code java.*}, {@code jdk.*}, {@code javafx.*}, {@code oracle.*}) of the
     * ModuleLayers that are visible from the caller: the layers of the classes on the call stack, and the boot
     * layer.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableSystemModules() {
        scanSourceSpec.enableDetectedModuleLayers();
        scanSpec.classpathSpec.scanSystemModules = true;
        return this;
    }

    /**
     * Scan the non-system modules of the ModuleLayers that are visible from the caller: the layers of the classes
     * on the call stack, and the boot layer.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableNonSystemModules() {
        scanSourceSpec.enableDetectedModuleLayers();
        scanSpec.classpathSpec.scanNonSystemModules = true;
        return this;
    }

    /**
     * Scan the modules of both kinds, system and non-system, of the ModuleLayers that are visible from the caller:
     * the layers of the classes on the call stack, and the boot layer.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableModules() {
        return enableSystemModules().enableNonSystemModules();
    }

    /**
     * Scan the non-system modules of the given ModuleLayers, and of their parent layers, rather than of the
     * ModuleLayers that are visible from the caller. Use this method if you define your own ModuleLayer, but the
     * scanning code is not running within it. Call {@link #enableModules()} as well to scan both, or
     * {@link #enableSystemModules()} as well to scan the system modules of the given layers too.
     *
     * @param moduleLayers
     *            The ModuleLayers to scan.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if no ModuleLayer is given.
     */
    public ClassGraph enableModuleLayers(final ModuleLayer... moduleLayers) {
        scanSourceSpec.enableModuleLayers(moduleLayers);
        scanSpec.classpathSpec.scanNonSystemModules = true;
        return this;
    }

    /**
     * Ignore parent module layers (i.e. only scan module layers that are not the parent of another module layer).
     *
     * @return this (for method chaining).
     */
    public ClassGraph ignoreParentModuleLayers() {
        scanSpec.classpathSpec.ignoreParentModuleLayers = true;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan one or more specific packages and their sub-packages.
     *
     * <p>
     * N.B. Automatically calls {@link #enableClassInfo()} -- call {@link #acceptPaths(String...)} instead if you
     * only need to scan resources.
     *
     * @param packageNames
     *            The fully-qualified names of packages to scan (using '.' as a separator). May include glob
     *            wildcards: {@code '*'} matches within a single package segment only, and {@code "**"}, used as a
     *            complete segment, matches zero or more package segments, e.g. {@code "com.**.internal"} matches
     *            {@code com.internal}, {@code com.a.internal} and {@code com.a.b.internal}. Any number of wildcards
     *            may be used, e.g. {@code "com.*.internal.*"}. Sub-packages of a matched package are also scanned,
     *            so a trailing {@code ".**"} is accepted but redundant. Note that a {@code '*'} wildcard must match
     *            at least one package segment, so {@code "java.awt.*"} matches the sub-packages of {@code java.awt}
     *            but not {@code java.awt} itself -- to scan {@code java.awt} and everything below it, use
     *            {@code "java.awt"}.
     * @return this (for method chaining).
     */
    public ClassGraph acceptPackages(final String... packageNames) {
        Assert.notNullElements(packageNames, "packageNames");
        enableClassInfo();
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
     * @param paths
     *            The paths to scan, relative to the package root of the classpath element (with '/' as a
     *            separator). May include glob wildcards: {@code '*'} matches within a single path segment only, and
     *            {@code "**"}, used as a complete segment, matches zero or more whole path segments. Any number of
     *            wildcards may be used. Sub-directories of a matched path are also scanned, so a trailing
     *            {@code "/**"} is accepted but redundant.
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
     * N.B. Automatically calls {@link #enableClassInfo()} -- call {@link #acceptPathsNonRecursive(String...)}
     * instead if you only need to scan resources.
     *
     * <p>
     * This may be particularly useful for scanning the package root ("") without recursively scanning everything in
     * the jar, dir or module.
     *
     * @param packageNames
     *            The fully-qualified names of packages to scan (with '.' as a separator). May not include a glob
     *            wildcard.
     *
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if any package name contains a glob wildcard.
     */
    public ClassGraph acceptPackagesNonRecursive(final String... packageNames) {
        Assert.notNullElements(packageNames, "packageNames");
        enableClassInfo();
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
     * This may be particularly useful for scanning the package root ("") without recursively scanning everything in
     * the jar, dir or module.
     *
     * @param paths
     *            The paths to scan, relative to the package root of the classpath element (with '/' as a
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
     * N.B. Automatically calls {@link #enableClassInfo()} -- call {@link #rejectPaths(String...)} instead if you
     * only need to scan resources.
     *
     * @param packageNames
     *            The fully-qualified names of packages to reject (using '.' as a separator). May include glob
     *            wildcards: {@code '*'} matches within a single package segment only, and {@code "**"}, used as a
     *            complete segment, matches zero or more package segments, e.g. {@code "com.**.internal"} matches
     *            {@code com.internal}, {@code com.a.internal} and {@code com.a.b.internal}. Any number of wildcards
     *            may be used, e.g. {@code "com.*.internal.*"}. Sub-packages of a matched package are also rejected,
     *            so a trailing {@code ".**"} is accepted but redundant. Note that a {@code '*'} wildcard must match
     *            at least one package segment, so {@code "java.awt.*"} matches the sub-packages of {@code java.awt}
     *            but not {@code java.awt} itself -- to reject {@code java.awt} and everything below it, use
     *            {@code "java.awt"}.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if any package name is the root package ({@code ""}), which would reject everything, leaving
     *             nothing to scan.
     */
    public ClassGraph rejectPackages(final String... packageNames) {
        Assert.notNullElements(packageNames, "packageNames");
        enableClassInfo();
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
     *            The paths to reject (with '/' as a separator). May include glob wildcards: {@code '*'} matches
     *            within a single path segment only, and {@code "**"}, used as a complete segment, matches zero or
     *            more whole path segments. Any number of wildcards may be used. Sub-directories of a matched path
     *            are also rejected, so a trailing {@code "/**"} is accepted but redundant.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if any path is the package root ({@code ""} or {@code "/"}), which would reject everything,
     *             leaving nothing to scan.
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
     * N.B. Automatically calls {@link #enableClassInfo()}.
     *
     *
     * @param classNames
     *            The fully-qualified names of classes to scan (using '.' as a separator). May contain glob
     *            wildcards, where {@code '*'} matches within a single package or class name segment, {@code "**"}
     *            matches zero or more whole packages, and {@code '?'} matches one character. To match a class name
     *            by glob in any package, you must include a package glob too, e.g. {@code "**.*Suffix"}.
     * @return this (for method chaining).
     */
    public ClassGraph acceptClasses(final String... classNames) {
        Assert.notNullElements(classNames, "classNames");
        enableClassInfo();
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
     * Specifically reject one or more specific classes, preventing them from being scanned even if they are in a
     * accepted package.
     *
     * <p>
     * N.B. Automatically calls {@link #enableClassInfo()}.
     *
     * @param classNames
     *            The fully-qualified names of classes to reject (using '.' as a separator). May contain glob
     *            wildcards, where {@code '*'} matches within a single package or class name segment, {@code "**"}
     *            matches zero or more whole packages, and {@code '?'} matches one character. To match a class name
     *            by glob in any package, you must include a package glob too, e.g. {@code "**.*Suffix"}.
     * @return this (for method chaining).
     */
    public ClassGraph rejectClasses(final String... classNames) {
        Assert.notNullElements(classNames, "classNames");
        enableClassInfo();
        for (final String className : classNames) {
            final var classNameNormalized = AcceptReject.normalizePackageOrClassName(className);
            scanSpec.classAcceptReject.addToReject(classNameNormalized);
            scanSpec.classfilePathAcceptReject
                    .addToReject(ClassNames.classNameToClassfilePath(classNameNormalized));
        }
        return this;
    }

    /**
     * Accept one or more jars. This will cause only the accepted jars to be scanned.
     *
     * @param jarLeafNames
     *            The leafnames of the jars that should be scanned (e.g. {@code "mylib.jar"}), matched ignoring
     *            case. May contain glob wildcards, where {@code '*'} matches zero or more characters
     *            ({@code "mylib-*.jar"}) and {@code '?'} matches one character.
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
     * Reject one or more jars, preventing them from being scanned.
     *
     * @param jarLeafNames
     *            The leafnames of the jars that should not be scanned (e.g. {@code "badlib.jar"}), matched ignoring
     *            case. May contain glob wildcards, where {@code '*'} matches zero or more characters
     *            ({@code "badlib-*.jar"}) and {@code '?'} matches one character.
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
     * Accept one or more modules for scanning. If any module is accepted, only the accepted modules are scanned
     * (any jars and directories on the classpath are still scanned, unless they are excluded by other criteria).
     *
     * <p>
     * This narrows what is scanned; it does not enable the scanning of modules. Call {@link #enableModules()},
     * {@link #enableSystemModules()}, {@link #enableNonSystemModules()} or
     * {@link #enableModuleLayers(ModuleLayer...)} to say which modules are looked for in the first place.
     *
     * @param moduleNames
     *            The names of the modules that should be scanned. May contain glob wildcards, where {@code '*'}
     *            matches within a single module name segment, {@code "**"} matches zero or more whole segments
     *            (e.g. {@code "jdk.**"} matches every module whose name starts with {@code "jdk."}), and
     *            {@code '?'} matches one character.
     * @return this (for method chaining).
     */
    // #658
    public ClassGraph acceptModules(final String... moduleNames) {
        Assert.notNullElements(moduleNames, "moduleNames");
        for (final String moduleName : moduleNames) {
            scanSpec.classpathSpec.moduleAcceptReject
                    .addToAccept(AcceptReject.normalizePackageOrClassName(moduleName));
        }
        return this;
    }

    /**
     * Reject one or more modules, preventing them from being scanned.
     *
     * @param moduleNames
     *            The names of the modules that should not be scanned. May contain glob wildcards, where {@code '*'}
     *            matches within a single module name segment, {@code "**"} matches zero or more whole segments, and
     *            {@code '?'} matches one character. Rejecting a system module leaves the other system modules
     *            scannable, if {@link #enableSystemModules()} was called.
     * @return this (for method chaining).
     */
    // #658
    public ClassGraph rejectModules(final String... moduleNames) {
        Assert.notNullElements(moduleNames, "moduleNames");
        for (final String moduleName : moduleNames) {
            scanSpec.classpathSpec.moduleAcceptReject
                    .addToReject(AcceptReject.normalizePackageOrClassName(moduleName));
        }
        return this;
    }

    /**
     * Accept classpath elements based on resource paths. Only classpath elements that contain resources with paths
     * matching the accept will be scanned.
     *
     * @param resourcePaths
     *            The resource paths, any of which must be present in a classpath element for the classpath element
     *            to be scanned. May contain glob wildcards, where {@code '*'} matches within a single path segment,
     *            {@code "**"} matches zero or more whole path segments (e.g. {@code "META-INF/**"}), and
     *            {@code '?'} matches one character.
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
     * Reject classpath elements based on resource paths. Classpath elements that contain resources with paths
     * matching the reject will not be scanned.
     *
     * @param resourcePaths
     *            The resource paths which cause a classpath not to be scanned if any are present in a classpath
     *            element for the classpath element. May contain glob wildcards, where {@code '*'} matches within a
     *            single path segment, {@code "**"} matches zero or more whole path segments, and {@code '?'}
     *            matches one character.
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
     * Enable classpath elements to be fetched from remote ({@code "http:"}/{@code "https:"}) URLs. Equivalent to:
     *
     * <p>
     * {@code new ClassGraph().enableURLScheme("http").enableURLScheme("https");}
     *
     * <p>
     * Scanning from http(s) URLs is disabled by default, as downloading and reading jars from a remote server may
     * present a security vulnerability. A custom URL scheme needs no enabling -- see
     * {@link #enableURLScheme(String)}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableRemoteJarScanning() {
        scanSpec.classpathSpec.enableURLScheme("http");
        scanSpec.vfsSpec.enableURLScheme("http");
        scanSpec.classpathSpec.enableURLScheme("https");
        scanSpec.vfsSpec.enableURLScheme("https");
        return this;
    }

    /**
     * Enable classpath elements to be fetched from {@link URL} connections with the specified URL scheme.
     *
     * <p>
     * Only {@code http}, {@code https}, {@code ftp} and {@code mailto} have to be enabled this way -- see
     * {@link #ClassGraph()}. A scheme that the JVM can open only because an application registered a
     * {@link java.net.URLStreamHandler} or a {@link java.nio.file.spi.FileSystemProvider} for it is already read as
     * found. Naming one here is still worth doing if classpath elements with that scheme arrive in a
     * {@code ':'}-separated classpath string such as {@code java.class.path}, since the scheme's own {@code ':'}
     * would otherwise be read as a separator and split the path element in two.
     *
     * @param scheme
     *            the URL scheme string, e.g. "resource" for a custom "resource:" URL scheme. The scheme name only,
     *            without the trailing {@code ':'}.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if the scheme is shorter than two characters (a one-character scheme cannot be told apart from a
     *             Windows drive letter), or is not a valid URL scheme.
     */
    public ClassGraph enableURLScheme(final String scheme) {
        Assert.notNull(scheme, "scheme");
        scanSpec.classpathSpec.enableURLScheme(scheme);
        scanSpec.vfsSpec.enableURLScheme(scheme);
        return this;
    }

    /**
     * Refuse to fetch a jarfile from a classpath element named by a {@link URL} with the specified URL scheme. The
     * classpath element is still reported, but the jarfile it names is not read, so neither its classes nor the
     * classpath elements its manifest declares are found.
     *
     * <p>
     * {@code http}, {@code https}, {@code ftp} and {@code mailto} are refused already -- see {@link #ClassGraph()}.
     * This adds a scheme to those.
     *
     * @param scheme
     *            the URL scheme string, e.g. "s3" for an "s3:" URL scheme. The scheme name only, without the
     *            trailing {@code ':'}.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if the scheme is shorter than two characters (a one-character scheme cannot be told apart from a
     *             Windows drive letter), or is not a valid URL scheme.
     */
    public ClassGraph disableURLScheme(final String scheme) {
        Assert.notNull(scheme, "scheme");
        scanSpec.vfsSpec.disableURLScheme(scheme);
        return this;
    }

    /**
     * Enables the scanning of the JRE's own {@code lib} and {@code ext} jars when they are found on the classpath.
     * These are skipped by default for speed, since they hold the system classes of a pre-modular JRE.
     *
     * <p>
     * This is about jars, not modules: call {@link #enableSystemModules()} to scan the system modules.
     *
     * <p>
     * N.B. Automatically calls {@link #enableClassInfo()}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableSystemJars() {
        enableClassInfo();
        scanSpec.classpathSpec.enableSystemJars = true;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The maximum size of an inner (nested) jar that has been deflated (i.e. compressed, not stored) within an
     * outer jar, before it has to be spilled to disk rather than stored in a RAM-backed {@link ByteBuffer} when it
     * is deflated, in order for the inner jar's entries to be read. (Note that this situation of having to deflate
     * a nested jar to RAM or disk in order to read it is rare, because normally adding a jarfile to another jarfile
     * will store the inner jar, rather than deflate it, because deflating a jarfile does not usually produce any
     * further compression gains. If an inner jar is stored, not deflated, then its zip entries can be read directly
     * using ClassGraph's own zipfile central directory parser, which can use file slicing to extract entries
     * directly from stored nested jars.)
     *
     * <p>
     * This is also the maximum size of a jar downloaded from an {@code http://} or {@code https://} classpath
     * {@link URL} to RAM. Once this many bytes have been read from the {@link URL}'s {@link InputStream}, then the
     * RAM contents are spilled over to a temporary file on disk, and the rest of the content is downloaded to the
     * temporary file. (This is also rare, because normally there are no {@code http://} or {@code https://}
     * classpath entries.)
     *
     * <p>
     * Default: 64MB (i.e. writing to disk is avoided wherever possible). Setting a lower max RAM size value will
     * decrease ClassGraph's memory usage if either of the above rare situations occurs.
     *
     * @param maxBufferedJarRAMSize
     *            The max RAM size to use for deflated inner jars or downloaded jars. This is the limit per jar, not
     *            for the whole classpath.
     * @return this (for method chaining).
     */
    public ClassGraph setMaxBufferedJarRAMSize(final int maxBufferedJarRAMSize) {
        scanSpec.vfsSpec.setMaxBufferedJarRAMSize(maxBufferedJarRAMSize);
        return this;
    }

    /**
     * Set the maximum length of time to wait for a worker thread to finish, once the calling thread has run out of
     * work of its own to do. If a worker thread does not finish within this time, the scan throws
     * {@link ClassGraphException} rather than blocking forever.
     *
     * <p>
     * A worker thread that never finishes means the scan cannot complete. The two known causes are a classloading
     * deadlock, where the calling thread holds a lock that the classloader needs in order to load one of
     * ClassGraph's own classes on a worker thread (#933) -- which can be avoided by calling {@link #scan(int)} with
     * a {@code numThreads} of 1, so that nothing is loaded on a worker thread -- and a worker thread blocking
     * indefinitely on a filesystem or network read of a classpath element.
     *
     * <p>
     * Default: 1 minute. Set a longer timeout if a scan of a very large or very slow classpath needs it. A timeout
     * that is zero or negative disables the timeout, so that worker threads are waited for indefinitely.
     *
     * @param workerTimeout
     *            the maximum length of time to wait for a worker thread to finish.
     * @return this (for method chaining).
     */
    public ClassGraph setWorkerTimeout(final Duration workerTimeout) {
        Assert.notNull(workerTimeout, "workerTimeout");
        scanSpec.workerTimeout = workerTimeout;
        return this;
    }

    /**
     * If true, provide all versions of a multi-release resource using their multi-release path prefix, instead of
     * just the one the running JVM would select. Implicitly disables {@link #enableClassInfo()} and all features
     * depending on it.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableMultiReleaseVersions() {
        scanSpec.vfsSpec.enableMultiReleaseVersions();

        scanSpec.enableClassInfo = false;
        scanSpec.ignoreClassVisibility = false;
        scanSpec.enableMethodInfo = false;
        scanSpec.ignoreMethodVisibility = false;
        scanSpec.enableFieldInfo = false;
        scanSpec.ignoreFieldVisibility = false;
        scanSpec.enableStaticFinalFieldConstantInitializerValues = false;
        scanSpec.enableAnnotationInfo = false;
        scanSpec.enableInterClassDependencies = false;
        // N.B. disableRuntimeInvisibleAnnotations is deliberately not set here -- it is only read when
        // enableAnnotationInfo is true, which this method has just disabled, so setting it would have no effect
        // on this scan, but would remain set if the caller subsequently re-enabled annotation info
        scanSpec.enableExternalClasses = false;
        scanSpec.classpathSpec.enableSystemJars = false;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Enables logging by calling {@link #verbose()}, and then sets the logger to "realtime logging mode", where log
     * entries are written out immediately to stderr, rather than only after the scan has completed. Can help to
     * identify problems where scanning is stuck in a loop, or where one scanning step is taking much longer than it
     * should, etc.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableRealtimeLogging() {
        verbose();
        LogNode.logInRealtime(true);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Asynchronously scans the classpath, calling the {@code scanResultProcessor} callback on success or the
     * {@code failureHandler} callback on failure.
     *
     * @param executorService
     *            A custom {@link ExecutorService} to use for scheduling worker tasks.
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning. Ideally the ExecutorService will have at least this many threads available.
     * @param scanResultProcessor
     *            A callback to run on successful scan. It is passed the {@link ScanResult}, and is responsible for
     *            closing it.
     * @param failureHandler
     *            A callback to run on failed scan. It is passed any {@link Throwable} thrown during the scan.
     */
    public void scanAsync(final ExecutorService executorService, final int numParallelTasks,
            final Consumer<ScanResult> scanResultProcessor, final Consumer<Throwable> failureHandler) {
        Assert.notNull(executorService, "executorService");
        // If scanResultProcessor is null, the scan won't do anything after completion, and the ScanResult will
        // simply be lost.
        Assert.notNull(scanResultProcessor, "scanResultProcessor");
        // The result of the Future<ScanObject> object returned by launchAsyncScan is discarded below, so a
        // FailureHandler is required, so that exceptions are not silently swallowed.
        Assert.notNull(failureHandler, "failureHandler");
        // Read the call stack on the calling thread, since it is the caller's classloaders and module layers that
        // are to be searched, not those of the thread that the scan happens to run on
        final var callStack = CallStack.read();
        // Use execute() rather than submit(), since a ScanResultProcessor and FailureHandler are used
        executorService.execute(() -> {
            try {
                // Call scanner, but ignore the returned ScanResult
                new Scanner(/* performScan = */ true, callStack, scanSpec, scanSourceSpec, executorService,
                        numParallelTasks, scanResultProcessor, failureHandler, topLevelLog).call();
            } catch (final Throwable t) {
                // Call failure handler. Anything thrown before the Scanner starts running the scan (e.g. by a
                // user-supplied classpath element filter, which the Scanner constructor calls) has to be caught
                // here too, otherwise it would be thrown on the ExecutorService's thread and lost, and the caller
                // would wait forever for a callback that never comes
                failureHandler.accept(t);
            }
        });
    }

    /**
     * Asynchronously scans the classpath for matching files, returning a {@code Future<ScanResult>}. You should
     * assign the wrapped {@link ScanResult} in a try-with-resources statement, or manually close it when you are
     * finished with it.
     *
     * <p>
     * The scan runs on a thread of the {@link ExecutorService}, so the classes that the scanner touches are loaded
     * on that thread. If the thread that calls this method then blocks on the returned {@link Future} while holding
     * a lock that the classloader also acquires, the scan can never complete (#933) -- use
     * {@link #scan(ExecutorService, int)}, which runs the scanner on the calling thread, if that is a possibility.
     *
     * @param executorService
     *            A custom {@link ExecutorService} to use for scheduling worker tasks.
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning. Ideally the ExecutorService will have at least this many threads available.
     * @return a {@code Future<ScanResult>}, that when resolved using get() yields a new {@link ScanResult} object
     *         representing the result of the scan.
     */
    public Future<ScanResult> scanAsync(final ExecutorService executorService, final int numParallelTasks) {
        Assert.notNull(executorService, "executorService");
        // Read the call stack on the calling thread, since it is the caller's classloaders and module layers that
        // are to be searched, not those of the thread that the scan happens to run on
        return executorService.submit(new Scanner(/* performScan = */ true, CallStack.read(), scanSpec,
                scanSourceSpec, executorService, numParallelTasks, /* scanResultProcessor = */ null,
                /* failureHandler = */ null, topLevelLog));
    }

    /**
     * Scans the classpath using the requested {@link ExecutorService} and the requested degree of parallelism,
     * blocking until the scan is complete. You should assign the returned {@link ScanResult} in a
     * try-with-resources statement, or manually close it when you are finished with it.
     *
     * <p>
     * The scan itself runs on the calling thread, and the {@link ExecutorService} is used only for the parallel
     * stages of the scan, so passing a {@code numParallelTasks} of 1 loads every class the scan needs on the
     * calling thread. That matters when the calling thread holds a lock that the classloader also acquires, since
     * loading a class on any other thread would then deadlock (#933).
     *
     * @param executorService
     *            A custom {@link ExecutorService} to use for scheduling worker tasks. This {@link ExecutorService}
     *            should start tasks in FIFO order to avoid a deadlock during scan, i.e. be sure to construct the
     *            {@link ExecutorService} with a {@link LinkedBlockingQueue} as its task queue. (This is the default
     *            for {@link Executors#newFixedThreadPool(int)}.)
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning. Ideally the ExecutorService will have at least this many threads available.
     * @return a {@link ScanResult} object representing the result of the scan.
     * @throws ClassGraphException
     *             if any of the worker threads throws an uncaught exception, or the scan was interrupted.
     */
    public ScanResult scan(final ExecutorService executorService, final int numParallelTasks) {
        Assert.notNull(executorService, "executorService");
        return scanOnThisThread(/* performScan = */ true, executorService, numParallelTasks);
    }

    /**
     * Run a scan on the calling thread, blocking until it is complete.
     *
     * <p>
     * The {@link Scanner} is run on the calling thread rather than being submitted to the {@link ExecutorService}
     * and waited for. Submitting it would leave the calling thread blocked on a {@link Future} while the classes
     * that the scanner touches were loaded on a worker thread, which deadlocks if the calling thread holds a lock
     * that the classloader also acquires -- a classloader that locks during early startup is not unusual. Neither
     * side of that cycle is a monitor that both threads contend for, so the JVM does not report it as a deadlock:
     * the scan simply never returns (#933). The {@link ExecutorService} is still used for the parallel stages of
     * the scan, and is not used at all when {@code numParallelTasks} is 1.
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
     *             if any of the worker threads throws an uncaught exception, or the scan was interrupted.
     */
    private ScanResult scanOnThisThread(final boolean performScan, final ExecutorService executorService,
            final int numParallelTasks) {
        // Read the call stack once, on the calling thread: the scan needs it both to decide whether loading a class
        // on a worker thread could deadlock (#933) and to find the caller's classloaders and module layers
        final var callStack = CallStack.read();
        try {
            final var scanResult = new Scanner(performScan, callStack, scanSpec, scanSourceSpec, executorService,
                    numTasksWithoutDeadlockHazard(callStack, numParallelTasks), /* scanResultProcessor = */ null,
                    /* failureHandler = */ null, topLevelLog).call();
            // A Scanner that was given no scan result processor always returns a scan result
            return Objects.requireNonNull(scanResult);

        } catch (final InterruptedException e) {
            // Throwing InterruptedException cleared the interrupt status, and this method reports the interruption
            // as an unchecked exception rather than rethrowing it, so restore the status, otherwise a caller that
            // catches ClassGraphException sees a thread that no longer looks interrupted
            Thread.currentThread().interrupt();
            throw new ClassGraphException("Scan interrupted", e);
        } catch (final CancellationException e) {
            throw new ClassGraphException("Scan interrupted", e);
        } catch (final ExecutionException e) {
            throw new ClassGraphException("Uncaught exception during scan", InterruptionChecker.getCause(e));
        } catch (final RuntimeException e) {
            // An unchecked exception thrown by the scanner used to reach the caller wrapped in an
            // ExecutionException by the Future, and was rewrapped as the cause of a ClassGraphException, so wrap it
            // the same way here rather than letting it propagate unwrapped
            throw new ClassGraphException("Uncaught exception during scan", e);
        }
    }

    /**
     * Reduce the number of parallel tasks to 1 if the calling thread is holding a lock that the classloader would
     * also need in order to load one of ClassGraph's own classes on a worker thread. Loading a class on a worker
     * thread would then deadlock the scan, and the deadlock cannot be broken, since a thread that is blocked on
     * class loading cannot be interrupted (#933). Running the whole scan on the calling thread is slower, but it
     * loads every class the scan needs on the thread that already holds the lock, so it cannot deadlock.
     *
     * @param callStack
     *            The call stack of the calling thread.
     * @param numParallelTasks
     *            The requested number of parallel tasks.
     * @return the number of parallel tasks to use.
     */
    private int numTasksWithoutDeadlockHazard(final CallStack callStack, final int numParallelTasks) {
        if (numParallelTasks <= 1) {
            // The scan already runs entirely on the calling thread
            return numParallelTasks;
        }
        final var frame = callStack.getFrameHoldingClassLoadingLock();
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
     * Scans the classpath with the requested number of threads, blocking until the scan is complete. You should
     * assign the returned {@link ScanResult} in a try-with-resources statement, or manually close it when you are
     * finished with it.
     *
     * <p>
     * Calling this with a {@code numThreads} of 1 starts no worker threads at all: the whole scan is run on the
     * calling thread, so every class the scan needs is loaded on the calling thread. Use that if the calling thread
     * holds a lock that the classloader also acquires, since loading a class on a worker thread would then deadlock
     * (#933). The two commonest ways to hold such a lock are detected automatically -- calling {@code scan()} from
     * a static initializer, or from a {@link ClassLoader} that is loading a class -- and the scan then falls back
     * to running on the calling thread whatever {@code numThreads} says, noting it in the verbose log.
     *
     * @param numThreads
     *            The number of worker threads to start up. If 1, the scan is run entirely on the calling thread.
     * @return a {@link ScanResult} object representing the result of the scan.
     * @throws ClassGraphException
     *             if any of the worker threads throws an uncaught exception, or the scan was interrupted.
     */
    public ScanResult scan(final int numThreads) {
        try (var executorService = new AutoCloseableExecutorService(numThreads, scanSpec.getWorkerTimeoutNanos())) {
            return scan(executorService, numThreads);
        }
    }

    /**
     * Scans the classpath, blocking until the scan is complete. You should assign the returned {@link ScanResult}
     * in a try-with-resources statement, or manually close it when you are finished with it.
     *
     * @return a {@link ScanResult} object representing the result of the scan.
     * @throws ClassGraphException
     *             if any of the worker threads throws an uncaught exception, or the scan was interrupted.
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
     *             if any of the worker threads throws an uncaught exception, or the scan was interrupted.
     */
    ScanResult getClasspathScanResult(final AutoCloseableExecutorService executorService) {
        return scanOnThisThread(/* performScan = */ false, executorService, DEFAULT_NUM_WORKER_THREADS);
    }

    /**
     * Returns the list of all unique File objects representing directories or zip/jarfiles on the classpath, in
     * classloader resolution order. Classpath elements that do not exist as a file or directory are not included in
     * the returned list.
     *
     * @return a {@code List<File>} consisting of the unique directories and jarfiles on the classpath, in classpath
     *         resolution order.
     * @throws ClassGraphException
     *             if any of the worker threads throws an uncaught exception, or the scan was interrupted.
     */
    public List<File> getClasspathFiles() {
        try (var executorService = new AutoCloseableExecutorService(DEFAULT_NUM_WORKER_THREADS,
                scanSpec.getWorkerTimeoutNanos()); var scanResult = getClasspathScanResult(executorService)) {
            return scanResult.getClasspathFiles();
        }
    }

    /**
     * Returns the list of all unique File objects representing directories or zip/jarfiles on the classpath, in
     * classloader resolution order, in the form of a classpath path string. Classpath elements that do not exist as
     * a file or directory are not included in the returned list. Note that the returned string contains only base
     * files, and does not include package roots or nested jars within jars, since the path separator (':')
     * conflicts with the URL scheme separator character (also ':') on Linux and Mac OS X. Call
     * {@link #getClasspathURIs()} to get the full URIs for classpath elements and modules.
     *
     * @return a classpath path string consisting of the unique directories and jarfiles on the classpath, in
     *         classpath resolution order.
     * @throws ClassGraphException
     *             if any of the worker threads throws an uncaught exception, or the scan was interrupted.
     */
    public String getClasspath() {
        return PathList.join(getClasspathFiles());
    }

    /**
     * Returns the ordered list of all unique {@link URI} objects representing directory/jar classpath elements and
     * modules. Classpath elements representing jarfiles or directories that do not exist are not included in the
     * returned list.
     *
     * @return the unique classpath elements and modules, as a list of {@link URI} objects.
     * @throws ClassGraphException
     *             if any of the worker threads throws an uncaught exception, or the scan was interrupted.
     */
    public List<URI> getClasspathURIs() {
        try (var executorService = new AutoCloseableExecutorService(DEFAULT_NUM_WORKER_THREADS,
                scanSpec.getWorkerTimeoutNanos()); var scanResult = getClasspathScanResult(executorService)) {
            return scanResult.getClasspathURIs();
        }
    }

    /**
     * Returns the ordered list of all unique {@link URL} objects representing directory/jar classpath elements and
     * modules. Classpath elements representing jarfiles or directories that do not exist, as well as modules with
     * unknown (null) location or with {@code jrt:} location URI scheme, are not included in the returned list.
     *
     * @return the unique classpath elements and modules, as a list of {@link URL} objects.
     * @throws ClassGraphException
     *             if any of the worker threads throws an uncaught exception, or the scan was interrupted.
     */
    public List<URL> getClasspathURLs() {
        try (var executorService = new AutoCloseableExecutorService(DEFAULT_NUM_WORKER_THREADS,
                scanSpec.getWorkerTimeoutNanos()); var scanResult = getClasspathScanResult(executorService)) {
            return scanResult.getClasspathURLs();
        }
    }

    /**
     * Returns the {@link ModuleReference} for each visible module.
     *
     * @return a list of the {@link ModuleReference} for each visible module.
     * @throws ClassGraphException
     *             if any of the worker threads throws an uncaught exception, or the scan was interrupted.
     */
    public List<ModuleReference> getModuleReferences() {
        try (var executorService = new AutoCloseableExecutorService(DEFAULT_NUM_WORKER_THREADS,
                scanSpec.getWorkerTimeoutNanos()); var scanResult = getClasspathScanResult(executorService)) {
            return scanResult.getModuleReferences();
        }
    }

    /**
     * Get the module path info provided on the commandline with {@code --module-path}, {@code --add-modules},
     * {@code --patch-module}, {@code --add-exports}, {@code --add-opens}, and {@code --add-reads}.
     *
     * <p>
     * Note that the returned {@link ModulePathInfo} object does not include classpath entries from the traditional
     * classpath or system modules. Use {@link #getModuleReferences()} to get all visible modules, including
     * anonymous, automatic and system modules.
     *
     * <p>
     * Also, {@link ModulePathInfo#getAddExports()} and {@link ModulePathInfo#getAddOpens()} will not contain
     * {@code Add-Exports} or {@code Add-Opens} entries from jarfile manifest files encountered during scanning,
     * unless you obtain the {@link ModulePathInfo} by calling {@link ScanResult#getModulePathInfo()} rather than by
     * calling {@link ClassGraph#getModulePathInfo()} before {@link ClassGraph#scan()}.
     *
     * @return The {@link ModulePathInfo}.
     */
    public ModulePathInfo getModulePathInfo() {
        return scanSpec.classpathSpec.modulePathInfo;
    }
}
