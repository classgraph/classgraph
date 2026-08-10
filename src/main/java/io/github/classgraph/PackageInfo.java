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

import static io.github.classgraph.PotentiallyUnmodifiableList.unmodifiable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.Assert;
import nonapi.io.github.classgraph.utils.CollectionUtils;
import org.jspecify.annotations.Nullable;

/** Holds metadata about a package encountered during a scan. */
public class PackageInfo implements Comparable<PackageInfo>, HasName, HasAnnotations {
    /** Name of the package. */
    private final String name;

    /**
     * Unique {@link AnnotationInfo} objects for any annotations on the package-info.class file, if present, else
     * null.
     */
    private @Nullable Set<AnnotationInfo> annotationInfoSet;

    /**
     * {@link AnnotationInfo} for any annotations on the package-info.class file, if present, else null.
     */
    private @Nullable AnnotationInfoList annotationInfo;

    /** The parent package of this package, or null if this is the root package. */
    private @Nullable PackageInfo parent;

    /** The child packages of this package, or null if none. */
    private @Nullable Set<PackageInfo> children;

    /** Set of classes in the package, or null if none. */
    private @Nullable Map<String, ClassInfo> memberClassNameToClassInfo;

    /** The result of the scan that produced this package, set once the scan is complete. */
    private @Nullable ScanResult scanResult;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Construct a PackageInfo object.
     *
     * @param packageName
     *            the package name
     */
    PackageInfo(final String packageName) {
        this.name = packageName;
    }

