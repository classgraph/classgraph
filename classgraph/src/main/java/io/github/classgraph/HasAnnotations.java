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
import java.lang.reflect.AnnotatedElement;

import io.github.classgraph.base.internal.utils.Assert;
import org.jspecify.annotations.Nullable;

/**
 * An element that annotations can be applied to: a class ({@link ClassInfo}), a field ({@link FieldInfo}), a method
 * ({@link MethodInfo}), a method parameter ({@link MethodParameterInfo}), a package ({@link PackageInfo}) or a
 * module ({@link ModuleInfo}). This is the ClassGraph counterpart of {@link AnnotatedElement}, and lets code that
 * only cares about annotations accept any of those types.
 *
 * <p>
 * Each method comes in an {@code All} form and a {@code Direct} form. The {@code All} form includes
 * meta-annotations -- the annotations on the element's own annotations, transitively -- and, for a class, any
 * {@link Inherited} annotations on its superclasses. The {@code Direct} form includes only the annotations written
 * on the element itself.
 *
 * <p>
 * Implementations need only supply {@link #getAllAnnotationInfo()}; the rest of this interface is derived from it.
 */
public interface HasAnnotations {
    /**
     * Get all annotations on this element, including meta-annotations, along with any annotation parameter values,
     * wrapped in {@link AnnotationInfo} objects.
     *
     * @return All annotations on this element, or the empty list if none.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    AnnotationInfoList getAllAnnotationInfo();

    /**
     * Get only the annotations written on this element, not the meta-annotations on those annotations, along with
     * any annotation parameter values, wrapped in {@link AnnotationInfo} objects.
     *
     * @return The annotations written on this element, or the empty list if none.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    default AnnotationInfoList getDirectAnnotationInfo() {
        return getAllAnnotationInfo().directOnly();
    }

    /**
     * Get the non-{@link Repeatable} annotation or meta-annotation on this element, or null if this element does
     * not have the annotation. (Use {@link #getAllAnnotationInfoRepeatable(Class)} for {@link Repeatable}
     * annotations, or {@link #getDirectAnnotationInfo(Class)} to ignore meta-annotations.)
     *
     * @param annotation
     *            the annotation class
     * @return An {@link AnnotationInfo} object representing the annotation on this element, or null if this element
     *         does not have the annotation.
     * @throws IllegalArgumentException
     *             if {@code annotation} is not an annotation type.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    default @Nullable AnnotationInfo getAllAnnotationInfo(final Class<? extends Annotation> annotation) {
        Assert.notNull(annotation, "annotation");
        Assert.isAnnotation(annotation);
        return getAllAnnotationInfo(annotation.getName());
    }

    /**
     * Get the named non-{@link Repeatable} annotation or meta-annotation on this element, or null if this element
     * does not have the named annotation. (Use {@link #getAllAnnotationInfoRepeatable(String)} for
     * {@link Repeatable} annotations, or {@link #getDirectAnnotationInfo(String)} to ignore meta-annotations.)
     *
     * <p>
     * If the named annotation can be reached in more than one way -- if it is written on this element and is also a
     * meta-annotation of one of the element's other annotations, for example -- then the one reached most directly
     * is returned.
     *
     * @param annotationName
     *            the name of the annotation class
     * @return An {@link AnnotationInfo} object representing the named annotation on this element, or null if this
     *         element does not have the named annotation.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    default @Nullable AnnotationInfo getAllAnnotationInfo(final String annotationName) {
        Assert.notNull(annotationName, "annotationName");
        return getAllAnnotationInfo().get(annotationName);
    }

    /**
     * Get the non-{@link Repeatable} annotation written on this element, or null if the annotation is not written
     * on this element. Meta-annotations are ignored. (Use {@link #getDirectAnnotationInfoRepeatable(Class)} for
     * {@link Repeatable} annotations.)
     *
     * @param annotation
     *            the annotation class
     * @return An {@link AnnotationInfo} object representing the annotation written on this element, or null if it
     *         is not written on this element.
     * @throws IllegalArgumentException
     *             if {@code annotation} is not an annotation type.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    default @Nullable AnnotationInfo getDirectAnnotationInfo(final Class<? extends Annotation> annotation) {
        Assert.notNull(annotation, "annotation");
        Assert.isAnnotation(annotation);
        return getDirectAnnotationInfo(annotation.getName());
    }

    /**
     * Get the named non-{@link Repeatable} annotation written on this element, or null if the named annotation is
     * not written on this element. Meta-annotations are ignored. (Use
     * {@link #getDirectAnnotationInfoRepeatable(String)} for {@link Repeatable} annotations.)
     *
     * @param annotationName
     *            the name of the annotation class
     * @return An {@link AnnotationInfo} object representing the named annotation written on this element, or null
     *         if it is not written on this element.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    default @Nullable AnnotationInfo getDirectAnnotationInfo(final String annotationName) {
        Assert.notNull(annotationName, "annotationName");
        return getDirectAnnotationInfo().get(annotationName);
    }

    /**
     * Get the {@link Repeatable} annotation or meta-annotation on this element, or the empty list if this element
     * does not have the annotation.
     *
     * @param annotation
     *            the annotation class
     * @return An {@link AnnotationInfoList} of all instances of the annotation on this element, or the empty list
     *         if this element does not have the annotation.
     * @throws IllegalArgumentException
     *             if {@code annotation} is not an annotation type.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    default AnnotationInfoList getAllAnnotationInfoRepeatable(final Class<? extends Annotation> annotation) {
        Assert.notNull(annotation, "annotation");
        Assert.isAnnotation(annotation);
        return getAllAnnotationInfoRepeatable(annotation.getName());
    }

    /**
     * Get the named {@link Repeatable} annotation or meta-annotation on this element, or the empty list if this
     * element does not have the named annotation.
     *
     * @param annotationName
     *            the name of the annotation class
     * @return An {@link AnnotationInfoList} of all instances of the named annotation on this element, or the empty
     *         list if this element does not have the named annotation.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    default AnnotationInfoList getAllAnnotationInfoRepeatable(final String annotationName) {
        Assert.notNull(annotationName, "annotationName");
        return getAllAnnotationInfo().getRepeatable(annotationName);
    }

    /**
     * Get the {@link Repeatable} annotation written on this element, or the empty list if it is not written on this
     * element. Meta-annotations are ignored.
     *
     * @param annotation
     *            the annotation class
     * @return An {@link AnnotationInfoList} of all instances of the annotation written on this element, or the
     *         empty list if it is not written on this element.
     * @throws IllegalArgumentException
     *             if {@code annotation} is not an annotation type.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    default AnnotationInfoList getDirectAnnotationInfoRepeatable(final Class<? extends Annotation> annotation) {
        Assert.notNull(annotation, "annotation");
        Assert.isAnnotation(annotation);
        return getDirectAnnotationInfoRepeatable(annotation.getName());
    }

    /**
     * Get the named {@link Repeatable} annotation written on this element, or the empty list if it is not written
     * on this element. Meta-annotations are ignored.
     *
     * @param annotationName
     *            the name of the annotation class
     * @return An {@link AnnotationInfoList} of all instances of the named annotation written on this element, or
     *         the empty list if it is not written on this element.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    default AnnotationInfoList getDirectAnnotationInfoRepeatable(final String annotationName) {
        Assert.notNull(annotationName, "annotationName");
        return getDirectAnnotationInfo().getRepeatable(annotationName);
    }

    /**
     * Check whether this element has the annotation, either written on the element itself or reachable as a
     * meta-annotation.
     *
     * @param annotation
     *            the annotation class
     * @return true if this element has the annotation.
     * @throws IllegalArgumentException
     *             if {@code annotation} is not an annotation type.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    default boolean hasAnnotation(final Class<? extends Annotation> annotation) {
        Assert.notNull(annotation, "annotation");
        Assert.isAnnotation(annotation);
        return hasAnnotation(annotation.getName());
    }

    /**
     * Check whether this element has the named annotation, either written on the element itself or reachable as a
     * meta-annotation.
     *
     * @param annotationName
     *            the name of the annotation class
     * @return true if this element has the named annotation.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    default boolean hasAnnotation(final String annotationName) {
        Assert.notNull(annotationName, "annotationName");
        return getAllAnnotationInfo().containsName(annotationName);
    }
}
