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

import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Holds metadata about a package encountered during a scan. */
public class PackageInfo implements Comparable<PackageInfo> {
    /** Name of the package. */
    private String name;

    /** {@link AnnotationInfo} for any annotations on the package-info.class file, if present, else null. */
    private AnnotationInfoList annotationInfo;

    /** The parent package of this package. */
    private PackageInfo parent;

    /** The child packages of this package. */
    private List<PackageInfo> children;

    /** Set of classes in the package. */
    private final Set<ClassInfo> memberClassInfo = new HashSet<>();

    /** Deerialization constructor. */
    PackageInfo() {
    }

    /** Construct a PackageInfo object. */
    PackageInfo(final String packageName) {
        this.name = packageName;
    }
    
    /** Add annotations found in a package descriptor classfile. */
    void addAnnotations(final AnnotationInfoList packageAnnotations) {
        // Currently only class annotations are used in the package-info.class file
        if (packageAnnotations != null && !packageAnnotations.isEmpty()) {
            if (this.annotationInfo == null) {
                this.annotationInfo = new AnnotationInfoList(packageAnnotations);
            } else {
                this.annotationInfo.addAll(packageAnnotations);
            }
        }
    }

    /**
     * Merge a {@link ClassInfo} object for a package-info.class file into this PackageInfo. (The same
     * package-info.class file may be present in multiple definitions of the package in different modules.)
     */
    void addClassInfo(final ClassInfo classInfo, final Map<String, ClassInfo> classNameToClassInfo) {
        memberClassInfo.add(classInfo);
    }

    /** The package name ("" for the root package). */
    public String getName() {
        return name;
    }

    /** Get any annotations on the {@code package-info.class} file. */
    public AnnotationInfoList getAnnotationInfo() {
        return annotationInfo == null ? AnnotationInfoList.EMPTY_LIST : annotationInfo;
    }

    /** The parent package of this package, or null if this is the root package. */
    public PackageInfo getParent() {
        return parent;
    }

    /** The child packages of this package, or the empty list if none. */
    public List<PackageInfo> getChildren() {
        // Ensure children are sorted
        Collections.sort(children, new Comparator<PackageInfo>() {
            @Override
            public int compare(final PackageInfo o1, final PackageInfo o2) {
                return o1.name.compareTo(o2.name);
            }
        });
        return children;
    }

    /** Get the {@link ClassInfo} objects for all classes that are members of this package. */
    public ClassInfoList getMemberClassInfo() {
        return new ClassInfoList(memberClassInfo, /* sortByName = */ true);
    }

    private void getMemberClassInfoRecursive(final Set<ClassInfo> reachableClassInfo) {
        reachableClassInfo.addAll(memberClassInfo);
        for (final PackageInfo subPackageInfo : getChildren()) {
            subPackageInfo.getMemberClassInfoRecursive(reachableClassInfo);
        }
    }

    /** Get the {@link ClassInfo} objects for all classes that are members of this package or a sub-package. */
    public ClassInfoList getMemberClassInfoRecursive() {
        final Set<ClassInfo> reachableClassInfo = new HashSet<>();
        getMemberClassInfoRecursive(reachableClassInfo);
        return new ClassInfoList(reachableClassInfo, /* sortByName = */ true);
    }

    @Override
    public int compareTo(final PackageInfo o) {
        return this.name.compareTo(o.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        } else if (o == null || !(o instanceof PackageInfo)) {
            return false;
        }
        return this.name.equals(((PackageInfo) o).name);
    }

    @Override
    public String toString() {
        return name;
    }
}
