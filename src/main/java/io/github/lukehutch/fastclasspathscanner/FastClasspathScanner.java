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
 * Copyright (c) 2016 Luke Hutchison
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
import java.lang.annotation.Annotation;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import io.github.lukehutch.fastclasspathscanner.classloaderhandler.ClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ClassAnnotationMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ClassMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FieldAnnotationMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchContentsProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchContentsProcessorWithContext;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchProcessorWithContext;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ImplementingClassMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.MethodAnnotationMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.StaticFinalFieldMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.SubclassMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.SubinterfaceMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.scanner.ClassLoaderFinder;
import io.github.lukehutch.fastclasspathscanner.scanner.FailureHandler;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResultProcessor;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec;
import io.github.lukehutch.fastclasspathscanner.scanner.Scanner;
import io.github.lukehutch.fastclasspathscanner.utils.AutoCloseableExecutorService;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.VersionFinder;

/**
 * Uber-fast, ultra-lightweight Java classpath scanner. Scans the classpath by parsing the classfile binary format
 * directly rather than by using reflection. (Reflection causes the classloader to load each class, which can take
 * an order of magnitude more time than parsing the classfile directly.)
 * 
 * Documentation:
 * 
 * https://github.com/lukehutch/fast-classpath-scanner/wiki
 */
public class FastClasspathScanner {
    /** The scanning specification (whitelisted and blacklisted packages, etc.), as passed into the constructor. */
    private final String[] scanSpecArgs;

    /** The scanning specification, parsed. */
    private ScanSpec scanSpec;

