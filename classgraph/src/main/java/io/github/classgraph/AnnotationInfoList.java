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
 * Copyright (c) 2026 Luke Hutchison
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

import java.lang.annotation.Annotation;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.ClassInfo.RelType;
import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.base.internal.utils.CollectionUtils;

/**
 * A list of {@link AnnotationInfo} objects, which stores both the reachable annotations (annotations that are
 * either directly present on the annotated item, or reached indirectly through a meta-annotation, or inherited from
 * a superclass or interface that was annotated with {@link java.lang.annotation.Inherited @Inherited}), and the
 * directly present annotations. (By default, accessing an {@link AnnotationInfoList} as a {@link List} returns the
 * reachable annotations; by calling {@link #directOnly()}, you can get only the directly present annotations.)
 *
 * <p>
 * Equality is {@link List} equality: two lists are equal if they hold equal annotations in the same order. Which of
 * the annotations are directly present is reported by {@link #directOnly()}, and is not part of the comparison, so
 * two lists that hold the same annotations are equal even when they were reached in different ways.
 */
public final class AnnotationInfoList extends MappableInfoList<AnnotationInfo> {
    /**
     * The annotations in this list that are directly present on the annotated item, rather than reached through a
     * meta-annotation or inherited. It is this list itself when every annotation in it is directly present. See
     * {@link #directOnly()}.
     */
    private final AnnotationInfoList directlyRelatedAnnotations;

    /** An unmodifiable empty {@link AnnotationInfoList}. */
    static final AnnotationInfoList EMPTY_LIST = new AnnotationInfoList(List.of());

    /**
     * Return an unmodifiable empty {@link AnnotationInfoList}.
     *
     * @return the unmodifiable empty {@link AnnotationInfoList}.
     */
    public static AnnotationInfoList emptyList() {
        return EMPTY_LIST;
    }

    /**
     * Construct a new unmodifiable {@link AnnotationInfoList} from a completed collection of {@link AnnotationInfo}
     * objects. The collection is copied, and the annotations are sorted by name. Annotations of the same name, such
     * as the repeats of a repeatable annotation, keep the order they have in the collection.
     *
     * @param annotationInfoCollection
     *            the annotations to add to the list. All of them are treated as directly present on the annotated
     *            item, rather than as meta-annotations.
     */
    public AnnotationInfoList(final Collection<AnnotationInfo> annotationInfoCollection) {
        this(sortCopyByName(
                Objects.requireNonNull(annotationInfoCollection, "annotationInfoCollection must not be null")));
    }

    /**
     * Copy a collection of annotations, and sort the copy by name. The sort is stable, so annotations of the same
     * name, such as the repeats of a repeatable annotation, keep the order they have in the collection, as in the
     * JDK, rather than being sorted by their parameter values.
     *
     * @param annotations
     *            the annotations
     * @return a copy of the annotations, sorted by name
     */
    static List<AnnotationInfo> sortCopyByName(final Collection<AnnotationInfo> annotations) {
        final List<AnnotationInfo> sorted = new ArrayList<>(annotations);
        CollectionUtils.sortIfNotEmpty(sorted, Comparator.comparing(AnnotationInfo::getName));
        return sorted;
    }

    /**
     * Constructor for a list all of whose annotations are treated as directly present on the annotated item. As in
     * {@link InfoList#InfoList(List)}, this list claims the given list, and the caller is responsible for having
     * sorted it.
     *
     * @param annotationInfo
     *            the annotations to add to the list
     */
    AnnotationInfoList(final List<AnnotationInfo> annotationInfo) {
        super(annotationInfo);
        // If only reachable annotations are given, treat all of them as direct
        directlyRelatedAnnotations = this;
    }

