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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Holds metadata about a package encountered during a scan. */
public class ModuleInfo implements Comparable<ModuleInfo>, HasName {
    /** The name of the module. */
    private String name;

    /** The location of the module. */
    private URI location;

    /** {@link AnnotationInfo} for any annotations on the package-info.class file, if present, else null. */
    private AnnotationInfoList annotationInfo;

    /** Set of classes in the module. */
    private final Set<ClassInfo> memberClassInfo = new HashSet<>();

    /** Deerialization constructor. */
    ModuleInfo() {
    }

    /** Construct a ModuleInfo object. */
    ModuleInfo(final ModuleRef moduleRef) {
        this.name = moduleRef.getName();
        this.location = moduleRef.getLocation();
    }

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
     * Merge a {@link ClassInfo} object for a module-info.class file into this ModuleInfo. (The same
     * module-info.class file may be present in multiple definitions of the same module in the module path.)
     */
    void addClassInfo(final ClassInfo classInfo, final Map<String, ClassInfo> classNameToClassInfo) {
        memberClassInfo.add(classInfo);
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

    /** Get the {@link ClassInfo} objects for all classes that are members of this package. */
    public ClassInfoList getMemberClassInfo() {
        return new ClassInfoList(memberClassInfo, /* sortByName = */ true);
    }

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
