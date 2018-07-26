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
import java.util.regex.Pattern;

import io.github.lukehutch.fastclasspathscanner.utils.AutoCloseableExecutorService;
import io.github.lukehutch.fastclasspathscanner.utils.JarUtils;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.VersionFinder;
import io.github.lukehutch.fastclasspathscanner.utils.WhiteBlackList;

/**
 * Uber-fast, ultra-lightweight Java classpath and module path scanner. Scans classfiles in the classpath and/or
 * module path by parsing the classfile binary format directly rather than by using reflection.
 *
 * <p>
 * Documentation: <a href= "https://github.com/lukehutch/fast-classpath-scanner/wiki">
 * https://github.com/lukehutch/fast-classpath-scanner/wiki</a>
 */
public class FastClasspathScanner {

    /** The scanning specification. */
    private final ScanSpec scanSpec = new ScanSpec();

    /**
     * The default number of worker threads to use while scanning. This number gave the best results on a relatively
     * modern laptop with SSD, while scanning a large classpath.
     */
    private static final int DEFAULT_NUM_WORKER_THREADS = 6;

    /** If non-null, log while scanning */
    private LogNode log;

    // -------------------------------------------------------------------------------------------------------------

    /** Construct a FastClasspathScanner instance. */
    public FastClasspathScanner() {
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
    public FastClasspathScanner enableAllInfo() {
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
    public FastClasspathScanner enableClassInfo() {
        scanSpec.enableClassInfo = true;
        return this;
    }

    /**
     * Causes class visibility to be ignored, enabling private, package-private and protected classes to be scanned.
     * By default, only public classes are scanned. (Automatically calls {@link #enableClassInfo()}.)
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner ignoreClassVisibility() {
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
    public FastClasspathScanner enableMethodInfo() {
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
    public FastClasspathScanner ignoreMethodVisibility() {
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
    public FastClasspathScanner enableFieldInfo() {
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
    public FastClasspathScanner ignoreFieldVisibility() {
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
    public FastClasspathScanner enableStaticFinalFieldConstantInitializerValues() {
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
    public FastClasspathScanner enableAnnotationInfo() {
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
    public FastClasspathScanner disableRuntimeInvisibleAnnotations() {
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
    public FastClasspathScanner disableJarScanning() {
        scanSpec.scanJars = false;
        return this;
    }

    /**
     * Disables the scanning of directories.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner disableDirScanning() {
        scanSpec.scanDirs = false;
        return this;
    }

    /**
     * Disables the scanning of modules.
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner disableModuleScanning() {
        scanSpec.scanModules = false;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Causes FastClasspathScanner to return classes that are not in the whitelisted packages, but that are directly
     * referred to by classes within whitelisted packages as a superclass, implemented interface or annotation.
     * (Automatically calls {@link #enableClassInfo()}.)
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableExternalClasses() {
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
    public FastClasspathScanner initializeLoadedClasses() {
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
    public FastClasspathScanner removeTemporaryFilesAfterScan() {
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
    public FastClasspathScanner stripZipSFXHeaders() {
        scanSpec.stripSFXHeader = true;
        return this;
    }

    /**
     * If this method is called, a new {@link java.net.URLClassLoader} is created for all classes found on the
     * classpath that match whitelist criteria. This may be needed if you get a {@link ClassNotFoundException},
     * {@link UnsatisfiedLinkError}, {@link NoClassDefFoundError}, {@link TypeNotPresentException}, etc., due to
     * trying to load classes that depend upon each other but that are loaded by different ClassLoaders in the
     * classpath.
     * 
     * @return this (for method chaining).
     */
    public FastClasspathScanner createClassLoaderForMatchingClasses() {
        scanSpec.createClassLoaderForMatchingClasses = true;
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
        scanSpec.registerClassLoaderHandler(classLoaderHandlerClass);
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
        scanSpec.overrideClasspath(overrideClasspath);
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
        scanSpec.filterClasspathElements(classpathElementFilter);
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
        scanSpec.addClassLoader(classLoader);
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
        scanSpec.overrideClassLoaders(overrideClassLoaders);
        return this;
    }

    /**
     * Ignore parent classloaders (i.e. only obtain paths to scan from classloader(s), do not also fetch paths from
     * parent classloader(s)).
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner ignoreParentClassLoaders() {
        scanSpec.ignoreParentClassLoaders = true;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan one or more specific packages and their sub-packages. (Automatically calls {@link #enableClassInfo()}.)
     *
     * @param packageNames
     *            The fully-qualified names of packages to scan (using '.' as a separator). May include a glob
     *            wildcard ('*').
     * @return this (for method chaining).
     */
    public FastClasspathScanner whitelistPackages(final String... packageNames) {
        enableClassInfo();
        for (final String packageName : packageNames) {
            if (packageName.startsWith("!") || packageName.startsWith("-")) {
                throw new IllegalArgumentException(
                        "This style of whitelisting/blacklisting is no longer supported: " + packageName);
            }
            // Whitelist package
            scanSpec.packageWhiteBlackList.addToWhitelist(WhiteBlackList.normalizePackageOrClassName(packageName));
            scanSpec.pathWhiteBlackList.addToWhitelist(WhiteBlackList.packageNameToPath(packageName));
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
     *            separator). May include a glob wildcard ('*').
     * @return this (for method chaining).
     */
    public FastClasspathScanner whitelistPaths(final String... paths) {
        for (final String path : paths) {
            // Whitelist path
            scanSpec.packageWhiteBlackList.addToWhitelist(WhiteBlackList.pathToPackageName(path));
            scanSpec.pathWhiteBlackList.addToWhitelist(WhiteBlackList.normalizePath(path));
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
     * whitelisted. (Automatically calls {@link #enableClassInfo()}.)
     * 
     * <p>
     * This may be particularly useful for scanning the package root ("") without recursively scanning everything in
     * the jar, dir or module.
     *
     * @param packageNames
     *            The fully-qualified names of packages to scan (with '.' as a separator).
     * @return this (for method chaining).
     */
    public FastClasspathScanner whitelistPackagesNonRecursive(final String... packageNames) {
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
     *            separator).
     * @return this (for method chaining).
     */
    public FastClasspathScanner whitelistPathsNonRecursive(final String... paths) {
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
     * Prevent the scanning of one or more specific packages and their sub-packages. (Automatically calls
     * {@link #enableClassInfo()}.)
     *
     * @param packageNames
     *            The fully-qualified names of packages to blacklist (with '.' as a separator). May include a glob
     *            wildcard ('*').
     * @return this (for method chaining).
     */
    public FastClasspathScanner blacklistPackages(final String... packageNames) {
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
     *            The paths to blacklist (with '/' as a separator). May include a glob wildcard ('*').
     * @return this (for method chaining).
     */
    public FastClasspathScanner blacklistPaths(final String... paths) {
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
     * itself whitelisted. (Automatically calls {@link #enableClassInfo()}.)
     *
     * @param classNames
     *            The fully-qualified names of classes to scan (using '.' as a separator).
     * @return this (for method chaining).
     */
    public FastClasspathScanner whitelistClasses(final String... classNames) {
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
     * whitelisted package. (Automatically calls {@link #enableClassInfo()}.)
     *
     * @param classNames
     *            The fully-qualified names of classes to blacklist (using '.' as a separator).
     * @return this (for method chaining).
     */
    public FastClasspathScanner blacklistClasses(final String... classNames) {
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
     *            The leafnames of the jars that should be scanned (e.g. "badlib.jar"). May contain a wildcard glob
     *            ('*').
     * @return this (for method chaining).
     */
    public FastClasspathScanner whitelistJars(final String... jarLeafNames) {
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
     *            The leafnames of the jars that should not be scanned (e.g. "badlib.jar"). May contain a wildcard
     *            glob ('*').
     * @return this (for method chaining).
     */
    public FastClasspathScanner blacklistJars(final String... jarLeafNames) {
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
     *            The leafnames of the lib/ext jar(s) that should be scanned (e.g. "mylib.jar"). May contain a
     *            wildcard glob ('*'). If you call this method with no parameters, all JRE/JDK "lib/" or "ext/" jars
     *            will be whitelisted.
     * @return this (for method chaining).
     */
    public FastClasspathScanner whitelistLibOrExtJars(final String... jarLeafNames) {
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
                    if (!found && log != null) {
                        log.log("Could not find lib or ext jar matching wildcard: " + jarLeafName);
                    }
                } else {
                    // No wildcards, just whitelist the named jar, if present
                    boolean found = false;
                    for (final String libOrExtJarPath : JarUtils.getJreLibOrExtJars()) {
                        final String libOrExtJarLeafName = JarUtils.leafName(libOrExtJarPath);
                        if (jarLeafName.equals(libOrExtJarLeafName)) {
                            scanSpec.libOrExtJarWhiteBlackList.addToWhitelist(jarLeafName);
                            if (log != null) {
                                log.log("Whitelisting lib or ext jar: " + libOrExtJarPath);
                            }
                            found = true;
                            break;
                        }
                    }
                    if (!found && log != null) {
                        log.log("Could not find lib or ext jar: " + jarLeafName);
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
     *            The leafnames of the lib/ext jar(s) that should not be scanned (e.g. "badlib.jar"). May contain a
     *            wildcard glob ('*'). If you call this method with no parameters, all JRE/JDK "lib/" or "ext/" jars
     *            will be blacklisted.
     * @return this (for method chaining).
     */
    public FastClasspathScanner blacklistLibOrExtJars(final String... jarLeafNames) {
        if (jarLeafNames.length == 0) {
            // Blacklist all lib or ext jars
            for (final String libOrExtJar : JarUtils.getJreLibOrExtJars()) {
                blacklistLibOrExtJars(libOrExtJar);
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
                    if (!found && log != null) {
                        log.log("Could not find lib or ext jar matching wildcard: " + jarLeafName);
                    }
                } else {
                    // No wildcards, just blacklist the named jar, if present
                    boolean found = false;
                    for (final String libOrExtJarPath : JarUtils.getJreLibOrExtJars()) {
                        final String libOrExtJarLeafName = JarUtils.leafName(libOrExtJarPath);
                        if (jarLeafName.equals(libOrExtJarLeafName)) {
                            scanSpec.libOrExtJarWhiteBlackList.addToBlacklist(jarLeafName);
                            if (log != null) {
                                log.log("Blacklisting lib or ext jar: " + libOrExtJarPath);
                            }
                            found = true;
                            break;
                        }
                    }
                    if (!found && log != null) {
                        log.log("Could not find lib or ext jar: " + jarLeafName);
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
     *            The names of the modules that should not be scanned. May contain a wildcard glob ('*').
     * @return this (for method chaining).
     */
    public FastClasspathScanner whitelistModules(final String... moduleNames) {
        for (final String moduleName : moduleNames) {
            scanSpec.moduleWhiteBlackList.addToWhitelist(moduleName);
        }
        return this;
    }

    /**
     * Blacklist one or more modules, preventing them from being scanned.
     *
     * @param moduleNames
     *            The names of the modules that should not be scanned. May contain a wildcard glob ('*').
     * @return this (for method chaining).
     */
    public FastClasspathScanner blacklistModules(final String... moduleNames) {
        for (final String moduleName : moduleNames) {
            scanSpec.moduleWhiteBlackList.addToBlacklist(moduleName);
        }
        return this;
    }

    /**
     * Enables the scanning of system packages (java.*, jdk.*, oracle.*, etc.) -- these are not scanned by default
     * for speed. Calls {#whitelistLibOrExtJars()} with no parameters, to enable scanning of jars in JRE/JDK "lib/"
     * and "ext/" directories. (Also automatically calls {@link #enableClassInfo()}.)
     *
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableSystemPackages() {
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
        /** Process the result of an asynchronous scan after scanning has completed. */
        public void processScanResult(ScanResult scanResult);
    }

    /** A callback used to handle failure during an asynchronous scan. */
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
                new Scanner(scanSpec, executorService, numParallelTasks, /* enableRecursiveScanning = */ true,
                        scanResultProcessor, failureHandler, log));
    }

    /**
     * Asynchronously scans the classpath for matching files, and calls any MatchProcessors if a match is
     * identified. Returns a Future object immediately after starting the scan. To block on scan completion, get the
     * result of the returned Future. Uses the provided ExecutorService, and divides the work according to the
     * requested degree of parallelism.
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
                new Scanner(scanSpec, executorService, numParallelTasks, /* enableRecursiveScanning = */ true,
                        /* scanResultProcessor = */ null, /* failureHandler = */ null, log));
    }

    /**
     * Scans the classpath for matching files, and calls any MatchProcessors if a match is identified. Uses the
     * provided ExecutorService, and divides the work according to the requested degree of parallelism. Blocks and
     * waits for the scan to complete before returning a ScanResult.
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
            ScanResult scanResult = executorService.submit(
                    // Call MatchProcessors before returning if in async scanning mode
                    new Scanner(scanSpec, executorService, numParallelTasks, /* enableRecursiveScanning = */ true,
                            /* scanResultProcessor = */ null, /* failureHandler = */ null, log)) //
                    .get();

            //    // Test serialization/deserialization by serializing and then deserializing the ScanResult 
            //    final String scanResultJson = scanResult.toJSON();
            //    scanResult = ScanResult.fromJSON(scanResultJson);

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
     * the scan to complete before returning a ScanResult.
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
     * starts up a new fixed thread pool for scanning, with the default number of threads (6). Blocks and waits for
     * the scan to complete before returning a ScanResult.
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
    public List<File> getClasspathFiles() {
        try {
            try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(
                    DEFAULT_NUM_WORKER_THREADS)) {
                return executorService.submit( //
                        new Scanner(scanSpec, executorService, DEFAULT_NUM_WORKER_THREADS,
                                /* enableRecursiveScanning = */ false, /* scanResultProcessor = */ null,
                                /* failureHandler = */ null,
                                log == null ? null : log.log("Getting unique classpath elements")))
                        .get().getClasspathFiles();
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
    public String getClasspath() {
        return JarUtils.pathElementsToPathStr(getClasspathFiles());
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
    public List<URL> getClasspathURLs() {
        try {
            try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(
                    DEFAULT_NUM_WORKER_THREADS)) {
                return executorService.submit( //
                        new Scanner(scanSpec, executorService, DEFAULT_NUM_WORKER_THREADS,
                                /* enableRecursiveScanning = */ false, /* scanResultProcessor = */ null,
                                /* failureHandler = */ null,
                                log == null ? null : log.log("Getting unique classpath elements")))
                        .get().getClasspathURLs();
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
     * Returns {@ModuleRef} references for all the visible modules.
     * 
     * @return a list of {@link ModuleRef} references for all the visible modules.
     */
    public List<ModuleRef> getModules() {
        try {
            try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(
                    DEFAULT_NUM_WORKER_THREADS)) {
                return executorService.submit( //
                        new Scanner(scanSpec, executorService, DEFAULT_NUM_WORKER_THREADS,
                                /* enableRecursiveScanning = */ false, /* scanResultProcessor = */ null,
                                /* failureHandler = */ null,
                                log == null ? null : log.log("Getting unique classpath elements")))
                        .get().getModules();
            }
        } catch (final InterruptedException e) {
            if (log != null) {
                log.log("Thread interrupted while getting modules");
            }
            throw new IllegalArgumentException("Scan interrupted", e);
        } catch (final ExecutionException e) {
            if (log != null) {
                log.log("Exception while getting modules", e);
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
