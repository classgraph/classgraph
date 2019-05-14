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

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import nonapi.io.github.classgraph.utils.CollectionUtils;

/** Holds metadata about a package encountered during a scan. */
public class ModuleInfo implements Comparable<ModuleInfo>, HasName {
    /** The name of the module. */
    private String name;

    /** The classpath element. */
    private transient ClasspathElement classpathElement;

    /** The {@link ModuleRef}. */
    private transient ModuleRef moduleRef;

    /** The location of the module as a URI. */
    private transient URI locationURI;

    /**
     * Unique {@link AnnotationInfo} objects for any annotations on the module-info.class file, if present, else
     * null.
     */
    private Set<AnnotationInfo> annotationInfoSet;

    /** {@link AnnotationInfo} objects for any annotations on the module-info.class file, if present, else null. */
    private AnnotationInfoList annotationInfo;

    /** {@link PackageInfo} objects for packages found within the class, if any, else null. */
    private Set<PackageInfo> packageInfoSet;

    /** Set of classes in the module. */
    private Set<ClassInfo> classInfoSet;

    // -------------------------------------------------------------------------------------------------------------

    /** Deerialization constructor. */
    ModuleInfo() {
        // Empty
    }

    /**
     * Construct a ModuleInfo object.
     *
     * @param moduleRef
     *            the module ref
     * @param classpathElement
     *            the classpath element
     */
    ModuleInfo(final ModuleRef moduleRef, final ClasspathElement classpathElement) {
        this.moduleRef = moduleRef;
        this.classpathElement = classpathElement;
        this.name = classpathElement.getModuleName();
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
     * The module location, or null for modules whose location is unknown.
     *
     * @return the module location, or null for modules whose location is unknown.
     */
    public URI getLocation() {
        if (locationURI == null) {
            locationURI = moduleRef != null ? moduleRef.getLocation() : null;
            if (locationURI == null) {
                locationURI = classpathElement.getURI();
            }
        }
        return locationURI;
    }

    /**
     * The {@link ModuleRef} for this module, or null if this module was obtained from a classpath element on the
     * traditional classpath that contained a {@code module-info.class} file.
     *
     * @return the {@link ModuleRef}, or null if this module was obtained from a classpath element on the
     *         traditional classpath that contained a {@code module-info.class} file.
     */
    public ModuleRef getModuleRef() {
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
        if (classInfoSet == null) {
            classInfoSet = new HashSet<>();
        }
        classInfoSet.add(classInfo);
    }

    /**
     * Get the {@link ClassInfo} object for the named class in this module, or null if the class was not found in
     * this module.
     *
     * @param className
     *            the class name
     * @return the {@link ClassInfo} object for the named class in this module, or null if the class was not found
     *         in this module.
     */
    public ClassInfo getClassInfo(final String className) {
        for (final ClassInfo ci : classInfoSet) {
            if (ci.getName().equals(className)) {
                return ci;
            }
        }
        return null;
    }

    /**
     * Get the list of {@link ClassInfo} objects for all classes that are members of this package.
     *
     * @return the list of {@link ClassInfo} objects for all classes that are members of this package.
     */
    public ClassInfoList getClassInfo() {
        return new ClassInfoList(classInfoSet, /* sortByName = */ true);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add a {@link PackageInfo} object to this {@link ModuleInfo}.
     *
     * @param packageInfo
     *            the {@link PackageInfo} object
     */
    void addPackageInfo(final PackageInfo packageInfo) {
        if (packageInfoSet == null) {
            packageInfoSet = new HashSet<>();
        }
        packageInfoSet.add(packageInfo);
    }

    /**
     * Get the {@link PackageInfo} object for the named package in this module, or null if the package was not found
     * in this module.
     *
     * @param packageName
     *            the package name
     * @return the {@link PackageInfo} object for the named package in this module, or null if the package was not
     *         found in this module.
     */
    public PackageInfo getPackageInfo(final String packageName) {
        if (packageInfoSet == null) {
            return null;
        }
        for (final PackageInfo pi : packageInfoSet) {
            if (pi.getName().equals(packageName)) {
                return pi;
            }
        }
        return null;
    }

    /**
     * Get the {@link PackageInfo} objects for all packages that are members of this module.
     *
     * @return the list of {@link PackageInfo} objects for all packages that are members of this module.
     */
    public PackageInfoList getPackageInfo() {
        if (packageInfoSet == null) {
            return new PackageInfoList(1);
        }
        final PackageInfoList packageInfoList = new PackageInfoList(packageInfoSet);
        CollectionUtils.sortIfNotEmpty(packageInfoList);
        return packageInfoList;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add annotations found in a module descriptor classfile.
     *
     * @param moduleAnnotations
     *            the module annotations
     */
    void addAnnotations(final AnnotationInfoList moduleAnnotations) {
        // Currently only class annotations are used in the module-info.class file
        if (moduleAnnotations != null && !moduleAnnotations.isEmpty()) {
            if (annotationInfoSet == null) {
                annotationInfoSet = new LinkedHashSet<>();
            }
            annotationInfoSet.addAll(moduleAnnotations);
        }
    }

    /**
     * Get a the named annotation on this module, or null if the module does not have the named annotation.
     * 
     * @param annotationName
     *            The annotation name.
     * @return An {@link AnnotationInfo} object representing the named annotation on this module, or null if the
     *         module does not have the named annotation.
     */
    public AnnotationInfo getAnnotationInfo(final String annotationName) {
        return getAnnotationInfo().get(annotationName);
    }

    /**
     * Get any annotations on the {@code package-info.class} file.
     *
     * @return the list of {@link AnnotationInfo} objects for annotations on the {@code package-info.class} file.
     */
    public AnnotationInfoList getAnnotationInfo() {
        if (annotationInfo == null) {
            if (annotationInfoSet == null) {
                annotationInfo = AnnotationInfoList.EMPTY_LIST;
            } else {
                annotationInfo = new AnnotationInfoList();
                annotationInfo.addAll(annotationInfoSet);
            }
        }
        return annotationInfo;
    }

    /**
     * Check if this module has the named annotation.
     * 
     * @param annotationName
     *            The name of an annotation.
     * @return true if this module has the named annotation.
     */
    public boolean hasAnnotation(final String annotationName) {
        return getAnnotationInfo().containsName(annotationName);
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see java.lang.Comparable#compareTo(java.lang.Object)
     */
    @Override
    public int compareTo(final ModuleInfo other) {
        final int diff = this.name.compareTo(other.name);
        if (diff != 0) {
            return diff;
        }
        final URI thisLoc = this.getLocation();
        final URI otherLoc = other.getLocation();
        if (thisLoc != null && otherLoc != null) {
            return thisLoc.compareTo(otherLoc);
        }
        return 0;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return name.hashCode();
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof ModuleInfo)) {
            return false;
        }
        return this.compareTo((ModuleInfo) obj) == 0;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return name;
    }
}
