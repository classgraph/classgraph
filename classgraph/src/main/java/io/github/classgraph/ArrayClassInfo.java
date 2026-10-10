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

import org.jspecify.annotations.Nullable;

/**
 * Holds metadata about an array class. This class extends {@link ClassInfo} with additional methods relevant to
 * array classes, in particular {@link #getArrayTypeSignature()}, {@link #getTypeSignatureString()},
 * {@link #getElementTypeSignature()}, {@link #getElementClassInfo()}, and {@link #getNumDimensions()}.
 *
 * <p>
 * An {@link ArrayClassInfo} object will not have any methods, fields or annotations.
 * {@link ClassInfo#isArrayClass()} will return true for this subclass of {@link ClassInfo}.
 *
 * <p>
 * An {@link ArrayClassInfo} is made after the scan, from an array type signature, and is not part of the class
 * graph, so some of the methods it inherits from {@link ClassInfo} do not return what reflection returns for the
 * array's {@link Class}:
 *
 * <ul>
 * <li>{@link #getModifiers()} returns 0, so {@link #isPublic()}, {@link #isAbstract()} and {@link #isFinal()}
 * return false and {@link #getModifiersString()} returns the empty string. For an array class,
 * {@link Class#getModifiers()} returns the access modifiers of the element type with {@code abstract} and
 * {@code final} added.
 * <li>{@link #getSuperclass()} returns null and {@link #getAllSuperinterfaces()} returns the empty list, where
 * reflection gives {@link Object} as the superclass and {@link Cloneable} and {@link java.io.Serializable} as the
 * interfaces.
 * <li>If {@link #getElementClassInfo()} is not null, the classpath element, {@link #getResource()},
 * {@link #isExternalClass()}, {@link #getModuleInfo()} and {@link #getPackageInfo()} are those of the element
 * class, so {@link #getResource()} returns the classfile of the element class. Otherwise {@link #getResource()},
 * {@link #getModuleInfo()} and {@link #getPackageInfo()} return null, and {@link #isExternalClass()} returns true.
 * <li>For an array with a primitive element type, {@link #getPackageName()} returns the empty string, where
 * {@link Class#getPackageName()} returns {@code "java.lang"}.
 * </ul>
 */
public final class ArrayClassInfo extends ClassInfo {
    /** The array type signature. */
    private final ArrayTypeSignature arrayTypeSignature;

    /** The element class info, or null if the element type is a primitive type. */
    private @Nullable ClassInfo elementClassInfo;

    /**
     * Constructor.
     *
     * @param arrayTypeSignature
     *            the array type signature
     */
    ArrayClassInfo(final ArrayTypeSignature arrayTypeSignature) {
        super(arrayTypeSignature.getClassName(), /* modifiers = */ 0, /* resource = */ null);
        this.arrayTypeSignature = arrayTypeSignature;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the raw type signature string of the array class, e.g. "[[I" for "int[][]". The array class is erased, so
     * this has no type arguments, e.g. it is "[Ljava/util/List;" for {@code List<String>[]}.
     *
     * @return The raw type signature string of the array class.
     */
    @Override
    public String getTypeSignatureString() {
        return arrayTypeSignature.getTypeSignatureString();
    }

    /**
     * Returns null, because array classes do not have a ClassTypeSignature. Call {@link #getArrayTypeSignature()}
     * instead.
     *
     * @return null (always).
     */
    @Override
    public @Nullable ClassTypeSignature getTypeSignature() {
        return null;
    }

    /**
     * Get the type signature of the array class, e.g. the {@link ArrayTypeSignature} for {@code "int[][]"}. The
     * array class is erased, so this has no type arguments or type annotations, e.g. it is the type signature of
     * {@code java.util.List[]} for {@code List<String>[]}. The type arguments and type annotations of an array type
     * are on the {@link ArrayTypeSignature} that {@link ArrayTypeSignature#getArrayClassInfo()} was called on.
     *
     * @return The array type signature of the class.
     */
    public ArrayTypeSignature getArrayTypeSignature() {
        return arrayTypeSignature;
    }

    /**
     * Get the type signature of the array elements. The array class is erased, so this has no type arguments or
     * type annotations, e.g. it is the type signature of {@code java.util.List} for {@code List<String>[]}.
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
    public @Nullable ClassInfo getElementClassInfo() {
        var elementInfo = elementClassInfo;
        final var scanRes = scanResult;
        if (elementInfo == null && scanRes != null) {
            if (!(arrayTypeSignature.getElementTypeSignature() instanceof BaseTypeSignature)) {
                // Look the element class up by name, since the class info of a type variable element type would be
                // the class that the type variable is used in
                final var name = getName();
                elementClassInfo = elementInfo = scanRes.classNameToClassInfo
                        .get(name.substring(0, name.length() - 2 * getNumDimensions()));
                if (elementInfo != null) {
                    // Copy over relevant fields from array element ClassInfo
                    this.classpathElement = elementInfo.classpathElement;
                    this.classfileResource = elementInfo.classfileResource;
                    this.isScannedClass = elementInfo.isScannedClass;
                    this.isExternalClass = elementInfo.isExternalClass;
                    this.moduleInfo = elementInfo.moduleInfo;
                    this.packageInfo = elementInfo.packageInfo;
                }
            }
        }
        return elementInfo;
    }

    @Override
    void setScanResult(final @Nullable ScanResult scanResult) {
        super.setScanResult(scanResult);
        arrayTypeSignature.setScanResult(scanResult);
        // Pre-load fields from element type
        getElementClassInfo();
    }
}