    /**
     * Constructor for a list of reachable annotations, given which of them are directly present on the annotated
     * item. As in {@link InfoList#InfoList(List)}, this list claims both of the given lists, and the caller is
     * responsible for having sorted them.
     *
     * @param reachableAnnotations
     *            the reachable annotations
     * @param directAnnotations
     *            the reachable annotations that are directly present on the annotated item
     */
    AnnotationInfoList(final List<AnnotationInfo> reachableAnnotations,
            final List<AnnotationInfo> directAnnotations) {
        super(reachableAnnotations);
        // The direct list is built by the one-argument constructor, so it is its own direct list, and directOnly()
        // returns the same list however many times it is called
        directlyRelatedAnnotations = new AnnotationInfoList(directAnnotations);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the subset of the {@link AnnotationInfo} objects in this list for which the given filter predicate is
     * true.
     *
     * @param filter
     *            The filter to apply. Only the {@link AnnotationInfo} objects for which the filter returns true are
     *            copied to the returned list.
     * @return The subset of the {@link AnnotationInfo} objects in this list for which the given filter predicate is
     *         true.
     */
    public AnnotationInfoList filter(final Predicate<AnnotationInfo> filter) {
        Assert.notNull(filter, "filter");
        // If every annotation in this list is directly present, then so is every annotation that is kept
        final var allDirect = directlyRelatedAnnotations == this;
        // Compare by identity: a meta-annotation or an inherited annotation can be equal to a direct annotation,
        // and is still not direct
        final Set<AnnotationInfo> directIdentitySet = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!allDirect) {
            directIdentitySet.addAll(directlyRelatedAnnotations);
        }
        final List<AnnotationInfo> reachableFiltered = new ArrayList<>();
        final List<AnnotationInfo> directlyRelatedFiltered = new ArrayList<>();
        for (final AnnotationInfo annotationInfo : this) {
            if (filter.test(annotationInfo)) {
                reachableFiltered.add(annotationInfo);
                if (!allDirect && directIdentitySet.contains(annotationInfo)) {
                    directlyRelatedFiltered.add(annotationInfo);
                }
            }
        }
        // Filtering preserves the order of this list, which is already sorted, so don't sort again
        return allDirect ? new AnnotationInfoList(reachableFiltered)
                : new AnnotationInfoList(reachableFiltered, directlyRelatedFiltered);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get {@link ClassInfo} objects for any classes referenced in this list.
     *
     * @param classNameToClassInfo
     *            the map to add a {@link ClassInfo} object to, for a referenced class that has none in the scan
     *            result.
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
     * Unpack the container annotations of {@link Repeatable} annotations. A repeatable annotation that is applied
     * more than once is stored in the classfile as a single container annotation, whose {@code value} parameter
     * holds the repeats. As in the JDK, the container stays in the list, as in
     * {@link java.lang.reflect.AnnotatedElement#getAnnotations()}, and the repeats are inserted right after it, in
     * the order they were written, as in {@link java.lang.reflect.AnnotatedElement#getAnnotationsByType(Class)}.
     *
     * @param annotations
     *            the annotations to search for containers. The repeats are inserted into this list.
     * @param allContainerAnnotationNames
     *            the names of all container annotations
     * @return the repeats that were inserted, or the empty list if none
     */
    static List<AnnotationInfo> unpackRepeatableAnnotations(final List<AnnotationInfo> annotations,
            final Set<String> allContainerAnnotationNames) {
        List<AnnotationInfo> repeats = List.of();
        for (var i = 0; i < annotations.size(); i++) {
            final var container = annotations.get(i);
            if (allContainerAnnotationNames.contains(container.getName())
                    && container.getParameterValues().getValue("value") instanceof final Object[] values) {
                for (final Object value : values) {
                    if (value instanceof final AnnotationInfo repeat) {
                        // Skip over the inserted repeat: like the JDK, unpack only one level of containers
                        annotations.add(++i, repeat);
                        if (repeats.isEmpty()) {
                            repeats = new ArrayList<>();
                        }
                        repeats.add(repeat);
                    }
                }
            }
        }
        return repeats;
    }

    /**
     * Unpack the container annotations of {@link Repeatable} annotations on a class, method, method parameter or
     * field (see {@link #unpackRepeatableAnnotations(List, Set)}), and link each repeat into the class graph. The
     * container was already linked when the classfile was read, so the class graph, like the annotation list, holds
     * both the container and the repeats.
     *
     * @param annotations
     *            the annotations to search for containers
     * @param allContainerAnnotationNames
     *            the names of all container annotations
     * @param containingClassInfo
     *            the containing class
     * @param forwardRelType
     *            the forward relationship type for linking
     * @param reverseRelType0
     *            the first reverse relationship type for linking
     * @param reverseRelType1
     *            the second reverse relationship type for linking (or null for none)
     */
    static void handleRepeatableAnnotations(final List<AnnotationInfo> annotations,
            final Set<String> allContainerAnnotationNames, final @Nullable ClassInfo containingClassInfo,
            final RelType forwardRelType, final RelType reverseRelType0, final @Nullable RelType reverseRelType1) {
        for (final AnnotationInfo repeat : unpackRepeatableAnnotations(annotations, allContainerAnnotationNames)) {
            final var annotationClass = repeat.getClassInfo();
            if (annotationClass != null) {
                final var containingClass = Objects.requireNonNull(containingClassInfo);
                containingClass.addRelatedClass(forwardRelType, annotationClass);
                annotationClass.addRelatedClass(reverseRelType0, containingClass);
                if (reverseRelType1 != null) {
                    annotationClass.addRelatedClass(reverseRelType1, containingClass);
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
            final List<AnnotationInfo> reachableAnnotationOut,
            final Map<AnnotationInfo, Directness> directnessOut) {
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
    private static void findMetaAnnotations(final AnnotationInfo ai, final List<AnnotationInfo> allAnnotationsOut,
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
    static AnnotationInfoList getIndirectAnnotations(final @Nullable List<AnnotationInfo> directAnnotationInfo,
            final @Nullable ClassInfo annotatedClass) {
        // Add direct annotations
        final Set<ClassInfo> directOrInheritedAnnotationClasses = new HashSet<>();
        final Set<ClassInfo> reachedAnnotationClasses = new HashSet<>();
        final List<AnnotationInfo> reachableAnnotationInfo = new ArrayList<>(
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
            for (final ClassInfo superclass : annotatedClass.allSuperclassesIncludingExternal()) {
                if (superclass.annotationInfo != null) {
                    // The annotation classes of the subclasses, which override those of this superclass. This is
                    // a copy, so that every repeat of a repeatable annotation on this superclass is inherited, not
                    // only the first one.
                    final Set<ClassInfo> overridingAnnotationClasses = new HashSet<>(
                            directOrInheritedAnnotationClasses);
                    for (final AnnotationInfo sai : superclass.annotationInfo) {
                        // Don't add inherited superclass annotation if it is overridden in a subclass
                        if (sai.isInherited() && !overridingAnnotationClasses.contains(sai.getClassInfo())) {
                            directOrInheritedAnnotationClasses.add(sai.getClassInfo());
                            addReachableAnnotation(sai, Directness.INHERITED, reachableAnnotationInfo, directness);
                            final List<AnnotationInfo> reachableMetaAnnotationInfo = new ArrayList<>(2);
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
        // Sort by name, then put the most directly related of any annotations that share a name first, so that
        // AnnotationInfoList#get(String) returns the annotation that is directly present on the annotated element,
        // if there is one #559. The sort is stable, so the repeats of a repeatable annotation keep the order they
        // were written in, as in the JDK, rather than being sorted by their parameter values.
        CollectionUtils.sortIfNotEmpty(reachableAnnotationInfo,
                Comparator.comparing(AnnotationInfo::getName).thenComparing(
                        (final AnnotationInfo ai) -> directness.getOrDefault(ai, Directness.META_ANNOTATION)));
        // The direct annotations are sorted by name only, with the same stable sort, for the same reason
        final List<AnnotationInfo> directAnnotationsSorted = directAnnotationInfo == null ? List.of()
                : sortCopyByName(directAnnotationInfo);
        return new AnnotationInfoList(reachableAnnotationInfo, directAnnotationsSorted);
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
        return directlyRelatedAnnotations;
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
        // Filtered rather than copied, so that directOnly() still leaves out the inherited and meta-annotations
        return filter(ai -> ai.getName().equals(name));
    }
}
