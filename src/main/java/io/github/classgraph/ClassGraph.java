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
package io.github.classgraph;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;

import io.github.classgraph.utils.AutoCloseableExecutorService;
import io.github.classgraph.utils.JarUtils;
import io.github.classgraph.utils.LogNode;
import io.github.classgraph.utils.VersionFinder;
import io.github.classgraph.utils.WhiteBlackList;

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
    private final ScanSpec scanSpec = new ScanSpec();

    /**
     * The default number of worker threads to use while scanning. This number gave the best results on a relatively
     * modern laptop with SSD, while scanning a large classpath.
     */
    private static final int DEFAULT_NUM_WORKER_THREADS = Math.max(
            // Always scan with at least 2 threads
            2, //
            (int) Math.ceil(
                    // Num IO threads (top out at 4, since most I/O devices won't scale better than this)
                    Math.min(4.0, Runtime.getRuntime().availableProcessors() * 0.75) +
                    // Num scanning threads (higher than available processors, because some threads can be blocked)
                            Runtime.getRuntime().availableProcessors() * 1.25) //
    );

    /** If non-null, log while scanning */
    private LogNode topLevelLog;

    // -------------------------------------------------------------------------------------------------------------

    /** Construct a ClassGraph instance. */
    public ClassGraph() {
    }

    /**
     * Get the version number of ClassGraph.
     *
     * @return the ClassGraph version, or "unknown" if it could not be determined.
     */
    public static final String getVersion() {
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
     * are not scanned. Automatically calls {@link #enableClassInfo()} and {@link #enableFieldInfo()}.
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
    public ClassGraph stripZipSFXHeaders() {
        scanSpec.stripSFXHeader = true;
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
        scanSpec.overrideClasspath(overrideClasspath);
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
        final String overrideClasspath = JarUtils.pathElementsToPathStr(overrideClasspathElements);
        if (overrideClasspath.isEmpty()) {
            throw new IllegalArgumentException("Can't override classpath with an empty path");
        }
        overrideClasspath(overrideClasspath);
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
     * modules not to be scanned.
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
            if (packageName.startsWith("!") || packageName.startsWith("-")) {
                throw new IllegalArgumentException(
                        "This style of whitelisting/blacklisting is no longer supported: " + packageName);
            }
            // Whitelist package
            scanSpec.packageWhiteBlackList.addToWhitelist(WhiteBlackList.normalizePackageOrClassName(packageName));
            scanSpec.pathWhiteBlackList.addToWhitelist(WhiteBlackList.packageNameToPath(packageName));
            // FIXME: using a wildcard makes whitelisting non-recursive 
            if (!packageName.contains("*")) {
                // Whitelist sub-packages
                scanSpec.packagePrefixWhiteBlackList
                        .addToWhitelist(WhiteBlackList.normalizePackageOrClassName(packageName) + ".");
                scanSpec.pathPrefixWhiteBlackList.addToWhitelist(WhiteBlackList.packageNameToPath(packageName));
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
            // Whitelist path
            scanSpec.packageWhiteBlackList.addToWhitelist(WhiteBlackList.pathToPackageName(path));
            scanSpec.pathWhiteBlackList.addToWhitelist(WhiteBlackList.normalizePath(path));
            // FIXME: using a wildcard makes whitelisting non-recursive 
            if (!path.contains("*")) {
                // Whitelist sub-directories / nested paths
                scanSpec.packagePrefixWhiteBlackList.addToWhitelist(WhiteBlackList.pathToPackageName(path) + ".");
                scanSpec.pathPrefixWhiteBlackList.addToWhitelist(WhiteBlackList.normalizePath(path));
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
            if (packageName.contains("*")) {
                throw new IllegalArgumentException("Cannot use a glob wildcard here: " + packageName);
            }
            // Whitelist package, but not sub-packages
            scanSpec.packageWhiteBlackList.addToWhitelist(WhiteBlackList.normalizePackageOrClassName(packageName));
            scanSpec.pathWhiteBlackList.addToWhitelist(WhiteBlackList.packageNameToPath(packageName));
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
            // Whitelist path, but not sub-directories / nested paths
            scanSpec.packageWhiteBlackList.addToWhitelist(WhiteBlackList.pathToPackageName(path));
            scanSpec.pathWhiteBlackList.addToWhitelist(WhiteBlackList.normalizePath(path));
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
            // Blacklisting always prevents further recursion, no need to blacklist sub-packages
            scanSpec.packageWhiteBlackList.addToBlacklist(WhiteBlackList.normalizePackageOrClassName(packageName));
            scanSpec.pathWhiteBlackList.addToBlacklist(WhiteBlackList.packageNameToPath(packageName));
            if (!packageName.contains("*")) {
                // Blacklist sub-packages (zipfile entries can occur in any order)
                scanSpec.packagePrefixWhiteBlackList
                        .addToBlacklist(WhiteBlackList.normalizePackageOrClassName(packageName) + ".");
                scanSpec.pathPrefixWhiteBlackList.addToBlacklist(WhiteBlackList.packageNameToPath(packageName));
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
            // Blacklisting always prevents further recursion, no need to blacklist sub-directories / nested paths
            scanSpec.packageWhiteBlackList.addToBlacklist(WhiteBlackList.pathToPackageName(path));
            scanSpec.pathWhiteBlackList.addToBlacklist(WhiteBlackList.normalizePath(path));
            if (!path.contains("*")) {
                // Blacklist sub-directories / nested paths
                scanSpec.packagePrefixWhiteBlackList.addToBlacklist(WhiteBlackList.pathToPackageName(path) + ".");
                scanSpec.pathPrefixWhiteBlackList.addToBlacklist(WhiteBlackList.normalizePath(path));
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
            // Whitelist the class itself
            scanSpec.classWhiteBlackList.addToWhitelist(WhiteBlackList.normalizePackageOrClassName(className));
            scanSpec.classfilePathWhiteBlackList.addToWhitelist(WhiteBlackList.classNameToClassfilePath(className));
            final int lastDotIdx = className.lastIndexOf('.');
            final String packageName = lastDotIdx < 0 ? "" : className.substring(0, lastDotIdx);
            // Record the package containing the class, so we can recurse to this point even if the package
            // is not itself whitelisted
            scanSpec.classPackageWhiteBlackList
                    .addToWhitelist(WhiteBlackList.normalizePackageOrClassName(packageName));
            scanSpec.classPackagePathWhiteBlackList.addToWhitelist(WhiteBlackList.packageNameToPath(packageName));
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
            scanSpec.classWhiteBlackList.addToBlacklist(WhiteBlackList.normalizePackageOrClassName(className));
            scanSpec.classfilePathWhiteBlackList.addToBlacklist(WhiteBlackList.classNameToClassfilePath(className));
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
     * Whitelist one or more jars in a JRE/JDK "lib/" or "ext/" directory (these directories are not scanned unless
     * {@link #enableSystemPackages()} is called, by association with the JRE/JDK).
     *
     * @param jarLeafNames
     *            The leafnames of the lib/ext jar(s) that should be scanned (e.g. {@code "mylib.jar"}). May contain
     *            a wildcard glob ({@code '*'}). If you call this method with no parameters, all JRE/JDK "lib/" or
     *            "ext/" jars will be whitelisted.
     * @return this (for method chaining).
     */
    public ClassGraph whitelistLibOrExtJars(final String... jarLeafNames) {
        if (jarLeafNames.length == 0) {
            // whitelist all lib or ext jars
            for (final String libOrExtJar : JarUtils.getJreLibOrExtJars()) {
                whitelistLibOrExtJars(JarUtils.leafName(libOrExtJar));
            }
        } else {
            for (final String jarLeafName : jarLeafNames) {
                final String leafName = JarUtils.leafName(jarLeafName);
                if (!leafName.equals(jarLeafName)) {
                    throw new IllegalArgumentException("Can only whitelist jars by leafname: " + jarLeafName);
                }
                if (jarLeafName.contains("*")) {
                    // Compare wildcarded pattern against all jars in lib and ext dirs 
                    final Pattern pattern = WhiteBlackList.globToPattern(jarLeafName);
                    boolean found = false;
                    for (final String libOrExtJarPath : JarUtils.getJreLibOrExtJars()) {
                        final String libOrExtJarLeafName = JarUtils.leafName(libOrExtJarPath);
                        if (pattern.matcher(libOrExtJarLeafName).matches()) {
                            // Check for "*" in filename to prevent infinite recursion (shouldn't happen)
                            if (!libOrExtJarLeafName.contains("*")) {
                                whitelistLibOrExtJars(libOrExtJarLeafName);
                            }
                            found = true;
                        }
                    }
                    if (!found && topLevelLog != null) {
                        topLevelLog.log("Could not find lib or ext jar matching wildcard: " + jarLeafName);
                    }
                } else {
                    // No wildcards, just whitelist the named jar, if present
                    boolean found = false;
                    for (final String libOrExtJarPath : JarUtils.getJreLibOrExtJars()) {
                        final String libOrExtJarLeafName = JarUtils.leafName(libOrExtJarPath);
                        if (jarLeafName.equals(libOrExtJarLeafName)) {
                            scanSpec.libOrExtJarWhiteBlackList.addToWhitelist(jarLeafName);
                            if (topLevelLog != null) {
                                topLevelLog.log("Whitelisting lib or ext jar: " + libOrExtJarPath);
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
        if (jarLeafNames.length == 0) {
            // Blacklist all lib or ext jars
            for (final String libOrExtJar : JarUtils.getJreLibOrExtJars()) {
                blacklistLibOrExtJars(JarUtils.leafName(libOrExtJar));
            }
        } else {
            for (final String jarLeafName : jarLeafNames) {
                final String leafName = JarUtils.leafName(jarLeafName);
                if (!leafName.equals(jarLeafName)) {
                    throw new IllegalArgumentException("Can only blacklist jars by leafname: " + jarLeafName);
                }
                if (jarLeafName.contains("*")) {
                    // Compare wildcarded pattern against all jars in lib and ext dirs 
                    final Pattern pattern = WhiteBlackList.globToPattern(jarLeafName);
                    boolean found = false;
                    for (final String libOrExtJarPath : JarUtils.getJreLibOrExtJars()) {
                        final String libOrExtJarLeafName = JarUtils.leafName(libOrExtJarPath);
                        if (pattern.matcher(libOrExtJarLeafName).matches()) {
                            // Check for "*" in filename to prevent infinite recursion (shouldn't happen)
                            if (!libOrExtJarLeafName.contains("*")) {
                                blacklistLibOrExtJars(libOrExtJarLeafName);
                            }
                            found = true;
                        }
                    }
                    if (!found && topLevelLog != null) {
                        topLevelLog.log("Could not find lib or ext jar matching wildcard: " + jarLeafName);
                    }
                } else {
                    // No wildcards, just blacklist the named jar, if present
                    boolean found = false;
                    for (final String libOrExtJarPath : JarUtils.getJreLibOrExtJars()) {
                        final String libOrExtJarLeafName = JarUtils.leafName(libOrExtJarPath);
                        if (jarLeafName.equals(libOrExtJarLeafName)) {
                            scanSpec.libOrExtJarWhiteBlackList.addToBlacklist(jarLeafName);
                            if (topLevelLog != null) {
                                topLevelLog.log("Blacklisting lib or ext jar: " + libOrExtJarPath);
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
        return this;
    }

    /**
     * Whitelist one or more modules to scan.
     *
     * @param moduleNames
     *            The names of the modules that should not be scanned. May contain a wildcard glob ({@code '*'}).
     * @return this (for method chaining).
     */
    public ClassGraph whitelistModules(final String... moduleNames) {
        for (final String moduleName : moduleNames) {
            scanSpec.moduleWhiteBlackList.addToWhitelist(moduleName);
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
            scanSpec.moduleWhiteBlackList.addToBlacklist(moduleName);
        }
        return this;
    }

    /**
     * Enables the scanning of system packages ({@code "java.*"}, {@code "javax.*"}, {@code "javafx.*"},
     * {@code "jdk.*"}, {@code "oracle.*"}, {@code "sun.*"}) -- these are not scanned by default for speed. Calls
     * {#whitelistLibOrExtJars()} with no parameters, to enable scanning of jars in JRE/JDK {@code "lib/"} and
     * {@code "ext/"} directories.
     * 
     * <p>
     * N.B. Automatically calls {@link #enableClassInfo()}.
     *
     * @return this (for method chaining).
     */
    public ClassGraph enableSystemPackages() {
        enableClassInfo();
        // Scan JRE lib and ext dirs
        whitelistLibOrExtJars();
        scanSpec.blacklistSystemJarsOrModules = false;
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
        public void processScanResult(ScanResult scanResult);
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
        public void onFailure(Throwable throwable);
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
        // Drop the returned Future<ScanResult>, a ScanResultProcessor is used instead
        executorService.submit(new Scanner(scanSpec, executorService, numParallelTasks, scanResultProcessor,
                failureHandler, topLevelLog));
    }

    /**
     * Asynchronously scans the classpath for matching files, returning a {@code Future<ScanResult>}.
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
        return executorService.submit(new Scanner(scanSpec, executorService, numParallelTasks,
                /* scanResultProcessor = */ null, /* failureHandler = */ null, topLevelLog));
    }

    /**
     * Scans the classpath using the requested {@link ExecutorService} and the requested degree of parallelism,
     * blocking until the scan is complete.
     *
     * @param executorService
     *            A custom {@link ExecutorService} to use for scheduling worker tasks. This {@link ExecutorService}
     *            should start tasks in FIFO order to avoid a deadlock during scan, i.e. be sure to construct the
     *            {@link ExecutorService} with a {@link LinkedBlockingQueue} as its task queue. (This is the default
     *            for {@link Executors#newFixedThreadPool(int)}.)
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning. Ideally the ExecutorService will have at least this many threads available.
     * @throws RuntimeException
     *             if any of the worker threads throws an uncaught exception, or the scan was interrupted.
     * @return a {@link ScanResult} object representing the result of the scan.
     */
    public ScanResult scan(final ExecutorService executorService, final int numParallelTasks) {
        try {
            // Start the scan and wait for completion
            final ScanResult scanResult = executorService
                    .submit(new Scanner(scanSpec, executorService, numParallelTasks,
                            /* scanResultProcessor = */ null, /* failureHandler = */ null, topLevelLog)) //
                    .get();

            //    // Test serialization/deserialization by serializing and then deserializing the ScanResult 
            //    final String scanResultJson = scanResult.toJSON();
            //    scanResult = ScanResult.fromJSON(scanResultJson);

            // Return the scanResult
            return scanResult;

        } catch (final InterruptedException e) {
            if (topLevelLog != null) {
                topLevelLog.log("Scan interrupted");
            }
            throw new RuntimeException("Scan interrupted", e);
        } catch (final ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause == null) {
                cause = e;
            }
            if (cause instanceof InterruptedException) {
                if (topLevelLog != null) {
                    topLevelLog.log("Scan interrupted");
                }
                throw new RuntimeException("Scan interrupted", e);
            } else {
                if (topLevelLog != null) {
                    topLevelLog.log("Unexpected exception during scan", e);
                }
                throw new RuntimeException(cause);
            }
        } finally {
            if (topLevelLog != null) {
                topLevelLog.flush();
            }
        }
    }

    /**
     * Scans the classpath with the requested number of threads, blocking until the scan is complete.
     *
     * @param numThreads
     *            The number of worker threads to start up.
     * @throws RuntimeException
     *             if any of the worker threads throws an uncaught exception, or the scan was interrupted.
     * @return a {@link ScanResult} object representing the result of the scan.
     */
    public ScanResult scan(final int numThreads) {
        try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(numThreads)) {
            return scan(executorService, numThreads);
        }
    }

    /**
     * Scans the classpath, blocking until the scan is complete.
     *
     * @throws RuntimeException
     *             if any of the worker threads throws an uncaught exception, or the scan was interrupted.
     * @return a {@link ScanResult} object representing the result of the scan.
     */
    public ScanResult scan() {
        return scan(DEFAULT_NUM_WORKER_THREADS);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the list of all unique File objects representing directories or zip/jarfiles on the classpath, in
     * classloader resolution order. Classpath elements that do not exist as a file or directory are not included in
     * the returned list.
     *
     * @return a {@code List<File>} consisting of the unique directories and jarfiles on the classpath, in classpath
     *         resolution order.
     */
    public List<File> getClasspathFiles() {
        try {
            try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(
                    DEFAULT_NUM_WORKER_THREADS)) {
                scanSpec.performScan = false;
                try (ScanResult scanResult = executorService.submit( //
                        new Scanner(scanSpec, executorService, DEFAULT_NUM_WORKER_THREADS,
                                /* scanResultProcessor = */ null, /* failureHandler = */ null, topLevelLog))
                        .get()) {
                    return scanResult.getClasspathFiles();
                }
            }
        } catch (final InterruptedException e) {
            if (topLevelLog != null) {
                topLevelLog.log("Thread interrupted while getting classpath elements");
            }
            throw new IllegalArgumentException("Scan interrupted", e);
        } catch (final ExecutionException e) {
            if (topLevelLog != null) {
                topLevelLog.log("Exception while getting classpath elements", e);
            }
            final Throwable cause = e.getCause();
            throw new RuntimeException(cause == null ? e : cause);
        } finally {
            if (topLevelLog != null) {
                topLevelLog.flush();
            }
        }
    }

    /**
     * Returns the list of all unique File objects representing directories or zip/jarfiles on the classpath, in
     * classloader resolution order, in the form of a classpath path string. Classpath elements that do not exist as
     * a file or directory are not included in the returned list.
     *
     * @return a classpath path string consisting of the unique directories and jarfiles on the classpath, in
     *         classpath resolution order.
     */
    public String getClasspath() {
        return JarUtils.pathElementsToPathStr(getClasspathFiles());
    }

    /**
     * Returns the list of all unique URL objects representing directories, zip/jarfiles or modules on the
     * classpath, in classloader resolution order. Classpath elements representing jarfiles or directories that do
     * not exist are not included in the returned list.
     *
     * @return a classpath path string consisting of the unique directories and jarfiles on the classpath, in
     *         classpath resolution order.
     */
    public List<URL> getClasspathURLs() {
        try {
            try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(
                    DEFAULT_NUM_WORKER_THREADS)) {
                scanSpec.performScan = false;
                final ScanResult scanResult = executorService.submit( //
                        new Scanner(scanSpec, executorService, DEFAULT_NUM_WORKER_THREADS,
                                /* scanResultProcessor = */ null, /* failureHandler = */ null, topLevelLog))
                        .get();
                return scanResult.getClasspathURLs();

            }
        } catch (final InterruptedException e) {
            if (topLevelLog != null) {
                topLevelLog.log("Thread interrupted while getting classpath elements");
            }
            throw new IllegalArgumentException("Scan interrupted", e);
        } catch (final ExecutionException e) {
            if (topLevelLog != null) {
                topLevelLog.log("Exception while getting classpath elements", e);
            }
            final Throwable cause = e.getCause();
            throw new RuntimeException(cause == null ? e : cause);
        } finally {
            if (topLevelLog != null) {
                topLevelLog.flush();
            }
        }
    }

    /**
     * Returns {@link ModuleRef} references for all the visible modules.
     * 
     * @return a list of {@link ModuleRef} references for all the visible modules.
     */
    public List<ModuleRef> getModules() {
        try {
            try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(
                    DEFAULT_NUM_WORKER_THREADS)) {
                scanSpec.performScan = false;
                try (ScanResult scanResult = executorService.submit( //
                        new Scanner(scanSpec, executorService, DEFAULT_NUM_WORKER_THREADS,
                                /* scanResultProcessor = */ null, /* failureHandler = */ null, topLevelLog))
                        .get()) {
                    return scanResult.getModules();
                }
            }
        } catch (final InterruptedException e) {
            if (topLevelLog != null) {
                topLevelLog.log("Thread interrupted while getting modules");
            }
            throw new IllegalArgumentException("Scan interrupted", e);
        } catch (final ExecutionException e) {
            if (topLevelLog != null) {
                topLevelLog.log("Exception while getting modules", e);
            }
            final Throwable cause = e.getCause();
            throw new RuntimeException(cause == null ? e : cause);
        } finally {
            if (topLevelLog != null) {
                topLevelLog.flush();
            }
        }
    }
}