    /** The unique classpath elements. */
    private List<File> classpathElts;

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
     * https://github.com/lukehutch/fast-classpath-scanner/wiki/2.-Constructor
     * 
     * The scanSpec, if non-empty, prevents irrelevant classpath entries from being unecessarily scanned, which can
     * be time-consuming.
     * 
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
     * If enableFieldTypeIndexing is true, enables field type indexing, which allows you to call
     * ScanResult#getClassesWithFieldsOfType(type). (If you add a field type match processor, this method is called
     * for you.) Field type indexing is disabled by default, because it is expensive in terms of time (adding ~10%
     * to the scan time) and memory, and it is not needed for most uses of FastClasspathScanner.
     * 
     * @param enableFieldTypeIndexing
     *            Whether or not to enable field type indexing.
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableFieldTypeIndexing(final boolean enableFieldTypeIndexing) {
        getScanSpec().enableFieldTypeIndexing = enableFieldTypeIndexing;
        return this;
    }

    /**
     * Enables field type indexing, which allows you to call ScanResult#getClassesWithFieldsOfType(type). (If you
     * add a field type match processor, this method is called for you.) Field type indexing is disabled by default,
     * because it is expensive in terms of time (adding ~10% to the scan time) and memory, and it is not needed for
     * most uses of FastClasspathScanner.
     * 
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableFieldTypeIndexing() {
        enableFieldTypeIndexing(true);
        return this;
    }

    /**
     * If enableMethodAnnotationIndexing is true, enables method annotation indexing, which allows you to call
     * ScanResult#getNamesOfClassesWithMethodAnnotation(annotation). (If you add a method annotation match
     * processor, this method is called for you.) Method annotation indexing is disabled by default, because it is
     * expensive in terms of time, and it is not needed for most uses of FastClasspathScanner.
     * 
     * @param enableMethodAnnotationIndexing
     *            Whether or not to enable method annotation indexing.
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableMethodAnnotationIndexing(final boolean enableMethodAnnotationIndexing) {
        getScanSpec().enableMethodAnnotationIndexing = enableMethodAnnotationIndexing;
        return this;
    }

    /**
     * Enables method annotation indexing, which allows you to call
     * ScanResult#getNamesOfClassesWithMethodAnnotation(annotation). (If you add a method annotation match
     * processor, this method is called for you.) Method annotation indexing is disabled by default, because it is
     * expensive in terms of time, and it is not needed for most uses of FastClasspathScanner.
     * 
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableMethodAnnotationIndexing() {
        enableMethodAnnotationIndexing(true);
        return this;
    }

    /**
     * If enableFieldAnnotationIndexing is true, enables field annotation indexing, which allows you to call
     * ScanResult#getNamesOfClassesWithFieldAnnotation(annotation). (If you add a method annotation match processor,
     * this method is called for you.) Method annotation indexing is disabled by default, because it is expensive in
     * terms of time, and it is not needed for most uses of FastClasspathScanner.
     * 
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableFieldAnnotationIndexing(final boolean enableFieldAnnotationIndexing) {
        getScanSpec().enableFieldAnnotationIndexing = enableFieldAnnotationIndexing;
        return this;
    }

    /**
     * Enables field annotation indexing, which allows you to call
     * ScanResult#getNamesOfClassesWithFieldAnnotation(annotation). (If you add a method annotation match processor,
     * this method is called for you.) Method annotation indexing is disabled by default, because it is expensive in
     * terms of time, and it is not needed for most uses of FastClasspathScanner.
     * 
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableFieldAnnotationIndexing() {
        enableFieldAnnotationIndexing(true);
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
     * If strictWhitelist is true, switches FastClasspathScanner to strict mode, which disallows searching/matching
     * based on blacklisted classes, and removes "external" classes from result lists returned by ScanSpec#get...()
     * methods. (External classes are classes outside of whitelisted packages that are directly referred to by
     * classes within whitelisted packages as a superclass, implemented interface or annotation.)
     * 
     * See the following for info on external classes, and strict mode vs. non-strict mode:
     * 
     * https://github.com/lukehutch/fast-classpath-scanner/wiki/2.-Constructor#external-classes
     * 
     * @param strictWhitelist
     *            Whether or not to switch to strict mode.
     * @return this (for method chaining).
     */
    public FastClasspathScanner strictWhitelist(final boolean strictWhitelist) {
        getScanSpec().strictWhitelist = strictWhitelist;
        return this;
    }

    /**
     * Switches FastClasspathScanner to strict mode, which disallows searching/matching based on blacklisted
     * classes, and removes "external" classes from result lists returned by ScanSpec#get...() methods. (External
     * classes are classes outside of whitelisted packages that are directly referred to by classes within
     * whitelisted packages as a superclass, implemented interface or annotation.)
     * 
     * See the following for info on external classes, and strict mode vs. non-strict mode:
     * 
     * https://github.com/lukehutch/fast-classpath-scanner/wiki/2.-Constructor#external-classes
     * 
     * @return this (for method chaining).
     */
    public FastClasspathScanner strictWhitelist() {
        strictWhitelist(true);
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
     * If true (the default), nested jarfiles (jarfiles within jarfiles, which have to be extracted during scanning
     * in order to be read) are removed from their temporary directory after the scan has completed. If false,
     * temporary files are only removed on JVM exit.
     * 
     * This method should be called if you need to access nested jarfiles (e.g. from a Spring classpath) after
     * scanning has completed. In particular, if you use ClasspathUtils.getClasspathResourceURL() in a
     * FileMatchProcessor and you need to use the returned URLs after scanning has completed, then you should call
     * FastClasspathScanner#removeTemporaryFilesAfterScan(false) before calling scan().
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

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Register an extra ClassLoaderHandler. Needed if FastClasspathScanner doesn't know how to extract classpath
     * entries from your runtime environment's ClassLoader. See:
     * 
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
     * If this method is called, nothing but the provided classpath will be scanned, i.e. this causes ClassLoaders
     * to be ignored, as well as the java.class.path system property.
     * 
     * @param overrideClasspathElements
     *            The custom classpath to use for scanning, with path elements separated by File.pathSeparatorChar.
     * @return this (for method chaining).
     */
    public FastClasspathScanner overrideClasspath(final Iterable<?> overrideClasspathElements) {
        final StringBuilder buf = new StringBuilder();
        for (final Object classpathElt : overrideClasspathElements) {
            if (classpathElt != null) {
                if (buf.length() > 0) {
                    buf.append(File.pathSeparatorChar);
                }
                buf.append(classpathElt);
            }
        }
        final String overrideClasspath = buf.toString();
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
        final StringBuilder buf = new StringBuilder();
        for (final Object classpathElt : overrideClasspathElements) {
            if (classpathElt != null) {
                if (buf.length() > 0) {
                    buf.append(File.pathSeparatorChar);
                }
                buf.append(classpathElt);
            }
        }
        final String overrideClasspath = buf.toString();
        if (overrideClasspath.isEmpty()) {
            throw new IllegalArgumentException("Can't override classpath with an empty path");
        }
        overrideClasspath(overrideClasspath);
        return this;
    }

    /**
     * Add a ClassLoader to the list of ClassLoaders to scan.
     * 
     * This call is ignored if overrideClasspath() is also called, or if this method is called before
     * overrideClassLoaders().
     * 
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
     * This call is ignored if overrideClasspath() is called.
     * 
     * @param ignoreParentClassLoaders
     *            If true, do not fetch paths from parent classloaders.
     * @return this (for method chaining).
     */
    public FastClasspathScanner ignoreParentClassLoaders(final boolean ignoreParentClassLoaders) {
        getScanSpec().ignoreParentClassLoaders(ignoreParentClassLoaders);
        return this;
    }

    /**
     * Ignore parent classloaders (i.e. only obtain paths to scan from classloader(s), do not also fetch paths from
     * parent classloader(s)).
     * 
     * This call is ignored if overrideClasspath() is called.
     * 
     * @return this (for method chaining).
     */
    public FastClasspathScanner ignoreParentClassLoaders() {
        getScanSpec().ignoreParentClassLoaders(true);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the classloader or classloaders most likely to represent the order that classloaders are used to resolve
     * classes in the current context. Uses the technique described by <a href=
     * "http://www.javaworld.com/article/2077344/core-java/find-a-way-out-of-the-classloader-maze.html">Vladimir
     * Roubtsov</a>.
     * 
     * <p>
     * Generally this will return exactly one ClassLoader, but if it returns more than one, the classloaders are
     * listed in the order they should be called in until one of them is able to load the named class. If you can
     * call only one ClassLoader, use the first element of the list.
     * 
     * <p>
     * If you have overridden the ClassLoader(s), then the override ClassLoader(s) will be returned instead.
     * 
     * @return A list of one or more ClassLoaders, out of the system ClassLoader, the current classloader, or the
     *         context classloader (or the override ClassLoaders, if ClassLoaders have been overridden).
     */
    public ClassLoader[] findBestClassLoader() {
        return ClassLoaderFinder.findEnvClassLoaders(getScanSpec(), log);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided ClassMatchProcessor for all standard classes, interfaces and annotations found in
     * whitelisted packages on the classpath.
     * 
     * @param classMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchAllClasses(final ClassMatchProcessor classMatchProcessor) {
        getScanSpec().matchAllClasses(classMatchProcessor);
        return this;
    }

    /**
     * Calls the provided ClassMatchProcessor for all standard classes (i.e. non-interface, non-annotation classes)
     * found in whitelisted packages on the classpath.
     * 
     * @param standardClassMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchAllStandardClasses(final ClassMatchProcessor standardClassMatchProcessor) {
        getScanSpec().matchAllStandardClasses(standardClassMatchProcessor);
        return this;
    }

    /**
     * Calls the provided ClassMatchProcessor for all interface classes (interface definitions) found in whitelisted
     * packages on the classpath.
     * 
     * @param interfaceClassMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchAllInterfaceClasses(final ClassMatchProcessor interfaceClassMatchProcessor) {
        getScanSpec().matchAllInterfaceClasses(interfaceClassMatchProcessor);
        return this;
    }

    /**
     * Calls the provided ClassMatchProcessor for all annotation classes (annotation definitions) found in
     * whitelisted packages on the classpath.
     * 
     * @param annotationClassMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchAllAnnotationClasses(final ClassMatchProcessor annotationClassMatchProcessor) {
        getScanSpec().matchAllAnnotationClasses(annotationClassMatchProcessor);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided SubclassMatchProcessor if classes are found on the classpath that extend the specified
     * superclass.
     * 
     * @param superclass
     *            The superclass to match (i.e. the class that subclasses need to extend to match).
     * @param subclassMatchProcessor
     *            the SubclassMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public <T> FastClasspathScanner matchSubclassesOf(final Class<T> superclass,
            final SubclassMatchProcessor<T> subclassMatchProcessor) {
        getScanSpec().matchSubclassesOf(superclass, subclassMatchProcessor);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided SubinterfaceMatchProcessor if an interface that extends a given superinterface is found on
     * the classpath.
     * 
     * @param superinterface
     *            The superinterface to match (i.e. the interface that subinterfaces need to extend to match).
     * @param subinterfaceMatchProcessor
     *            the SubinterfaceMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public <T> FastClasspathScanner matchSubinterfacesOf(final Class<T> superinterface,
            final SubinterfaceMatchProcessor<T> subinterfaceMatchProcessor) {
        getScanSpec().matchSubinterfacesOf(superinterface, subinterfaceMatchProcessor);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided InterfaceMatchProcessor for classes on the classpath that implement the specified
     * interface or a subinterface, or whose superclasses implement the specified interface or a sub-interface.
     * 
     * @param implementedInterface
     *            The interface that classes need to implement.
     * @param interfaceMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public <T> FastClasspathScanner matchClassesImplementing(final Class<T> implementedInterface,
            final ImplementingClassMatchProcessor<T> interfaceMatchProcessor) {
        getScanSpec().matchClassesImplementing(implementedInterface, interfaceMatchProcessor);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided ClassMatchProcessor for classes on the classpath that have a field of the given type.
     * Matches classes that have fields of the given type, array fields with an element type of the given type, and
     * fields of parameterized type that have a type parameter of the given type.
     * 
     * Calls enableFieldTypeIndexing() for you.
     * 
     * @param fieldType
     *            The type of the field to match..
     * @param classMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public <T> FastClasspathScanner matchClassesWithFieldOfType(final Class<T> fieldType,
            final ClassMatchProcessor classMatchProcessor) {
        enableFieldTypeIndexing();
        getScanSpec().matchClassesWithFieldOfType(fieldType, classMatchProcessor);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided ClassAnnotationMatchProcessor if classes are found on the classpath that have the
     * specified annotation.
     * 
     * @param annotation
     *            The class annotation to match.
     * @param classAnnotationMatchProcessor
     *            the ClassAnnotationMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchClassesWithAnnotation(final Class<?> annotation,
            final ClassAnnotationMatchProcessor classAnnotationMatchProcessor) {
        getScanSpec().matchClassesWithAnnotation(annotation, classAnnotationMatchProcessor);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided MethodAnnotationMatchProcessor if classes are found on the classpath that have one or more
     * methods with the specified annotation.
     * 
     * Calls enableMethodAnnotationIndexing() for you.
     * 
     * @param annotation
     *            The method annotation to match.
     * @param methodAnnotationMatchProcessor
     *            the MethodAnnotationMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchClassesWithMethodAnnotation(final Class<? extends Annotation> annotation,
            final MethodAnnotationMatchProcessor methodAnnotationMatchProcessor) {
        enableMethodAnnotationIndexing();
        getScanSpec().matchClassesWithMethodAnnotation(annotation, methodAnnotationMatchProcessor);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided FieldAnnotationMatchProcessor if classes are found on the classpath that have one or more
     * fields with the specified annotation.
     * 
     * Calls enableFieldAnnotationIndexing() for you.
     * 
     * @param annotation
     *            The field annotation to match.
     * @param fieldAnnotationMatchProcessor
     *            the FieldAnnotationMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchClassesWithFieldAnnotation(final Class<? extends Annotation> annotation,
            final FieldAnnotationMatchProcessor fieldAnnotationMatchProcessor) {
        enableFieldAnnotationIndexing();
        getScanSpec().matchClassesWithFieldAnnotation(annotation, fieldAnnotationMatchProcessor);
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
     * Calls the given StaticFinalFieldMatchProcessor if classes are found on the classpath that contain static
     * final fields that match one of a set of fully-qualified field names, e.g.
     * "com.package.ClassName.STATIC_FIELD_NAME".
     * 
     * Field values are obtained from the constant pool in classfiles, not from a loaded class using reflection.
     * This allows you to detect changes to the classpath and then run another scan that picks up the new values of
     * selected static constants without reloading the class. (Class reloading is fraught with issues, see:
     * http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html )
     * 
     * Note: Only static final fields with constant-valued literals are matched, not fields with initializer values
     * that are the result of an expression or reference, except for cases where the compiler is able to simplify an
     * expression into a single constant at compiletime, such as in the case of string concatenation.
     * 
     * Note that the visibility of the fields is not checked if ignoreFieldVisibility() was called before scan().
     * 
     * @param fullyQualifiedStaticFinalFieldNames
     *            The set of fully-qualified static field names to match.
     * @param staticFinalFieldMatchProcessor
     *            the StaticFinalFieldMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchStaticFinalFieldNames(final Set<String> fullyQualifiedStaticFinalFieldNames,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        getScanSpec().matchStaticFinalFieldNames(fullyQualifiedStaticFinalFieldNames,
                staticFinalFieldMatchProcessor);
        return this;
    }

    /**
     * Calls the given StaticFinalFieldMatchProcessor if classes are found on the classpath that contain static
     * final fields that match a fully-qualified field name, e.g. "com.package.ClassName.STATIC_FIELD_NAME".
     * 
     * Field values are obtained from the constant pool in classfiles, *not* from a loaded class using reflection.
     * This allows you to detect changes to the classpath and then run another scan that picks up the new values of
     * selected static constants without reloading the class. (Class reloading is fraught with issues, see:
     * http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html )
     * 
     * Note: Only static final fields with constant-valued literals are matched, not fields with initializer values
     * that are the result of an expression or reference, except for cases where the compiler is able to simplify an
     * expression into a single constant at compiletime, such as in the case of string concatenation.
     * 
     * Note that the visibility of the fields is not checked if ignoreFieldVisibility() was called before scan().
     * 
     * @param fullyQualifiedStaticFinalFieldName
     *            The fully-qualified static field name to match
     * @param staticFinalFieldMatchProcessor
     *            the StaticFinalFieldMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchStaticFinalFieldNames(final String fullyQualifiedStaticFinalFieldName,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        getScanSpec().matchStaticFinalFieldNames(fullyQualifiedStaticFinalFieldName,
                staticFinalFieldMatchProcessor);
        return this;
    }

    /**
     * Calls the given StaticFinalFieldMatchProcessor if classes are found on the classpath that contain static
     * final fields that match one of a list of fully-qualified field names, e.g.
     * "com.package.ClassName.STATIC_FIELD_NAME".
     * 
     * Field values are obtained from the constant pool in classfiles, *not* from a loaded class using reflection.
     * This allows you to detect changes to the classpath and then run another scan that picks up the new values of
     * selected static constants without reloading the class. (Class reloading is fraught with issues, see:
     * http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html )
     * 
     * Note: Only static final fields with constant-valued literals are matched, not fields with initializer values
     * that are the result of an expression or reference, except for cases where the compiler is able to simplify an
     * expression into a single constant at compiletime, such as in the case of string concatenation.
     * 
     * Note that the visibility of the fields is not checked if ignoreFieldVisibility() was called before scan().
     * 
     * @param fullyQualifiedStaticFinalFieldNames
     *            The list of fully-qualified static field names to match.
     * @param staticFinalFieldMatchProcessor
     *            the StaticFinalFieldMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchStaticFinalFieldNames(final String[] fullyQualifiedStaticFinalFieldNames,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        getScanSpec().matchStaticFinalFieldNames(fullyQualifiedStaticFinalFieldNames,
                staticFinalFieldMatchProcessor);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath with the given regexp pattern in their
     * path.
     * 
     * @param pathRegexp
     *            The regexp to match, e.g. "app/templates/.*\\.html"
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchFilenamePattern(final String pathRegexp,
            final FileMatchProcessor fileMatchProcessor) {
        getScanSpec().matchFilenamePattern(pathRegexp, fileMatchProcessor);
        return this;
    }

    /**
     * Calls the given FileMatchContentsProcessor if files are found on the classpath with the given regexp pattern
     * in their path.
     * 
     * @param pathRegexp
     *            The regexp to match, e.g. "app/templates/.*\\.html"
     * @param fileMatchContentsProcessor
     *            The FileMatchContentsProcessor to call when each match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchFilenamePattern(final String pathRegexp,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        getScanSpec().matchFilenamePattern(pathRegexp, fileMatchContentsProcessor);
        return this;
    }

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath with the given regexp
     * pattern in their path.
     * 
     * @param pathRegexp
     *            The regexp to match, e.g. "app/templates/.*\\.html"
     * @param fileMatchProcessorWithContext
     *            The FileMatchProcessorWithContext to call when each match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchFilenamePattern(final String pathRegexp,
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        getScanSpec().matchFilenamePattern(pathRegexp, fileMatchProcessorWithContext);
        return this;
    }

    /**
     * Calls the given FileMatchContentsProcessorWithContext if files are found on the classpath with the given
     * regexp pattern in their path.
     * 
     * @param pathRegexp
     *            The regexp to match, e.g. "app/templates/.*\\.html"
     * @param fileMatchContentsProcessorWithContext
     *            The FileMatchContentsProcessorWithContext to call when each match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchFilenamePattern(final String pathRegexp,
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        getScanSpec().matchFilenamePattern(pathRegexp, fileMatchContentsProcessorWithContext);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that exactly match the given relative
     * path.
     * 
     * @param relativePathToMatch
     *            The complete path to match relative to the classpath entry, e.g.
     *            "app/templates/WidgetTemplate.html"
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchFilenamePath(final String relativePathToMatch,
            final FileMatchProcessor fileMatchProcessor) {
        getScanSpec().matchFilenamePath(relativePathToMatch, fileMatchProcessor);
        return this;
    }

    /**
     * Calls the given FileMatchContentsProcessor if files are found on the classpath that exactly match the given
     * relative path.
     * 
     * @param relativePathToMatch
     *            The complete path to match relative to the classpath entry, e.g.
     *            "app/templates/WidgetTemplate.html"
     * @param fileMatchContentsProcessor
     *            The FileMatchContentsProcessor to call when each match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchFilenamePath(final String relativePathToMatch,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        getScanSpec().matchFilenamePath(relativePathToMatch, fileMatchContentsProcessor);
        return this;
    }

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath that exactly match the
     * given relative path.
     * 
     * @param relativePathToMatch
     *            The complete path to match relative to the classpath entry, e.g.
     *            "app/templates/WidgetTemplate.html"
     * @param fileMatchProcessorWithContext
     *            The FileMatchProcessorWithContext to call when each match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchFilenamePath(final String relativePathToMatch,
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        getScanSpec().matchFilenamePath(relativePathToMatch, fileMatchProcessorWithContext);
        return this;
    }

    /**
     * Calls the given FileMatchContentsProcessorWithContext if files are found on the classpath that exactly match
     * the given relative path.
     * 
     * @param relativePathToMatch
     *            The complete path to match relative to the classpath entry, e.g.
     *            "app/templates/WidgetTemplate.html"
     * @param fileMatchContentsProcessorWithContext
     *            The FileMatchContentsProcessorWithContext to call when each match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchFilenamePath(final String relativePathToMatch,
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        getScanSpec().matchFilenamePath(relativePathToMatch, fileMatchContentsProcessorWithContext);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that exactly match the given path
     * leafname.
     * 
     * @param pathLeafToMatch
     *            The complete path leaf to match, e.g. "WidgetTemplate.html"
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchProcessor fileMatchProcessor) {
        getScanSpec().matchFilenamePathLeaf(pathLeafToMatch, fileMatchProcessor);
        return this;
    }

    /**
     * Calls the given FileMatchContentsProcessor if files are found on the classpath that exactly match the given
     * path leafname.
     * 
     * @param pathLeafToMatch
     *            The complete path leaf to match, e.g. "WidgetTemplate.html"
     * @param fileMatchContentsProcessor
     *            The FileMatchContentsProcessor to call when each match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        getScanSpec().matchFilenamePathLeaf(pathLeafToMatch, fileMatchContentsProcessor);
        return this;
    }

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath that exactly match the
     * given path leafname.
     * 
     * @param pathLeafToMatch
     *            The complete path leaf to match, e.g. "WidgetTemplate.html"
     * @param fileMatchProcessorWithContext
     *            The FileMatchProcessorWithContext to call when each match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        getScanSpec().matchFilenamePathLeaf(pathLeafToMatch, fileMatchProcessorWithContext);
        return this;
    }

    /**
     * Calls the given FileMatchContentsProcessorWithContext if files are found on the classpath that exactly match
     * the given path leafname.
     * 
     * @param pathLeafToMatch
     *            The complete path leaf to match, e.g. "WidgetTemplate.html"
     * @param fileMatchContentsProcessorWithContext
     *            The FileMatchContentsProcessorWithContext to call when each match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        getScanSpec().matchFilenamePathLeaf(pathLeafToMatch, fileMatchContentsProcessorWithContext);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that have the given file extension.
     * 
     * @param extensionToMatch
     *            The extension to match, e.g. "html" matches "WidgetTemplate.html" and "WIDGET.HTML".
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchFilenameExtension(final String extensionToMatch,
            final FileMatchProcessor fileMatchProcessor) {
        getScanSpec().matchFilenameExtension(extensionToMatch, fileMatchProcessor);
        return this;
    }

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that have the given file extension.
     * 
     * @param extensionToMatch
     *            The extension to match, e.g. "html" matches "WidgetTemplate.html".
     * @param fileMatchContentsProcessor
     *            The FileMatchContentsProcessor to call when each match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchFilenameExtension(final String extensionToMatch,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        getScanSpec().matchFilenameExtension(extensionToMatch, fileMatchContentsProcessor);
        return this;
    }

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath that have the given file
     * extension.
     * 
     * @param extensionToMatch
     *            The extension to match, e.g. "html" matches "WidgetTemplate.html" and "WIDGET.HTML".
     * @param fileMatchProcessorWithContext
     *            The FileMatchProcessorWithContext to call when each match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchFilenameExtension(final String extensionToMatch,
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        getScanSpec().matchFilenameExtension(extensionToMatch, fileMatchProcessorWithContext);
        return this;
    }

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath that have the given file
     * extension.
     * 
     * @param extensionToMatch
     *            The extension to match, e.g. "html" matches "WidgetTemplate.html".
     * @param fileMatchContentsProcessorWithContext
     *            The FileMatchContentsProcessorWithContext to call when each match is found.
     * @return this (for method chaining).
     */
    public FastClasspathScanner matchFilenameExtension(final String extensionToMatch,
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        getScanSpec().matchFilenameExtension(extensionToMatch, fileMatchContentsProcessorWithContext);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Asynchronously scans the classpath for matching files, and if scanResultProcessor is non-null, also calls any
     * MatchProcessors if a match is identified.
     * 
     * @param executorService
     *            A custom ExecutorService to use for scheduling worker tasks.
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning. Ideally the ExecutorService will have at least this many threads available.
     * @param isAsyncScan
     *            If true, this is an async scan -- don't allow running from class initializers, in order to prevent
     *            a class initializer deadlock.
     * @param scanResultProcessor
     *            If non-null, specifies a callback to run on the ScanResult after asynchronous scanning has
     *            completed and MatchProcessors have been run.
     * @param failureHandler
     *            If non-null, specifies a callback to run if an exception is thrown during an asynchronous scan. If
     *            a FailureHandler is provided and an exception is thrown, the resulting Future's get() method will
     *            return null rather than throwing an ExecutionException.
     * 
     * @return a Future<ScanResult> object, that when resolved using get() yields a new ScanResult object. You can
     *         call cancel(true) on this Future if you want to interrupt the scan.
     */
    private Future<ScanResult> launchAsyncScan(final ExecutorService executorService, final int numParallelTasks,
            final boolean isAsyncScan, final ScanResultProcessor scanResultProcessor,
            final FailureHandler failureHandler) {
        final ScanSpec scanSpec = getScanSpec();
        if (isAsyncScan && scanSpec.hasMatchProcessors()) {
            // Disallow MatchProcessors when launched asynchronously from a class initializer, to prevent class
            // initializer deadlock if any of the MatchProcessors try to refer to the incompletely-initialized
            // class -- see bug #103.
            try {
                try {
                    // Generate stacktrace, so that we can get caller info
                    throw new Exception();
                } catch (final Exception e) {
                    final StackTraceElement[] elts = e.getStackTrace();
                    for (final StackTraceElement elt : elts) {
                        if ("<clinit>".equals(elt.getMethodName())) {
                            throw new RuntimeException("Cannot use MatchProcessors while launching a scan "
                                    + "from a class initialization block (for class " + elt.getClassName()
                                    + "), as this can lead to a class initializer deadlock. See: "
                                    + "https://github.com/lukehutch/fast-classpath-scanner/issues/103");
                        }
                    }
                }
            } catch (final RuntimeException e) {
                // Re-catch the RuntimeException so we have the stacktrace for the above failure
                if (failureHandler == null) {
                    throw e;
                } else {
                    if (log != null) {
                        log.log(e);
                        log.flush();
                    }
                    failureHandler.onFailure(e);
                    return executorService.submit(new Callable<ScanResult>() {
                        @Override
                        public ScanResult call() throws Exception {
                            // Return null from the Future if a FailureHandler was added and there was an exception
                            return null;
                        }
                    });
                }
            }
        }
        return executorService.submit(
                // Call MatchProcessors before returning if in async scanning mode
                new Scanner(scanSpec, executorService, numParallelTasks, /* enableRecursiveScanning = */ true,
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
            // If scanResultProcessor is null, the scan won't do anything after completion,
            // and the ScanResult will simply be lost.
            throw new IllegalArgumentException("scanResultProcessor cannot be null");
        }
        if (failureHandler == null) {
            // The result of the Future<ScanObject> object returned by launchAsyncScan is discarded below,
            // so we force the addition of a FailureHandler so that exceptions are not silently swallowed.
            throw new IllegalArgumentException("failureHandler cannot be null");
        }
        // Drop the returned Future<ScanResult>, a ScanResultProcessor is used instead
        launchAsyncScan(executorService, numParallelTasks, /* isAsyncScan = */ true, new ScanResultProcessor() {
            @Override
            public void processScanResult(final ScanResult scanResult) {
                // Call any MatchProcessors after scan has completed
                getScanSpec().callMatchProcessors(scanResult);
                // Call the provided ScanResultProcessor
                scanResultProcessor.processScanResult(scanResult);
                // Free temporary files if necessary
                if (scanSpec.removeTemporaryFilesAfterScan) {
                    scanResult.freeTempFiles(log);
                }
            }
        }, failureHandler);
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
        return launchAsyncScan(executorService, numParallelTasks, isAsyncScan,
                runMatchProcessorsOnWorkerThread ? new ScanResultProcessor() {
                    @Override
                    public void processScanResult(final ScanResult scanResult) {
                        // Call MatchProcessors after scan has completed
                        getScanSpec().callMatchProcessors(scanResult);
                        // Free temporary files if necessary
                        if (scanSpec.removeTemporaryFilesAfterScan) {
                            scanResult.freeTempFiles(log);
                        }
                    }
                } : null, /* failureHandler = */ null);
    }

    /**
     * Asynchronously scans the classpath for matching files, and calls any MatchProcessors if a match is
     * identified. Returns a Future object immediately after starting the scan. To block on scan completion, get the
     * result of the returned Future. Uses the provided ExecutorService, and divides the work according to the
     * requested degree of parallelism. This method should be called after all required MatchProcessors have been
     * added.
     * 
     * Note on thread safety: MatchProcessors are all run on a separate thread from the thread that calls this
     * method (although the MatchProcessors are all run on one thread). You will need to add your own
     * synchronization logic if MatchProcessors interact with the main thread. See the following for more info:
     * 
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
     * @throws MatchProcessorException
     *             if classloading fails for any of the classes matched by a MatchProcessor, or if a MatchProcessor
     *             throws an exception.
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
            // Start the scan, and then wait for scan completion
            final ScanResult scanResult = scanAsync(executorService, numParallelTasks, /* isAsyncScan = */ false,
                    /* runMatchProcessorsOnWorkerThread = */ false).get();

            // Call MatchProcessors in the same thread as the caller, to avoid deadlock (see bug #103)
            getScanSpec().callMatchProcessors(scanResult);

            // Free temporary files
            if (scanSpec.removeTemporaryFilesAfterScan) {
                scanResult.freeTempFiles(log);
            }

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
            } else if (cause instanceof MatchProcessorException) {
                if (log != null) {
                    log.log("Exception during scan", e);
                }
                throw (MatchProcessorException) cause;
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
     * @throws MatchProcessorException
     *             if classloading fails for any of the classes matched by a MatchProcessor, or if a MatchProcessor
     *             throws an exception.
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
     * @throws MatchProcessorException
     *             if classloading fails for any of the classes matched by a MatchProcessor, or if a MatchProcessor
     *             throws an exception.
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
     * See the following for info on thread safety:
     * 
     * https://github.com/lukehutch/fast-classpath-scanner/wiki/1.-Usage#multithreading-issues
     * 
     * Note that if there are nested jarfiles on the classpath, e.g. file:///path/to/jar1.jar!/path/to/jar2.jar ,
     * then both FastClasspathScanner#scanAsync() and FastClasspathScanner#getUniqueClasspathElementsAsync() will
     * cause jar2.jar to be extracted to a temporary file, however
     * FastClasspathScanner#getUniqueClasspathElementsAsync() will not remove this temporary file after the scan (so
     * that the file is still accessible to the caller -- each of the File objects in the returned list of classpath
     * elements should exist). These extracted temporary files are marked for deletion on JVM exit, however.
     * 
     * @param executorService
     *            A custom ExecutorService to use for scheduling worker tasks.
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning. Ideally the ExecutorService will have at least this many threads available.
     * @return a Future<List<File>>, that when resolved with get() returns a list of the unique directories and
     *         jarfiles on the classpath, in classpath resolution order. You can call cancel(true) on this Future if
     *         you want to interrupt the process (although the result is typically returned quickly).
     */
    public Future<List<File>> getUniqueClasspathElementsAsync(final ExecutorService executorService,
            final int numParallelTasks) {
        // No need to call disallowCallingFromClassInitializer() here, because no MatchProcessors are run,
        // so class initializer deadlock cannot occur.
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
                    // elements resulting from jars within jars are left in place. However, they are cleaned
                    // up on normal JVM exit.
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
     * Note that if there are nested jarfiles on the classpath, e.g. file:///path/to/jar1.jar!/path/to/jar2.jar ,
     * then both FastClasspathScanner#scan() and FastClasspathScanner#getUniqueClasspathElements() will cause
     * jar2.jar to be extracted to a temporary file, however FastClasspathScanner#getUniqueClasspathElements() will
     * not remove this temporary file after the scan (so that the file is still accessible to the caller -- each of
     * the File objects in the returned list of classpath elements should exist). These extracted temporary files
     * are marked for deletion on JVM exit, however.
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
     * @return a List<File> consisting of the unique directories and jarfiles on the classpath, in classpath
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
     * Note that if there are nested jarfiles on the classpath, e.g. file:///path/to/jar1.jar!/path/to/jar2.jar ,
     * then both FastClasspathScanner#scan() and FastClasspathScanner#getUniqueClasspathElements() will cause
     * jar2.jar to be extracted to a temporary file, however FastClasspathScanner#getUniqueClasspathElements() will
     * not remove this temporary file after the scan (so that the file is still accessible to the caller -- each of
     * the File objects in the returned list of classpath elements should exist). These extracted temporary files
     * are marked for deletion on JVM exit, however.
     * 
     * @throws ScanInterruptedException
     *             if any of the worker threads are interrupted during the scan (shouldn't happen under normal
     *             circumstances).
     * @return a List<File> consisting of the unique directories and jarfiles on the classpath, in classpath
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
     * Note that if there are nested jarfiles on the classpath, e.g. file:///path/to/jar1.jar!/path/to/jar2.jar ,
     * then both FastClasspathScanner#scan() and FastClasspathScanner#getUniqueClasspathElements() will cause
     * jar2.jar to be extracted to a temporary file, however FastClasspathScanner#getUniqueClasspathElements() will
     * not remove this temporary file after the scan (so that the file is still accessible to the caller -- each of
     * the File objects in the returned list of classpath elements should exist). These extracted temporary files
     * are marked for deletion on JVM exit, however.
     * 
     * @throws ScanInterruptedException
     *             if any of the worker threads are interrupted during the scan (shouldn't happen under normal
     *             circumstances).
     * @return a the unique directories and jarfiles on the classpath, in classpath resolution order, as a path
     *         string.
     */
    public String getUniqueClasspathElementsAsPathStr() {
        final StringBuilder buf = new StringBuilder();
        for (final File f : getUniqueClasspathElements()) {
            if (buf.length() > 0) {
                buf.append(File.pathSeparatorChar);
            }
            buf.append(f.toString());
        }
        return buf.toString();
    }
}
