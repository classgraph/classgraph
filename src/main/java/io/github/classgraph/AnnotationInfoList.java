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

import java.io.Serial;
import java.lang.annotation.Annotation;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.classgraph.ClassInfo.RelType;
import nonapi.io.github.classgraph.utils.Assert;
import nonapi.io.github.classgraph.utils.CollectionUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import org.jspecify.annotations.Nullable;

/**
 * A list of {@link AnnotationInfo} objects, which stores both the reachable annotations (annotations that are
 * either directly present on the annotated item, or reached indirectly through a meta-annotation, or inherited from
 * a superclass or interface that was annotated with {@link java.lang.annotation.Inherited @Inherited}), and the
 * directly present annotations. (By default, accessing an {@link AnnotationInfoList} as a {@link List} returns the
 * reachable annotations; by calling {@link #directOnly()}, you can get only the directly present annotations.)
 */
public class AnnotationInfoList extends MappableInfoList<AnnotationInfo> {
    /**
     * The set of annotations directly related to a class or method and not inherited through a meta-annotated
     * annotation. This field is nullable, as the annotation info list is incrementally built. See
     * {@link #directOnly()}.
     */
    private @Nullable AnnotationInfoList directlyRelatedAnnotations;

    /** serialVersionUID */
    @Serial
    private static final long serialVersionUID = 1L;

    /** An unmodifiable empty {@link AnnotationInfoList}. */
    static final AnnotationInfoList EMPTY_LIST = new AnnotationInfoList();
    static {
        EMPTY_LIST.makeUnmodifiable();
    }

    /**
     * Return an unmodifiable empty {@link AnnotationInfoList}.
     *
     * @return the unmodifiable empty {@link AnnotationInfoList}.
     */
    public static AnnotationInfoList emptyList() {
        return EMPTY_LIST;
    }

    /**
     * Construct a new modifiable empty list of {@link AnnotationInfo} objects.
     */
    public AnnotationInfoList() {
        super();
    }

    /**
     * Construct a new modifiable empty list of {@link AnnotationInfo} objects, given a size hint.
     *
     * @param sizeHint
     *            the expected number of elements
     */
    public AnnotationInfoList(final int sizeHint) {
        super(sizeHint);
    }

    /**
     * Construct a new modifiable empty {@link AnnotationInfoList}, given an initial list of {@link AnnotationInfo}
     * objects.
     *
     * @param reachableAnnotations
     *            the annotations to add to the list. All of them are treated as directly present on the annotated
     *            item, rather than as meta-annotations.
     */
    public AnnotationInfoList(final AnnotationInfoList reachableAnnotations) {
        // If only reachable annotations are given, treat all of them as direct
        this(Objects.requireNonNull(reachableAnnotations, "reachableAnnotations must not be null"),
                reachableAnnotations);
    }

