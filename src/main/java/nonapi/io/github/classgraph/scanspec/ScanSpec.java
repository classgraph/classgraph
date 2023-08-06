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
package nonapi.io.github.classgraph.scanspec;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.classgraph.ClassGraph.ClasspathElementFilter;
import io.github.classgraph.ClassGraph.ClasspathElementURLFilter;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ModulePathInfo;
import io.github.classgraph.ScanResult;
import nonapi.io.github.classgraph.scanspec.AcceptReject.AcceptRejectLeafname;
import nonapi.io.github.classgraph.scanspec.AcceptReject.AcceptRejectPrefix;
import nonapi.io.github.classgraph.scanspec.AcceptReject.AcceptRejectWholeString;
import nonapi.io.github.classgraph.utils.LogNode;

/**
 * The scanning specification.
 */
public class ScanSpec {
    /** Package accept/reject criteria (with separator '.'). */
    public AcceptRejectWholeString packageAcceptReject = new AcceptRejectWholeString('.');

    /** Package prefix accept/reject criteria, for recursive scanning (with separator '.', ending in '.'). */
    public AcceptRejectPrefix packagePrefixAcceptReject = new AcceptRejectPrefix('.');

    /** Path accept/reject criteria (with separator '/'). */
    public AcceptRejectWholeString pathAcceptReject = new AcceptRejectWholeString('/');

    /** Path prefix accept/reject criteria, for recursive scanning (with separator '/', ending in '/'). */
    public AcceptRejectPrefix pathPrefixAcceptReject = new AcceptRejectPrefix('/');

    /** Class accept/reject criteria (fully-qualified class names, with separator '.'). */
    public AcceptRejectWholeString classAcceptReject = new AcceptRejectWholeString('.');

    /** Classfile accept/reject criteria (path to classfiles, with separator '/', ending in ".class"). */
    public AcceptRejectWholeString classfilePathAcceptReject = new AcceptRejectWholeString('/');

    /** Package containing accept/reject criteriaed classes (with separator '.'). */
    public AcceptRejectWholeString classPackageAcceptReject = new AcceptRejectWholeString('.');

    /** Path to accept/reject criteriaed classes (with separator '/'). */
    public AcceptRejectWholeString classPackagePathAcceptReject = new AcceptRejectWholeString('/');

    /** Module accept/reject criteria (with separator '.'). */
    public AcceptRejectWholeString moduleAcceptReject = new AcceptRejectWholeString('.');

    /** Jar accept/reject criteria (leafname only, ending in ".jar"). */
    public AcceptRejectLeafname jarAcceptReject = new AcceptRejectLeafname('/');

    /** Classpath element resource path accept/reject criteria. */
    public AcceptRejectWholeString classpathElementResourcePathAcceptReject = //
            new AcceptRejectWholeString('/');

    /** lib/ext jar accept/reject criteria (leafname only, ending in ".jar"). */
    public AcceptRejectLeafname libOrExtJarAcceptReject = new AcceptRejectLeafname('/');

    // -------------------------------------------------------------------------------------------------------------

    /** If true, scan jarfiles. */
    public boolean scanJars = true;

    /** If true, scan nested jarfiles (jarfiles within jarfiles). */
    public boolean scanNestedJars = true;

    /** If true, scan directories. */
    public boolean scanDirs = true;

    /** If true, scan modules. */
    public boolean scanModules = true;

    /** If true, scan classfile bytecodes, producing {@link ClassInfo} objects. */
    public boolean enableClassInfo;

    /**
     * If true, enables the saving of field info during the scan. This information can be obtained using
     * {@link ClassInfo#getFieldInfo()}. By default, field info is not scanned, for efficiency.
     */
    public boolean enableFieldInfo;

    /**
     * If true, enables the saving of method info during the scan. This information can be obtained using
     * {@link ClassInfo#getMethodInfo()}. By default, method info is not scanned, for efficiency.
     */
    public boolean enableMethodInfo;

    /**
     * If true, enables the saving of annotation info (for class, field, method or method parameter annotations)
     * during the scan. This information can be obtained using {@link ClassInfo#getAnnotationInfo()} etc. By
     * default, annotation info is not scanned, for efficiency.
     */
    public boolean enableAnnotationInfo;

    /** Enable the storing of constant initializer values for static final fields in ClassInfo objects. */
    public boolean enableStaticFinalFieldConstantInitializerValues;

    /** If true, enables the determination of inter-class dependencies. */
    public boolean enableInterClassDependencies;

