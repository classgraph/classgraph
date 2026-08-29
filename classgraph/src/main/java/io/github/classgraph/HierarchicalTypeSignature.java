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
import java.util.List;

import io.github.classgraph.Classfile.TypePathNode;
import io.github.classgraph.base.internal.utils.CollectionUtils;
import org.jspecify.annotations.Nullable;

/**
 * The base class for all parsed elements of the generic signature grammar in section 4.7.9.1 of the JVM
 * Specification. Subclasses are {@link ClassTypeSignature}, {@link MethodTypeSignature}, {@link TypeSignature},
 * {@link TypeParameter} and {@link TypeArgument}.
 */
public abstract class HierarchicalTypeSignature extends ScanResultObject {
    /** The type annotations on this type, in the order they were read, or null if none. */
    @Nullable
    List<AnnotationInfo> typeAnnotations;

    /** The value returned by {@link #getTypeAnnotationInfo()}, or null if it has not been built yet. */
    private @Nullable AnnotationInfoList typeAnnotationInfoRef;

    /** A hierarchical type signature. */
    HierarchicalTypeSignature() {
        super();
    }

    /**
     * Add a type annotation.
     *
     * @param annotationInfo
     *            the annotation
     */
    void addTypeAnnotation(final AnnotationInfo annotationInfo) {
        var typeAnnotationList = typeAnnotations;
        if (typeAnnotationList == null) {
            typeAnnotations = typeAnnotationList = new ArrayList<>(1);
        }
        // Type annotations are applied by a decorator that runs when the type signature is first requested, which
        // is after the signature has been given its ScanResult, so the annotation has to be given the ScanResult
        // here rather than by setScanResult
        annotationInfo.setScanResult(scanResult);
        typeAnnotationList.add(annotationInfo);
    }

    @Override
    void setScanResult(final @Nullable ScanResult scanResult) {
        super.setScanResult(scanResult);
        final var typeAnnotationList = typeAnnotations;
        if (typeAnnotationList != null) {
            for (final AnnotationInfo annotationInfo : typeAnnotationList) {
                annotationInfo.setScanResult(scanResult);
            }
        }
    }

    /**
     * Get a list of {@link AnnotationInfo} objects for any type annotations on this type, or null if none.
     *
     * @return a list of {@link AnnotationInfo} objects for any type annotations on this type, or null if none.
     */
    public @Nullable AnnotationInfoList getTypeAnnotationInfo() {
        synchronized (this) {
            if (typeAnnotationInfoRef == null) {
                final var typeAnnotationList = typeAnnotations;
                if (typeAnnotationList == null) {
                    return null;
                }
                // The order of type annotations in a classfile is not specified, so sort them by name
                typeAnnotationInfoRef = new AnnotationInfoList(CollectionUtils.sortCopy(typeAnnotationList));
            }
            return typeAnnotationInfoRef;
        }
    }

    /**
     * Add a type annotation.
     *
     * @param typePath
     *            the type path
     * @param annotationInfo
     *            the annotation
     */
    abstract void addTypeAnnotation(List<TypePathNode> typePath, AnnotationInfo annotationInfo);

    /**
     * Render type signature to string.
     *
     * @param useSimpleNames
     *            if true, strip package and outer class names from class names
     * @param annotationsToExclude
     *            toplevel annotations to exclude, to eliminate duplication (toplevel annotations are both
     *            class/field/method annotations and type annotations).
     * @param buf
     *            the buffer to append to
     */
    protected abstract void toStringInternal(final boolean useSimpleNames,
            @Nullable List<AnnotationInfo> annotationsToExclude, StringBuilder buf);

    /**
     * Render type signature to string.
     *
     * @param useSimpleNames
     *            if true, strip package and outer class names from class names
     * @param buf
     *            the buffer to append to
     */
    @Override
    protected void toString(final boolean useSimpleNames, final StringBuilder buf) {
        toStringInternal(useSimpleNames, /* annotationsToExclude = */ null, buf);
    }
}
