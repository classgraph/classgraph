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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The type annotations on one part of a type signature: a type, one nested class of a class reference, or the
 * explicit receiver parameter of a method. Type signatures allocate this only when they get their first type
 * annotation; a class reference then allocates one for each of its nested classes. It is not thread-safe: type
 * annotations are added before the type signature is handed out, and the type signature synchronizes calls to
 * {@link #getAnnotationInfo()}.
 */
final class TypeAnnotations implements Iterable<AnnotationInfo> {
    /** The type annotations, in the order they were read. */
    private final List<AnnotationInfo> annotations;

    /** The value returned by {@link #getAnnotationInfo()}, or null if it has not been built yet. */
    private @Nullable AnnotationInfoList annotationInfoRef;

    /** Create an empty list of type annotations. */
    TypeAnnotations() {
        this.annotations = new ArrayList<>(1);
    }

    /**
     * Make a copy of a list of type annotations.
     *
     * @param source
     *            the type annotations to copy.
     */
    private TypeAnnotations(final TypeAnnotations source) {
        this.annotations = new ArrayList<>(source.annotations);
    }

    /**
     * Make a copy of these type annotations, which can be changed without changing this list.
     *
     * @return the copy.
     */
    TypeAnnotations copy() {
        return new TypeAnnotations(this);
    }

    /**
     * Add a type annotation.
     *
     * @param annotationInfo
     *            the annotation
     * @param scanResult
     *            the {@link ScanResult} of the type signature that holds these type annotations.
     */
    void add(final AnnotationInfo annotationInfo, final @Nullable ScanResult scanResult) {
        // The type annotations read from the classfile are added when the type signature is first requested, which
        // is after the signature has been given its ScanResult, so the annotation has to be given the ScanResult
        // here rather than by setScanResult
        annotationInfo.setScanResult(scanResult);
        annotations.add(annotationInfo);
        annotationInfoRef = null;
    }

    /**
     * Put type annotations before these type annotations. The annotations are not given a {@link ScanResult}: they
     * are shared with the type signature that type variable substitution copied them from, and keep its ScanResult.
     *
     * @param annotationsToPrepend
     *            the type annotations to put first.
     */
    void prepend(final List<AnnotationInfo> annotationsToPrepend) {
        annotations.addAll(0, annotationsToPrepend);
        annotationInfoRef = null;
    }

    /**
     * Set the {@link ScanResult} of each type annotation.
     *
     * @param scanResult
     *            the scan result.
     */
    void setScanResult(final @Nullable ScanResult scanResult) {
        for (final AnnotationInfo annotationInfo : annotations) {
            annotationInfo.setScanResult(scanResult);
        }
    }

    /**
     * Get the type annotations, in the order they were read.
     *
     * @return the type annotations. The list must not be changed.
     */
    List<AnnotationInfo> asList() {
        return annotations;
    }

    /**
     * Test whether there are no type annotations.
     *
     * @return true if there are no type annotations.
     */
    boolean isEmpty() {
        return annotations.isEmpty();
    }

    /**
     * Get the type annotations, sorted by name, since the order of type annotations in a classfile is not
     * specified. Annotations of the same name keep the order they were read in.
     *
     * @return the type annotations, sorted by name.
     */
    AnnotationInfoList getAnnotationInfo() {
        var annotationInfo = annotationInfoRef;
        if (annotationInfo == null) {
            annotationInfoRef = annotationInfo = new AnnotationInfoList(
                    AnnotationInfoList.sortCopyByName(annotations));
        }
        return annotationInfo;
    }

    /**
     * Get the type annotations, in the order they were read.
     *
     * @return an iterator over the type annotations.
     */
    @Override
    public Iterator<AnnotationInfo> iterator() {
        return annotations.iterator();
    }

    @Override
    public int hashCode() {
        return annotations.hashCode();
    }

    @Override
    public boolean equals(final @Nullable Object obj) {
        return obj == this || obj instanceof final TypeAnnotations other && other.annotations.equals(annotations);
    }
}