    /**
     * Constructor.
     *
     * @param reachableAnnotations
     *            the reachable annotations
     * @param directlyRelatedAnnotations
     *            the directly related annotations
     */
    AnnotationInfoList(final AnnotationInfoList reachableAnnotations,
            final @Nullable AnnotationInfoList directlyRelatedAnnotations) {
        super(reachableAnnotations);
        this.directlyRelatedAnnotations = directlyRelatedAnnotations;
    }

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
        Assert.notNull(filter, "filter");
        final AnnotationInfoList annotationInfoFiltered = new AnnotationInfoList();
        for (final AnnotationInfo resource : this) {
            if (filter.accept(resource)) {
                annotationInfoFiltered.add(resource);
            }
        }
        return annotationInfoFiltered;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get {@link ClassInfo} objects for any classes referenced in this list.
     *
     * @param classNameToClassInfo
     *            the map from class name to {@link ClassInfo}.
     * @param refdClassInfo
     *            the referenced class info
     * @param log
     *            the log node, or null to skip logging
     */
    void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo, final @Nullable LogNode log) {
        for (final AnnotationInfo ai : this) {
            ai.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Handle {@link Repeatable} annotations.
     *
     * @param allRepeatableAnnotationNames
     *            the names of all repeatable annotations
     * @param containingClassInfo
     *            the containing class
     * @param forwardRelType
     *            the forward relationship type for linking (or null for none)
     * @param reverseRelType0
     *            the first reverse relationship type for linking (or null for none)
     * @param reverseRelType1
     *            the second reverse relationship type for linking (or null for none)
     */
    void handleRepeatableAnnotations(final Set<String> allRepeatableAnnotationNames,
            final @Nullable ClassInfo containingClassInfo, final RelType forwardRelType,
            final RelType reverseRelType0, final @Nullable RelType reverseRelType1) {
        List<AnnotationInfo> repeatableAnnotations = null;
        for (var i = size() - 1; i >= 0; --i) {
            final var ai = get(i);
            if (allRepeatableAnnotationNames.contains(ai.getName())) {
                if (repeatableAnnotations == null) {
                    repeatableAnnotations = new ArrayList<>();
                }
                repeatableAnnotations.add(ai);
                // Remove repeatable annotation
                remove(i);
            }
        }
        // Add the component annotations in each of the parameters of the repeatable annotation
        if (repeatableAnnotations != null) {
            for (final AnnotationInfo repeatableAnnotation : repeatableAnnotations) {
                final var values = repeatableAnnotation.getParameterValues();
                if (!values.isEmpty()) {
                    final var apv = values.get("value");
                    if (apv != null) {
                        final var arr = apv.getValue();
                        if (arr instanceof final Object[] arrValues) {
                            for (final Object value : arrValues) {
                                if (value instanceof final AnnotationInfo ai) {
                                    add(ai);

                                    // Link annotation, if necessary
                                    if (forwardRelType != null
                                            && (reverseRelType0 != null || reverseRelType1 != null)) {
                                        final var annotationClass = ai.getClassInfo();
                                        if (annotationClass != null) {
                                            final var containingClass = Objects.requireNonNull(containingClassInfo);
                                            containingClass.addRelatedClass(forwardRelType, annotationClass);
                                            if (reverseRelType0 != null) {
                                                annotationClass.addRelatedClass(reverseRelType0, containingClass);
                                            }
                                            if (reverseRelType1 != null) {
                                                annotationClass.addRelatedClass(reverseRelType1, containingClass);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * How directly an annotation is related to the annotated class, method, method parameter or field: it is
     * directly present on it, or it is inherited from a superclass, or it is a meta-annotation on one of the other
     * two. The same annotation can be reached in more than one of these ways, and the most direct of them is the
     * one that should be reported.
     */
    // #559
    private enum Directness {
        /** The annotation is directly present on the annotated element. */
        DIRECTLY_PRESENT,

        /** The annotation is {@link Inherited} from a superclass. */
        INHERITED,

        /** The annotation is a meta-annotation of another annotation. */
        META_ANNOTATION
    }

    /**
     * Add a reachable annotation to a list, unless the same annotation was already reached by a route at least as
     * direct.
     *
     * @param ai
     *            the annotation
     * @param directness
     *            how directly the annotation is related to the annotated element
     * @param reachableAnnotationOut
     *            the list of reachable annotations
     * @param directnessOut
     *            the map from annotation to how directly it is related to the annotated element
     */
    // #559
    private static void addReachableAnnotation(final AnnotationInfo ai, final Directness directness,
            final AnnotationInfoList reachableAnnotationOut, final Map<AnnotationInfo, Directness> directnessOut) {
        final var prevDirectness = directnessOut.get(ai);
        if (prevDirectness == null) {
            directnessOut.put(ai, directness);
            reachableAnnotationOut.add(ai);
        } else if (directness.compareTo(prevDirectness) < 0) {
            directnessOut.put(ai, directness);
        }
    }

    /**
     * Find the transitive closure of meta-annotations.
     *
     * @param ai
     *            the annotationInfo object
     * @param allAnnotationsOut
     *            annotations out
     * @param visited
     *            visited
     * @param directnessOut
     *            the map from annotation to how directly it is related to the annotated element
     */
    private static void findMetaAnnotations(final AnnotationInfo ai, final AnnotationInfoList allAnnotationsOut,
            final Set<ClassInfo> visited, final Map<AnnotationInfo, Directness> directnessOut) {
        final var annotationClassInfo = ai.getClassInfo();
        if (annotationClassInfo != null && annotationClassInfo.annotationInfo != null
        // Don't get in a cycle
                && visited.add(annotationClassInfo)) {
            for (final AnnotationInfo metaAnnotationInfo : annotationClassInfo.annotationInfo) {
                // N.B. read the class name from the AnnotationInfo rather than from its ClassInfo, since ClassInfo
                // is null if the meta-annotation's class was not encountered during the scan
                final var metaAnnotationClassName = metaAnnotationInfo.getName();
                // Don't treat java.lang.annotation annotations as meta-annotations
                if (!metaAnnotationClassName.startsWith("java.lang.annotation.")) {
                    // Add the meta-annotation to the transitive closure
                    addReachableAnnotation(metaAnnotationInfo, Directness.META_ANNOTATION, allAnnotationsOut,
                            directnessOut);
                    // Recurse to meta-meta-annotation
                    findMetaAnnotations(metaAnnotationInfo, allAnnotationsOut, visited, directnessOut);
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
     * @return the indirect annotations
     */
    static AnnotationInfoList getIndirectAnnotations(final @Nullable AnnotationInfoList directAnnotationInfo,
            final @Nullable ClassInfo annotatedClass) {
        // Add direct annotations
        final Set<ClassInfo> directOrInheritedAnnotationClasses = new HashSet<>();
        final Set<ClassInfo> reachedAnnotationClasses = new HashSet<>();
        final AnnotationInfoList reachableAnnotationInfo = new AnnotationInfoList(
                directAnnotationInfo == null ? 2 : directAnnotationInfo.size());
        // Record how directly each annotation is related to the annotated element, so that an annotation that is
        // reached in more than one way is listed only once, and is sorted according to the most direct of those
        // ways #559
        final Map<AnnotationInfo, Directness> directness = new IdentityHashMap<>();
        if (directAnnotationInfo != null) {
            for (final AnnotationInfo dai : directAnnotationInfo) {
                directOrInheritedAnnotationClasses.add(dai.getClassInfo());
                addReachableAnnotation(dai, Directness.DIRECTLY_PRESENT, reachableAnnotationInfo, directness);
                findMetaAnnotations(dai, reachableAnnotationInfo, reachedAnnotationClasses, directness);
            }
        }
        if (annotatedClass != null) {
            // Add any @Inherited annotations on superclasses
            for (final ClassInfo superclass : annotatedClass.getAllSuperclasses()) {
                if (superclass.annotationInfo != null) {
                    for (final AnnotationInfo sai : superclass.annotationInfo) {
                        // Don't add inherited superclass annotation if it is overridden in a subclass
                        if (sai.isInherited() && directOrInheritedAnnotationClasses.add(sai.getClassInfo())) {
                            addReachableAnnotation(sai, Directness.INHERITED, reachableAnnotationInfo, directness);
                            final AnnotationInfoList reachableMetaAnnotationInfo = new AnnotationInfoList(2);
                            findMetaAnnotations(sai, reachableMetaAnnotationInfo, reachedAnnotationClasses,
                                    new IdentityHashMap<>());
                            // Meta-annotations also have to have @Inherited to be inherited
                            for (final AnnotationInfo rmai : reachableMetaAnnotationInfo) {
                                if (rmai.isInherited()) {
                                    addReachableAnnotation(rmai, Directness.META_ANNOTATION,
                                            reachableAnnotationInfo, directness);
                                }
                            }
                        }
                    }
                }
            }
        }
        // Return sorted annotation list
        final var directAnnotationInfoSorted = directAnnotationInfo == null ? AnnotationInfoList.EMPTY_LIST
                : new AnnotationInfoList(directAnnotationInfo);
        CollectionUtils.sortIfNotEmpty(directAnnotationInfoSorted);
        final AnnotationInfoList annotationInfoList = new AnnotationInfoList(reachableAnnotationInfo,
                directAnnotationInfoSorted);
        // Sort by name, then put the most directly related of any annotations that share a name first, so that
        // AnnotationInfoList#get(String) returns the annotation that is directly present on the annotated element,
        // if there is one #559
        CollectionUtils.sortIfNotEmpty(annotationInfoList,
                Comparator.comparing(AnnotationInfo::getName).thenComparing(
                        (final AnnotationInfo ai) -> directness.getOrDefault(ai, Directness.META_ANNOTATION))
                        .thenComparing(Comparator.naturalOrder()));
        return annotationInfoList;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the list of direct annotations, excluding meta-annotations. If this {@link AnnotationInfoList} consists
     * of class annotations, i.e. if it was produced using {@link ClassInfo#getAllAnnotationInfo()}, then the
     * returned list also excludes annotations inherited from a superclass or implemented interface that was
     * annotated with {@link java.lang.annotation.Inherited @Inherited}.
     *
     * @return The list of directly-related annotations.
     */
    public AnnotationInfoList directOnly() {
        // If directlyRelatedAnnotations == null, this is already a list of direct annotations (the list of
        // AnnotationInfo objects created when the classfile is read). Otherwise return a new list consisting of
        // only the direct annotations.
        return this.directlyRelatedAnnotations == null ? this
                // Make .directOnly() idempotent
                : new AnnotationInfoList(directlyRelatedAnnotations, /* directlyRelatedAnnotations = */ null);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the {@link Repeatable} annotation with the given class, or the empty list if none found.
     *
     * @param annotationClass
     *            The class to search for.
     * @return The list of annotations with the given class, or the empty list if none found.
     * @throws IllegalArgumentException
     *             if {@code annotationClass} is not an annotation type.
     */
    public AnnotationInfoList getRepeatable(final Class<? extends Annotation> annotationClass) {
        Assert.notNull(annotationClass, "annotationClass");
        Assert.isAnnotation(annotationClass);
        return getRepeatable(annotationClass.getName());
    }

    /**
     * Get the {@link Repeatable} annotation with the given name, or the empty list if none found.
     *
     * @param name
     *            The name to search for.
     * @return The list of annotations with the given name, or the empty list if none found.
     */
    public AnnotationInfoList getRepeatable(final String name) {
        Assert.notNull(name, "name");
        var hasNamedAnnotation = false;
        for (final AnnotationInfo ai : this) {
            if (ai.getName().equals(name)) {
                hasNamedAnnotation = true;
                break;
            }
        }
        if (!hasNamedAnnotation) {
            return AnnotationInfoList.EMPTY_LIST;
        }
        final AnnotationInfoList matchingAnnotations = new AnnotationInfoList(size());
        for (final AnnotationInfo ai : this) {
            if (ai.getName().equals(name)) {
                matchingAnnotations.add(ai);
            }
        }
        return matchingAnnotations;
    }

    // -------------------------------------------------------------------------------------------------------------

    /*
     * (non-Javadoc)
     *
     * @see java.util.ArrayList#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final @Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof final AnnotationInfoList other)
                || ((directlyRelatedAnnotations == null) != (other.directlyRelatedAnnotations == null))) {
            return false;
        }
        if (directlyRelatedAnnotations == null) {
            return super.equals(other);
        }
        return super.equals(other) && directlyRelatedAnnotations.equals(other.directlyRelatedAnnotations);
    }

    /*
     * (non-Javadoc)
     *
     * @see java.util.ArrayList#hashCode()
     */
    @Override
    public int hashCode() {
        return super.hashCode() ^ (directlyRelatedAnnotations == null ? 0 : directlyRelatedAnnotations.hashCode());
    }
}
