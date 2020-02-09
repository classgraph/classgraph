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
package io.github.classgraph;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;

import nonapi.io.github.classgraph.classpath.SystemJarFinder;
import nonapi.io.github.classgraph.concurrency.AutoCloseableExecutorService;
import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.scanspec.WhiteBlackList;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.VersionFinder;

/**
 * Uber-fast, ultra-lightweight Java classpath and module path scanner. Scans classfiles in the classpath and/or
 * module path by parsing the classfile binary format directly rather than by using reflection.
 *
 * <p>
 * Documentation: <a href= "https://github.com/classgraph/classgraph/wiki">
 * https://github.com/classgraph/classgraph/wiki</a>
 */
public class ClassGraph {
    /** The scanning specification. */
    ScanSpec scanSpec = new ScanSpec();

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

    /** If non-null, log while scanning. */
    private LogNode topLevelLog;

    // -------------------------------------------------------------------------------------------------------------

    /** Construct a ClassGraph instance. */
    public ClassGraph() {
        // Initialize ScanResult, if this is the first call to ClassGraph constructor
        ScanResult.init();
    }

    /**
     * Get the version number of ClassGraph.
     *
     * @return the ClassGraph version, or "unknown" if it could not be determined.
     */
    public static String getVersion() {
        return VersionFinder.getVersion();
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
     * Enables the scanning of classfiles, producing {@link ClassInfo} objects in the {@link ScanResult}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableClassInfo() {
        scanSpec.enableClassInfo = true;
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
     * scan. This information can be obtained using {@link ClassInfo#getAnnotationInfo()},
     * {@link FieldInfo#getAnnotationInfo()}, and {@link MethodParameterInfo#getAnnotationInfo()}. By default,
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
        scanSpec.scanNestedJars = false;
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

    /**
     * Disables the scanning of modules.
     *
     * @return this (for method chaining).
     */
    public ClassGraph disableModuleScanning() {
        scanSpec.scanModules = false;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Causes ClassGraph to return classes that are not in the whitelisted packages, but that are directly referred
     * to by classes within whitelisted packages as a superclass, implemented interface or annotation.
     * (Automatically calls {@link #enableClassInfo()}.)
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableExternalClasses() {
        enableClassInfo();
        scanSpec.enableExternalClasses = true;
        return this;
    }

    /**
     * Causes classes loaded using {@link ClassInfo#loadClass()} to be are initialized after class loading (the
     * default is to not initialize classes).
     *
     * @return this (for method chaining).
     */
    public ClassGraph initializeLoadedClasses() {
        scanSpec.initializeLoadedClasses = true;
        return this;
    }

    /**
     * Remove temporary files, including nested jarfiles (jarfiles within jarfiles, which have to be extracted
     * during scanning in order to be read) from their temporary directory as soon as the scan has completed. The
     * default is for temporary files to be removed by the {@link ScanResult} finalizer, or on JVM exit.
     *
     * @return this (for method chaining).
     */
    public ClassGraph removeTemporaryFilesAfterScan() {
        scanSpec.removeTemporaryFilesAfterScan = true;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Override the automatically-detected classpath with a custom path, with path elements separated by
     * File.pathSeparatorChar. Causes system ClassLoaders and the java.class.path system property to be ignored.
     * Also causes modules not to be scanned.
     *
     * <p>
     * If this method is called, nothing but the provided classpath will be scanned, i.e. this causes ClassLoaders
     * to be ignored, as well as the java.class.path system property.
     *
     * @param overrideClasspath
     *            The custom classpath to use for scanning, with path elements separated by File.pathSeparatorChar.
     * @return this (for method chaining).
     */
    public ClassGraph overrideClasspath(final String overrideClasspath) {
        if (overrideClasspath.isEmpty()) {
            throw new IllegalArgumentException("Can't override classpath with an empty path");
        }
        for (final String classpathElement : JarUtils.smartPathSplit(overrideClasspath, scanSpec)) {
            scanSpec.addClasspathOverride(classpathElement);
        }
        return this;
    }

    /**
     * Override the automatically-detected classpath with a custom path. Causes system ClassLoaders and the
     * java.class.path system property to be ignored. Also causes modules not to be scanned.
     * 
     * <p>
     * Works for Iterables of any type whose toString() method resolves to a classpath element string, e.g. String,
     * File or Path.
     *
     * @param overrideClasspathElements
     *            The custom classpath to use for scanning, with path elements separated by File.pathSeparatorChar.
     * @return this (for method chaining).
     */
    public ClassGraph overrideClasspath(final Iterable<?> overrideClasspathElements) {
        if (!overrideClasspathElements.iterator().hasNext()) {
            throw new IllegalArgumentException("Can't override classpath with an empty path");
        }
        for (final Object classpathElement : overrideClasspathElements) {
            scanSpec.addClasspathOverride(classpathElement);
        }
        return this;
    }

    /**
     * Override the automatically-detected classpath with a custom path. Causes system ClassLoaders and the
     * java.class.path system property to be ignored. Also causes modules not to be scanned.
     * 
     * <p>
     * Works for arrays of any member type whose toString() method resolves to a classpath element string, e.g.
     * String, File or Path.
     *
     * @param overrideClasspathElements
     *            The custom classpath to use for scanning, with path elements separated by File.pathSeparatorChar.
     * @return this (for method chaining).
     */
    public ClassGraph overrideClasspath(final Object... overrideClasspathElements) {
        if (overrideClasspathElements.length == 0) {
            throw new IllegalArgumentException("Can't override classpath with an empty path");
        }
        for (final Object classpathElement : overrideClasspathElements) {
            scanSpec.addClasspathOverride(classpathElement);
        }
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
         * Whether or not to include a given classpath element in the scan.
         *
         * @param classpathElementPathStr
         *            The path string of a classpath element, normalized so that the path separator is '/'. This
         *            will usually be a file path, but could be a URL, or it could be a path for a nested jar, where
         *            the paths are separated using '!', in Java convention. "jar:" and/or "file:" will have been
         *            stripped from the beginning, if they were present in the classpath.
         * @return true if the path string passed is a path you want to scan.
         */
        boolean includeClasspathElement(String classpathElementPathStr);
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
    public ClassGraph filterClasspathElements(final ClasspathElementFilter classpathElementFilter) {
        scanSpec.filterClasspathElements(classpathElementFilter);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add a ClassLoader to the list of ClassLoaders to scan.
     *
     * <p>
     * This call is ignored if {@link #overrideClasspath(String)} is also called, or if this method is called before
     * {@link #overrideClassLoaders(ClassLoader...)}.
     *
     * @param classLoader
     *            The additional ClassLoader to scan.
     * @return this (for method chaining).
     */
    public ClassGraph addClassLoader(final ClassLoader classLoader) {
        scanSpec.addClassLoader(classLoader);
        return this;
    }

    /**
     * Completely override (and ignore) system ClassLoaders and the java.class.path system property. Also causes
     * modules not to be scanned. Note that you may want to use this together with
     * {@link #ignoreParentClassLoaders()} to extract classpath URLs from only the classloaders you specified in the
     * parameter to `overrideClassLoaders`, and not their parent classloaders.
     *
     * <p>
     * This call is ignored if {@link #overrideClasspath(String)} is called.
     *
     * @param overrideClassLoaders
     *            The ClassLoaders to scan instead of the automatically-detected ClassLoaders.
     * @return this (for method chaining).
     */
    public ClassGraph overrideClassLoaders(final ClassLoader... overrideClassLoaders) {
        scanSpec.overrideClassLoaders(overrideClassLoaders);
        return this;
    }

    /**
     * Ignore parent classloaders (i.e. only obtain paths to scan from classloaders that are not the parent of
     * another classloader).
     *
     * @return this (for method chaining).
     */
    public ClassGraph ignoreParentClassLoaders() {
        scanSpec.ignoreParentClassLoaders = true;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add a ModuleLayer to the list of ModuleLayers to scan. Use this method if you define your own ModuleLayer,
     * but the scanning code is not running within that custom ModuleLayer.
     *
     * <p>
     * This call is ignored if it is called before {@link #overrideModuleLayers(Object...)}.
     *
     * @param moduleLayer
     *            The additional ModuleLayer to scan. (The parameter is of type {@link Object} for backwards
     *            compatibility with JDK 7 and JDK 8, but the argument should be of type ModuleLayer.)
     * @return this (for method chaining).
     */
    public ClassGraph addModuleLayer(final Object moduleLayer) {
        scanSpec.addModuleLayer(moduleLayer);
        return this;
    }

    /**
     * Completely override (and ignore) the visible ModuleLayers, and instead scan the requested ModuleLayers.
     *
     * <p>
     * This call is ignored if overrideClasspath() is called.
     *
     * @param overrideModuleLayers
     *            The ModuleLayers to scan instead of the automatically-detected ModuleLayers. (The parameter is of
     *            type {@link Object}[] for backwards compatibility with JDK 7 and JDK 8, but the argument should be
     *            of type ModuleLayer[].)
     * @return this (for method chaining).
     */
    public ClassGraph overrideModuleLayers(final Object... overrideModuleLayers) {
        scanSpec.overrideModuleLayers(overrideModuleLayers);
        return this;
    }

    /**
     * Ignore parent module layers (i.e. only scan module layers that are not the parent of another module layer).
     *
     * @return this (for method chaining).
     */
    public ClassGraph ignoreParentModuleLayers() {
        scanSpec.ignoreParentModuleLayers = true;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan one or more specific packages and their sub-packages.
     * 
     * <p>
     * N.B. Automatically calls {@link #enableClassInfo()} -- call {@link #whitelistPaths(String...)} instead if you
     * only need to scan resources.
     *
     * @param packageNames
     *            The fully-qualified names of packages to scan (using '.' as a separator). May include a glob
     *            wildcard ({@code '*'}).
     * @return this (for method chaining).
     */
    public ClassGraph whitelistPackages(final String... packageNames) {
        enableClassInfo();
        for (final String packageName : packageNames) {
            final String packageNameNormalized = WhiteBlackList.normalizePackageOrClassName(packageName);
            if (packageNameNormalized.startsWith("!") || packageNameNormalized.startsWith("-")) {
                throw new IllegalArgumentException(
                        "This style of whitelisting/blacklisting is no longer supported: " + packageNameNormalized);
            }
            // Whitelist package
            scanSpec.packageWhiteBlackList.addToWhitelist(packageNameNormalized);
            final String path = WhiteBlackList.packageNameToPath(packageNameNormalized);
            scanSpec.pathWhiteBlackList.addToWhitelist(path + "/");
            if (packageNameNormalized.isEmpty()) {
                scanSpec.pathWhiteBlackList.addToWhitelist("");
            }
            if (!packageNameNormalized.contains("*")) {
                // Whitelist sub-packages
                if (packageNameNormalized.isEmpty()) {
                    scanSpec.packagePrefixWhiteBlackList.addToWhitelist("");
                    scanSpec.pathPrefixWhiteBlackList.addToWhitelist("");
                } else {
                    scanSpec.packagePrefixWhiteBlackList.addToWhitelist(packageNameNormalized + ".");
                    scanSpec.pathPrefixWhiteBlackList.addToWhitelist(path + "/");
                }
            }
        }
        return this;
    }

    /**
     * Scan one or more specific paths, and their sub-directories or nested paths.
     *
     * @param paths
     *            The paths to scan, relative to the package root of the classpath element (with '/' as a
     *            separator). May include a glob wildcard ({@code '*'}).
     * @return this (for method chaining).
     */
    public ClassGraph whitelistPaths(final String... paths) {
        for (final String path : paths) {
            final String pathNormalized = WhiteBlackList.normalizePath(path);
            // Whitelist path
            final String packageName = WhiteBlackList.pathToPackageName(pathNormalized);
            scanSpec.packageWhiteBlackList.addToWhitelist(packageName);
            scanSpec.pathWhiteBlackList.addToWhitelist(pathNormalized + "/");
            if (pathNormalized.isEmpty()) {
                scanSpec.pathWhiteBlackList.addToWhitelist("");
            }
            if (!pathNormalized.contains("*")) {
                // Whitelist sub-directories / nested paths
                if (pathNormalized.isEmpty()) {
                    scanSpec.packagePrefixWhiteBlackList.addToWhitelist("");
                    scanSpec.pathPrefixWhiteBlackList.addToWhitelist("");
                } else {
                    scanSpec.packagePrefixWhiteBlackList.addToWhitelist(packageName + ".");
                    scanSpec.pathPrefixWhiteBlackList.addToWhitelist(pathNormalized + "/");
                }
            }
        }
        return this;
    }

    /**
     * Scan one or more specific packages, without recursively scanning sub-packages unless they are themselves
     * whitelisted.
     * 
     * <p>
     * N.B. Automatically calls {@link #enableClassInfo()} -- call {@link #whitelistPathsNonRecursive(String...)}
     * instead if you only need to scan resources.
     * 
     * <p>
     * This may be particularly useful for scanning the package root ("") without recursively scanning everything in
     * the jar, dir or module.
     *
     * @param packageNames
     *            The fully-qualified names of packages to scan (with '.' as a separator). May not include a glob
     *            wildcard ({@code '*'}).
     * 
     * @return this (for method chaining).
     */
    public ClassGraph whitelistPackagesNonRecursive(final String... packageNames) {
        enableClassInfo();
        for (final String packageName : packageNames) {
            final String packageNameNormalized = WhiteBlackList.normalizePackageOrClassName(packageName);
            if (packageNameNormalized.contains("*")) {
                throw new IllegalArgumentException("Cannot use a glob wildcard here: " + packageNameNormalized);
            }
            // Whitelist package, but not sub-packages
            scanSpec.packageWhiteBlackList.addToWhitelist(packageNameNormalized);
            scanSpec.pathWhiteBlackList
                    .addToWhitelist(WhiteBlackList.packageNameToPath(packageNameNormalized) + "/");
            if (packageNameNormalized.isEmpty()) {
                scanSpec.pathWhiteBlackList.addToWhitelist("");
            }
        }
        return this;
    }

    /**
     * Scan one or more specific paths, without recursively scanning sub-directories or nested paths unless they are
     * themselves whitelisted.
     * 
     * <p>
     * This may be particularly useful for scanning the package root ("") without recursively scanning everything in
     * the jar, dir or module.
     *
     * @param paths
     *            The paths to scan, relative to the package root of the classpath element (with '/' as a
     *            separator). May not include a glob wildcard ({@code '*'}).
     * @return this (for method chaining).
     */
    public ClassGraph whitelistPathsNonRecursive(final String... paths) {
        for (final String path : paths) {
            if (path.contains("*")) {
                throw new IllegalArgumentException("Cannot use a glob wildcard here: " + path);
            }
            final String pathNormalized = WhiteBlackList.normalizePath(path);
            // Whitelist path, but not sub-directories / nested paths
            scanSpec.packageWhiteBlackList.addToWhitelist(WhiteBlackList.pathToPackageName(pathNormalized));
            scanSpec.pathWhiteBlackList.addToWhitelist(pathNormalized + "/");
            if (pathNormalized.isEmpty()) {
                scanSpec.pathWhiteBlackList.addToWhitelist("");
            }
        }
        return this;
    }

    /**
     * Prevent the scanning of one or more specific packages and their sub-packages.
     * 
     * <p>
     * N.B. Automatically calls {@link #enableClassInfo()} -- call {@link #blacklistPaths(String...)} instead if you
     * only need to scan resources.
     *
     * @param packageNames
     *            The fully-qualified names of packages to blacklist (with '.' as a separator). May include a glob
     *            wildcard ({@code '*'}).
     * @return this (for method chaining).
     */
    public ClassGraph blacklistPackages(final String... packageNames) {
        enableClassInfo();
        for (final String packageName : packageNames) {
            final String packageNameNormalized = WhiteBlackList.normalizePackageOrClassName(packageName);
            if (packageNameNormalized.isEmpty()) {
                throw new IllegalArgumentException(
                        "Blacklisting the root package (\"\") will cause nothing to be scanned");
            }
            // Blacklisting always prevents further recursion, no need to blacklist sub-packages
            scanSpec.packageWhiteBlackList.addToBlacklist(packageNameNormalized);
            final String path = WhiteBlackList.packageNameToPath(packageNameNormalized);
            scanSpec.pathWhiteBlackList.addToBlacklist(path + "/");
            if (!packageNameNormalized.contains("*")) {
                // Blacklist sub-packages (zipfile entries can occur in any order)
                scanSpec.packagePrefixWhiteBlackList.addToBlacklist(packageNameNormalized + ".");
                scanSpec.pathPrefixWhiteBlackList.addToBlacklist(path + "/");
            }
        }
        return this;
    }

    /**
     * Prevent the scanning of one or more specific paths and their sub-directories / nested paths.
     *
     * @param paths
     *            The paths to blacklist (with '/' as a separator). May include a glob wildcard ({@code '*'}).
     * @return this (for method chaining).
     */
    public ClassGraph blacklistPaths(final String... paths) {
        for (final String path : paths) {
            final String pathNormalized = WhiteBlackList.normalizePath(path);
            if (pathNormalized.isEmpty()) {
                throw new IllegalArgumentException(
                        "Blacklisting the root package (\"\") will cause nothing to be scanned");
            }
            // Blacklisting always prevents further recursion, no need to blacklist sub-directories / nested paths
            final String packageName = WhiteBlackList.pathToPackageName(pathNormalized);
            scanSpec.packageWhiteBlackList.addToBlacklist(packageName);
            scanSpec.pathWhiteBlackList.addToBlacklist(pathNormalized + "/");
            if (!pathNormalized.contains("*")) {
                // Blacklist sub-directories / nested paths
                scanSpec.packagePrefixWhiteBlackList.addToBlacklist(packageName + ".");
                scanSpec.pathPrefixWhiteBlackList.addToBlacklist(pathNormalized + "/");
            }
        }
        return this;
    }

    /**
     * Scan one or more specific classes, without scanning other classes in the same package unless the package is
     * itself whitelisted.
     * 
     * <p>
     * N.B. Automatically calls {@link #enableClassInfo()}.
     *
     * 
     * @param classNames
     *            The fully-qualified names of classes to scan (using '.' as a separator). May not include a glob
     *            wildcard ({@code '*'}).
     * @return this (for method chaining).
     */
    public ClassGraph whitelistClasses(final String... classNames) {
        enableClassInfo();
        for (final String className : classNames) {
            if (className.contains("*")) {
                throw new IllegalArgumentException("Cannot use a glob wildcard here: " + className);
            }
            final String classNameNormalized = WhiteBlackList.normalizePackageOrClassName(className);
            // Whitelist the class itself
            scanSpec.classWhiteBlackList.addToWhitelist(classNameNormalized);
            scanSpec.classfilePathWhiteBlackList
                    .addToWhitelist(WhiteBlackList.classNameToClassfilePath(classNameNormalized));
            final String packageName = PackageInfo.getParentPackageName(classNameNormalized);
            // Record the package containing the class, so we can recurse to this point even if the package
            // is not itself whitelisted
            scanSpec.classPackageWhiteBlackList.addToWhitelist(packageName);
            scanSpec.classPackagePathWhiteBlackList
                    .addToWhitelist(WhiteBlackList.packageNameToPath(packageName) + "/");
        }
        return this;
    }

    /**
     * Specifically blacklist one or more specific classes, preventing them from being scanned even if they are in a
     * whitelisted package.
     * 
     * <p>
     * N.B. Automatically calls {@link #enableClassInfo()}.
     *
     * @param classNames
     *            The fully-qualified names of classes to blacklist (using '.' as a separator). May not include a
     *            glob wildcard ({@code '*'}).
     * @return this (for method chaining).
     */
    public ClassGraph blacklistClasses(final String... classNames) {
        enableClassInfo();
        for (final String className : classNames) {
            if (className.contains("*")) {
                throw new IllegalArgumentException("Cannot use a glob wildcard here: " + className);
            }
            final String classNameNormalized = WhiteBlackList.normalizePackageOrClassName(className);
            scanSpec.classWhiteBlackList.addToBlacklist(classNameNormalized);
            scanSpec.classfilePathWhiteBlackList
                    .addToBlacklist(WhiteBlackList.classNameToClassfilePath(classNameNormalized));
        }
        return this;
    }

    /**
     * Whitelist one or more jars. This will cause only the whitelisted jars to be scanned.
     *
     * @param jarLeafNames
     *            The leafnames of the jars that should be scanned (e.g. {@code "mylib.jar"}). May contain a
     *            wildcard glob ({@code "mylib-*.jar"}).
     * @return this (for method chaining).
     */
    public ClassGraph whitelistJars(final String... jarLeafNames) {
        for (final String jarLeafName : jarLeafNames) {
            final String leafName = JarUtils.leafName(jarLeafName);
            if (!leafName.equals(jarLeafName)) {
                throw new IllegalArgumentException("Can only whitelist jars by leafname: " + jarLeafName);
            }
            scanSpec.jarWhiteBlackList.addToWhitelist(leafName);
        }
        return this;
    }

    /**
     * Blacklist one or more jars, preventing them from being scanned.
     *
     * @param jarLeafNames
     *            The leafnames of the jars that should be scanned (e.g. {@code "badlib.jar"}). May contain a
     *            wildcard glob ({@code "badlib-*.jar"}).
     * @return this (for method chaining).
     */
    public ClassGraph blacklistJars(final String... jarLeafNames) {
        for (final String jarLeafName : jarLeafNames) {
            final String leafName = JarUtils.leafName(jarLeafName);
            if (!leafName.equals(jarLeafName)) {
                throw new IllegalArgumentException("Can only blacklist jars by leafname: " + jarLeafName);
            }
            scanSpec.jarWhiteBlackList.addToBlacklist(leafName);
        }
        return this;
    }

    /**
     * Add lib or ext jars to whitelist or blacklist.
     *
     * @param whitelist
     *            if true, add to whitelist, otherwise add to blacklist.
     * @param jarLeafNames
     *            the jar leaf names to whitelist
     */
    private void whitelistOrBlacklistLibOrExtJars(final boolean whitelist, final String... jarLeafNames) {
        if (jarLeafNames.length == 0) {
            // If no jar leafnames are given, whitelist or blacklist all lib or ext jars
            for (final String libOrExtJar : SystemJarFinder.getJreLibOrExtJars()) {
                whitelistOrBlacklistLibOrExtJars(whitelist, JarUtils.leafName(libOrExtJar));
            }
        } else {
            for (final String jarLeafName : jarLeafNames) {
                final String leafName = JarUtils.leafName(jarLeafName);
                if (!leafName.equals(jarLeafName)) {
                    throw new IllegalArgumentException("Can only " + (whitelist ? "whitelist" : "blacklist")
                            + " jars by leafname: " + jarLeafName);
                }
                if (jarLeafName.contains("*")) {
                    // Compare wildcarded pattern against all jars in lib and ext dirs 
                    final Pattern pattern = WhiteBlackList.globToPattern(jarLeafName);
                    boolean found = false;
                    for (final String libOrExtJarPath : SystemJarFinder.getJreLibOrExtJars()) {
                        final String libOrExtJarLeafName = JarUtils.leafName(libOrExtJarPath);
                        if (pattern.matcher(libOrExtJarLeafName).matches()) {
                            // Check for "*" in filename to prevent infinite recursion (shouldn't happen)
                            if (!libOrExtJarLeafName.contains("*")) {
                                whitelistOrBlacklistLibOrExtJars(whitelist, libOrExtJarLeafName);
                            }
                            found = true;
                        }
                    }
                    if (!found && topLevelLog != null) {
                        topLevelLog.log("Could not find lib or ext jar matching wildcard: " + jarLeafName);
                    }
                } else {
                    // No wildcards, just whitelist or blacklist the named jar, if present
                    boolean found = false;
                    for (final String libOrExtJarPath : SystemJarFinder.getJreLibOrExtJars()) {
                        final String libOrExtJarLeafName = JarUtils.leafName(libOrExtJarPath);
                        if (jarLeafName.equals(libOrExtJarLeafName)) {
                            if (whitelist) {
                                scanSpec.libOrExtJarWhiteBlackList.addToWhitelist(jarLeafName);
                            } else {
                                scanSpec.libOrExtJarWhiteBlackList.addToBlacklist(jarLeafName);
                            }
                            if (topLevelLog != null) {
                                topLevelLog.log((whitelist ? "Whitelisting" : "Blacklisting") + " lib or ext jar: "
                                        + libOrExtJarPath);
                            }
                            found = true;
                            break;
                        }
                    }
                    if (!found && topLevelLog != null) {
                        topLevelLog.log("Could not find lib or ext jar: " + jarLeafName);
                    }
                }
            }
        }
    }

    /**
     * Whitelist one or more jars in a JRE/JDK "lib/" or "ext/" directory (these directories are not scanned unless
     * {@link #enableSystemJarsAndModules()} is called, by association with the JRE/JDK).
     *
     * @param jarLeafNames
     *            The leafnames of the lib/ext jar(s) that should be scanned (e.g. {@code "mylib.jar"}). May contain
     *            a wildcard glob ({@code '*'}). Note that if you call this method with no parameters, all JRE/JDK
     *            "lib/" or "ext/" jars will be whitelisted.
     * @return this (for method chaining).
     */
    public ClassGraph whitelistLibOrExtJars(final String... jarLeafNames) {
        whitelistOrBlacklistLibOrExtJars(/* whitelist = */ true, jarLeafNames);
        return this;
    }

    /**
     * Blacklist one or more jars in a JRE/JDK "lib/" or "ext/" directory, preventing them from being scanned.
     *
     * @param jarLeafNames
     *            The leafnames of the lib/ext jar(s) that should not be scanned (e.g.
     *            {@code "jre/lib/badlib.jar"}). May contain a wildcard glob ({@code '*'}). If you call this method
     *            with no parameters, all JRE/JDK {@code "lib/"} or {@code "ext/"} jars will be blacklisted.
     * @return this (for method chaining).
     */
    public ClassGraph blacklistLibOrExtJars(final String... jarLeafNames) {
        whitelistOrBlacklistLibOrExtJars(/* whitelist = */ false, jarLeafNames);
        return this;
    }

    /**
     * Whitelist one or more modules to scan.
     *
     * @param moduleNames
     *            The names of the modules that should be scanned. May contain a wildcard glob ({@code '*'}).
     * @return this (for method chaining).
     */
    public ClassGraph whitelistModules(final String... moduleNames) {
        for (final String moduleName : moduleNames) {
            scanSpec.moduleWhiteBlackList.addToWhitelist(WhiteBlackList.normalizePackageOrClassName(moduleName));
        }
        return this;
    }

    /**
     * Blacklist one or more modules, preventing them from being scanned.
     *
     * @param moduleNames
     *            The names of the modules that should not be scanned. May contain a wildcard glob ({@code '*'}).
     * @return this (for method chaining).
     */
    public ClassGraph blacklistModules(final String... moduleNames) {
        for (final String moduleName : moduleNames) {
            scanSpec.moduleWhiteBlackList.addToBlacklist(WhiteBlackList.normalizePackageOrClassName(moduleName));
        }
        return this;
    }

    /**
     * Whitelist classpath elements based on resource paths. Only classpath elements that contain resources with
     * paths matching the whitelist will be scanned.
     *
     * @param resourcePaths
     *            The resource paths, any of which must be present in a classpath element for the classpath element
     *            to be scanned. May contain a wildcard glob ({@code '*'}).
     * @return this (for method chaining).
     */
    public ClassGraph whitelistClasspathElementsContainingResourcePath(final String... resourcePaths) {
        for (final String resourcePath : resourcePaths) {
            final String resourcePathNormalized = WhiteBlackList.normalizePath(resourcePath);
            scanSpec.classpathElementResourcePathWhiteBlackList.addToWhitelist(resourcePathNormalized);
        }
        return this;
    }

    /**
     * Blacklist classpath elements based on resource paths. Classpath elements that contain resources with paths
     * matching the blacklist will not be scanned.
     *
     * @param resourcePaths
     *            The resource paths which cause a classpath not to be scanned if any are present in a classpath
     *            element for the classpath element. May contain a wildcard glob ({@code '*'}).
     * @return this (for method chaining).
     */
    public ClassGraph blacklistClasspathElementsContainingResourcePath(final String... resourcePaths) {
        for (final String resourcePath : resourcePaths) {
            final String resourcePathNormalized = WhiteBlackList.normalizePath(resourcePath);
            scanSpec.classpathElementResourcePathWhiteBlackList.addToBlacklist(resourcePathNormalized);
        }
        return this;
    }

    /**
     * Enable classpath elements to be fetched from remote ("http:"/"https:") URLs (or URLs with custom schemes).
     * Equivalent to:
     * 
     * <p>
     * {@code new ClassGraph().enableURLScheme("http").enableURLScheme("https");}
     * 
     * <p>
     * Scanning from http(s) URLs is disabled by default, as this may present a security vulnerability, since
     * classes from downloaded jars can be subsequently loaded using {@link ClassInfo#loadClass}.
     * 
     * @return this (for method chaining).
     */
    public ClassGraph enableRemoteJarScanning() {
        scanSpec.enableURLScheme("http");
        scanSpec.enableURLScheme("https");
        return this;
    }

    /**
     * Enable classpath elements to be fetched from {@link URL} connections with the specified URL scheme (also
     * works for any custom URL schemes that have been defined, as long as they have more than two characters, in
     * order to not conflict with Windows drive letters).
     *
     * @param scheme
     *            the URL scheme string, e.g. "resource" for a custom "resource:" URL scheme.
     * @return this (for method chaining).
     */
    public ClassGraph enableURLScheme(final String scheme) {
        scanSpec.enableURLScheme(scheme);
        return this;
    }

    /**
     * Enables the scanning of system packages ({@code "java.*"}, {@code "javax.*"}, {@code "javafx.*"},
     * {@code "jdk.*"}, {@code "oracle.*"}, {@code "sun.*"}) -- these are not scanned by default for speed.
     * 
     * <p>
     * N.B. Automatically calls {@link #enableClassInfo()}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableSystemJarsAndModules() {
        enableClassInfo();
        scanSpec.enableSystemJarsAndModules = true;
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
        scanSpec.maxBufferedJarRAMSize = maxBufferedJarRAMSize;
        return this;
    }

    /**
     * If true, use a {@link RandomAccessFile} rather than a {@link MappedByteBuffer} to open jarfiles, which is
     * slower, but does not use up virtual memory space. You can call this method to disable memory mapping if you
     * run into an {@link OutOfMemoryError} when scanning.
     * 
     * @return this (for method chaining).
     */
    public ClassGraph disableMemoryMapping() {
        scanSpec.disableMemoryMapping = true;
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

    /** A callback used to process the result of a successful asynchronous scan. */
    @FunctionalInterface
    public interface ScanResultProcessor {
        /**
         * Process the result of an asynchronous scan after scanning has completed.
         * 
         * @param scanResult
         *            the {@link ScanResult} to process.
         */
        void processScanResult(ScanResult scanResult);
    }

    /** A callback used to handle failure during an asynchronous scan. */
    @FunctionalInterface
    public interface FailureHandler {
        /**
         * Called on scanning failure during an asynchronous scan.
         * 
         * @param throwable
         *            the {@link Throwable} that was thrown during scanning.
         */
        void onFailure(Throwable throwable);
    }

    /**
     * Asynchronously scans the classpath, calling a {@link ScanResultProcessor} callback on success or a
     * {@link FailureHandler} callback on failure.
     *
     * @param executorService
     *            A custom {@link ExecutorService} to use for scheduling worker tasks.
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning. Ideally the ExecutorService will have at least this many threads available.
     * @param scanResultProcessor
     *            A {@link ScanResultProcessor} callback to run on successful scan.
     * @param failureHandler
     *            A {@link FailureHandler} callback to run on failed scan. This is passed any {@link Throwable}
     *            thrown during the scan.
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
        // Use execute() rather than submit(), since a ScanResultProcessor and FailureHandler are used
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // Call scanner, but ignore the returned ScanResult
                    new Scanner(/* performScan = */ true, scanSpec, executorService, numParallelTasks,
                            scanResultProcessor, failureHandler, topLevelLog).call();
                } catch (final InterruptedException | CancellationException | ExecutionException e) {
                    // Call failure handler
                    failureHandler.onFailure(e);
                }
            }
        });
    }

    /**
     * Asynchronously scans the classpath for matching files, returning a {@code Future<ScanResult>}. You should
     * assign the wrapped {@link ScanResult} in a try-with-resources statement, or manually close it when you are
     * finished with it.
     * 
     * @param performScan
     *            If true, performing a scan. If false, only fetching the classpath.
     * @param executorService
     *            A custom {@link ExecutorService} to use for scheduling worker tasks.
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning. Ideally the ExecutorService will have at least this many threads available.
     * @return a {@code Future<ScanResult>}, that when resolved using get() yields a new {@link ScanResult} object
     *         representing the result of the scan.
     */
    private Future<ScanResult> scanAsync(final boolean performScan, final ExecutorService executorService,
            final int numParallelTasks) {
        try {
            return executorService.submit(new Scanner(performScan, scanSpec, executorService, numParallelTasks,
                    /* scanResultProcessor = */ null, /* failureHandler = */ null, topLevelLog));
        } catch (final InterruptedException e) {
            // Interrupted during the Scanner constructor's execution (specifically, by getModuleOrder(),
            // which is unlikely to ever actually be interrupted -- but this exception needs to be caught).
            return executorService.submit(new Callable<ScanResult>() {
                @Override
                public ScanResult call() throws Exception {
                    throw e;
                }
            });
        }
    }

    /**
     * Asynchronously scans the classpath for matching files, returning a {@code Future<ScanResult>}. You should
     * assign the wrapped {@link ScanResult} in a try-with-resources statement, or manually close it when you are
     * finished with it.
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
        return scanAsync(/* performScan = */ true, executorService, numParallelTasks);
    }

    /**
     * Scans the classpath using the requested {@link ExecutorService} and the requested degree of parallelism,
     * blocking until the scan is complete. You should assign the returned {@link ScanResult} in a
     * try-with-resources statement, or manually close it when you are finished with it.
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
        try {
            // Start the scan and wait for completion

            // Return the scanResult, then block waiting for the result
            final ScanResult scanResult = scanAsync(executorService, numParallelTasks).get();

            //    // Test serialization/deserialization by serializing and then deserializing the ScanResult 
            //    if (scanSpec.enableClassInfo && scanSpec.performScan) {
            //        final String scanResultJson = scanResult.toJSON(2);
            //        final ScanResult scanResultFromJson = ScanResult.fromJSON(scanResultJson);
            //        final String scanResultJson2 = scanResult.toJSON(2);
            //        if (!scanResultJson2.equals(scanResultJson)) {
            //            throw new RuntimeException("Serialization mismatch");
            //        }
            //        scanResult = scanResultFromJson;
            //    }

            // The resulting scanResult cannot be null, but check for null to keep SpotBugs happy
            if (scanResult == null) {
                throw new NullPointerException();
            }
            return scanResult;

        } catch (final InterruptedException | CancellationException e) {
            throw ClassGraphException.newClassGraphException("Scan interrupted", e);
        } catch (final ExecutionException e) {
            throw ClassGraphException.newClassGraphException("Uncaught exception during scan",
                    InterruptionChecker.getCause(e));
        }
    }

    /**
     * Scans the classpath with the requested number of threads, blocking until the scan is complete. You should
     * assign the returned {@link ScanResult} in a try-with-resources statement, or manually close it when you are
     * finished with it.
     *
     * @param numThreads
     *            The number of worker threads to start up.
     * @return a {@link ScanResult} object representing the result of the scan.
     * @throws ClassGraphException
     *             if any of the worker threads throws an uncaught exception, or the scan was interrupted.
     */
    public ScanResult scan(final int numThreads) {
        try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(numThreads)) {
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
        try {
            final ScanResult scanResult = scanAsync(/* performScan = */ false, executorService,
                    DEFAULT_NUM_WORKER_THREADS).get();

            // The resulting scanResult cannot be null, but check for null to keep SpotBugs happy
            if (scanResult == null) {
                throw new NullPointerException();
            }
            return scanResult;

        } catch (final InterruptedException | CancellationException e) {
            throw ClassGraphException.newClassGraphException("Scan interrupted", e);
        } catch (final ExecutionException e) {
            throw ClassGraphException.newClassGraphException("Uncaught exception during scan",
                    InterruptionChecker.getCause(e));
        }
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
        try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(
                DEFAULT_NUM_WORKER_THREADS); ScanResult scanResult = getClasspathScanResult(executorService)) {
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
        return JarUtils.pathElementsToPathStr(getClasspathFiles());
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
        try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(
                DEFAULT_NUM_WORKER_THREADS); ScanResult scanResult = getClasspathScanResult(executorService)) {
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
        try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(
                DEFAULT_NUM_WORKER_THREADS); ScanResult scanResult = getClasspathScanResult(executorService)) {
            return scanResult.getClasspathURLs();
        }
    }

    /**
     * Returns {@link ModuleRef} references for all the visible modules.
     *
     * @return a list of {@link ModuleRef} references for all the visible modules.
     * @throws ClassGraphException
     *             if any of the worker threads throws an uncaught exception, or the scan was interrupted.
     */
    public List<ModuleRef> getModules() {
        try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(
                DEFAULT_NUM_WORKER_THREADS); ScanResult scanResult = getClasspathScanResult(executorService)) {
            return scanResult.getModules();
        }
    }

    /**
     * Get the module path info provided on the commandline with {@code --module-path}, {@code --add-modules},
     * {@code --patch-module}, {@code --add-exports}, {@code --add-opens}, and {@code --add-reads}.
     * 
     * <p>
     * Note that the returned {@link ModulePathInfo} object does not include classpath entries from the traditional
     * classpath or system modules. Use {@link #getModules()} to get all visible modules, including anonymous,
     * automatic and system modules.
     * 
     * <p>
     * Also, {@link ModulePathInfo#addExports} and {@link ModulePathInfo#addOpens} will not contain
     * {@code Add-Exports} or {@code Add-Opens} entries from jarfile manifest files encountered during scanning,
     * unless you obtain the {@link ModulePathInfo} by calling {@link ScanResult#getModulePathInfo()} rather than by
     * calling {@link ClassGraph#getModulePathInfo()} before {@link ClassGraph#scan()}.
     * 
     * @return The {@link ModulePathInfo}.
     */
    public ModulePathInfo getModulePathInfo() {
        return scanSpec.modulePathInfo;
    }
}
