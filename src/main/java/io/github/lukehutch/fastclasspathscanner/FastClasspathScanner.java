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
import java.lang.annotation.RetentionPolicy;
import java.net.URL;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import io.github.lukehutch.fastclasspathscanner.classloaderhandler.ClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec;
import io.github.lukehutch.fastclasspathscanner.scanner.Scanner;
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

    /** The unique classpath elements. */
    private List<File> classpathElts;

    /** The unique classpath element URLs. */
    private List<URL> classpathEltURLs;

    /** The FastClasspathScanner version. */
    private static String version;

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
        if (version == null) {
            version = VersionFinder.getVersion();
        }
        return version;
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
     * Switches on verbose mode for debugging purposes if verbose == true. Call immediately after calling the
     * constructor if you want full log output. Prints debug info to System.err.
     *
     * @param verbose
     *            Whether or not to give verbose output.
     * @return this (for method chaining).
     */
    public FastClasspathScanner verbose(final boolean verbose) {
        if (verbose) {
            if (log == null) {
                log = new LogNode();
            }
        } else {
            log = null;
        }
        return this;
    }

    /**
     * Switches on verbose mode for debugging purposes. Call immediately after calling the constructor if you want
     * full log output. Prints debug info to System.err.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner verbose() {
        verbose(true);
        return this;
    }

    /**
     * If ignoreFieldVisibility is true, causes FastClasspathScanner to ignore field visibility, enabling it to see
     * private, package-private and protected fields. This affects finding classes with fields of a given type, as
     * well as matching static final fields with constant initializers, and saving FieldInfo for the class. If
     * false, fields must be public to be indexed/matched.
     *
     * @param ignoreFieldVisibility
     *            Whether or not to ignore the field visibility modifier.
     * @return this (for method chaining).
     */
    public FastClasspathScanner ignoreFieldVisibility(final boolean ignoreFieldVisibility) {
        getScanSpec().ignoreFieldVisibility = ignoreFieldVisibility;
        return this;
    }

    /**
     * This method causes FastClasspathScanner to ignore field visibility, enabling it to see private,
     * package-private and protected fields. This affects finding classes with fields of a given type, as well as
     * matching static final fields with constant initializers, and saving FieldInfo for the class. If false, fields
     * must be public to be indexed/matched.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner ignoreFieldVisibility() {
        ignoreFieldVisibility(true);
        return this;
    }

    /**
     * If ignoreMethodVisibility is true, causes FastClasspathScanner to ignore method visibility, enabling it to
     * see private, package-private and protected methods. This affects finding classes that have methods with a
     * given annotation, and also the saving of MethodInfo for the class. If false, methods must be public for the
     * containing classes to be indexed/matched.
     *
     * @param ignoreMethodVisibility
     *            Whether or not to ignore the method visibility modifier.
     * @return this (for method chaining).
     */
    public FastClasspathScanner ignoreMethodVisibility(final boolean ignoreMethodVisibility) {
        getScanSpec().ignoreMethodVisibility = ignoreMethodVisibility;
        return this;
    }

    /**
     * This method causes FastClasspathScanner to ignore method visibility, enabling it to see private,
     * package-private and protected methods. This affects finding classes that have methods with a given
     * annotation, and also the saving of MethodInfo for the class. If false, methods must be public for the
     * containing classes to be indexed/matched.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner ignoreMethodVisibility() {
        ignoreMethodVisibility(true);
        return this;
    }

    /**
     * If enableFieldInfo is true, enables the saving of field info during the scan. This information can be
     * obtained using ClassInfo#getFieldInfo(). By default, field info is not saved, because enabling this option
     * will cause the scan to take somewhat longer and potentially consume a lot more memory.
     *
     * @param enableFieldInfo
     *            If true, save field info while scanning. (Default false.)
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableFieldInfo(final boolean enableFieldInfo) {
        getScanSpec().enableFieldInfo = enableFieldInfo;
        return this;
    }

    /**
     * Enables the saving of field info during the scan. This information can be obtained using
     * ClassInfo#getFieldInfo(). By default, field info is not saved, because enabling this option will cause the
     * scan to take somewhat longer and potentially consume a lot more memory.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableFieldInfo() {
        return enableFieldInfo(true);
    }

    /**
     * If enableMethodInfo is true, enables the saving of method info during the scan. This information can be
     * obtained using ClassInfo#getMethodInfo(). By default, method info is not saved, because enabling this option
     * will cause the scan to take somewhat longer and potentially consume a lot more memory.
     *
     * @param enableMethodInfo
     *            If true, save method info while scanning. (Default false.)
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableMethodInfo(final boolean enableMethodInfo) {
        getScanSpec().enableMethodInfo = enableMethodInfo;
        return this;
    }

    /**
     * Enables the saving of method info during the scan. This information can be obtained using
     * ClassInfo#getMethodInfo(). By default, method info is not saved, because enabling this option will cause the
     * scan to take somewhat longer and potentially consume a lot more memory.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableMethodInfo() {
        return enableMethodInfo(true);
    }

    /**
     * Allows you to scan default packages (with package name "") without scanning sub-packages unless they are
     * whitelisted. This is needed because if you add the package name "" to the whitelist, that package and all
     * sub-packages will be scanned, which means everything will be scanned. This method makes it possible to
     * whitelist just the toplevel (default) package but not its sub-packages.
     *
     * @param alwaysScanClasspathElementRoot
     *            If true, always scan the classpath element root, regardless of the whitelist or blacklist.
     * @return this (for method chaining).
     */
    public FastClasspathScanner alwaysScanClasspathElementRoot(final boolean alwaysScanClasspathElementRoot) {
        if (alwaysScanClasspathElementRoot) {
            getScanSpec().whitelistedPathsNonRecursive.add("");
            getScanSpec().whitelistedPathsNonRecursive.add("/");
        } else {
            getScanSpec().whitelistedPathsNonRecursive.remove("");
            getScanSpec().whitelistedPathsNonRecursive.remove("/");
        }
        return this;
    }

    /**
     * Allows you to scan default packages (with package name "") without scanning sub-packages unless they are
     * whitelisted. This is needed because if you add the package name "" to the whitelist, that package and all
     * sub-packages will be scanned, which means everything will be scanned. This method makes it possible to
     * whitelist just the toplevel (default) package but not its sub-packages.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner alwaysScanClasspathElementRoot() {
        return alwaysScanClasspathElementRoot(true);
    }

    /**
     * If strictWhitelist is true, switches FastClasspathScanner to strict mode, which disallows searching/matching
     * based on blacklisted classes, and removes "external" classes from result lists returned by ScanSpec#get...()
     * methods. (External classes are classes outside of whitelisted packages that are directly referred to by
     * classes within whitelisted packages as a superclass, implemented interface or annotation.)
     *
     * <p>
     * Deprecated (and now has no effect) -- non-strict mode is now the default (as of version 3.0.2). Use
     * {@link #enableExternalClasses()} to disable strict mode.
     * 
     * <p>
     * See the following for info on external classes, and strict mode vs. non-strict mode:
     *
     * <p>
     * https://github.com/lukehutch/fast-classpath-scanner/wiki/2.-Constructor#external-classes
     *
     * @param strictWhitelist
     *            Whether or not to switch to strict mode.
     * @return this (for method chaining).
     */
    @Deprecated
    public FastClasspathScanner strictWhitelist(final boolean strictWhitelist) {
        return this;
    }

    /**
     * Switches FastClasspathScanner to strict mode, which disallows searching/matching based on blacklisted
     * classes, and removes "external" classes from result lists returned by ScanSpec#get...() methods. (External
     * classes are classes outside of whitelisted packages that are directly referred to by classes within
     * whitelisted packages as a superclass, implemented interface or annotation.)
     * 
     * <p>
     * Deprecated (and now has no effect)-- non-strict mode is the default (as of version 3.0.2). Use
     * {@link #enableExternalClasses()} to disable strict mode.
     *
     * <p>
     * See the following for info on external classes, and strict mode vs. non-strict mode:
     *
     * <p>
     * https://github.com/lukehutch/fast-classpath-scanner/wiki/2.-Constructor#external-classes
     *
     * @return this (for method chaining).
     */
    @Deprecated
    public FastClasspathScanner strictWhitelist() {
        return this;
    }

    /**
     * Causes FastClasspathScanner to return matching classes that are not in the whitelisted packages, but that are
     * directly referred to by classes within whitelisted packages as a superclass, implemented interface or
     * annotation.
     *
     * <p>
     * See the following for info on external classes, and strict mode vs. non-strict mode:
     *
     * <p>
     * https://github.com/lukehutch/fast-classpath-scanner/wiki/2.-Constructor#external-classes
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableExternalClasses(final boolean enableExternalClasses) {
        getScanSpec().enableExternalClasses = enableExternalClasses;
        return this;
    }

    /**
     * Causes FastClasspathScanner to return matching classes that are not in the whitelisted packages, but that are
     * directly referred to by classes within whitelisted packages as a superclass, implemented interface or
     * annotation.
     *
     * <p>
     * See the following for info on external classes, and strict mode vs. non-strict mode:
     *
     * <p>
     * https://github.com/lukehutch/fast-classpath-scanner/wiki/2.-Constructor#external-classes
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableExternalClasses() {
        enableExternalClasses(true);
        return this;
    }

    /**
     * If initializeLoadedClasses is true, classes loaded with Class.forName() are initialized before passing class
     * references to MatchProcessors. If false (the default), matched classes are loaded but not initialized before
     * passing class references to MatchProcessors (meaning classes are instead initialized lazily on first usage of
     * the class).
     *
     * @param initializeLoadedClasses
     *            Whether or not to initialize classes before passing class references to MatchProcessors. (The
     *            default value is false.)
     * @return this (for method chaining).
     */
    public FastClasspathScanner initializeLoadedClasses(final boolean initializeLoadedClasses) {
        getScanSpec().initializeLoadedClasses = initializeLoadedClasses;
        return this;
    }

    /**
     * If true, nested jarfiles (jarfiles within jarfiles, which have to be extracted during scanning in order to be
     * read) are removed from their temporary directory as soon as the scan has completed. If false (the default),
     * temporary files removed by the {@link ScanResult} finalizer, or on JVM exit.
     *
     * @param removeTemporaryFilesAfterScan
     *            Whether or not to remove temporary files after scanning. (The default value is true.)
     * @return this (for method chaining).
     */
    public FastClasspathScanner removeTemporaryFilesAfterScan(final boolean removeTemporaryFilesAfterScan) {
        getScanSpec().removeTemporaryFilesAfterScan = removeTemporaryFilesAfterScan;
        return this;
    }

    /**
     * Disable recursive scanning. Causes only toplevel entries within each whitelisted package to be scanned, i.e.
     * sub-packages of whitelisted packages will not be scanned. If no whitelisted packages were provided to the
     * constructor, then only the toplevel directory within each classpath element will be scanned.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner disableRecursiveScanning() {
        return disableRecursiveScanning(true);
    }

    /**
     * If true, disable recursive scanning. Causes only toplevel entries within each whitelisted package to be
     * scanned, i.e. sub-packages of whitelisted packages will not be scanned. If no whitelisted packages were
     * provided to the constructor, then only the toplevel directory within each classpath element will be scanned.
     * If false (the default), whitelisted paths and their subdirectories will be scanned.
     *
     * @param disableRecursiveScanning
     *            Whether or not to disable recursive scanning. (The default value is false.)
     * @return this (for method chaining).
     */
    public FastClasspathScanner disableRecursiveScanning(final boolean disableRecursiveScanning) {
        getScanSpec().disableRecursiveScanning = disableRecursiveScanning;
        return this;
    }

    /**
     * Manually strip the self extracting executable header from zipfiles (i.e. anything before the magic marker
     * "PK", e.g. a Bash script added by Spring-Boot). Slightly increases scanning time, since zipfiles have to be
     * opened twice (once as a byte stream, to check if there is an SFX header, then once as a ZipFile, for
     * decompression).
     * 
     * Should only be needed in rare cases, where you are dealing with jarfiles with prepended (ZipSFX) headers,
     * where your JVM does not already automatically skip forward to the first "PK" marker (Oracle JVM on Linux does
     * this automatically).
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

    /**
     * Add a classpath element filter. The includeClasspathElement method should return true if the path string
     * passed to it is a path you want to scan.
     */
    @FunctionalInterface
    public interface ClasspathElementFilter {
        /**
         * @param classpathElementString
         *            The path string of a classpath element, normalized so that the path separator is '/'. This
         *            will usually be a file path, but could be a URL, or it could be a path for a nested jar, where
         *            the paths are separated using '!', in Java convention. "jar:" and/or "file:" will have been
         *            stripped from the beginning, if they were present in the classpath.
         * @return true if the path string passed is a path you want to scan.
         */
        public boolean includeClasspathElement(String classpathElementString);
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

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Ignore parent classloaders (i.e. only obtain paths to scan from classloader(s), do not also fetch paths from
     * parent classloader(s)).
     *
     * <p>
     * This call is ignored if overrideClasspath() is called.
     *
     * @param ignoreParentClassLoaders
     *            If true, do not fetch paths from parent classloaders.
     * @return this (for method chaining).
     */
    public FastClasspathScanner ignoreParentClassLoaders(final boolean ignoreParentClassLoaders) {
        getScanSpec().ignoreParentClassLoaders = ignoreParentClassLoaders;
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
     * Set annotation visibility (to match the annotation retention policy).
     *
     * @param annotationVisibility
     *            The annotation visibility: RetentionPolicy.RUNTIME matches only runtime-visible annotations. The
     *            default value, RetentionPolicy.CLASS, matches all annotations (both runtime-visible and
     *            runtime-invisible). RetentionPolicy.SOURCE will cause an IllegalArgumentException to be thrown,
     *            since SOURCE-annotated annotations are not retained in classfiles.
     * @return this (for method chaining).
     */
    public FastClasspathScanner setAnnotationVisibility(final RetentionPolicy annotationVisibility) {
        if (annotationVisibility == RetentionPolicy.SOURCE) {
            throw new IllegalArgumentException("RetentionPolicy.SOURCE annotations are not retained in classfiles");
        }
        getScanSpec().annotationVisibility = annotationVisibility;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

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
     * Asynchronously scans the classpath for matching files, and if runAsynchronously is true, also calls any
     * MatchProcessors if a match is identified.
     *
     * @param executorService
     *            A custom ExecutorService to use for scheduling worker tasks.
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning. Ideally the ExecutorService will have at least this many threads available.
     * @param runMatchProcessorsOnWorkerThread
     *            If true, run MatchProcessors in one of the worker threads after obtaining the ScanResult.
     * @return a Future<ScanResult> object, that when resolved using get() yields a new ScanResult object. You can
     *         call cancel(true) on this Future if you want to interrupt the scan.
     */
    private Future<ScanResult> scanAsync(final ExecutorService executorService, final int numParallelTasks,
            final boolean isAsyncScan, final boolean runMatchProcessorsOnWorkerThread) {
        return executorService.submit(
                // Call MatchProcessors before returning if in async scanning mode
                new Scanner(getScanSpec(), executorService, numParallelTasks, /* enableRecursiveScanning = */ true,
                        /* scanResultProcessor = */ null, /* failureHandler = */ null, log));
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
        return scanAsync(executorService, numParallelTasks, /* isAsyncScan = */ true,
                /* runMatchProcessorsOnWorkerThread = */ true);
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
     * @throws ScanInterruptedException
     *             if any of the worker threads are interrupted during the scan. If you care about thread
     *             interruption, you should catch this exception. If you don't plan to interrupt the scan, you
     *             probably don't need to catch this.
     * @throws RuntimeException
     *             if any of the worker threads throws an uncaught exception. (Should not happen, this would
     *             indicate a bug in FastClasspathScanner.)
     * @return a new ScanResult object, containing info about the class graph within whitelisted packages
     *         encountered during the scan.
     */
    public ScanResult scan(final ExecutorService executorService, final int numParallelTasks) {
        try {
            // Start the scan
            final Future<ScanResult> scanFuture = scanAsync(executorService, numParallelTasks,
                    /* isAsyncScan = */ false, /* runMatchProcessorsOnWorkerThread = */ false);

            // Wait for scan completion
            final ScanResult scanResult = scanFuture.get();

            // // TODO: test serialization and deserialization by serializing and then deserializing the ScanResult 
            // final String scanResultJson = scanResult.toJSON();
            // scanResult = ScanResult.fromJSON(scanResultJson);

            // Return the scanResult after calling MatchProcessors
            return scanResult;

        } catch (final InterruptedException e) {
            if (log != null) {
                log.log("Scan interrupted");
            }
            throw new ScanInterruptedException();
        } catch (final ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause == null) {
                cause = e;
            }
            if (cause instanceof InterruptedException) {
                if (log != null) {
                    log.log("Scan interrupted");
                }
                throw new ScanInterruptedException();
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
     * @throws ScanInterruptedException
     *             if any of the worker threads are interrupted during the scan (shouldn't happen under normal
     *             circumstances).
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
     * @throws ScanInterruptedException
     *             if any of the worker threads are interrupted during the scan (shouldn't happen under normal
     *             circumstances).
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
     * Asynchronously returns the list of all unique File objects representing directories or zip/jarfiles on the
     * classpath, in classloader resolution order. Classpath elements that do not exist are not included in the
     * list.
     *
     * <p>
     * See the following for info on thread safety:
     *
     * <p>
     * https://github.com/lukehutch/fast-classpath-scanner/wiki/1.-Usage#multithreading-issues
     *
     * <p>
     * Note that if there are nested jarfiles on the classpath, e.g. {@code
     * file:///path/to/jar1.jar!/path/to/jar2.jar}, then both FastClasspathScanner#scanAsync() and
     * FastClasspathScanner#getUniqueClasspathElementsAsync() will cause jar2.jar to be extracted to a temporary
     * file, however FastClasspathScanner#getUniqueClasspathElementsAsync() will not remove this temporary file
     * after the scan (so that the file is still accessible to the caller -- each of the File objects in the
     * returned list of classpath elements should exist). These extracted temporary files are marked for deletion on
     * JVM exit, however.
     *
     * @param executorService
     *            A custom ExecutorService to use for scheduling worker tasks.
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning. Ideally the ExecutorService will have at least this many threads available.
     * @return a {@code Future<List<File>>}, that when resolved with get() returns a list of the unique directories
     *         and jarfiles on the classpath, in classpath resolution order. You can call cancel(true) on this
     *         Future if you want to interrupt the process (although the result is typically returned quickly).
     */
    public Future<List<File>> getUniqueClasspathElementsAsync(final ExecutorService executorService,
            final int numParallelTasks) {
        // No need to call disallowCallingFromClassInitializer() here, because no MatchProcessors are run, so class
        // initializer deadlock cannot occur.
        final Future<List<File>> classpathElementsFuture;
        try {
            final Future<ScanResult> scanResultFuture = executorService.submit( //
                    new Scanner(getScanSpec(), executorService, numParallelTasks,
                            /* enableRecursiveScanning = */ false, /* scanResultProcessor = */ null,
                            /* failureHandler = */ null,
                            log == null ? null : log.log("Getting unique classpath elements")));
            classpathElementsFuture = executorService.submit(new Callable<List<File>>() {
                @Override
                public List<File> call() throws Exception {
                    final ScanResult scanResult = scanResultFuture.get();
                    final List<File> uniqueClasspathElements = scanResult.getUniqueClasspathElements();
                    // N.B. scanResult.freeTempFiles() is *not* called for this method, so that the classpath
                    // elements resulting from jars within jars are left in place. However, they are cleaned up on
                    // normal JVM exit.
                    return uniqueClasspathElements;
                }
            });
        } finally {
            if (log != null) {
                log.flush();
            }
        }
        return classpathElementsFuture;
    }

    /**
     * Returns the list of all unique File objects representing directories or zip/jarfiles on the classpath, in
     * classloader resolution order. Classpath elements that do not exist are not included in the list. Blocks until
     * the result can be returned, when all classpath elements have been found and tested to see if they exist in
     * the filesystem.
     *
     * <p>
     * Note that if there are nested jarfiles on the classpath, e.g. {@code
     * file:///path/to/jar1.jar!/path/to/jar2.jar}, then both FastClasspathScanner#scan() and
     * FastClasspathScanner#getUniqueClasspathElements() will cause jar2.jar to be extracted to a temporary file,
     * however FastClasspathScanner#getUniqueClasspathElements() will not remove this temporary file after the scan
     * (so that the file is still accessible to the caller -- each of the File objects in the returned list of
     * classpath elements should exist). These extracted temporary files are marked for deletion on JVM exit,
     * however.
     *
     * @param executorService
     *            A custom ExecutorService to use for scheduling worker tasks.
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning. Ideally the ExecutorService will have at least this many threads available.
     * @throws ScanInterruptedException
     *             if any of the worker threads are interrupted during the scan. If you care about thread
     *             interruption, you should catch this exception. If you don't plan to interrupt the scan, you
     *             probably don't need to catch this.
     * @return a {@code List<File>} consisting of the unique directories and jarfiles on the classpath, in classpath
     *         resolution order.
     */
    public List<File> getUniqueClasspathElements(final ExecutorService executorService,
            final int numParallelTasks) {
        if (classpathElts == null) {
            try {
                classpathElts = getUniqueClasspathElementsAsync(executorService, numParallelTasks).get();
            } catch (final InterruptedException e) {
                if (log != null) {
                    log.log("Thread interrupted while getting classpath elements");
                }
                throw new ScanInterruptedException();
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
        return classpathElts;
    }

    /**
     * Returns the list of all unique File objects representing directories or zip/jarfiles on the classpath, in
     * classloader resolution order. Classpath elements that do not exist are not included in the list. Blocks until
     * the result can be returned, when all classpath elements have been found and tested to see if they exist in
     * the filesystem.
     *
     * <p>
     * Note that if there are nested jarfiles on the classpath, e.g. {@code
     * file:///path/to/jar1.jar!/path/to/jar2.jar}, then both FastClasspathScanner#scan() and
     * FastClasspathScanner#getUniqueClasspathElements() will cause jar2.jar to be extracted to a temporary file,
     * however FastClasspathScanner#getUniqueClasspathElements() will not remove this temporary file after the scan
     * (so that the file is still accessible to the caller -- each of the File objects in the returned list of
     * classpath elements should exist). These extracted temporary files are marked for deletion on JVM exit,
     * however.
     *
     * @throws ScanInterruptedException
     *             if any of the worker threads are interrupted during the scan (shouldn't happen under normal
     *             circumstances).
     * @return a {@code List<File>} consisting of the unique directories and jarfiles on the classpath, in classpath
     *         resolution order.
     */
    public List<File> getUniqueClasspathElements() {
        if (classpathElts == null) {
            try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(
                    DEFAULT_NUM_WORKER_THREADS)) {
                return getUniqueClasspathElements(executorService, DEFAULT_NUM_WORKER_THREADS);
            }
        }
        return classpathElts;
    }

    /**
     * Returns all unique directories or zip/jarfiles on the classpath, in classloader resolution order, as a
     * classpath string, delineated with the standard path separator character. Classpath elements that do not exist
     * are not included in the path. Blocks until the result can be returned, when all classpath elements have been
     * found and tested to see if they exist in the filesystem.
     *
     * <p>
     * Note that if there are nested jarfiles on the classpath, e.g. {@code
     * file:///path/to/jar1.jar!/path/to/jar2.jar}, then both FastClasspathScanner#scan() and
     * FastClasspathScanner#getUniqueClasspathElements() will cause jar2.jar to be extracted to a temporary file,
     * however FastClasspathScanner#getUniqueClasspathElements() will not remove this temporary file after the scan
     * (so that the file is still accessible to the caller -- each of the File objects in the returned list of
     * classpath elements should exist). These extracted temporary files are marked for deletion on JVM exit,
     * however.
     *
     * @throws ScanInterruptedException
     *             if any of the worker threads are interrupted during the scan (shouldn't happen under normal
     *             circumstances).
     * @return a the unique directories and jarfiles on the classpath, in classpath resolution order, as a path
     *         string.
     */
    public String getUniqueClasspathElementsAsPathStr() {
        return JarUtils.pathElementsToPathStr(getUniqueClasspathElements());
    }

    /**
     * Asynchronously returns the list of all unique URLs representing directories or zip/jarfiles on the classpath,
     * in classloader resolution order. Classpath elements that do not exist are not included in the list.
     *
     * <p>
     * See the following for info on thread safety:
     *
     * <p>
     * https://github.com/lukehutch/fast-classpath-scanner/wiki/1.-Usage#multithreading-issues
     *
     * <p>
     * Note that if there are nested jarfiles on the classpath, e.g. {@code
     * file:///path/to/jar1.jar!/path/to/jar2.jar}, then both FastClasspathScanner#scanAsync() and
     * FastClasspathScanner#getUniqueClasspathElementsAsync() will cause jar2.jar to be extracted to a temporary
     * file, however FastClasspathScanner#getUniqueClasspathElementURLsAsync() will not remove this temporary file
     * after the scan (so that the file is still accessible to the caller -- each of the File objects in the
     * returned list of classpath elements should exist). These extracted temporary files are marked for deletion on
     * JVM exit, however.
     *
     * @param executorService
     *            A custom ExecutorService to use for scheduling worker tasks.
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning. Ideally the ExecutorService will have at least this many threads available.
     * @return a {@code Future<List<URL>>}, that when resolved with get() returns a list of URLs for the unique
     *         directories and jarfiles on the classpath, in classpath resolution order. You can call cancel(true)
     *         on this Future if you want to interrupt the process (although the result is typically returned
     *         quickly).
     */
    public Future<List<URL>> getUniqueClasspathElementURLsAsync(final ExecutorService executorService,
            final int numParallelTasks) {
        // No need to call disallowCallingFromClassInitializer() here, because no MatchProcessors are run, so class
        // initializer deadlock cannot occur.
        final Future<List<URL>> classpathElementsFuture;
        try {
            final Future<ScanResult> scanResultFuture = executorService.submit( //
                    new Scanner(getScanSpec(), executorService, numParallelTasks,
                            /* enableRecursiveScanning = */ false, /* scanResultProcessor = */ null,
                            /* failureHandler = */ null,
                            log == null ? null : log.log("Getting unique classpath elements")));
            classpathElementsFuture = executorService.submit(new Callable<List<URL>>() {
                @Override
                public List<URL> call() throws Exception {
                    final ScanResult scanResult = scanResultFuture.get();
                    final List<URL> uniqueClasspathElementURLs = scanResult.getUniqueClasspathElementURLs();
                    // N.B. scanResult.freeTempFiles() is *not* called for this method, so that the classpath
                    // elements resulting from jars within jars are left in place. However, they are cleaned up on
                    // normal JVM exit.
                    return uniqueClasspathElementURLs;
                }
            });
        } finally {
            if (log != null) {
                log.flush();
            }
        }
        return classpathElementsFuture;
    }

    /**
     * Returns the list of all unique URL objects representing directories or zip/jarfiles on the classpath, in
     * classloader resolution order. Classpath elements that do not exist are not included in the list. Blocks until
     * the result can be returned, when all classpath elements have been found and tested to see if they exist in
     * the filesystem.
     *
     * <p>
     * Note that if there are nested jarfiles on the classpath, e.g. {@code
     * file:///path/to/jar1.jar!/path/to/jar2.jar}, then both FastClasspathScanner#scan() and
     * FastClasspathScanner#getUniqueClasspathElements() will cause jar2.jar to be extracted to a temporary file,
     * however FastClasspathScanner#getUniqueClasspathElementURLs() will not remove this temporary file after the
     * scan (so that the file is still accessible to the caller -- each of the File objects in the returned list of
     * classpath elements should exist). These extracted temporary files are marked for deletion on JVM exit,
     * however.
     *
     * @param executorService
     *            A custom ExecutorService to use for scheduling worker tasks.
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning. Ideally the ExecutorService will have at least this many threads available.
     * @throws ScanInterruptedException
     *             if any of the worker threads are interrupted during the scan. If you care about thread
     *             interruption, you should catch this exception. If you don't plan to interrupt the scan, you
     *             probably don't need to catch this.
     * @return a {@code List<URL>} consisting of the unique directories and jarfiles on the classpath, in classpath
     *         resolution order.
     */
    public List<URL> getUniqueClasspathElementURLs(final ExecutorService executorService,
            final int numParallelTasks) {
        if (classpathEltURLs == null) {
            try {
                classpathEltURLs = getUniqueClasspathElementURLsAsync(executorService, numParallelTasks).get();
            } catch (final InterruptedException e) {
                if (log != null) {
                    log.log("Thread interrupted while getting classpath elements");
                }
                throw new ScanInterruptedException();
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
        return classpathEltURLs;
    }

    /**
     * Returns the list of all unique URL objects representing directories or zip/jarfiles on the classpath, in
     * classloader resolution order. Classpath elements that do not exist are not included in the list. Blocks until
     * the result can be returned, when all classpath elements have been found and tested to see if they exist in
     * the filesystem.
     *
     * <p>
     * Note that if there are nested jarfiles on the classpath, e.g. {@code
     * file:///path/to/jar1.jar!/path/to/jar2.jar}, then both FastClasspathScanner#scan() and
     * FastClasspathScanner#getUniqueClasspathElements() will cause jar2.jar to be extracted to a temporary file,
     * however FastClasspathScanner#getUniqueClasspathElementURLs() will not remove this temporary file after the
     * scan (so that the file is still accessible to the caller -- each of the File objects in the returned list of
     * classpath elements should exist). These extracted temporary files are marked for deletion on JVM exit,
     * however.
     *
     * @throws ScanInterruptedException
     *             if any of the worker threads are interrupted during the scan (shouldn't happen under normal
     *             circumstances).
     * @return a {@code List<URL>} consisting of the unique directories and jarfiles on the classpath, in classpath
     *         resolution order.
     */
    public List<URL> getUniqueClasspathElementURLs() {
        if (classpathEltURLs == null) {
            try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(
                    DEFAULT_NUM_WORKER_THREADS)) {
                return getUniqueClasspathElementURLs(executorService, DEFAULT_NUM_WORKER_THREADS);
            }
        }
        return classpathEltURLs;
    }
}
