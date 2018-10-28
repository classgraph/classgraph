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

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.classgraph.InfoList.MappableInfoList;

/** A list of {@link AnnotationInfo} objects. */
public class AnnotationInfoList extends MappableInfoList<AnnotationInfo> {
    /**
     * The set of annotations directly related to a class or method and not inherited through a meta-annotated
     * annotation. This field is nullable, as the annotation info list is incrementally built. See
     * {@link #directOnly()}.
     */
    private final AnnotationInfoList directlyRelatedAnnotations;

    AnnotationInfoList() {
        super();
        this.directlyRelatedAnnotations = null;
    }

    AnnotationInfoList(final int sizeHint) {
        super(sizeHint);
        this.directlyRelatedAnnotations = null;
    }

    AnnotationInfoList(final AnnotationInfoList reachableAnnotations) {
        // If only reachable annotations are given, treat all of them as direct
        this(reachableAnnotations, reachableAnnotations);
    }

    AnnotationInfoList(final AnnotationInfoList reachableAnnotations,
            final AnnotationInfoList directlyRelatedAnnotations) {
        super(reachableAnnotations);
        this.directlyRelatedAnnotations = directlyRelatedAnnotations;
    }

    static final AnnotationInfoList EMPTY_LIST = new AnnotationInfoList() {
        @Override
        public boolean add(final AnnotationInfo e) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public void add(final int index, final AnnotationInfo element) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean remove(final Object o) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public AnnotationInfo remove(final int index) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean addAll(final Collection<? extends AnnotationInfo> c) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean addAll(final int index, final Collection<? extends AnnotationInfo> c) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean removeAll(final Collection<?> c) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean retainAll(final Collection<?> c) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public void clear() {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public AnnotationInfo set(final int index, final AnnotationInfo element) {
            throw new IllegalArgumentException("List is immutable");
        }
    };

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Filter an {@link AnnotationInfoList} using a predicate mapping an {@link AnnotationInfo} object to a boolean,
     * producing another {@link AnnotationInfoList} for all items in the list for which the predicate is true.
     */
    @FunctionalInterface
    public interface AnnotationInfoFilter {
        /**
         * Whether or not to allow an {@link AnnotationInfo} list item through the filter.
         *
         * @param annotationInfo
         *            The {@link AnnotationInfo} item to filter.
         * @return Whether or not to allow the item through the filter. If true, the item is copied to the output
         *         list; if false, it is excluded.
         */
        boolean accept(AnnotationInfo annotationInfo);
    }

    /**
     * Find the subset of the {@link AnnotationInfo} objects in this list for which the given filter predicate is
     * true.
     *
     * @param filter
     *            The {@link AnnotationInfoFilter} to apply.
     * @return The subset of the {@link AnnotationInfo} objects in this list for which the given filter predicate is
     *         true.
     */
    public AnnotationInfoList filter(final AnnotationInfoFilter filter) {
        final AnnotationInfoList annotationInfoFiltered = new AnnotationInfoList();
        for (final AnnotationInfo resource : this) {
            if (filter.accept(resource)) {
                annotationInfoFiltered.add(resource);
            }
        }
        return annotationInfoFiltered;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Find the transitive closure of meta-annotations. */
    private static void findMetaAnnotations(final AnnotationInfo ai, final AnnotationInfoList allAnnotationsOut,
            final Set<ClassInfo> visited) {
        final ClassInfo annotationClassInfo = ai.getClassInfo();
        if (annotationClassInfo != null && annotationClassInfo.annotationInfo != null) {
            // Don't get in a cycle
            if (visited.add(annotationClassInfo)) {
                for (final AnnotationInfo metaAnnotationInfo : annotationClassInfo.annotationInfo) {
                    final ClassInfo metaAnnotationClassInfo = metaAnnotationInfo.getClassInfo();
                    final String metaAnnotationClassName = metaAnnotationClassInfo.getName();
                    // Don't treat java.lang.annotation annotations as meta-annotations 
                    if (!metaAnnotationClassName.startsWith("java.lang.annotation.")) {
                        // Add the meta-annotation to the transitive closure
                        allAnnotationsOut.add(metaAnnotationInfo);
                        // Recurse to meta-meta-annotation
                        findMetaAnnotations(metaAnnotationInfo, allAnnotationsOut, visited);
                    }
                }
            }
        }
    }

    /**
     * Get the indirect annotations on a class (meta-annotations and/or inherited annotations).
     * 
     * @param directAnnotationInfo
     *            the direct annotations on the class, method, method parameter or field.
     * @param annotatedClass
     *            for class annotations, this is the annotated class, else null.
     */
    static AnnotationInfoList getIndirectAnnotations(final AnnotationInfoList directAnnotationInfo,
            final ClassInfo annotatedClass) {
        // Add direct annotations
        final Set<ClassInfo> directOrInheritedAnnotationClasses = new HashSet<>();
        final Set<ClassInfo> reachedAnnotationClasses = new HashSet<>();
        final AnnotationInfoList reachableAnnotationInfo = new AnnotationInfoList(
                directAnnotationInfo == null ? 2 : directAnnotationInfo.size());
        if (directAnnotationInfo != null) {
            for (final AnnotationInfo dai : directAnnotationInfo) {
                directOrInheritedAnnotationClasses.add(dai.getClassInfo());
                reachableAnnotationInfo.add(dai);
                findMetaAnnotations(dai, reachableAnnotationInfo, reachedAnnotationClasses);
            }
        }
        if (annotatedClass != null) {
            // Add any @Inherited annotations on superclasses
            for (final ClassInfo superclass : annotatedClass.getSuperclasses()) {
                if (superclass.annotationInfo != null) {
                    for (final AnnotationInfo sai : superclass.annotationInfo) {
                        if (sai.isInherited()) {
                            // Don't add inherited superclass annotation if it is overridden in a subclass 
                            if (directOrInheritedAnnotationClasses.add(sai.getClassInfo())) {
                                reachableAnnotationInfo.add(sai);
                                final AnnotationInfoList reachableMetaAnnotationInfo = new AnnotationInfoList(2);
                                findMetaAnnotations(sai, reachableMetaAnnotationInfo, reachedAnnotationClasses);
                                // Meta-annotations also have to have @Inherited to be inherited
                                for (final AnnotationInfo rmai : reachableMetaAnnotationInfo) {
                                    if (rmai.isInherited()) {
                                        reachableAnnotationInfo.add(rmai);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Return sorted annotation list
        final AnnotationInfoList directAnnotationInfoSorted = directAnnotationInfo == null
                ? AnnotationInfoList.EMPTY_LIST
                : new AnnotationInfoList(directAnnotationInfo);
        Collections.sort(directAnnotationInfoSorted);
        final AnnotationInfoList annotationInfoList = new AnnotationInfoList(reachableAnnotationInfo,
                directAnnotationInfoSorted);
        Collections.sort(annotationInfoList);
        return annotationInfoList;
    }

    /**
     * returns the list of direct annotations, excluding meta-annotations. If this {@link AnnotationInfoList}
     * consists of class annotations, i.e. if it was produced using `ClassInfo#getAnnotationInfo()`, then the
     * returned list also excludes annotations inherited from a superclass or implemented interface that was
     * meta-annotated with {@link java.lang.annotation.Inherited @Inherited}.
     *
     * @return The list of directly-related annotations.
     */
    public AnnotationInfoList directOnly() {
        // If directlyRelatedAnnotations == null, this is already a list of direct annotations (the list of
        // AnnotationInfo objects created when the classfile is read). Otherwise return a new list consisting
        // of only the direct annotations.
        return this.directlyRelatedAnnotations == null ? this
                // Make .directOnly() idempotent
                : new AnnotationInfoList(directlyRelatedAnnotations, /* directlyRelatedAnnotations = */ null);
    }
}
