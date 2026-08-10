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

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import nonapi.io.github.classgraph.utils.Assert;
import nonapi.io.github.classgraph.utils.CollectionUtils;
import org.jspecify.annotations.Nullable;

/** Holds metadata about a module encountered during a scan. */
public class ModuleInfo implements Comparable<ModuleInfo>, HasName, HasAnnotations {
    /** The name of the module. */
    private final String name;

    /** The classpath element. */
    private final ClasspathElement classpathElement;

    /**
     * The {@link ModuleRef}, or null if this module was obtained from a classpath element on the traditional
     * classpath.
     */
    private @Nullable ModuleRef moduleRef;

    /** The location of the module as a URI, or null if the location is unknown. */
    private @Nullable URI locationURI;

    /**
     * Unique {@link AnnotationInfo} objects for any annotations on the module-info.class file, if present, else
     * null.
     */
    private @Nullable Set<AnnotationInfo> annotationInfoSet;

    /**
     * {@link AnnotationInfo} objects for any annotations on the module-info.class file, if present, else null.
     */
    private @Nullable AnnotationInfoList annotationInfo;

    /**
     * {@link PackageInfo} objects for packages found within the class, keyed by package name, if any, else null.
     */
    private @Nullable Map<String, PackageInfo> packageNameToPackageInfo;

    /** Classes in the module, keyed by class name, or null if none. */
    private @Nullable Map<String, ClassInfo> classNameToClassInfo;

    /** The result of the scan that produced this module, set once the scan is complete. */
    private @Nullable ScanResult scanResult;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Construct a ModuleInfo object.
     *
     * @param moduleRef
     *            the module ref, or null if this module was obtained from a classpath element on the traditional
     *            classpath
     * @param classpathElement
     *            the classpath element
     * @param name
     *            the module name
     */
    ModuleInfo(final @Nullable ModuleRef moduleRef, final ClasspathElement classpathElement, final String name) {
        this.moduleRef = moduleRef;
        this.classpathElement = classpathElement;
        this.name = name;
    }

    /**
     * The module name, or {@code ""} for the unnamed module.
     *
     * @return the module name, or {@code ""} for the unnamed module.
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * The module location as a {@link URI}, or null for modules whose location is unknown.
     *
     * @return the module location as a {@link URI}, or null for modules whose location is unknown.
     */
    public @Nullable URI getLocationURI() {
        var location = locationURI;
        if (location == null) {
            final var moduleReference = moduleRef;
            location = moduleReference != null ? moduleReference.getLocationURI() : null;
            if (location == null) {
                try {
                    location = classpathElement.getURI();
                } catch (final IllegalStateException e) {
                    // The classpath element has no known URI either, so the location is unknown
                    return null;
                }
            }
            locationURI = location;
        }
        return location;
    }

