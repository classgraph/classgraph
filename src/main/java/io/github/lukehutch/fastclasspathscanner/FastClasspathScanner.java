/*
 * This file is part of FastClasspathScanner.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import io.github.lukehutch.fastclasspathscanner.utils.AutoCloseableExecutorService;
import io.github.lukehutch.fastclasspathscanner.utils.JarUtils;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.VersionFinder;

/**
 * Uber-fast, ultra-lightweight Java classpath scanner. Scans the classpath by parsing the classfile binary format
 * directly rather than by using reflection. (Reflection causes the classloader to load each class, which can take
 * an order of magnitude more time than parsing the classfile directly.)
 *
 * <p>
 * Documentation:
 *
 * <p>
 * https://github.com/lukehutch/fast-classpath-scanner/wiki
 */
public class FastClasspathScanner {
    /**
     * The scanning specification (whitelisted and blacklisted packages, etc.), as passed into the constructor.
     */
    private final String[] scanSpecArgs;

    /** The scanning specification, parsed. */
    private ScanSpec scanSpec;

    /**
     * The default number of worker threads to use while scanning. This number gave the best results on a relatively
     * modern laptop with SSD, while scanning a large classpath.
     */
    private static final int DEFAULT_NUM_WORKER_THREADS = 6;

    /** If non-null, log while scanning */
    private LogNode log;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Construct a FastClasspathScanner instance. You can pass a scanning specification to the constructor to
     * describe what should be scanned -- see the docs for info:
     *
     * <p>
     * https://github.com/lukehutch/fast-classpath-scanner/wiki/2.-Constructor
     *
     * <p>
     * The scanSpec, if non-empty, prevents irrelevant classpath entries from being unecessarily scanned, which can
     * be time-consuming.
     *
     * <p>
     * Note that calling the constructor does not start the scan, you must separately call .scan() to perform the
     * actual scan.
     *
     * @param scanSpec
     *            The constructor accepts a list of whitelisted package prefixes / jar names to scan, as well as
     *            blacklisted packages/jars not to scan, where blacklisted entries are prefixed with the '-'
     *            character. See https://github.com/lukehutch/fast-classpath-scanner/wiki/2.-Constructor for info.
     */
    public FastClasspathScanner(final String... scanSpec) {
        this.scanSpecArgs = scanSpec;
    }