    /**
     * If true, allow external classes (classes outside of accepted packages) to be returned in the ScanResult, if
     * they are directly referred to by an accepted class, as a superclass, implemented interface or annotation.
     * Disabled by default.
     */
    public boolean enableExternalClasses;

    /**
     * If true, system jarfiles (rt.jar) and system packages and modules (java.*, jre.*, etc.) should be scanned .
     */
    public boolean enableSystemJarsAndModules;

    /**
     * If true, ignore class visibility. If false, classes must be public to be scanned.
     */
    public boolean ignoreClassVisibility;

    /**
     * If true, ignore field visibility. If false, fields must be public to be scanned.
     */
    public boolean ignoreFieldVisibility;

    /**
     * If true, ignore method visibility. If false, methods must be public to be scanned.
     */
    public boolean ignoreMethodVisibility;

    /**
     * If true, don't scan runtime-invisible annotations (only scan annotations with RetentionPolicy.RUNTIME).
     */
    public boolean disableRuntimeInvisibleAnnotations;

    /**
     * If true, when classes have superclasses, implemented interfaces or annotations that are external classes,
     * those classes are also scanned. (Even though this slows down scanning a bit, there is no API for disabling
     * this currently, since disabling it can lead to problems -- see #261.)
     */
    public boolean extendScanningUpwardsToExternalClasses = true;

    /**
     * URL schemes that are allowed in classpath elements (not counting the optional "jar:" prefix and/or "file:",
     * which are automatically allowed).
     */
    public Set<String> allowedURLSchemes;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * If non-null, specifies manually-added classloaders that should be searched after the context classloader(s).
     */
    public transient List<ClassLoader> addedClassLoaders;

    /**
     * If non-null, this list of ClassLoaders will be searched instead of the visible/context ClassLoader(s). In
     * particular, this causes ClassGraph to ignore the java.class.path system property.
     */
    public transient List<ClassLoader> overrideClassLoaders;

    /**
     * If non-null, specifies manually-added ModuleLayers that should be searched after the visible ModuleLayers.
     */
    public transient List<Object> addedModuleLayers;

    /**
     * If non-null, this list of ModuleLayers will be searched instead of the visible ModuleLayers.
     */
    public transient List<Object> overrideModuleLayers;

    /**
     * If non-null, specifies a list of classpath elements (String, {@link URL} or {@link URI} to use to override
     * the default classpath.
     */
    public List<Object> overrideClasspath;

    /** If non-null, a list of filter operations to apply to classpath elements. */
    public transient List<Object> classpathElementFilters;

    /** Whether to initialize classes when loading them. */
    public boolean initializeLoadedClasses;

    /**
     * If true, nested jarfiles (jarfiles within jarfiles) that are extracted during scanning are removed from their
     * temporary directory (e.g. /tmp/ClassGraph-8JX2u4w) after the scan has completed. If false, temporary files
     * are removed by the {@link ScanResult} finalizer, or on JVM exit.
     */
    public boolean removeTemporaryFilesAfterScan;

    /** If true, do not fetch paths from parent classloaders. */
    public boolean ignoreParentClassLoaders;

    /** If true, do not scan module layers that are the parent of other module layers. */
    public boolean ignoreParentModuleLayers;

    /** Commandline module path parameters. */
    public ModulePathInfo modulePathInfo = new ModulePathInfo();

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
     */
    public int maxBufferedJarRAMSize = 64 * 1024 * 1024;

    /** If true, use a {@link MappedByteBuffer} rather than the {@link FileChannel} API to access file content. */
    public boolean enableMemoryMapping;

    /** If true, all multi-release versions of a resource are found. */
    public boolean enableMultiReleaseVersions;

    // -------------------------------------------------------------------------------------------------------------

