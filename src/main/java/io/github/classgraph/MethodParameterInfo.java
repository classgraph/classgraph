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

import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;

import nonapi.io.github.classgraph.utils.Assert;
import org.jspecify.annotations.Nullable;

/**
 * Information on the parameters of a method.
 *
 * @author lukehutch
 */
public class MethodParameterInfo {
    /** The containing method. */
    private final MethodInfo methodInfo;

    /** The annotation info, or null if the parameter has no annotations. */
    final AnnotationInfo @Nullable [] annotationInfo;

    /** The modifiers. */
    private final int modifiers;

    /** The type descriptor, or null if none was found for this parameter. */
    private final @Nullable TypeSignature typeDescriptor;

    /** The type signature, or null if none was found for this parameter. */
    private final @Nullable TypeSignature typeSignature;

    /** The parameter name, or null if the parameter is unnamed. */
    private final @Nullable String name;

    /** The scan result. Set by {@link #setScanResult(ScanResult)} after construction. */
    private @Nullable ScanResult scanResult;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param methodInfo     The {@link MethodInfo} for the defining method.
     * @param annotationInfo {@link AnnotationInfo} for any annotations on this
     *                       method parameter.
     * @param modifiers      The method parameter modifiers.
     * @param typeDescriptor The method parameter type descriptor.
     * @param typeSignature  The method parameter type signature.
     * @param name           The method parameter name.
     */
    MethodParameterInfo(final MethodInfo methodInfo, final AnnotationInfo @Nullable [] annotationInfo,
            final int modifiers, final @Nullable TypeSignature typeDescriptor,
            final @Nullable TypeSignature typeSignature, final @Nullable String name) {
        this.methodInfo = methodInfo;
        this.name = name;
        this.modifiers = modifiers;
        this.typeDescriptor = typeDescriptor;
        this.typeSignature = typeSignature;
        this.annotationInfo = annotationInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the {@link MethodInfo} for the defining method.
     *
     * @return The {@link MethodInfo} for the defining method.
     */
    public MethodInfo getMethodInfo() {
        return methodInfo;
    }

    /**
     * Method parameter name. May be null, for unnamed parameters (e.g. synthetic
     * parameters), or if compiled for JDK version lower than 8, or if compiled for
     * JDK version 8+ but without the commandline switch `-parameters`.
     *
     * @return The method parameter name.
     */
    public @Nullable String getName() {
        return name;
    }

    /**
     * Method parameter modifiers. May be zero, if no modifier bits set, or if
     * compiled for JDK version lower than 8, or if compiled for JDK version 8+ but
     * without the commandline switch `-parameters`.
     *
     * @return The method parameter modifiers.
     */
    public int getModifiers() {
        return modifiers;
    }

    /**
     * Get the method parameter modifiers as a String, e.g. "final". For the
     * modifier bits, call {@link #getModifiers()}.
     *
     * @return The modifiers for the method parameter, as a String.
     */
    public String getModifiersString() {
        final StringBuilder buf = new StringBuilder();
        modifiersToString(modifiers, buf);
        return buf.toString();
    }

    /**
     * Method parameter type signature, possibly including generic type information
     * (or null if no type signature information available for this parameter).
     *
     * @return The method type signature, if available, else null.
     */
    public @Nullable TypeSignature getTypeSignature() {
        return typeSignature;
    }

    /**
     * Method parameter type descriptor.
     *
     * @return The method type descriptor.
     */
    public @Nullable TypeSignature getTypeDescriptor() {
        return typeDescriptor;
    }

    /**
     * Method parameter type signature, or if not available, method type descriptor.
     *
     * @return The method type signature, if present, otherwise the method type
     *         descriptor.
     */
    public @Nullable TypeSignature getTypeSignatureOrTypeDescriptor() {
        return typeSignature != null ? typeSignature : typeDescriptor;
    }

    /**
     * Get the annotations and meta-annotations on this method parameter.
     *
     * @return {@link AnnotationInfo} for the annotations and meta-annotations on
     *         this method parameter, or the empty list if none.
     */
    public AnnotationInfoList getAllAnnotationInfo() {
        if (!Objects.requireNonNull(scanResult).scanSpec.enableAnnotationInfo) {
            throw new IllegalStateException("Please call ClassGraph#enableAnnotationInfo() before #scan()");
        }
        if (annotationInfo == null || annotationInfo.length == 0) {
            return AnnotationInfoList.EMPTY_LIST;
        } else {
            final AnnotationInfoList annotationInfoList = new AnnotationInfoList(annotationInfo.length);
            Collections.addAll(annotationInfoList, annotationInfo);
            return AnnotationInfoList.getIndirectAnnotations(annotationInfoList, /* annotatedClass = */ null);
        }
    }

    /**
     * Get only the annotations directly present on this method parameter, not the
     * meta-annotations on those annotations.
     *
     * @return {@link AnnotationInfo} for the annotations directly present on this
     *         method parameter, or the empty list if none.
     */
    public AnnotationInfoList getDirectAnnotationInfo() {
        return getAllAnnotationInfo().directOnly();
    }

    /**
     * Get the non-{@link Repeatable} annotation or meta-annotation on this method
     * parameter, or null if the method parameter does not have the annotation. (Use
     * {@link #getAllAnnotationInfoRepeatable(Class)} for {@link Repeatable}
     * annotations, or {@link #getDirectAnnotationInfo(Class)} to ignore
     * meta-annotations.)
     *
     * @param annotation the annotation class
     * @return An {@link AnnotationInfo} object representing the annotation on this
     *         method parameter, or null if the method parameter does not have the
     *         annotation.
     */
    public @Nullable AnnotationInfo getAllAnnotationInfo(final Class<? extends Annotation> annotation) {
        Assert.notNull(annotation, "annotation");
        Assert.isAnnotation(annotation);
        return getAllAnnotationInfo(annotation.getName());
    }

    /**
     * Get the named non-{@link Repeatable} annotation or meta-annotation on this
     * method parameter, or null if the method parameter does not have the named
     * annotation. (Use {@link #getAllAnnotationInfoRepeatable(String)} for
     * {@link Repeatable} annotations, or {@link #getDirectAnnotationInfo(String)} to
     * ignore meta-annotations.)
     *
     * <p>
     * If the named annotation can be reached in more than one way -- if it is
     * directly present on the method parameter and is also a meta-annotation of one
     * of the parameter's other annotations, for example -- then the one reached most
     * directly is returned. Call {@link #getDirectAnnotationInfo(String)} if you want
     * only the annotation present on the method parameter itself.
     *
     * @param annotationName the name of the annotation class
     * @return An {@link AnnotationInfo} object representing the named annotation on
     *         this method parameter, or null if the method parameter does not have
     *         the named annotation.
     */
    public @Nullable AnnotationInfo getAllAnnotationInfo(final String annotationName) {
        Assert.notNull(annotationName, "annotationName");
        return getAllAnnotationInfo().get(annotationName);
    }

    /**
     * Get the non-{@link Repeatable} annotation directly present on this method
     * parameter, or null if the annotation is not directly present.
     * Meta-annotations are ignored. (Use
     * {@link #getDirectAnnotationInfoRepeatable(Class)} for {@link Repeatable}
     * annotations.)
     *
     * @param annotation the annotation class
     * @return An {@link AnnotationInfo} object representing the annotation directly
     *         present on this method parameter, or null if it is not directly
     *         present.
     */
    public @Nullable AnnotationInfo getDirectAnnotationInfo(final Class<? extends Annotation> annotation) {
        Assert.notNull(annotation, "annotation");
        Assert.isAnnotation(annotation);
        return getDirectAnnotationInfo(annotation.getName());
    }

    /**
     * Get the named non-{@link Repeatable} annotation directly present on this
     * method parameter, or null if the named annotation is not directly present.
     * Meta-annotations are ignored. (Use
     * {@link #getDirectAnnotationInfoRepeatable(String)} for {@link Repeatable}
     * annotations.)
     *
     * @param annotationName the name of the annotation class
     * @return An {@link AnnotationInfo} object representing the named annotation
     *         directly present on this method parameter, or null if it is not
     *         directly present.
     */
    public @Nullable AnnotationInfo getDirectAnnotationInfo(final String annotationName) {
        Assert.notNull(annotationName, "annotationName");
        return getDirectAnnotationInfo().get(annotationName);
    }

    /**
     * Get the {@link Repeatable} annotation or meta-annotation on this method
     * parameter, or the empty list if the method parameter does not have the
     * annotation.
     *
     * @param annotation the annotation class
     * @return An {@link AnnotationInfoList} containing all instances of the
     *         annotation on this method parameter, or the empty list if the method
     *         parameter does not have the annotation.
     */
    public AnnotationInfoList getAllAnnotationInfoRepeatable(final Class<? extends Annotation> annotation) {
        Assert.notNull(annotation, "annotation");
        Assert.isAnnotation(annotation);
        return getAllAnnotationInfoRepeatable(annotation.getName());
    }

    /**
     * Get the named {@link Repeatable} annotation or meta-annotation on this method
     * parameter, or the empty list if the method parameter does not have the named
     * annotation.
     *
     * @param annotationName the name of the annotation class
     * @return An {@link AnnotationInfoList} containing all instances of the named
     *         annotation on this method parameter, or the empty list if the method
     *         parameter does not have the named annotation.
     */
    public AnnotationInfoList getAllAnnotationInfoRepeatable(final String annotationName) {
        Assert.notNull(annotationName, "annotationName");
        return getAllAnnotationInfo().getRepeatable(annotationName);
    }

    /**
     * Get the {@link Repeatable} annotation directly present on this method
     * parameter, or the empty list if it is not directly present. Meta-annotations
     * are ignored.
     *
     * @param annotation the annotation class
     * @return An {@link AnnotationInfoList} containing all instances of the
     *         annotation directly present on this method parameter, or the empty
     *         list if it is not directly present.
     */
    public AnnotationInfoList getDirectAnnotationInfoRepeatable(final Class<? extends Annotation> annotation) {
        Assert.notNull(annotation, "annotation");
        Assert.isAnnotation(annotation);
        return getDirectAnnotationInfoRepeatable(annotation.getName());
    }

    /**
     * Get the named {@link Repeatable} annotation directly present on this method
     * parameter, or the empty list if it is not directly present. Meta-annotations
     * are ignored.
     *
     * @param annotationName the name of the annotation class
     * @return An {@link AnnotationInfoList} containing all instances of the named
     *         annotation directly present on this method parameter, or the empty
     *         list if it is not directly present.
     */
    public AnnotationInfoList getDirectAnnotationInfoRepeatable(final String annotationName) {
        Assert.notNull(annotationName, "annotationName");
        return getDirectAnnotationInfo().getRepeatable(annotationName);
    }

    /**
     * Check whether this method parameter has the annotation.
     *
     * @param annotation the annotation class
     * @return true if this method parameter has the annotation.
     */
    public boolean hasAnnotation(final Class<? extends Annotation> annotation) {
        Assert.notNull(annotation, "annotation");
        Assert.isAnnotation(annotation);
        return hasAnnotation(annotation.getName());
    }

    /**
     * Check whether this method parameter has the named annotation.
     *
     * @param annotationName the name of the annotation class
     * @return true if this method parameter has the named annotation.
     */
    public boolean hasAnnotation(final String annotationName) {
        Assert.notNull(annotationName, "annotationName");
        return getAllAnnotationInfo().containsName(annotationName);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Sets the scan result.
     *
     * @param scanResult the new scan result
     */
    protected void setScanResult(final @Nullable ScanResult scanResult) {
        this.scanResult = scanResult;
        if (this.annotationInfo != null) {
            for (final AnnotationInfo ai : annotationInfo) {
                ai.setScanResult(scanResult);
            }
        }
        if (this.typeDescriptor != null) {
            this.typeDescriptor.setScanResult(scanResult);
        }
        if (this.typeSignature != null) {
            this.typeSignature.setScanResult(scanResult);
        }
    }

    /**
     * Returns true if this method parameter is final.
     *
     * @return True if this method parameter is final.
     */
    public boolean isFinal() {
        return Modifier.isFinal(modifiers);
    }

    /**
     * Returns true if this method parameter is synthetic.
     *
     * @return True if this method parameter is synthetic.
     */
    public boolean isSynthetic() {
        return (modifiers & 0x1000) != 0;
    }

    /**
     * Returns true if this method parameter is mandated.
     *
     * @return True if this method parameter is mandated.
     */
    public boolean isMandated() {
        return (modifiers & 0x8000) != 0;
    }

    // -------------------------------------------------------------------------------------------------------------

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final MethodParameterInfo other)) {
            return false;
        }
        return Objects.equals(methodInfo, other.methodInfo) && Objects.deepEquals(annotationInfo, other.annotationInfo)
                && modifiers == other.modifiers && Objects.equals(typeDescriptor, other.typeDescriptor)
                && Objects.equals(typeSignature, other.typeSignature) && Objects.equals(name, other.name);
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return Objects.hash(methodInfo, Arrays.hashCode(annotationInfo), typeDescriptor, typeSignature, name)
                + modifiers;
    }

    /**
     * Convert modifiers into a string representation, e.g. "public static final".
     *
     * @param modifiers The field or method modifiers.
     * @param buf       the buffer to append to
     */
    static void modifiersToString(final int modifiers, final StringBuilder buf) {
        if ((modifiers & Modifier.FINAL) != 0) {
            buf.append("final ");
        }
        if ((modifiers & 0x1000) != 0) {
            buf.append("synthetic ");
        }
        if ((modifiers & 0x8000) != 0) {
            buf.append("mandated ");
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Render to string.
     *
     * @param useSimpleNames if true, strip package and outer class names from class
     *                       names
     * @param buf            the buffer to append to
     */
    protected void toString(final boolean useSimpleNames, final StringBuilder buf) {
        if (annotationInfo != null) {
            for (final AnnotationInfo anAnnotationInfo : annotationInfo) {
                anAnnotationInfo.toString(useSimpleNames, buf);
                buf.append(' ');
            }
        }

        modifiersToString(modifiers, buf);

        Objects.requireNonNull(getTypeSignatureOrTypeDescriptor()).toString(useSimpleNames, buf);

        buf.append(' ');
        buf.append(name == null ? "_unnamed_param" : name);
    }

    /**
     * Render to string with simple names for classes.
     *
     * @return the string representation.
     */
    public String toStringWithSimpleNames() {
        final StringBuilder buf = new StringBuilder();
        toString(/* useSimpleNames = */ true, buf);
        return buf.toString();
    }

    /**
     * Render to string.
     *
     * @return the string representation.
     */
    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        toString(/* useSimpleNames = */ false, buf);
        return buf.toString();
    }
}