    /**
     * Get the version number of FastClasspathScanner.
     *
     * @return the FastClasspathScanner version, or "unknown" if it could not be determined.
     */
    public static final String getVersion() {
        return VersionFinder.getVersion();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Lazy initializer for scanSpec. (This is lazy so that you have a chance to call verbose() before the ScanSpec
     * constructor tries to log something.)
     */
    private synchronized ScanSpec getScanSpec() {
        if (scanSpec == null) {
            scanSpec = new ScanSpec(scanSpecArgs, log == null ? null : log.log("Parsing scan spec"));
        }
        return scanSpec;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Switches on verbose logging to System.err.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner verbose() {
        if (log == null) {
            log = new LogNode();
        }
        return this;
    }

    /**
     * Causes field visibility to be ignored, enabling private, package-private and protected fields to be scanned.
     * This affects finding classes with fields of a given type, as well as matching static final fields with
     * constant initializers, and saving FieldInfo for the class. If false, fields must be public to be
     * indexed/matched.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner ignoreFieldVisibility() {
        getScanSpec().ignoreFieldVisibility = true;
        return this;
    }

    /**
     * Causes method visibility to be ignored, enabling private, package-private and protected methods to be
     * scanned. This affects finding classes that have methods with a given annotation, and also the saving of
     * MethodInfo for the class. If false, methods must be public for the containing classes to be indexed/matched.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner ignoreMethodVisibility() {
        getScanSpec().ignoreMethodVisibility = true;
        return this;
    }

    /**
     * Enables the saving of field info during the scan. This information can be obtained using
     * {@link ClassInfo#getFieldInfo()}. By default, field info is not scanned, for efficiency.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableFieldInfo() {
        getScanSpec().enableFieldInfo = true;
        return this;
    }

    /**
     * Enables the saving of static final field constant initializer values. By default, constant initializer values
     * are not stored, for efficiency. Calls {@link #enableFieldInfo}.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableStaticFinalFieldConstValues() {
        enableFieldInfo();
        getScanSpec().enableAnnotationInfo = true;
        return this;
    }

    /**
     * Enables the saving of method info during the scan. This information can be obtained using
     * {@link ClassInfo#getMethodInfo()} etc. By default, method info is not scanned, for efficiency.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableMethodInfo() {
        getScanSpec().enableMethodInfo = true;
        return this;
    }

    /**
     * Enables the saving of annotation info (for class, field, method and method parameter annotations) during the
     * scan. This information can be obtained using {@link ClassInfo#getAnnotationInfo()} etc. By default,
     * annotation info is not scanned, for efficiency.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableAnnotationInfo() {
        getScanSpec().enableAnnotationInfo = true;
        return this;
    }

    /**
     * Causes only runtime visible annotations to be scanned (runtime invisible annotations will be ignored).
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner disableRuntimeInvisibleAnnotations() {
        getScanSpec().disableRuntimeInvisibleAnnotations = true;
        return this;
    }

    /**
     * Disables the scanning of classfiles (causes only resources to be obtainable from the {@link ScanResult}, not
     * {@link ClassInfo} objects).
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner disableClassfileScanning() {
        getScanSpec().scanClassfiles = false;
        return this;
    }

    /**
     * Disables the scanning of jarfiles.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner disableJarScanning() {
        getScanSpec().scanJars = false;
        return this;
    }

    /**
     * Disables the scanning of directories.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner disableDirScanning() {
        getScanSpec().scanDirs = false;
        return this;
    }

    /**
     * Disables the scanning of modules.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner disableModuleScanning() {
        getScanSpec().scanModules = false;
        return this;
    }

    /**
     * Allows you to scan default packages (with package name "") without scanning sub-packages unless they are
     * whitelisted. This may be needed in some cases because if you add the package name "" to the whitelist, that
     * package and all sub-packages (meaning everything) will be scanned. This method makes it possible to whitelist
     * just the toplevel (default) package but not its sub-packages.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner alwaysScanClasspathElementRoot() {
        // TODO: add method for whitelisting directories non-recursively
        getScanSpec().whitelistedPathsNonRecursive.add("");
        getScanSpec().whitelistedPathsNonRecursive.add("/");
        return this;
    }

    /**
     * Causes FastClasspathScanner to return classes that are not in the whitelisted packages, but that are directly
     * referred to by classes within whitelisted packages as a superclass, implemented interface or annotation.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableExternalClasses() {
        getScanSpec().enableExternalClasses = true;
        return this;
    }

    /**
     * Causes classes loaded using {@link ClassInfo#getClassRef()} to be are initialized after class loading (the
     * default is to not initialize classes).
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner initializeLoadedClasses() {
        getScanSpec().initializeLoadedClasses = true;
        return this;
    }

    /**
     * Remove temporary files, including nested jarfiles (jarfiles within jarfiles, which have to be extracted
     * during scanning in order to be read) from their temporary directory as soon as the scan has completed. The
     * default is for temporary files to be removed by the {@link ScanResult} finalizer, or on JVM exit.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner removeTemporaryFilesAfterScan() {
        getScanSpec().removeTemporaryFilesAfterScan = true;
        return this;
    }

    /**
     * Disable recursive scanning. Causes only toplevel entries within each whitelisted package to be scanned, i.e.
     * sub-packages of whitelisted packages will not be scanned. If no whitelisted packages were provided to the
     * constructor, then only the toplevel directory within each classpath element will be scanned.
     *
     * @return this (for method chaining).
     */
    // TODO: remove this option, and make all whitelisting / blacklisting specified as either recursive or not
    public FastClasspathScanner disableRecursiveScanning() {
        getScanSpec().disableRecursiveScanning = true;
        return this;
    }

    /**
     * Manually strip the self extracting executable header from zipfiles (i.e. anything before the magic marker
     * "PK", e.g. a Bash script added by Spring-Boot). Increases scanning time, since zipfiles have to be opened
     * twice (once as a byte stream, to check if there is an SFX header, then once as a ZipFile, for decompression).
     * 
     * <p>
     * Should only be needed in rare cases, where you are dealing with jarfiles with prepended (ZipSFX) headers,
     * where your JVM does not already automatically skip forward to the first "PK" marker (most JVMs should do this
     * automatically, so this option should not be needed).
     * 
     * @return this (for method chaining).
     */
    public FastClasspathScanner stripZipSFXHeaders() {
        getScanSpec().stripSFXHeader = true;
        return this;
    }

    /**
     * If this method is called, a new {@link java.net.URLClassLoader} is created for all classes found on the
     * classpath that match whitelist criteria. This may be needed if you get a ClassNotFoundException,
     * UnsatisfiedLinkError, NoClassDefFoundError, etc., due to trying to load classes that depend upon each other
     * but that are loaded by different ClassLoaders in the classpath.
     * 
     * @return this (for method chaining).
     */
    public FastClasspathScanner createClassLoaderForMatchingClasses() {
        getScanSpec().createClassLoaderForMatchingClasses = true;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Register an extra ClassLoaderHandler. Needed if FastClasspathScanner doesn't know how to extract classpath
     * entries from your runtime environment's ClassLoader. See:
     *
     * <p>
     * https://github.com/lukehutch/fast-classpath-scanner/wiki/4.-Working-with-nonstandard-ClassLoaders
     *
     * @param classLoaderHandlerClass
     *            The ClassLoaderHandler class to register.
     * @return this (for method chaining).
     */
    public FastClasspathScanner registerClassLoaderHandler(
            final Class<? extends ClassLoaderHandler> classLoaderHandlerClass) {
        getScanSpec().registerClassLoaderHandler(classLoaderHandlerClass);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Override the automatically-detected classpath with a custom path, with path elements separated by
     * File.pathSeparatorChar. Causes system ClassLoaders and the java.class.path system property to be ignored.
     *
     * <p>
     * If this method is called, nothing but the provided classpath will be scanned, i.e. this causes ClassLoaders
     * to be ignored, as well as the java.class.path system property.
     *
     * @param overrideClasspath
     *            The custom classpath to use for scanning, with path elements separated by File.pathSeparatorChar.
     * @return this (for method chaining).
     */
    public FastClasspathScanner overrideClasspath(final String overrideClasspath) {
        getScanSpec().overrideClasspath(overrideClasspath);
        return this;
    }

    /**
     * Override the automatically-detected classpath with a custom path. Causes system ClassLoaders and the
     * java.class.path system property to be ignored. Works for Iterables of any type whose toString() method
     * resolves to a classpath element string, e.g. String, File or Path.
     *
     * <p>
     * If this method is called, nothing but the provided classpath will be scanned, i.e. this causes ClassLoaders
     * to be ignored, as well as the java.class.path system property.
     *
     * @param overrideClasspathElements
     *            The custom classpath to use for scanning, with path elements separated by File.pathSeparatorChar.
     * @return this (for method chaining).
     */
    public FastClasspathScanner overrideClasspath(final Iterable<?> overrideClasspathElements) {
        final String overrideClasspath = JarUtils.pathElementsToPathStr(overrideClasspathElements);
        if (overrideClasspath.isEmpty()) {
            throw new IllegalArgumentException("Can't override classpath with an empty path");
        }
        overrideClasspath(overrideClasspath);
        return this;
    }

    /**
     * Override the automatically-detected classpath with a custom path. Causes system ClassLoaders and the
     * java.class.path system property to be ignored. Works for arrays of any member type whose toString() method
     * resolves to a classpath element string, e.g. String, File or Path.
     *
     * @param overrideClasspathElements
     *            The custom classpath to use for scanning, with path elements separated by File.pathSeparatorChar.
     * @return this (for method chaining).
     */
    public FastClasspathScanner overrideClasspath(final Object... overrideClasspathElements) {
        final String overrideClasspath = JarUtils.pathElementsToPathStr(overrideClasspathElements);
        if (overrideClasspath.isEmpty()) {
            throw new IllegalArgumentException("Can't override classpath with an empty path");
        }
        overrideClasspath(overrideClasspath);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add a classpath element filter. The includeClasspathElement method should return true if the path string
     * passed to it is a path you want to scan.
     */
    @FunctionalInterface
    public interface ClasspathElementFilter {
        /**
         * @param classpathElementPathStr
         *            The path string of a classpath element, normalized so that the path separator is '/'. This
         *            will usually be a file path, but could be a URL, or it could be a path for a nested jar, where
         *            the paths are separated using '!', in Java convention. "jar:" and/or "file:" will have been
         *            stripped from the beginning, if they were present in the classpath.
         * @return true if the path string passed is a path you want to scan.
         */
        public boolean includeClasspathElement(String classpathElementPathStr);
    }

    /**
     * Add a classpath element filter. The provided ClasspathElementFilter should return true if the path string
     * passed to it is a path you want to scan.
     * 
     * @param classpathElementFilter
     *            The filter function to use. This function should return true if the classpath element path should
     *            be scanned, and false if not.
     * @return this (for method chaining).
     */
    public FastClasspathScanner filterClasspathElements(final ClasspathElementFilter classpathElementFilter) {
        getScanSpec().filterClasspathElements(classpathElementFilter);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add a ClassLoader to the list of ClassLoaders to scan.
     *
     * <p>
     * This call is ignored if overrideClasspath() is also called, or if this method is called before
     * overrideClassLoaders().
     *
     * <p>
     * This call is ignored if overrideClasspath() is called.
     *
     * @param classLoader
     *            The additional ClassLoader to scan.
     * @return this (for method chaining).
     */
    public FastClasspathScanner addClassLoader(final ClassLoader classLoader) {
        getScanSpec().addClassLoader(classLoader);
        return this;
    }

    /**
     * Completely override (and ignore) system ClassLoaders and the java.class.path system property.
     *
     * <p>
     * This call is ignored if overrideClasspath() is called.
     *
     * @param overrideClassLoaders
     *            The ClassLoaders to scan instead of the automatically-detected ClassLoaders.
     * @return this (for method chaining).
     */
    public FastClasspathScanner overrideClassLoaders(final ClassLoader... overrideClassLoaders) {
        getScanSpec().overrideClassLoaders(overrideClassLoaders);
        return this;
    }

    /**
     * Ignore parent classloaders (i.e. only obtain paths to scan from classloader(s), do not also fetch paths from
     * parent classloader(s)).
     *
     * <p>
     * This call is ignored if overrideClasspath() is called.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner ignoreParentClassLoaders() {
        getScanSpec().ignoreParentClassLoaders = true;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A callback that can be used to processes the result of an asynchronous scan after scanning has completed and
     * any MatchProcessors have been run.
     */
    @FunctionalInterface
    public interface ScanResultProcessor {
        /**
         * Process the result of an asynchronous scan after scanning has completed and any MatchProcessors have been
         * run.
         */
        public void processScanResult(ScanResult scanResult);
    }

    /** A callback that can be called on scanning failure during an asynchronous scan. */
    @FunctionalInterface
    public interface FailureHandler {
        /** Called on scanning failure during an asynchronous scan. */
        public void onFailure(Throwable throwable);
    }

    /**
     * Asynchronously scans the classpath for matching files, and if runAsynchronously is true, also calls any
     * MatchProcessors if a match is identified.
     *
     * @param executorService
     *            A custom ExecutorService to use for scheduling worker tasks.
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning. Ideally the ExecutorService will have at least this many threads available.
     * @param scanResultProcessor
     *            A callback to run on successful scan. Passed the ScanResult after asynchronous scanning has
     *            completed and MatchProcessors have been run. (If null, throws IllegalArgumentException.)
     * @param failureHandler
     *            A callback to run on failed scan. Passed any Throwable thrown during the scan. (If null, throws
     *            IllegalArgumentException.)
     */
    public void scanAsync(final ExecutorService executorService, final int numParallelTasks,
            final ScanResultProcessor scanResultProcessor, final FailureHandler failureHandler) {
        if (scanResultProcessor == null) {
            // If scanResultProcessor is null, the scan won't do anything after completion, and the ScanResult will
            // simply be lost.
            throw new IllegalArgumentException("scanResultProcessor cannot be null");
        }
        if (failureHandler == null) {
            // The result of the Future<ScanObject> object returned by launchAsyncScan is discarded below, so we
            // force the addition of a FailureHandler so that exceptions are not silently swallowed.
            throw new IllegalArgumentException("failureHandler cannot be null");
        }
        // Drop the returned Future<ScanResult>, a ScanResultProcessor is used instead
        executorService.submit(
                // Call MatchProcessors before returning if in async scanning mode
                new Scanner(getScanSpec(), executorService, numParallelTasks, /* enableRecursiveScanning = */ true,
                        scanResultProcessor, failureHandler, log));
    }

    /**
     * Asynchronously scans the classpath for matching files, and calls any MatchProcessors if a match is
     * identified. Returns a Future object immediately after starting the scan. To block on scan completion, get the
     * result of the returned Future. Uses the provided ExecutorService, and divides the work according to the
     * requested degree of parallelism. This method should be called after all required MatchProcessors have been
     * added.
     *
     * <p>
     * Note on thread safety: MatchProcessors are all run on a separate thread from the thread that calls this
     * method (although the MatchProcessors are all run on one thread). You will need to add your own
     * synchronization logic if MatchProcessors interact with the main thread. See the following for more info:
     *
     * <p>
     * https://github.com/lukehutch/fast-classpath-scanner/wiki/1.-Usage#multithreading-issues
     *
     * @param executorService
     *            A custom ExecutorService to use for scheduling worker tasks.
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning. Ideally the ExecutorService will have at least this many threads available.
     * @return a Future<ScanResult> object, that when resolved using get() yields a new ScanResult object. This
     *         ScanResult object contains info about the class graph within whitelisted packages encountered during
     *         the scan. Calling get() on this Future object throws InterruptedException if the scanning is
     *         interrupted before it completes, or throws ExecutionException if something goes wrong during
     *         scanning. If ExecutionException is thrown, and the cause is a MatchProcessorException, then either
     *         classloading failed for some class, or a MatchProcessor threw an exception.
     */
    public Future<ScanResult> scanAsync(final ExecutorService executorService, final int numParallelTasks) {
        return executorService.submit(
                // Call MatchProcessors before returning if in async scanning mode
                new Scanner(getScanSpec(), executorService, numParallelTasks, /* enableRecursiveScanning = */ true,
                        /* scanResultProcessor = */ null, /* failureHandler = */ null, log));
    }

    /**
     * Scans the classpath for matching files, and calls any MatchProcessors if a match is identified. Uses the
     * provided ExecutorService, and divides the work according to the requested degree of parallelism. Blocks and
     * waits for the scan to complete before returning a ScanResult. This method should be called after all required
     * MatchProcessors have been added.
     *
     * @param executorService
     *            A custom ExecutorService to use for scheduling worker tasks. This ExecutorService should start
     *            tasks in FIFO order to avoid a deadlock during scan, i.e. be sure to construct the ExecutorService
     *            with a LinkedBlockingQueue as its task queue. (This is the default for
     *            Executors.newFixedThreadPool().)
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning. Ideally the ExecutorService will have at least this many threads available.
     * @throws RuntimeException
     *             if any of the worker threads throws an uncaught exception. (Should not happen, this would
     *             indicate a bug in FastClasspathScanner.)
     * @return a new ScanResult object, containing info about the class graph within whitelisted packages
     *         encountered during the scan.
     */
    public ScanResult scan(final ExecutorService executorService, final int numParallelTasks) {
        try {
            // Start the scan and wait for completion
            final ScanResult scanResult = executorService.submit(
                    // Call MatchProcessors before returning if in async scanning mode
                    new Scanner(getScanSpec(), executorService, numParallelTasks,
                            /* enableRecursiveScanning = */ true, /* scanResultProcessor = */ null,
                            /* failureHandler = */ null, log)) //
                    .get();

            // // TODO: test serialization and deserialization by serializing and then deserializing the ScanResult 
            // final String scanResultJson = scanResult.toJSON();
            // scanResult = ScanResult.fromJSON(scanResultJson);

            // Return the scanResult after calling MatchProcessors
            return scanResult;

        } catch (final InterruptedException e) {
            if (log != null) {
                log.log("Scan interrupted");
            }
            throw new IllegalArgumentException("Scan interrupted", e);
        } catch (final ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause == null) {
                cause = e;
            }
            if (cause instanceof InterruptedException) {
                if (log != null) {
                    log.log("Scan interrupted");
                }
                throw new IllegalArgumentException("Scan interrupted", e);
            } else {
                if (log != null) {
                    log.log("Unexpected exception during scan", e);
                }
                throw new RuntimeException(cause);
            }
        } finally {
            if (log != null) {
                log.flush();
            }
        }
    }

    /**
     * Scans the classpath for matching files, and calls any MatchProcessors if a match is identified. Temporarily
     * starts up a new fixed thread pool for scanning, with the requested number of threads. Blocks and waits for
     * the scan to complete before returning a ScanResult. This method should be called after all required
     * MatchProcessors have been added.
     *
     * @param numThreads
     *            The number of worker threads to start up.
     * @throws RuntimeException
     *             if any of the worker threads throws an uncaught exception. (Should not happen, this would
     *             indicate a bug in FastClasspathScanner.)
     * @return a new ScanResult object, containing info about the class graph within whitelisted packages
     *         encountered during the scan.
     */
    public ScanResult scan(final int numThreads) {
        try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(numThreads)) {
            return scan(executorService, numThreads);
        }
    }

    /**
     * Scans the classpath for matching files, and calls any MatchProcessors if a match is identified. Temporarily
     * starts up a new fixed thread pool for scanning, with the default number of threads. Blocks and waits for the
     * scan to complete before returning a ScanResult. This method should be called after all required
     * MatchProcessors have been added.
     *
     * @throws RuntimeException
     *             if any of the worker threads throws an uncaught exception. (Should not happen, this would
     *             indicate a bug in FastClasspathScanner.)
     * @return a new ScanResult object, containing info about the class graph within whitelisted packages
     *         encountered during the scan.
     */
    public ScanResult scan() {
        return scan(DEFAULT_NUM_WORKER_THREADS);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the list of all unique File objects representing directories or zip/jarfiles on the classpath, in
     * classloader resolution order. Will cause "http://" and "https://" classpath element URLs to be downloaded to
     * a temporary file, and inner zipfiles (jars within jars) to be extracted to temporary files. Classpath
     * elements that do not exist as a file or directory, including JPMS modules that are not backed by a "file:/"
     * URL, are not included in the returned list.
     *
     * @return a {@code List<File>} consisting of the unique directories and jarfiles on the classpath, in classpath
     *         resolution order.
     */
    public List<File> getUniqueClasspathElements() {
        try {
            try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(
                    DEFAULT_NUM_WORKER_THREADS)) {
                return executorService.submit( //
                        new Scanner(getScanSpec(), executorService, DEFAULT_NUM_WORKER_THREADS,
                                /* enableRecursiveScanning = */ false, /* scanResultProcessor = */ null,
                                /* failureHandler = */ null,
                                log == null ? null : log.log("Getting unique classpath elements")))
                        .get().getUniqueClasspathElements();
            }
        } catch (final InterruptedException e) {
            if (log != null) {
                log.log("Thread interrupted while getting classpath elements");
            }
            throw new IllegalArgumentException("Scan interrupted", e);
        } catch (final ExecutionException e) {
            if (log != null) {
                log.log("Exception while getting classpath elements", e);
            }
            final Throwable cause = e.getCause();
            throw new RuntimeException(cause == null ? e : cause);
        } finally {
            if (log != null) {
                log.flush();
            }
        }
    }

    /**
     * Returns the list of all unique File objects representing directories or zip/jarfiles on the classpath, in
     * classloader resolution order, in the form of a classpath path string. Will cause "http://" and "https://"
     * classpath element URLs to be downloaded to a temporary file, and inner zipfiles (jars within jars) to be
     * extracted to temporary files. Classpath elements that do not exist as a file or directory, including JPMS
     * modules that are not backed by a "file:/" URL, are not included in the returned list.
     *
     * @return a classpath path string consisting of the unique directories and jarfiles on the classpath, in
     *         classpath resolution order.
     */
    public String getUniqueClasspathElementsAsPathStr() {
        return JarUtils.pathElementsToPathStr(getUniqueClasspathElements());
    }

    /**
     * Returns the list of all unique URL objects representing directories, zip/jarfiles or modules on the
     * classpath, in classloader resolution order. Will cause "http://" and "https://" classpath element URLs to be
     * downloaded to a temporary file, and inner zipfiles (jars within jars) to be extracted to temporary files.
     * Classpath elements representing jarfiles or directories that do not exist are not included in the returned
     * list.
     *
     * @return a classpath path string consisting of the unique directories and jarfiles on the classpath, in
     *         classpath resolution order.
     */
    public List<URL> getUniqueClasspathElementURLs() {
        try {
            try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(
                    DEFAULT_NUM_WORKER_THREADS)) {
                return executorService.submit( //
                        new Scanner(getScanSpec(), executorService, DEFAULT_NUM_WORKER_THREADS,
                                /* enableRecursiveScanning = */ false, /* scanResultProcessor = */ null,
                                /* failureHandler = */ null,
                                log == null ? null : log.log("Getting unique classpath elements")))
                        .get().getUniqueClasspathElementURLs();
            }
        } catch (final InterruptedException e) {
            if (log != null) {
                log.log("Thread interrupted while getting classpath elements");
            }
            throw new IllegalArgumentException("Scan interrupted", e);
        } catch (final ExecutionException e) {
            if (log != null) {
                log.log("Exception while getting classpath elements", e);
            }
            final Throwable cause = e.getCause();
            throw new RuntimeException(cause == null ? e : cause);
        } finally {
            if (log != null) {
                log.flush();
            }
        }
    }
}