    /**
     * The {@link ModuleRef} for this module, or null if this module was obtained from a classpath element on the
     * traditional classpath that contained a {@code module-info.class} file.
     *
     * @return the {@link ModuleRef}, or null if this module was obtained from a classpath element on the
     *         traditional classpath that contained a {@code module-info.class} file.
     */
    public @Nullable ModuleRef getModuleRef() {
        return moduleRef;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add a {@link ClassInfo} object to this {@link ModuleInfo}.
     *
     * @param classInfo
     *            the {@link ClassInfo} object to add
     */
    void addClassInfo(final ClassInfo classInfo) {
        var classes = classNameToClassInfo;
        if (classes == null) {
            classNameToClassInfo = classes = new HashMap<>();
        }
        classes.put(classInfo.getName(), classInfo);
    }

    /**
     * Get the {@link ClassInfo} object for the named class in this module, or null if the class was not found in
     * this module.
     *
     * @param className
     *            The fully-qualified name of the class, in the same form as {@link Class#getName()}.
     * @return the {@link ClassInfo} object for the named class in this module, or null if the class was not found
     *         in this module.
     */
    public @Nullable ClassInfo getClassInfo(final String className) {
        Assert.notNull(className, "className");
        // classNameToClassInfo is null if no classes in this module were accepted, e.g. if the module-info.class
        // file was the only classfile read from the module
        final var classes = classNameToClassInfo;
        return classes == null ? null : classes.get(className);
    }

    /**
     * Get the list of {@link ClassInfo} objects for all classes that are members of this module.
     *
     * @return the list of {@link ClassInfo} objects for all classes that are members of this module.
     */
    public ClassInfoList getClassInfo() {
        final var classes = classNameToClassInfo;
        // The ClassInfoList(Collection) constructor uniquifies and sorts by name
        return classes == null ? ClassInfoList.EMPTY_LIST : unmodifiable(new ClassInfoList(classes.values()));
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add a {@link PackageInfo} object to this {@link ModuleInfo}.
     *
     * @param packageInfo
     *            the {@link PackageInfo} object
     */
    void addPackageInfo(final PackageInfo packageInfo) {
        var packages = packageNameToPackageInfo;
        if (packages == null) {
            packageNameToPackageInfo = packages = new HashMap<>();
        }
        packages.put(packageInfo.getName(), packageInfo);
    }

    /**
     * Get the {@link PackageInfo} object for the named package in this module, or null if the package was not found
     * in this module.
     *
     * @param packageName
     *            The package name, with {@code '.'} between package name segments, e.g. {@code "com.xyz"}. The root
     *            package is named {@code ""}.
     * @return the {@link PackageInfo} object for the named package in this module, or null if the package was not
     *         found in this module.
     */
    public @Nullable PackageInfo getPackageInfo(final String packageName) {
        Assert.notNull(packageName, "packageName");
        final var packages = packageNameToPackageInfo;
        return packages == null ? null : packages.get(packageName);
    }

    /**
     * Get the {@link PackageInfo} objects for all packages that are members of this module.
     *
     * @return the list of {@link PackageInfo} objects for all packages that are members of this module.
     */
    public PackageInfoList getPackageInfo() {
        final var packages = packageNameToPackageInfo;
        if (packages == null) {
            return PackageInfoList.EMPTY_LIST;
        }
        final PackageInfoList packageInfoList = new PackageInfoList(packages.values());
        CollectionUtils.sortIfNotEmpty(packageInfoList);
        return unmodifiable(packageInfoList);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Set ScanResult backreferences in info objects after scan has completed.
     *
     * @param scanResult
     *            the scan result
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
     * Add annotations found in a module descriptor classfile.
     *
     * @param moduleAnnotations
     *            the module annotations
     */
    void addAnnotations(final @Nullable AnnotationInfoList moduleAnnotations) {
        // Currently only class annotations are used in the module-info.class file
        if (moduleAnnotations != null && !moduleAnnotations.isEmpty()) {
            var annotations = annotationInfoSet;
            if (annotations == null) {
                annotationInfoSet = annotations = new LinkedHashSet<>();
            }
            annotations.addAll(moduleAnnotations);
        }
    }

    /**
     * Get a list of the annotations and meta-annotations on the {@code module-info.class} file for this module.
     *
     * @return A list of the annotations and meta-annotations on the {@code module-info.class} file, along with any
     *         annotation parameter values, wrapped in {@link AnnotationInfo} objects, or the empty list if none.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    @Override
    public AnnotationInfoList getAllAnnotationInfo() {
        // scanResult is only null if the scan has not completed, which callers cannot observe
        if (scanResult != null) {
            scanResult.scanSpec.checkAnnotationInfoEnabled();
        }
        var annotations = annotationInfo;
        if (annotations == null) {
            final var annotationSet = annotationInfoSet;
            if (annotationSet == null) {
                annotations = AnnotationInfoList.EMPTY_LIST;
            } else {
                final AnnotationInfoList directAnnotations = new AnnotationInfoList(annotationSet.size());
                directAnnotations.addAll(annotationSet);
                // A module has no superclass, so there are no @Inherited annotations to add
                annotations = unmodifiable(
                        AnnotationInfoList.getIndirectAnnotations(directAnnotations, /* annotatedClass = */ null));
            }
            annotationInfo = annotations;
        }
        return annotations;
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public int compareTo(final ModuleInfo other) {
        final var diff = this.name.compareTo(other.name);
        if (diff != 0) {
            return diff;
        }
        final var thisLoc = this.getLocationURI();
        final var otherLoc = other.getLocationURI();
        if (thisLoc != null && otherLoc != null) {
            return thisLoc.compareTo(otherLoc);
        }
        return 0;
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final ModuleInfo other)) {
            return false;
        }
        return this.compareTo(other) == 0;
    }

    /**
     * Render this module as a string, in the same form as {@link Module#toString()}, e.g.
     * {@code "module java.base"}. Call {@link #getName()} for the module name alone.
     *
     * @return the string representation.
     */
    @Override
    public String toString() {
        return "module " + name;
    }
}
