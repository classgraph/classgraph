/*
 * This file is part of FastClasspathScanner.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
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
package io.github.lukehutch.fastclasspathscanner;

import java.lang.reflect.Modifier;

import io.github.lukehutch.fastclasspathscanner.utils.TypeUtils;

/**
 * Information on the parameters of a method.
 * 
 * @author lukehutch
 */
public class MethodParameterInfo {
    final AnnotationInfo[] annotationInfo;
    final int modifiers;
    final TypeSignature typeDescriptor;
    final TypeSignature typeSignature;
    final String name;
    ScanResult scanResult;

    /**
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
     * @param scanResult
     *            The ScanResult.
     */
    public MethodParameterInfo(final AnnotationInfo[] annotationInfo, final int modifiers,
            final TypeSignature typeDescriptor, final TypeSignature typeSignature, final String name,
            final ScanResult scanResult) {
        this.name = name;
        this.modifiers = modifiers;
        this.typeDescriptor = typeDescriptor;
        this.typeSignature = typeSignature;
        this.annotationInfo = annotationInfo;
        this.scanResult = scanResult;
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
        return TypeUtils.modifiersToString(getModifiers(), /* isMethod = */ true);
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
    public AnnotationInfo[] getAnnotationInfo() {
        if (!scanResult.scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException(
                    "Please call FastClasspathScanner#enableAnnotationInfo() before #scan()");
        }
        return annotationInfo;
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();

        final AnnotationInfo[] annInfo = getAnnotationInfo();
        if (annInfo != null) {
            for (int j = 0; j < annInfo.length; j++) {
                annInfo[j].toString(buf);
                buf.append(' ');
            }
        }

        final int flag = getModifiers();
        if ((flag & Modifier.FINAL) != 0) {
            buf.append("final ");
        }
        if ((flag & TypeUtils.MODIFIER_SYNTHETIC) != 0) {
            buf.append("synthetic ");
        }
        if ((flag & TypeUtils.MODIFIER_MANDATED) != 0) {
            buf.append("mandated ");
        }

        buf.append(getTypeSignatureOrTypeDescriptor().toString());

        buf.append(' ');
        buf.append(name == null ? "_unnamed_param" : name);

        return buf.toString();
    }
}