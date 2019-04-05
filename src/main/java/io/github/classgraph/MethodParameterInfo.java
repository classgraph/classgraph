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

import java.lang.annotation.Repeatable;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;

/**
 * Information on the parameters of a method.
 * 
 * @author lukehutch
 */
public class MethodParameterInfo {
    /** The containing method. */
    private final MethodInfo methodInfo;

    /** The annotation info. */
    final AnnotationInfo[] annotationInfo;

    /** The modifiers. */
    private final int modifiers;

    /** The type descriptor. */
    private final TypeSignature typeDescriptor;

    /** The type signature. */
    private final TypeSignature typeSignature;

    /** The parameter name. */
    private final String name;

    /** The scan result. */
    private ScanResult scanResult;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param methodInfo
     *            The {@link MethodInfo} for the defining method.
     * @param annotationInfo
     *            {@link AnnotationInfo} for any annotations on this method parameter.
     * @param modifiers
     *            The method parameter modifiers.
     * @param typeDescriptor
     *            The method parameter type descriptor.
     * @param typeSignature
     *            The method parameter type signature.
     * @param name
     *            The method parameter name.
     */
    MethodParameterInfo(final MethodInfo methodInfo, final AnnotationInfo[] annotationInfo, final int modifiers,
            final TypeSignature typeDescriptor, final TypeSignature typeSignature, final String name) {
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
     * Method parameter name. May be null, for unnamed parameters (e.g. synthetic parameters), or if compiled for
     * JDK version lower than 8, or if compiled for JDK version 8+ but without the commandline switch `-parameters`.
     * 
     * @return The method parameter name.
     */
    public String getName() {
        return name;
    }

    /**
     * Method parameter modifiers. May be zero, if no modifier bits set, or if compiled for JDK version lower than
     * 8, or if compiled for JDK version 8+ but without the commandline switch `-parameters`.
     * 
     * @return The method parameter modifiers.
     */
    public int getModifiers() {
        return modifiers;
    }

    /**
     * Get the method parameter modifiers as a String, e.g. "final". For the modifier bits, call
     * {@link #getModifiers()}.
     * 
     * @return The modifiers for the method parameter, as a String.
     */
    public String getModifiersStr() {
        final StringBuilder buf = new StringBuilder();
        modifiersToString(modifiers, buf);
        return buf.toString();
    }

    /**
     * Method parameter type signature, possibly including generic type information (or null if no type signature
     * information available for this parameter).
     * 
     * @return The method type signature, if available, else null.
     */
    public TypeSignature getTypeSignature() {
        return typeSignature;
    }

    /**
     * Method parameter type descriptor.
     * 
     * @return The method type descriptor.
     */
    public TypeSignature getTypeDescriptor() {
        return typeDescriptor;
    }

    /**
     * Method parameter type signature, or if not available, method type descriptor.
     * 
     * @return The method type signature, if present, otherwise the method type descriptor.
     */
    public TypeSignature getTypeSignatureOrTypeDescriptor() {
        return typeSignature != null ? typeSignature : typeDescriptor;
    }

    /**
     * Method parameter annotation info (or null if no annotations).
     * 
     * @return {@link AnnotationInfo} for any annotations on this method parameter.
     */
    public AnnotationInfoList getAnnotationInfo() {
        if (!scanResult.scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableAnnotationInfo() before #scan()");
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
     * Get a the named non-{@link Repeatable} annotation on this method, or null if the method parameter does not
     * have the named annotation. (Use {@link #getAnnotationInfoRepeatable(String)} for {@link Repeatable}
     * annotations.)
     * 
     * @param annotationName
     *            The annotation name.
     * @return An {@link AnnotationInfo} object representing the named annotation on this method parameter, or null
     *         if the method parameter does not have the named annotation.
     */
    public AnnotationInfo getAnnotationInfo(final String annotationName) {
        return getAnnotationInfo().get(annotationName);
    }

    /**
     * Get a the named {@link Repeatable} annotation on this method, or the empty list if the method parameter does
     * not have the named annotation.
     * 
     * @param annotationName
     *            The annotation name.
     * @return An {@link AnnotationInfoList} containing all instances of the named annotation on this method
     *         parameter, or the empty list if the method parameter does not have the named annotation.
     */
    public AnnotationInfoList getAnnotationInfoRepeatable(final String annotationName) {
        return getAnnotationInfo().getRepeatable(annotationName);
    }

    /**
     * Check whether this method parameter has the named annotation.
     *
     * @param annotationName
     *            The name of an annotation.
     * @return true if this method parameter has the named annotation.
     */
    public boolean hasAnnotation(final String annotationName) {
        return getAnnotationInfo().containsName(annotationName);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Sets the scan result.
     *
     * @param scanResult
     *            the new scan result
     */
    protected void setScanResult(final ScanResult scanResult) {
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

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof MethodParameterInfo)) {
            return false;
        }
        final MethodParameterInfo other = (MethodParameterInfo) obj;
        return Objects.equals(methodInfo, other.methodInfo)
                && Objects.deepEquals(annotationInfo, other.annotationInfo) && modifiers == other.modifiers
                && Objects.equals(typeDescriptor, other.typeDescriptor)
                && Objects.equals(typeSignature, other.typeSignature) && Objects.equals(name, other.name);
    }

    /* (non-Javadoc)
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
     * @param modifiers
     *            The field or method modifiers.
     * @param buf
     *            The buffer to write the result into.
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

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();

        if (annotationInfo != null) {
            for (final AnnotationInfo anAnnotationInfo : annotationInfo) {
                anAnnotationInfo.toString(buf);
                buf.append(' ');
            }
        }

        modifiersToString(modifiers, buf);

        buf.append(getTypeSignatureOrTypeDescriptor().toString());

        buf.append(' ');
        buf.append(name == null ? "_unnamed_param" : name);

        return buf.toString();
    }
}