    /**
     * The package name ("" for the root package).
     *
     * @return the name
     */
    @Override
    public String getName() {
        return name;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add annotations found in a package descriptor classfile.
     *
     * @param packageAnnotations
     *            the package annotations
     */
    void addAnnotations(final @Nullable AnnotationInfoList packageAnnotations) {
        // Add class annotations from the package-info.class file
        if (packageAnnotations != null && !packageAnnotations.isEmpty()) {
            var annotations = annotationInfoSet;
            if (annotations == null) {
                annotationInfoSet = annotations = new LinkedHashSet<>();
            }
            annotations.addAll(packageAnnotations);
        }
    }

    /**
     * Merge a {@link ClassInfo} object for a package-info.class file into this PackageInfo. (The same
     * package-info.class file may be present in multiple definitions of the package in different modules.)
     *
     * @param classInfo
     *            the {@link ClassInfo} object to add to the package.
     */
    void addClassInfo(final ClassInfo classInfo) {
        var memberClasses = memberClassNameToClassInfo;
        if (memberClasses == null) {
            memberClassNameToClassInfo = memberClasses = new HashMap<>();
        }
        memberClasses.put(classInfo.getName(), classInfo);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Used to set the {@link ScanResult} of this package's annotations after the scan is complete.
     *
     * @param scanResult
     *            the {@link ScanResult}
     */
    void setScanResult(final ScanResult scanResult) {
        this.scanResult = scanResult;
        final var annotations = annotationInfoSet;
        if (annotations != null) {
            for (final AnnotationInfo ai : annotations) {
                ai.setScanResult(scanResult);
            }
        }
    }

    /**
     * Get a list of the annotations and meta-annotations on the {@code package-info.class} file for this package.
     *
     * @return A list of the annotations and meta-annotations on the {@code package-info.class} file, along with any
     *         annotation parameter values, wrapped in {@link AnnotationInfo} objects, or the empty list if none.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    @Override
    public AnnotationInfoList getAllAnnotationInfo() {
        // scanResult is only null if the scan has not completed, which callers cannot observe
        if (scanResult != null && !scanResult.scanSpec.enableAnnotationInfo) {
            throw new IllegalStateException("Please call ClassGraph#enableAnnotationInfo() before #scan()");
        }
        var annotations = annotationInfo;
        if (annotations == null) {
            final var annotationSet = annotationInfoSet;
            if (annotationSet == null) {
                annotations = AnnotationInfoList.EMPTY_LIST;
            } else {
                final AnnotationInfoList directAnnotations = new AnnotationInfoList(annotationSet.size());
                directAnnotations.addAll(annotationSet);
                // A package has no superclass, so there are no @Inherited annotations to add
                annotations = unmodifiable(
                        AnnotationInfoList.getIndirectAnnotations(directAnnotations, /* annotatedClass = */ null));
            }
            annotationInfo = annotations;
        }
        return annotations;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The parent package of this package, or null if this is the root package.
     *
     * @return the parent package, or null if this is the root package.
     */
    public @Nullable PackageInfo getParent() {
        return parent;
    }

    /**
     * The child packages of this package, or the empty list if none.
     *
     * @return the child packages, or the empty list if none.
     */
    public PackageInfoList getChildren() {
        final var childPackages = children;
        if (childPackages == null) {
            return PackageInfoList.EMPTY_LIST;
        }
        final PackageInfoList childrenSorted = new PackageInfoList(childPackages);
        // Ensure children are sorted
        CollectionUtils.sortIfNotEmpty(childrenSorted, Comparator.comparing(o1 -> o1.name));
        return unmodifiable(childrenSorted);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the {@link ClassInfo} object for the named class in this package, or null if the class was not found in
     * this package.
     *
     * @param className
     *            The fully-qualified name of the class, in the same form as {@link Class#getName()}.
     * @return the {@link ClassInfo} object for the named class in this package, or null if the class was not found
     *         in this package.
     */
    public @Nullable ClassInfo getClassInfo(final String className) {
        Assert.notNull(className, "className");
        final var memberClasses = memberClassNameToClassInfo;
        return memberClasses == null ? null : memberClasses.get(className);
    }

    /**
     * Get the {@link ClassInfo} objects for all classes that are members of this package.
     *
     * @return the {@link ClassInfo} objects for all classes that are members of this package.
     */
    public ClassInfoList getClassInfo() {
        final var memberClasses = memberClassNameToClassInfo;
        return memberClasses == null ? ClassInfoList.EMPTY_LIST
                : new ClassInfoList(new HashSet<>(memberClasses.values()), /* sortByName = */ true);
    }

    /**
     * Get the {@link ClassInfo} objects within this package recursively.
     *
     * @param reachableClassInfo
     *            the reachable class info
     */
    private void obtainClassInfoRecursive(final Set<ClassInfo> reachableClassInfo) {
        final var memberClasses = memberClassNameToClassInfo;
        if (memberClasses != null) {
            reachableClassInfo.addAll(memberClasses.values());
        }
        for (final PackageInfo subPackageInfo : getChildren()) {
            subPackageInfo.obtainClassInfoRecursive(reachableClassInfo);
        }
    }

    /**
     * Get the {@link ClassInfo} objects for all classes that are members of this package or a sub-package.
     *
     * @return the {@link ClassInfo} objects for all classes that are members of this package or a sub-package.
     */
    public ClassInfoList getClassInfoRecursive() {
        final Set<ClassInfo> reachableClassInfo = new HashSet<>();
        obtainClassInfoRecursive(reachableClassInfo);
        return new ClassInfoList(reachableClassInfo, /* sortByName = */ true);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the name of the parent package of a parent, or the package of the named class.
     *
     * @param packageOrClassName
     *            The package or class name.
     * @return the parent package, or the package of the named class, or null if packageOrClassName is the root
     *         package ("").
     */
    static @Nullable String getParentPackageName(final String packageOrClassName) {
        if (packageOrClassName.isEmpty()) {
            return null;
        }
        final var lastDotIdx = packageOrClassName.lastIndexOf('.');
        return lastDotIdx < 0 ? "" : packageOrClassName.substring(0, lastDotIdx);
    }

    /**
     * Get the {@link PackageInfo} object for the named package, creating it if it doesn't exist, and also creating
     * {@link PackageInfo} objects for any needed parent packages for which a {@link PackageInfo} has not yet been
     * created.
     *
     * @param packageName
     *            the package name
     * @param packageNameToPackageInfo
     *            a map from package name to package info
     * @param scanSpec
     *            the ScanSpec.
     * @return the {@link PackageInfo} for the named package.
     */
    static PackageInfo getOrCreatePackage(final String packageName,
            final Map<String, PackageInfo> packageNameToPackageInfo, final ScanSpec scanSpec) {
        // Get or create PackageInfo object for this package
        var packageInfo = packageNameToPackageInfo.get(packageName);
        if (packageInfo != null) {
            // PackageInfo object already exists for this package
            return packageInfo;
        }

        // Create new PackageInfo for this package
        packageNameToPackageInfo.put(packageName, packageInfo = new PackageInfo(packageName));

        // If this is not the root package ("")
        if (!packageName.isEmpty()) {
            // Recursively create PackageInfo objects for parent packages (until a parent package that already
            // exists or that is not accepted is reached), and connect each ancestral package to its parent
            // packageName is non-empty here, so getParentPackageName cannot return null
            final var parentPackageName = Objects.requireNonNull(getParentPackageName(packageInfo.name));
            if (scanSpec.packageAcceptReject.isAcceptedAndNotRejected(parentPackageName)
                    || scanSpec.packagePrefixAcceptReject.isAcceptedAndNotRejected(parentPackageName)) {
                final var parentPackageInfo = getOrCreatePackage(parentPackageName, packageNameToPackageInfo,
                        scanSpec);
                // Link package to parent
                var parentChildren = parentPackageInfo.children;
                if (parentChildren == null) {
                    parentPackageInfo.children = parentChildren = new HashSet<>();
                }
                parentChildren.add(packageInfo);
                packageInfo.parent = parentPackageInfo;
            }
        }

        // Return the newly-created PackageInfo object
        return packageInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Comparable#compareTo(java.lang.Object)
     */
    @Override
    public int compareTo(final PackageInfo o) {
        return this.name.compareTo(o.name);
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return name.hashCode();
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final PackageInfo other)) {
            return false;
        }
        return this.name.equals(other.name);
    }

    /**
     * Render this package as a string, in the same form as {@link Package#toString()}, e.g.
     * {@code "package com.xyz"}. Call {@link #getName()} for the package name alone.
     *
     * @return the string representation.
     */
    @Override
    public String toString() {
        return "package " + name;
    }
}
