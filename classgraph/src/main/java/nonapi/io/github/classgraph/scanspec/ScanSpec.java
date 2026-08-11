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

import java.lang.reflect.Field;

import nonapi.io.github.classgraph.classpathspec.ClassPathSpec;
import nonapi.io.github.classgraph.utils.AcceptReject;
import nonapi.io.github.classgraph.utils.AcceptReject.AcceptRejectLeafname;
import nonapi.io.github.classgraph.utils.AcceptReject.AcceptRejectPrefix;
import nonapi.io.github.classgraph.utils.AcceptReject.AcceptRejectWholeString;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.vfsspec.VfsScanSpec;
import org.jspecify.annotations.Nullable;

/**
 * The scanning specification.
 *
 * <p>
 * This holds the settings that the scanner itself reads. The settings that are read by the libraries the scanner is
 * built on are held in the specs of those libraries, which are composed into this one: the classpath and module
 * path to search is described by {@link #classPathSpec}, and how archives are read is described by
 * {@link #vfsScanSpec}.
 */
public class ScanSpec {
    /** How the classpath and the module path are found. */
    public final ClassPathSpec classPathSpec = new ClassPathSpec();

    /** How jarfiles are read. */
    public final VfsScanSpec vfsScanSpec = new VfsScanSpec();

    // -------------------------------------------------------------------------------------------------------------

    /** Package accept/reject criteria (with separator '.'). */
    public AcceptRejectWholeString packageAcceptReject = new AcceptRejectWholeString('.');

    /**
     * Package prefix accept/reject criteria, for recursive scanning (with separator '.', ending in '.').
     */
    public AcceptRejectPrefix packagePrefixAcceptReject = new AcceptRejectPrefix('.');

    /** Path accept/reject criteria (with separator '/'). */
    public AcceptRejectWholeString pathAcceptReject = new AcceptRejectWholeString('/');

    /**
     * Path prefix accept/reject criteria, for recursive scanning (with separator '/', ending in '/').
     */
    public AcceptRejectPrefix pathPrefixAcceptReject = new AcceptRejectPrefix('/');

    /**
     * Class accept/reject criteria (fully-qualified class names, with separator '.').
     */
    public AcceptRejectWholeString classAcceptReject = new AcceptRejectWholeString('.');

    /**
     * Classfile accept/reject criteria (path to classfiles, with separator '/', ending in ".class").
     */
    public AcceptRejectWholeString classfilePathAcceptReject = new AcceptRejectWholeString('/');

    /** Package containing accepted/rejected classes (with separator '.'). */
    public AcceptRejectWholeString classPackageAcceptReject = new AcceptRejectWholeString('.');

    /** Path to accepted/rejected classes (with separator '/'). */
    public AcceptRejectWholeString classPackagePathAcceptReject = new AcceptRejectWholeString('/');

    /** Jar accept/reject criteria (leafname only, ending in ".jar"). */
    public AcceptRejectLeafname jarAcceptReject = new AcceptRejectLeafname('/');

    /** Classpath element resource path accept/reject criteria. */
    public AcceptRejectWholeString classpathElementResourcePathAcceptReject = //
            new AcceptRejectWholeString('/');

    // -------------------------------------------------------------------------------------------------------------

    /** If true, scan jarfiles. */
    public boolean scanJars = true;

    /** If true, scan directories. */
    public boolean scanDirs = true;

    /** If true, scan classfile bytecodes, producing {@code ClassInfo} objects. */
    public boolean enableClassInfo;

    /**
     * If true, enables the saving of field info during the scan. This information can be obtained using
     * {@code ClassInfo#getFieldInfo()}. By default, field info is not scanned, for efficiency.
     */
    public boolean enableFieldInfo;

    /**
     * If true, enables the saving of method info during the scan. This information can be obtained using
     * {@code ClassInfo#getMethodInfo()}. By default, method info is not scanned, for efficiency.
     */
    public boolean enableMethodInfo;

    /**
     * If true, enables the saving of annotation info (for class, field, method or method parameter annotations)
     * during the scan. This information can be obtained using {@code ClassInfo#getAllAnnotationInfo()} etc. By
     * default, annotation info is not scanned, for efficiency.
     */
    public boolean enableAnnotationInfo;

