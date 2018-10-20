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

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Holds metadata about a package encountered during a scan. */
public class ModuleInfo implements Comparable<ModuleInfo>, HasName {
    /** The name of the module. */
    private String name;

    /** The location of the module. */
    private URI location;

    /** The {@link ModuleRef}. */
    private ModuleRef moduleRef;

    /** {@link AnnotationInfo} objects for any annotations on the package-info.class file, if present, else null. */
    private AnnotationInfoList annotationInfo;

    /** {@link PackageInfo} objects for packages found within the class, if any, else null. */
    private Set<PackageInfo> packageInfoSet;

    /** Set of classes in the module. */
    private final Set<ClassInfo> classInfoSet = new HashSet<>();

    // -------------------------------------------------------------------------------------------------------------

    /** Deerialization constructor. */
    ModuleInfo() {
    }

    /** Construct a ModuleInfo object. */
    ModuleInfo(final ModuleRef moduleRef) {
        this.moduleRef = moduleRef;
        this.name = moduleRef.getName();
        this.location = moduleRef.getLocation();
    }

    /** The module name ({@code "<unnamed>"} for the unnamed module). */
    @Override
    public String getName() {
        return name;
    }

    /** The module location. */
    public URI getLocation() {
        return location;
    }

    /** The {@link ModuleRef} for this module. */
    public ModuleRef getModuleRef() {
        return moduleRef;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Add a {@link ClassiInfo} object to this {@link ModuleInfo}. */
    void addClassInfo(final ClassInfo classInfo) {
        classInfoSet.add(classInfo);
    }

    /**
     * Get the {@link ClassInfo} object for the named class in this module, or null if the class was not found in
     * this module.
     */
    public ClassInfo getClassInfo(final String className) {
        if (classInfoSet == null) {
            return null;
        }
        for (final ClassInfo ci : classInfoSet) {
            if (ci.getName().equals(className)) {
                return ci;
            }
        }
        return null;
    }

    /** Get the {@link ClassInfo} objects for all classes that are members of this package. */
    public ClassInfoList getClassInfo() {
        return new ClassInfoList(classInfoSet, /* sortByName = */ true);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Add a {@link PackageInfo} object to this {@link ModuleInfo}. */
    void addPackageInfo(final PackageInfo packageInfo) {
        if (packageInfoSet == null) {
            packageInfoSet = new HashSet<>();
        }
        packageInfoSet.add(packageInfo);
    }

    /**
     * Get the {@link PackageInfo} object for the named packagein this module, or null if the package was not found
     * in this module.
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

    /** Get the {@link PackageInfo} objects for all packages that are members of this module. */
    public PackageInfoList getPackageInfo() {
        if (packageInfoSet == null) {
            return new PackageInfoList(1);
        }
        final PackageInfoList packageInfoList = new PackageInfoList(packageInfoSet);
        Collections.sort(packageInfoList);
        return packageInfoList;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Add annotations found in a module descriptor classfile. */
    void addAnnotations(final AnnotationInfoList moduleAnnotations) {
        // Currently only class annotations are used in the module-info.class file
        if (moduleAnnotations != null && !moduleAnnotations.isEmpty()) {
            if (this.annotationInfo == null) {
                this.annotationInfo = new AnnotationInfoList(moduleAnnotations);
            } else {
                this.annotationInfo.addAll(moduleAnnotations);
            }
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

    /** Get any annotations on the {@code package-info.class} file. */
    public AnnotationInfoList getAnnotationInfo() {
        return annotationInfo == null ? AnnotationInfoList.EMPTY_LIST : annotationInfo;
    }

    /**
     * @param annotationName
     *            The name of an annotation.
     * @return true if this module has the named annotation.
     */
    public boolean hasAnnotation(final String annotationName) {
        return getAnnotationInfo().containsName(annotationName);
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public int compareTo(final ModuleInfo o) {
        final int diff = this.name.compareTo(o.name);
        if (diff != 0) {
            return diff;
        } else {
            return this.location.compareTo(o.location);
        }
    }

    @Override
    public int hashCode() {
        return name.hashCode() * location.hashCode();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        } else if (o == null || !(o instanceof ModuleInfo)) {
            return false;
        }
        return this.compareTo((ModuleInfo) o) == 0;
    }

    @Override
    public String toString() {
        return name + " [" + location + "]";
    }
}
