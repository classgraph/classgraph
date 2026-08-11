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

import nonapi.io.github.classgraph.types.ParseException;
import org.jspecify.annotations.Nullable;

/**
 * Stores the type descriptor of a {@code Class<?>}, as found in an annotation parameter value.
 */
public class AnnotationClassRef extends ScanResultObject {
    /** The type descriptor str. */
    private final String typeDescriptorStr;

    /** The type signature. */
    private @Nullable TypeSignature typeSignature;

    /** The class name. */
    private @Nullable String className;

    /**
     * Constructor.
     *
     * @param typeDescriptorStr
     *            the type descriptor str
     */
    AnnotationClassRef(final String typeDescriptorStr) {
        super();
        this.typeDescriptorStr = typeDescriptorStr;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the name of the referenced class.
     *
     * @return The name of the referenced class.
     */
    public String getName() {
        return getClassName();
    }

    /**
     * Get the type signature.
     *
     * @return The type signature of the {@code Class<?>} reference. This will be a {@link ClassRefTypeSignature}, a
     *         {@link BaseTypeSignature}, or an {@link ArrayTypeSignature}.
     */
    private TypeSignature getTypeSignature() {
        var typeSig = typeSignature;
        if (typeSig == null) {
            try {
                // There can't be any type variables to resolve in ClassRefTypeSignature, BaseTypeSignature or
                // ArrayTypeSignature, so just set definingClassName to null
                typeSignature = typeSig = TypeSignature.parse(typeDescriptorStr, /* definingClassName = */ null);
                typeSig.setScanResult(scanResult);
            } catch (final ParseException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return typeSig;
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    protected String getClassName() {
        var name = className;
        if (name == null) {
            final var typeSig = getTypeSignature();
            if (typeSig instanceof final BaseTypeSignature baseTypeSignature) {
                name = baseTypeSignature.getTypeName();
            } else if (typeSig instanceof final ClassRefTypeSignature classRefTypeSignature) {
                name = classRefTypeSignature.getFullyQualifiedClassName();
            } else if (typeSig instanceof final ArrayTypeSignature arrayTypeSignature) {
                name = arrayTypeSignature.getClassName();
            } else {
                throw new IllegalArgumentException("Got unexpected type " + typeSig.getClass().getName()
                        + " for ref type signature: " + typeDescriptorStr);
            }
            className = name;
        }
        return name;
    }

    /**
     * Get the class info.
     *
     * @return The {@link ClassInfo} object for the referenced class, or null if the referenced class was not
     *         encountered during scanning (i.e. if no ClassInfo object was created for the class during scanning).
     */
    @Override
    public @Nullable ClassInfo getClassInfo() {
        return getTypeSignature().getClassInfo();
    }

    @Override
    void setScanResult(final @Nullable ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (typeSignature != null) {
            typeSignature.setScanResult(scanResult);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public int hashCode() {
        return getTypeSignature().hashCode();
    }

    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final AnnotationClassRef other)) {
            return false;
        }
        return getTypeSignature().equals(other.getTypeSignature());
    }

    @Override
    protected void toString(final boolean useSimpleNames, final StringBuilder buf) {
        // This matches Annotation::toString() on JDK 9 and above, which renders a Class-valued annotation
        // parameter in Java source syntax, e.g. "java.lang.String.class", "int.class" or
        // "java.lang.String[].class". (JDK 8 instead rendered the same values as "class java.lang.String",
        // "int" and "class [Ljava.lang.String;", using the "interface" prefix for interfaces and annotations.)
        buf.append(getTypeSignature().toString(useSimpleNames)).append(".class");
    }
}