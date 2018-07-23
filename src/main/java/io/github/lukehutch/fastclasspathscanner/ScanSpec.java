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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner.ClasspathElementFilter;
import io.github.lukehutch.fastclasspathscanner.classloaderhandler.ClassLoaderHandlerRegistry;
import io.github.lukehutch.fastclasspathscanner.classloaderhandler.ClassLoaderHandlerRegistry.ClassLoaderHandlerRegistryEntry;
import io.github.lukehutch.fastclasspathscanner.utils.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.utils.JarUtils;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.WhiteBlackList;
import io.github.lukehutch.fastclasspathscanner.utils.WhiteBlackList.WhiteBlackListLeafname;
import io.github.lukehutch.fastclasspathscanner.utils.WhiteBlackList.WhiteBlackListPrefix;
import io.github.lukehutch.fastclasspathscanner.utils.WhiteBlackList.WhiteBlackListWholeString;

/**
 * Parses the scanning specification that was passed to the FastClasspathScanner constructor, and finds all
 * ClassLoaders. Also defines core MatchProcessor matching logic.
 */
public class ScanSpec {

    /** Constructor for deserialization. */
    ScanSpec() {
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Package white/blacklist (with separator '.'). */
    public WhiteBlackListWholeString packageWhiteBlackList = new WhiteBlackListWholeString();

    /** Package prefix white/blacklist, for recursive scanning (with separator '.', ending in '.'). */
    public WhiteBlackListPrefix packagePrefixWhiteBlackList = new WhiteBlackListPrefix();

    /** Path white/blacklist (with separator '/'). */
    public WhiteBlackListWholeString pathWhiteBlackList = new WhiteBlackListWholeString();

    /** Path prefix white/blacklist, for recursive scanning (with separator '/', ending in '/'). */
    public WhiteBlackListPrefix pathPrefixWhiteBlackList = new WhiteBlackListPrefix();

    /** Class white/blacklist (fully-qualified class names, with separator '.'). */
    public WhiteBlackListWholeString classWhiteBlackList = new WhiteBlackListWholeString();

    /** Classfile white/blacklist (path to classfiles, with separator '/', ending in ".class"). */
    public WhiteBlackListWholeString classfilePathWhiteBlackList = new WhiteBlackListWholeString();

    /** Package containing white/blacklisted classes (with separator '.'). */
    public WhiteBlackListWholeString classPackageWhiteBlackList = new WhiteBlackListWholeString();

    /** Path to white/blacklisted classes (with separator '/'). */
    public WhiteBlackListWholeString classPackagePathWhiteBlackList = new WhiteBlackListWholeString();

    /** Module white/blacklist (with separator '.'). */
    public WhiteBlackListWholeString moduleWhiteBlackList = new WhiteBlackListWholeString();

    /** Jar white/blacklist (leafname only, ending in ".jar"). */
    public WhiteBlackListLeafname jarWhiteBlackList = new WhiteBlackListLeafname();

    /** lib/ext jar white/blacklist (leafname only, ending in ".jar"). */
    public WhiteBlackListLeafname libOrExtJarWhiteBlackList = new WhiteBlackListLeafname();

    /** Need to sort prefixes to ensure correct whitelist/blacklist evaluation (see Issue #167). */
    void sortPrefixes() {
        for (final Field field : ScanSpec.class.getDeclaredFields()) {
            if (WhiteBlackList.class.isAssignableFrom(field.getType())) {
                try {
                    ((WhiteBlackList) field.get(this)).sortPrefixes();
                } catch (final Exception e) {
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** If true, scan jarfiles. */
    public boolean scanJars = true;

    /** If true, scan directories. */
    public boolean scanDirs = true;

    /** If true, scan modules. */
    public boolean scanModules = true;

    /** If true, scan classfile bytecodes, producing {@link ClassInfo} objects. */
    public boolean enableClassInfo = false;

    /**
     * If true, enables the saving of field info during the scan. This information can be obtained using
     * {@link ClassInfo#getFieldInfo()}. By default, field info is not scanned, for efficiency.
     */
    public boolean enableFieldInfo = false;

    /**
     * If true, enables the saving of method info during the scan. This information can be obtained using
     * {@link ClassInfo#getMethodInfo()}. By default, method info is not scanned, for efficiency.
     */
    public boolean enableMethodInfo = false;

    /**
     * If true, enables the saving of annotation info (for class, field, method or method parameter annotations)
     * during the scan. This information can be obtained using {@link ClassInfo#getAnnotationInfo()} etc. By
     * default, annotation info is not scanned, for efficiency.
     */
    public boolean enableAnnotationInfo = false;

    /** Enable the storing of constant initializer values for static final fields in ClassInfo objects. */
    public boolean enableStaticFinalFieldConstantInitializerValues = false;

    /**
     * If true, allow external classes (classes outside of whitelisted packages) to be returned in the ScanResult,
     * if they are directly referred to by a whitelisted class, as a superclass, implemented interface or
     * annotation. Disabled by default.
     */
    public boolean enableExternalClasses = false;

    /**
     * True if JRE system jarfiles (rt.jar etc.) should not be scanned. By default, these are not scanned. This can
     * be overridden by including "!!" in the scan spec. Disabling this blacklisting will increase the time or
     * memory required to scan the classpath.
     */
    public boolean blacklistSystemJarsOrModules = true;

    /**
     * If true, ignore class visibility. If false, classes must be public to be scanned.
     */
    public boolean ignoreClassVisibility;

    /**
     * If true, ignore field visibility. If false, fields must be public to be scanned.
     */
    public boolean ignoreFieldVisibility = false;

    /**
     * If true, ignore method visibility. If false, methods must be public to be scanned.
     */
    public boolean ignoreMethodVisibility = false;

    /**
     * If true, don't scan runtime-invisible annotations (only scan annotations with RetentionPolicy.RUNTIME).
     */
    public boolean disableRuntimeInvisibleAnnotations = false;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * If non-null, specified manually-added classloaders that should be searched after the context classloader(s).
     */
    public transient List<ClassLoader> addedClassLoaders;

    /**
     * If non-null, all ClassLoaders have been overriden. In particular, this causes FastClasspathScanner to ignore
     * the java.class.path system property.
     */
    public transient List<ClassLoader> overrideClassLoaders;

    /** If non-null, specifies a classpath to override the default one. */
    public String overrideClasspath;

    /** If non-null, a list of filter operations to apply to classpath elements. */
    public transient List<ClasspathElementFilter> classpathElementFilters;

    /** Manually-registered ClassLoaderHandlers. */
    public transient final ArrayList<ClassLoaderHandlerRegistryEntry> extraClassLoaderHandlers = new ArrayList<>();

    /**
     * If true, classes loaded with Class.forName() are initialized before passing class references to
     * MatchProcessors. If false (the default), matched classes are loaded but not initialized before passing class
     * references to MatchProcessors (meaning classes are instead initialized lazily on first usage of the class).
     */
    public transient boolean initializeLoadedClasses = false;

    /**
     * If true, nested jarfiles (jarfiles within jarfiles) that are extracted during scanning are removed from their
     * temporary directory (e.g. /tmp/FastClasspathScanner-8JX2u4w) after the scan has completed. If false,
     * temporary files are removed by the {@link ScanResult} finalizer, or on JVM exit.
     */
    public transient boolean removeTemporaryFilesAfterScan = false;

    /** If true, do not fetch paths from parent classloaders. */
    public transient boolean ignoreParentClassLoaders = false;

    /**
     * If true, manually strip the self extracting executable header from zipfiles (i.e. anything before the magic
     * marker "PK", e.g. a Bash script added by Spring-Boot). Slightly increases scanning time, since zipfiles have
     * to be opened twice (once as a byte stream, to check if there is an SFX header, then once as a ZipFile, for
     * decompression).
     * 
     * Should only be needed in rare cases, where you are dealing with jarfiles with prepended (ZipSFX) headers,
     * where your JVM does not already automatically skip forward to the first "PK" marker (Oracle JVM on Linux does
     * this automatically).
     */
    public transient boolean stripSFXHeader = false;

    /**
     * If this method is called, a new {@link java.net.URLClassLoader} is created for all classes found on the
     * classpath that match whitelist criteria. This may be needed if you get a ClassNotFoundException,
     * UnsatisfiedLinkError, NoClassDefFoundError, etc., due to trying to load classes that depend upon each other
     * but that are loaded by different ClassLoaders in the classpath.
     */
    public transient boolean createClassLoaderForMatchingClasses = false;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Register an extra ClassLoaderHandler.
     *
     * @param classLoaderHandler
     *            The class of the ClassLoaderHandler that can handle those ClassLoaders.
     */
    public void registerClassLoaderHandler(final Class<? extends ClassLoaderHandler> classLoaderHandler) {
        this.extraClassLoaderHandlers.add(new ClassLoaderHandlerRegistryEntry(classLoaderHandler));
    }

    /**
     * Override the automatically-detected classpath with a custom search path. You can specify multiple elements,
     * separated by File.pathSeparatorChar. If this method is called, nothing but the provided classpath will be
     * scanned, i.e. causes ClassLoaders to be ignored, as well as the java.class.path system property.
     * 
     * @param overrideClasspath
     *            The classpath to scan.
     */
    public void overrideClasspath(final String overrideClasspath) {
        this.overrideClasspath = overrideClasspath;
    }

    /**
     * Add a classpath element filter. The provided ClasspathElementFilter should return true if the path string
     * passed to it is a path you want to scan.
     * 
     * @param classpathElementFilter
     *            The classpath element filter to apply to all discovered classpath elements, to decide which should
     *            be scanned.
     */
    public void filterClasspathElements(final ClasspathElementFilter classpathElementFilter) {
        if (this.classpathElementFilters == null) {
            this.classpathElementFilters = new ArrayList<>(2);
        }
        this.classpathElementFilters.add(classpathElementFilter);
    }

    /**
     * Add a ClassLoader to the list of ClassLoaders to scan. (This only works if overrideClasspath() is not
     * called.)
     * 
     * @param classLoader
     *            The classloader to add.
     */
    public void addClassLoader(final ClassLoader classLoader) {
        if (this.addedClassLoaders == null) {
            this.addedClassLoaders = new ArrayList<>();
        }
        if (classLoader != null) {
            this.addedClassLoaders.add(classLoader);
        }
    }

    /**
     * Completely override the list of ClassLoaders to scan. (This only works if overrideClasspath() is not called.)
     * Causes the java.class.path system property to be ignored.
     * 
     * @param overrideClassLoaders
     *            The classloaders to override the default context classloaders with.
     */
    public void overrideClassLoaders(final ClassLoader... overrideClassLoaders) {
        this.addedClassLoaders = null;
        this.overrideClassLoaders = new ArrayList<>();
        for (final ClassLoader classLoader : overrideClassLoaders) {
            if (classLoader != null) {
                this.overrideClassLoaders.add(classLoader);
            }
        }
        if (this.overrideClassLoaders.isEmpty()) {
            // Reset back to null if the list of classloaders is empty
            this.overrideClassLoaders = null;
        } else {
            // overrideClassLoaders() overrides addClassloader()
            this.addedClassLoaders = null;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Whether a path is a descendant of a blacklisted path, or an ancestor or descendant of a whitelisted path.
     */
    static enum ScanSpecPathMatch {
        HAS_BLACKLISTED_PATH_PREFIX, HAS_WHITELISTED_PATH_PREFIX, AT_WHITELISTED_PATH, //
        ANCESTOR_OF_WHITELISTED_PATH, AT_WHITELISTED_CLASS_PACKAGE, NOT_WITHIN_WHITELISTED_PATH;
    }

    /**
     * Returns true if the given directory path is a descendant of a blacklisted path, or an ancestor or descendant
     * of a whitelisted path. The path should end in "/".
     */
    ScanSpecPathMatch dirWhitelistMatchStatus(final String relativePath) {
        // In blacklisted path
        if (pathWhiteBlackList.isBlacklisted(relativePath)) {
            // The directory is blacklisted.
            return ScanSpecPathMatch.HAS_BLACKLISTED_PATH_PREFIX;
        }
        if (pathPrefixWhiteBlackList.isBlacklisted(relativePath)) {
            // An prefix of this path is blacklisted.
            return ScanSpecPathMatch.HAS_BLACKLISTED_PATH_PREFIX;
        }

        // At whitelisted path
        if (pathWhiteBlackList.whitelistIsEmpty() && classPackagePathWhiteBlackList.whitelistIsEmpty()) {
            // There are no whitelisted packages, so everything non-blacklisted is whitelisted
            return ScanSpecPathMatch.AT_WHITELISTED_PATH;
        }
        if (pathWhiteBlackList.isSpecificallyWhitelistedAndNotBlacklisted(relativePath)) {
            // Reached a whitelisted path
            return ScanSpecPathMatch.AT_WHITELISTED_PATH;
        }
        if (classPackagePathWhiteBlackList.isSpecificallyWhitelistedAndNotBlacklisted(relativePath)) {
            // Reached a package containing a specifically-whitelisted class
            return ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE;
        }

        // Ancestor of whitelisted path
        if (relativePath.equals("/")) {
            // The default package is always the ancestor of whitelisted paths (need to keep recursing)
            return ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH;
        }
        if (pathWhiteBlackList.whitelistHasPrefix(relativePath)) {
            // relativePath is an ancestor (prefix) of a whitelisted path
            return ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH;
        }
        if (classfilePathWhiteBlackList.whitelistHasPrefix(relativePath)) {
            // relativePath is an ancestor (prefix) of a whitelisted class' parent directory
            return ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH;
        }

        // Descendant of whitelisted path
        if (pathPrefixWhiteBlackList.isSpecificallyWhitelisted(relativePath)) {
            // Path prefix matches one in the whitelist
            return ScanSpecPathMatch.HAS_WHITELISTED_PATH_PREFIX;
        }

        // Not in whitelisted path
        return ScanSpecPathMatch.NOT_WITHIN_WHITELISTED_PATH;
    }

    /**
     * Returns true if the given relative path (for a classfile name, including ".class") matches a
     * specifically-whitelisted (and non-blacklisted) classfile's relative path.
     */
    boolean isSpecificallyWhitelistedClass(final String relativePath) {
        return classfilePathWhiteBlackList.isSpecificallyWhitelistedAndNotBlacklisted(relativePath);
    }

    /** Returns true if the class is specifically blacklisted, or is within a blacklisted package. */
    boolean classIsBlacklisted(final String className) {
        return classWhiteBlackList.isBlacklisted(className) || packagePrefixWhiteBlackList.isBlacklisted(className);
    }

    /** Returns true if a jarfile is whitelisted and not blacklisted. */
    boolean jarIsWhitelistedAndNotBlacklisted(final String jarName) {
        final String jarLeafName = JarUtils.leafName(jarName);
        return jarWhiteBlackList.isWhitelistedAndNotBlacklisted(jarLeafName);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the list of all ClassLoaderHandlerRegistryEntry objects for user-defined ClassLoaderHandlers, followed by
     * the defaults in the ClassLoaderHandlerRegistry.
     */
    public List<ClassLoaderHandlerRegistryEntry> getAllClassLoaderHandlerRegistryEntries() {
        // Get all manually-added ClassLoaderHandlers (these are added before the default ClassLoaderHandlers,
        // so that the behavior of the defaults can be overridden)
        List<ClassLoaderHandlerRegistryEntry> allClassLoaderHandlerRegistryEntries;
        if (extraClassLoaderHandlers.isEmpty()) {
            allClassLoaderHandlerRegistryEntries = ClassLoaderHandlerRegistry.DEFAULT_CLASS_LOADER_HANDLERS;
        } else {
            allClassLoaderHandlerRegistryEntries = new ArrayList<>(extraClassLoaderHandlers);
            allClassLoaderHandlerRegistryEntries.addAll(ClassLoaderHandlerRegistry.DEFAULT_CLASS_LOADER_HANDLERS);
        }
        return allClassLoaderHandlerRegistryEntries;
    }

    public ClassLoaderHandler findClassLoaderHandlerForClassLoader(final ClassLoader classLoader,
            final LogNode log) {
        return ClasspathFinder.findClassLoaderHandlerForClassLoader(this, classLoader, log);
    }

    // -------------------------------------------------------------------------------------------------------------

    public void log(final LogNode log) {
        if (log != null) {
            final LogNode scanSpecLog = log.log("ScanSpec:");
            for (final Field field : ScanSpec.class.getDeclaredFields()) {
                try {
                    scanSpecLog.log(field.getName() + ": " + field.get(this));
                } catch (final Exception e) {
                }
            }
        }
    }
}
