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

import java.util.Map;
import java.util.Set;

/**
 * Holds metadata about an array class. This class extends {@link ClassInfo} with additional methods relevant to
 * array classes, in particular {@link #getArrayTypeSignature()}, {@link #getTypeSignatureStr()},
 * {@link #getElementTypeSignature()}, {@link #getElementClassInfo()}, {@link #loadElementClass()}, and
 * {@link #getNumDimensions()}.
 * 
 * <p>
 * An {@link ArrayClassInfo} object will not have any methods, fields or annotations.
 * {@link ClassInfo#isArrayClass()} will return true for this subclass of {@link ClassInfo}.
 */
public class ArrayClassInfo extends ClassInfo {
    /** The array type signature. */
    private ArrayTypeSignature arrayTypeSignature;

    /** The element class info. */
    private ClassInfo elementClassInfo;

    /** Default constructor for deserialization. */
    ArrayClassInfo() {
        super();
    }

    /**
     * Constructor.
     *
     * @param arrayTypeSignature
     *            the array type signature
     */
    ArrayClassInfo(final ArrayTypeSignature arrayTypeSignature) {
        super(arrayTypeSignature.getClassName(), /* modifiers = */ 0, /* resource = */ null);
        this.arrayTypeSignature = arrayTypeSignature;
        // Pre-load fields from element type
        getElementClassInfo();
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ClassInfo#setScanResult(io.github.classgraph.ScanResult)
     */
    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the raw type signature string of the array class, e.g. "[[I" for "int[][]".
     *
     * @return The raw type signature string of the array class.
     */
    @Override
    public String getTypeSignatureStr() {
        return arrayTypeSignature.getTypeSignatureStr();
    }

    /**
     * Returns null, because array classes do not have a ClassTypeSignature. Call {@link #getArrayTypeSignature()}
     * instead.
     *
     * @return null (always).
     */
    @Override
    public ClassTypeSignature getTypeSignature() {
        return null;
    }

    /**
     * Get the type signature of the class.
     *
     * @return The class type signature, if available, otherwise returns null.
     */
    public ArrayTypeSignature getArrayTypeSignature() {
        return arrayTypeSignature;
    }

    /**
     * Get the type signature of the array elements.
     *
     * @return The type signature of the array elements.
     */
    public TypeSignature getElementTypeSignature() {
        return arrayTypeSignature.getElementTypeSignature();
    }

    /**
     * Get the number of dimensions of the array.
     *
     * @return The number of dimensions of the array.
     */
    public int getNumDimensions() {
        return arrayTypeSignature.getNumDimensions();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the {@link ClassInfo} instance for the array element type.
     *
     * @return the {@link ClassInfo} instance for the array element type. Returns null if the element type was not
     *         found during the scan. In particular, will return null for arrays that have a primitive element type.
     */
    public ClassInfo getElementClassInfo() {
        if (elementClassInfo == null) {
            final TypeSignature elementTypeSignature = arrayTypeSignature.getElementTypeSignature();
            if (!(elementTypeSignature instanceof BaseTypeSignature)) {
                elementClassInfo = arrayTypeSignature.getElementTypeSignature().getClassInfo();
                if (elementClassInfo != null) {
                    // Copy over relevant fields from array element ClassInfo
                    this.classpathElement = elementClassInfo.classpathElement;
                    this.classfileResource = elementClassInfo.classfileResource;
                    this.classLoader = elementClassInfo.classLoader;
                    this.isScannedClass = elementClassInfo.isScannedClass;
                    this.isExternalClass = elementClassInfo.isExternalClass;
                    this.moduleInfo = elementClassInfo.moduleInfo;
                    this.packageInfo = elementClassInfo.packageInfo;
                }
            }
        }
        return elementClassInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get a {@code Class<?>} reference for the array element type. Causes the ClassLoader to load the element
     * class, if it is not already loaded.
     *
     * @param ignoreExceptions
     *            Whether or not to ignore exceptions.
     * @return a {@code Class<?>} reference for the array element type. Also works for arrays of primitive element
     *         type.
     */
    public Class<?> loadElementClass(final boolean ignoreExceptions) {
        return arrayTypeSignature.loadElementClass(ignoreExceptions);
    }

    /**
     * Get a {@code Class<?>} reference for the array element type. Causes the ClassLoader to load the element
     * class, if it is not already loaded.
     *
     * @return a {@code Class<?>} reference for the array element type. Also works for arrays of primitive element
     *         type.
     */
    public Class<?> loadElementClass() {
        return arrayTypeSignature.loadElementClass();
    }

    /**
     * Obtain a {@code Class<?>} reference for the array class named by this {@link ArrayClassInfo} object. Causes
     * the ClassLoader to load the element class, if it is not already loaded.
     *
     * @param ignoreExceptions
     *            Whether or not to ignore exceptions
     * @return The class reference, or null, if ignoreExceptions is true and there was an exception or error loading
     *         the class.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false and there were problems loading the class.
     */
    @Override
    public Class<?> loadClass(final boolean ignoreExceptions) {
        if (classRef == null) {
            classRef = arrayTypeSignature.loadClass(ignoreExceptions);
        }
        return classRef;
    }

    /**
     * Obtain a {@code Class<?>} reference for the array class named by this {@link ArrayClassInfo} object. Causes
     * the ClassLoader to load the element class, if it is not already loaded.
     * 
     * @return The class reference.
     * @throws IllegalArgumentException
     *             if there were problems loading the class.
     */
    @Override
    public Class<?> loadClass() {
        if (classRef == null) {
            classRef = arrayTypeSignature.loadClass();
        }
        return classRef;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get {@link ClassInfo} objects for any classes referenced in the type descriptor or type signature.
     *
     * @param classNameToClassInfo
     *            the map from class name to {@link ClassInfo}.
     * @param refdClassInfo
     *            the referenced class info
     */
    @Override
    protected void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo) {
        super.findReferencedClassInfo(classNameToClassInfo, refdClassInfo);
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see io.github.classgraph.ClassInfo#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        return super.equals(obj);
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ClassInfo#hashCode()
     */
    @Override
    public int hashCode() {
        return super.hashCode();
    }
}
