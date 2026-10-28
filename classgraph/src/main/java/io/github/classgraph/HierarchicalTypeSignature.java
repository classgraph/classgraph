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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;

/**
 * The base class for all parsed elements of the generic signature grammar in section 4.7.9.1 of the JVM
 * Specification. Subclasses are {@link ClassTypeSignature}, {@link MethodTypeSignature}, {@link TypeSignature},
 * {@link TypeParameter} and {@link TypeArgument}.
 */
public abstract sealed class HierarchicalTypeSignature extends ScanResultObject
        permits TypeSignature, ClassTypeSignature, MethodTypeSignature, TypeArgument, TypeParameter {
    /** The type annotations on this type, or null if none. */
    @Nullable
    TypeAnnotations typeAnnotations;

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
            typeAnnotations = typeAnnotationList = new TypeAnnotations();
        }
        typeAnnotationList.add(annotationInfo, scanResult);
    }

    /**
     * Copy type annotations from another type signature.
     *
     * @param source
     *            the type signature to copy type annotations from.
     */
    void copyTypeAnnotationsFrom(final HierarchicalTypeSignature source) {
        final var sourceTypeAnnotations = source.typeAnnotations;
        typeAnnotations = sourceTypeAnnotations == null ? null : sourceTypeAnnotations.copy();
    }

    /**
     * Prepend type annotations to this type signature.
     *
     * @param annotations
     *            the type annotations to prepend.
     */
    void prependTypeAnnotations(final List<AnnotationInfo> annotations) {
        var typeAnnotationList = typeAnnotations;
        if (typeAnnotationList == null) {
            typeAnnotations = typeAnnotationList = new TypeAnnotations();
        }
        typeAnnotationList.prepend(annotations);
    }

    @Override
    void setScanResult(final @Nullable ScanResult scanResult) {
        super.setScanResult(scanResult);
        final var typeAnnotationList = typeAnnotations;
        if (typeAnnotationList != null) {
            typeAnnotationList.setScanResult(scanResult);
        }
    }

    /**
     * Check that annotation info was enabled for the scan.
     *
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    final void checkTypeAnnotationInfoEnabled() {
        // A type signature that was never given a ScanResult did not come from a scan, so has no setting to check
        final var scanRes = scanResult;
        if (scanRes != null) {
            scanRes.scanSpec.checkAnnotationInfoEnabled();
        }
    }

    /**
     * Return true if this type has any type annotations.
     *
     * @return true if this type has any type annotations.
     */
    final boolean hasTypeAnnotations() {
        synchronized (this) {
            final var typeAnnotationList = typeAnnotations;
            return typeAnnotationList != null && !typeAnnotationList.isEmpty();
        }
    }

    /**
     * Get a list of {@link AnnotationInfo} objects for any type annotations on this type.
     *
     * @return a list of {@link AnnotationInfo} objects for any type annotations on this type, or the empty list if
     *         none.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    public AnnotationInfoList getTypeAnnotationInfo() {
        checkTypeAnnotationInfoEnabled();
        synchronized (this) {
            final var typeAnnotationList = typeAnnotations;
            return typeAnnotationList == null ? AnnotationInfoList.EMPTY_LIST
                    : typeAnnotationList.getAnnotationInfo(scanResult);
        }
    }

    /**
     * Get the names of any classes referenced in the type signature.
     *
     * @param refdClassNames
     *            the set to add the referenced class names to.
     */
    abstract void findReferencedClassNames(Set<String> refdClassNames);

    /**
     * Get {@link ClassInfo} objects for any classes referenced in the type signature.
     *
     * @param classNameToClassInfo
     *            the map to add a {@link ClassInfo} object to, for a referenced class that has none in the scan
     *            result.
     * @param refdClassInfo
     *            the set to add the referenced class info to.
     * @param log
     *            the log node, or null to skip logging
     */
    @Override
    final void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo, final @Nullable LogNode log) {
        final Set<String> refdClassNames = new HashSet<>();
        findReferencedClassNames(refdClassNames);
        for (final String refdClassName : refdClassNames) {
            final var classInfo = ClassInfo.getOrCreateClassInfo(refdClassName, scanResult().classNameToClassInfo,
                    classNameToClassInfo);
            classInfo.scanResult = scanResult;
            refdClassInfo.add(classInfo);
        }
    }

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