    /**
     * Enable the storing of constant initializer values for static final fields in ClassInfo objects.
     */
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
     * this currently, since disabling it can lead to problems.)
     */
    // #261
    public boolean extendScanningUpwardsToExternalClasses = true;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * If true, nested jarfiles (jarfiles within jarfiles) that are extracted during scanning are removed from their
     * temporary directory (e.g. /tmp/ClassGraph-8JX2u4w) after the scan has completed. If false, temporary files
     * are removed when the {@code ScanResult} is closed, or failing that, on JVM exit.
     */
    public boolean removeTemporaryFilesAfterScan;

    // -------------------------------------------------------------------------------------------------------------

    /** Constructor. */
    public ScanSpec() {
        // Intentionally empty
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Throw {@link IllegalStateException} if {@link #enableClassInfo} was not set before the scan.
     *
     * @throws IllegalStateException
     *             if {@code ClassGraph#enableClassInfo()} was not called before the scan.
     */
    public void checkClassInfoEnabled() {
        checkEnabled(enableClassInfo, "enableClassInfo");
    }

    /**
     * Throw {@link IllegalStateException} if {@link #enableMethodInfo} was not set before the scan.
     *
     * @throws IllegalStateException
     *             if {@code ClassGraph#enableMethodInfo()} was not called before the scan.
     */
    public void checkMethodInfoEnabled() {
        checkEnabled(enableMethodInfo, "enableMethodInfo");
    }

    /**
     * Throw {@link IllegalStateException} if {@link #enableFieldInfo} was not set before the scan.
     *
     * @throws IllegalStateException
     *             if {@code ClassGraph#enableFieldInfo()} was not called before the scan.
     */
    public void checkFieldInfoEnabled() {
        checkEnabled(enableFieldInfo, "enableFieldInfo");
    }

    /**
     * Throw {@link IllegalStateException} if {@link #enableAnnotationInfo} was not set before the scan.
     *
     * @throws IllegalStateException
     *             if {@code ClassGraph#enableAnnotationInfo()} was not called before the scan.
     */
    public void checkAnnotationInfoEnabled() {
        checkEnabled(enableAnnotationInfo, "enableAnnotationInfo");
    }

    /**
     * Throw {@link IllegalStateException} if {@link #enableInterClassDependencies} was not set before the scan.
     *
     * @throws IllegalStateException
     *             if {@code ClassGraph#enableInterClassDependencies()} was not called before the scan.
     */
    public void checkInterClassDependenciesEnabled() {
        checkEnabled(enableInterClassDependencies, "enableInterClassDependencies");
    }

    /**
     * Throw {@link IllegalStateException} if {@link #enableStaticFinalFieldConstantInitializerValues} was not set
     * before the scan.
     *
     * @throws IllegalStateException
     *             if {@code ClassGraph#enableStaticFinalFieldConstantInitializerValues()} was not called before the
     *             scan.
     */
    public void checkStaticFinalFieldConstantInitializerValuesEnabled() {
        checkEnabled(enableStaticFinalFieldConstantInitializerValues,
                "enableStaticFinalFieldConstantInitializerValues");
    }

    /**
     * Throw {@link IllegalStateException} naming the {@code ClassGraph} method that has to be called before the
     * scan, if the scan option it sets was not enabled.
     *
     * @param enabled
     *            whether the scan option was enabled.
     * @param enablerMethodName
     *            the name of the {@code ClassGraph} method that enables the scan option.
     * @throws IllegalStateException
     *             if the scan option was not enabled.
     */
    private static void checkEnabled(final boolean enabled, final String enablerMethodName) {
        if (!enabled) {
            throw new IllegalStateException("Please call ClassGraph#" + enablerMethodName + "() before #scan()");
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Sort prefixes to ensure correct accept/reject evaluation. */
    // #167
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
        // The composed specs have to be sorted explicitly, since getDeclaredFields() does not return the fields of
        // other classes
        classPathSpec.sortPrefixes();
    }

    // -------------------------------------------------------------------------------------------------------------

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
            // A prefix of this path is rejected.
            return ScanSpecPathMatch.HAS_REJECTED_PATH_PREFIX;
        }

        if (pathAcceptReject.acceptIsEmpty() && classPackagePathAcceptReject.acceptIsEmpty()) {
            // If there are no accepted packages, the root package is accepted
            return relativePath.isEmpty() || "/".equals(relativePath) ? ScanSpecPathMatch.AT_ACCEPTED_PATH
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
        "/".equals(relativePath)
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
    public void log(final @Nullable LogNode log) {
        if (log != null) {
            final var scanSpecLog = log.log("ScanSpec:");
            for (final Field field : ScanSpec.class.getDeclaredFields()) {
                if (field.getType() == ClassPathSpec.class || field.getType() == VfsScanSpec.class) {
                    // The composed specs log their own fields, below
                    continue;
                }
                try {
                    scanSpecLog.log(field.getName() + ": " + field.get(this));
                } catch (final ReflectiveOperationException e) {
                    // Ignore
                }
            }
            classPathSpec.log(log);
            vfsScanSpec.log(log);
        }
    }
}
