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

import java.util.Set;

import io.github.classgraph.utils.Parser.ParseException;

/**
 * Stores the type descriptor of a {@code Class<?>}, as found in an annotation parameter value.
 */
public class AnnotationClassRef extends ScanResultObject {
    private String typeDescriptorStr;
    private transient TypeSignature typeSignature;
    private transient String className;

    AnnotationClassRef() {
    }

    AnnotationClassRef(final String typeDescriptorStr) {
        this.typeDescriptorStr = typeDescriptorStr;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * @return The name of the referenced class.
     */
    public String getName() {
        return getClassName();
    }

    /**
     * @return The type signature of the {@code Class<?>} reference. This will be a {@link ClassRefTypeSignature} or
     *         a {@link BaseTypeSignature}.
     */
    private TypeSignature getTypeSignature() {
        if (typeSignature == null) {
            try {
                // There can't be any type variables to resolve in either ClassRefTypeSignature or
                // BaseTypeSignature, so just set definingClassName to null
                typeSignature = TypeSignature.parse(typeDescriptorStr, /* definingClassName = */ null);
                typeSignature.setScanResult(scanResult);
            } catch (final ParseException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return typeSignature;
    }

    /**
     * Loads the referenced class, returning a {@code Class<?>} reference for the referenced class.
     * 
     * @param ignoreExceptions
     *            if true, ignore exceptions and instead return null if the class could not be loaded.
     * @return The {@code Class<?>} reference for the referenced class.
     * @throws IllegalArgumentException
     *             if the class could not be loaded and ignoreExceptions was false.
     */
    @Override
    public Class<?> loadClass(final boolean ignoreExceptions) {
        getTypeSignature();
        if (typeSignature instanceof BaseTypeSignature) {
            return ((BaseTypeSignature) typeSignature).getType();
        } else if (typeSignature instanceof ClassRefTypeSignature) {
            return ((ClassRefTypeSignature) typeSignature).loadClass(ignoreExceptions);
        } else {
            throw new IllegalArgumentException("Got unexpected type " + typeSignature.getClass().getName()
                    + " for ref type signature: " + typeDescriptorStr);
        }
    }

    /**
     * Loads the referenced class, returning a {@code Class<?>} reference for the referenced class.
     * 
     * @return The {@code Class<?>} reference for the referenced class.
     * @throws IllegalArgumentException
     *             if the class could not be loaded.
     */
    @Override
    public Class<?> loadClass() {
        return loadClass(/* ignoreExceptions = */ false);
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    protected String getClassName() {
        if (className == null) {
            getTypeSignature();
            if (typeSignature instanceof BaseTypeSignature) {
                className = ((BaseTypeSignature) typeSignature).getType().getName();
            } else if (typeSignature instanceof ClassRefTypeSignature) {
                className = ((ClassRefTypeSignature) typeSignature).getFullyQualifiedClassName();
            } else {
                throw new IllegalArgumentException("Got unexpected type " + typeSignature.getClass().getName()
                        + " for ref type signature: " + typeDescriptorStr);
            }
        }
        return className;
    }

    /**
     * @return The {@link ClassInfo} object for the referenced class, or null if the referenced class was not
     *         encountered during scanning (i.e. if no ClassInfo object was created for the class during scanning).
     *         N.B. even if this method returns null, {@link #loadClass()} may be able to load the referenced class
     *         by name.
     */
    @Override
    public ClassInfo getClassInfo() {
        getClassName();
        return super.getClassInfo();
    }

    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (typeSignature != null) {
            typeSignature.setScanResult(scanResult);
        }
    }

    @Override
    protected void getClassNamesFromTypeDescriptors(final Set<String> classNames) {
        classNames.add(getClassName());
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public int hashCode() {
        return getTypeSignature().hashCode();
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof AnnotationClassRef)) {
            return false;
        }
        return getTypeSignature().equals(((AnnotationClassRef) obj).getTypeSignature());
    }

    @Override
    public String toString() {
        return getTypeSignature().toString();
    }
}