    /** Constructor for deserialization. */
    public ScanSpec() {
        // Intentionally empty
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Sort prefixes to ensure correct accept/reject evaluation (see Issue #167). */
    public void sortPrefixes() {
        for (final Field field : ScanSpec.class.getDeclaredFields()) {
            if (AcceptReject.class.isAssignableFrom(field.getType())) {
                try {
                    ((AcceptReject) field.get(this)).sortPrefixes();
                } catch (final ReflectiveOperationException e) {
                    throw new RuntimeException("Field is not accessible: " + field, e);
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Override the automatically-detected classpath with a custom path. You can specify multiple elements in
     * separate calls, and if this method is called even once, the default classpath will be overridden, such that
     * nothing but the provided classpath will be scanned, i.e. causes ClassLoaders to be ignored, as well as the
     * java.class.path system property.
     * 
     * @param overrideClasspathElement
     *            The classpath element to add as an override to the default classpath.
     */
    public void addClasspathOverride(final Object overrideClasspathElement) {
        if (this.overrideClasspath == null) {
            this.overrideClasspath = new ArrayList<>();
        }
        if (overrideClasspathElement instanceof ClassLoader) {
            throw new IllegalArgumentException(
                    "Need to pass ClassLoader instances to overrideClassLoaders, not overrideClasspath");
        }
        this.overrideClasspath
                .add(overrideClasspathElement instanceof String || overrideClasspathElement instanceof URL
                        || overrideClasspathElement instanceof URI ? overrideClasspathElement
                                : overrideClasspathElement.toString());
    }

    /**
     * Add a classpath element filter. The provided {@link ClasspathElementFilter} or
     * {@link ClasspathElementURLFilter} should return true if the path string or {@link URL} passed to it is a path
     * that should be scanned.
     * 
     * @param filterLambda
     *            The classpath element filter to apply to all discovered classpath elements, to decide which should
     *            be scanned.
     */
    public void filterClasspathElements(final Object filterLambda) {
        if (!(filterLambda instanceof ClasspathElementFilter
                || filterLambda instanceof ClasspathElementURLFilter)) {
            throw new IllegalArgumentException();
        }
        if (this.classpathElementFilters == null) {
            this.classpathElementFilters = new ArrayList<>(2);
        }
        this.classpathElementFilters.add(filterLambda);
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
     * Allow a specified URL scheme in classpath elements.
     *
     * @param scheme
     *            the scheme, e.g. "http".
     */
    public void enableURLScheme(final String scheme) {
        if (scheme == null || scheme.length() < 2) {
            throw new IllegalArgumentException("URL schemes must contain at least two characters");
        }
        if (allowedURLSchemes == null) {
            allowedURLSchemes = new HashSet<>();
        }
        allowedURLSchemes.add(scheme.toLowerCase());
    }

    /**
     * Completely override the list of ClassLoaders to scan. (This only works if overrideClasspath() is not called.)
     * Causes the java.class.path system property to be ignored.
     * 
     * @param overrideClassLoaders
     *            The classloaders to override the default context classloaders with.
     */
    public void overrideClassLoaders(final ClassLoader... overrideClassLoaders) {
        if (overrideClassLoaders.length == 0) {
            throw new IllegalArgumentException("At least one override ClassLoader must be provided");
        }
        this.addedClassLoaders = null;
        this.overrideClassLoaders = new ArrayList<>();
        for (final ClassLoader classLoader : overrideClassLoaders) {
            if (classLoader != null) {
                this.overrideClassLoaders.add(classLoader);
            }
        }
    }

    /**
     * Return true if the argument is a ModuleLayer or a subclass of ModuleLayer.
     *
     * @param moduleLayer
     *            the module layer
     * @return true if the argument is a ModuleLayer or a subclass of ModuleLayer.
     */
    private static boolean isModuleLayer(final Object moduleLayer) {
        if (moduleLayer == null) {
            throw new IllegalArgumentException("ModuleLayer references must not be null");
        }
        for (Class<?> currClass = moduleLayer.getClass(); currClass != null; currClass = currClass
                .getSuperclass()) {
            if (currClass.getName().equals("java.lang.ModuleLayer")) {
                return true;
            }
        }
        return false;
    }

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
     */
    public void addModuleLayer(final Object moduleLayer) {
        if (!isModuleLayer(moduleLayer)) {
            throw new IllegalArgumentException("moduleLayer must be of type java.lang.ModuleLayer");
        }
        if (this.addedModuleLayers == null) {
            this.addedModuleLayers = new ArrayList<>();
        }
        this.addedModuleLayers.add(moduleLayer);
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
     */
    public void overrideModuleLayers(final Object... overrideModuleLayers) {
        if (overrideModuleLayers == null) {
            throw new IllegalArgumentException("overrideModuleLayers cannot be null");
        }
        if (overrideModuleLayers.length == 0) {
            throw new IllegalArgumentException("At least one override ModuleLayer must be provided");
        }
        for (final Object moduleLayer : overrideModuleLayers) {
            if (!isModuleLayer(moduleLayer)) {
                throw new IllegalArgumentException("moduleLayer must be of type java.lang.ModuleLayer");
            }
        }
        this.addedModuleLayers = null;
        this.overrideModuleLayers = new ArrayList<>();
        Collections.addAll(this.overrideModuleLayers, overrideModuleLayers);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Whether a path is a descendant of a rejected path, or an ancestor or descendant of an accepted path.
     */
    public enum ScanSpecPathMatch {
        /** Path starts with (or is) a rejected path prefix. */
        HAS_REJECTED_PATH_PREFIX,
        /** Path starts with an accepted path prefix. */
        HAS_ACCEPTED_PATH_PREFIX,
        /** Path is accepted. */
        AT_ACCEPTED_PATH,
        /** Path is an ancestor of an accepted path. */
        ANCESTOR_OF_ACCEPTED_PATH,
        /** Path is the package of a specifically-accepted class. */
        AT_ACCEPTED_CLASS_PACKAGE,
        /** Path is not accepted and not rejected. */
        NOT_WITHIN_ACCEPTED_PATH
    }

    /**
     * Returns true if the given directory path is a descendant of a rejected path, or an ancestor or descendant of
     * an accepted path. The path should end in "/".
     *
     * @param relativePath
     *            the relative path
     * @return the {@link ScanSpecPathMatch}
     */
    public ScanSpecPathMatch dirAcceptMatchStatus(final String relativePath) {
        // In rejected path
        if (pathAcceptReject.isRejected(relativePath) || pathPrefixAcceptReject.isRejected(relativePath)) {
            // An prefix of this path is rejected.
            return ScanSpecPathMatch.HAS_REJECTED_PATH_PREFIX;
        }

        if (pathAcceptReject.acceptIsEmpty() && classPackagePathAcceptReject.acceptIsEmpty()) {
            // If there are no accepted packages, the root package is accepted
            return relativePath.isEmpty() || relativePath.equals("/") ? ScanSpecPathMatch.AT_ACCEPTED_PATH
                    : ScanSpecPathMatch.HAS_ACCEPTED_PATH_PREFIX;
        }

        // At accepted path
        if (pathAcceptReject.isSpecificallyAcceptedAndNotRejected(relativePath)) {
            // Reached an accepted path
            return ScanSpecPathMatch.AT_ACCEPTED_PATH;
        }
        if (classPackagePathAcceptReject.isSpecificallyAcceptedAndNotRejected(relativePath)) {
            // Reached a package containing a specifically-accepted class
            return ScanSpecPathMatch.AT_ACCEPTED_CLASS_PACKAGE;
        }

        // Descendant of accepted path
        if (pathPrefixAcceptReject.isSpecificallyAccepted(relativePath)) {
            // Path prefix matches one in the accept
            return ScanSpecPathMatch.HAS_ACCEPTED_PATH_PREFIX;
        }

        // Ancestor of accepted path
        if (
        // The default package is always the ancestor of accepted paths (need to keep recursing)
        relativePath.equals("/")
                // relativePath is an ancestor (prefix) of an accepted path
                || pathAcceptReject.acceptHasPrefix(relativePath)
                // relativePath is an ancestor (prefix) of an accepted class' parent directory
                || classfilePathAcceptReject.acceptHasPrefix(relativePath)) {
            return ScanSpecPathMatch.ANCESTOR_OF_ACCEPTED_PATH;
        }

        // Not in accepted path
        return ScanSpecPathMatch.NOT_WITHIN_ACCEPTED_PATH;
    }

    /**
     * Returns true if the given relative path (for a classfile name, including ".class") matches a
     * specifically-accepted (and non-rejected) classfile's relative path.
     *
     * @param relativePath
     *            the relative path
     * @return true if the given relative path (for a classfile name, including ".class") matches a
     *         specifically-accepted (and non-rejected) classfile's relative path.
     */
    public boolean classfileIsSpecificallyAccepted(final String relativePath) {
        return classfilePathAcceptReject.isSpecificallyAcceptedAndNotRejected(relativePath);
    }

    /**
     * Returns true if the class is specifically rejected, or is within a rejected package.
     *
     * @param className
     *            the class name
     * @return true if the class is specifically rejected, or is within a rejected package.
     */
    public boolean classOrPackageIsRejected(final String className) {
        return classAcceptReject.isRejected(className) || packagePrefixAcceptReject.isRejected(className);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Write to log.
     *
     * @param log
     *            The {@link LogNode} to log to.
     */
    public void log(final LogNode log) {
        if (log != null) {
            final LogNode scanSpecLog = log.log("ScanSpec:");
            for (final Field field : ScanSpec.class.getDeclaredFields()) {
                try {
                    scanSpecLog.log(field.getName() + ": " + field.get(this));
                } catch (final ReflectiveOperationException e) {
                    // Ignore
                }
            }
        }
    }
}
