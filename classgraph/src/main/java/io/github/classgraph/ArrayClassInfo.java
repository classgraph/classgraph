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
 * {@link ArrayClassInfo} instances are created on demand from array type signatures rather than scanned from
 * classfiles, so several methods inherited from {@link ClassInfo} differ from standard reflection on an array
 * {@link Class}:
 *
 * <ul>
 * <li>{@link #getModifiers()} returns 0 ({@link #isPublic()}, {@link #isAbstract()}, and {@link #isFinal()} return
 * false, and {@link #getModifiersString()} returns {@code ""}).
 * <li>{@link #getSuperclass()} returns null and {@link #getAllSuperinterfaces()} returns the empty list.
 * <li>If {@link #getElementClassInfo()} is non-null, {@link #getResource()}, {@link #isExternalClass()},
 * {@link #getModuleInfo()}, and {@link #getPackageInfo()} delegate to the element class; otherwise
 * {@link #getResource()}, {@link #getModuleInfo()}, and {@link #getPackageInfo()} return null and
 * {@link #isExternalClass()} returns true.
 * <li>For primitive array types, {@link #getPackageName()} returns {@code ""}.
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
     * Get the raw type signature string of the erased array class (e.g. {@code "[[I"} for {@code int[][]}, or
     * {@code "[Ljava/util/List;"} for {@code List<String>[]}).
     *
     * @return The raw type signature string of the array class.
     */
    @Override
    public String getTypeSignatureString() {
        return arrayTypeSignature.getTypeSignatureString();
    }

    /**
     * Returns null, because array classes do not have a {@link ClassTypeSignature}. Call
     * {@link #getArrayTypeSignature()} instead.
     *
     * @return null.
     */
    @Override
    public @Nullable ClassTypeSignature getTypeSignature() {
        return null;
    }

    /**
     * Get the erased {@link ArrayTypeSignature} of the array class (without type arguments or type annotations).
     * Type arguments and type annotations are available on the {@link ArrayTypeSignature} on which
     * {@link ArrayTypeSignature#getArrayClassInfo()} was called.
     *
     * @return The array type signature of the class.
     */
    public ArrayTypeSignature getArrayTypeSignature() {
        return arrayTypeSignature;
    }

    /**
     * Get the erased {@link TypeSignature} of the array elements.
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
     * @return The {@link ClassInfo} instance for the array element type, or null if the element type is primitive
     *         or was not encountered during scanning.
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
