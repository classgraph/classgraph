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

import java.lang.reflect.Array;
import java.util.Set;

import nonapi.io.github.classgraph.types.ParseException;
import nonapi.io.github.classgraph.types.Parser;

/** An array type signature. */
public class ArrayTypeSignature extends ReferenceTypeSignature {
    /** The array element type signature. */
    private final TypeSignature elementTypeSignature;

    /** The number of array dimensions. */
    private final int numDims;

    /** The raw type signature string for the array type. */
    private final String typeSignatureStr;

    /** Human-readable class name, e.g. "java.lang.String[]". */
    private String className;

    /** Array class info. */
    private ArrayClassInfo arrayClassInfo;

    /** The element class. */
    private Class<?> elementClassRef;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param elementTypeSignature
     *            The type signature of the array elements.
     * @param numDims
     *            The number of array dimensions.
     * @param typeSignatureStr
     *            Raw array type signature string (e.g. "[[I")
     */
    ArrayTypeSignature(final TypeSignature elementTypeSignature, final int numDims, final String typeSignatureStr) {
        super();
        this.elementTypeSignature = elementTypeSignature;
        this.numDims = numDims;
        this.typeSignatureStr = typeSignatureStr;
    }

    /**
     * Constructor.
     *
     * @param eltClassName
     *            The type signature of the array elements.
     * @param numDims
     *            The number of array dimensions.
     */
    ArrayTypeSignature(final String eltClassName, final int numDims) {
        super();
        final BaseTypeSignature baseTypeSignature = BaseTypeSignature.getTypeSignature(eltClassName);
        String eltTypeSigStr;
        if (baseTypeSignature != null) {
            // Element type is a base (primitive) type
            eltTypeSigStr = baseTypeSignature.getTypeSignatureChar();
            this.elementTypeSignature = baseTypeSignature;
        } else {
            // Element type is not a base (primitive) type -- create a type signature for element type
            eltTypeSigStr = "L" + eltClassName.replace('.', '/') + ";";
            try {
                this.elementTypeSignature = ClassRefTypeSignature.parse(new Parser(eltTypeSigStr),
                        // No type variables to resolve for generic types
                        /* definingClassName = */ null);
                if (this.elementTypeSignature == null) {
                    throw new IllegalArgumentException(
                            "Could not form array base type signature for class " + eltClassName);
                }
            } catch (final ParseException e) {
                throw new IllegalArgumentException(
                        "Could not form array base type signature for class " + eltClassName);
            }
        }
        final StringBuilder buf = new StringBuilder(numDims + eltTypeSigStr.length());
        for (int i = 0; i < numDims; i++) {
            buf.append('[');
        }
        buf.append(eltTypeSigStr);
        this.typeSignatureStr = buf.toString();
        this.numDims = numDims;
    }

    /**
     * Get the raw array type signature string, e.g. "[[I".
     * 
     * @return the raw array type signature string.
     */
    public String getTypeSignatureStr() {
        return typeSignatureStr;
    }

    /**
     * Get the type signature of the array elements.
     *
     * @return The type signature of the array elements.
     */
    public TypeSignature getElementTypeSignature() {
        return elementTypeSignature;
    }

    /**
     * Get the number of dimensions of the array.
     *
     * @return The number of dimensions of the array.
     */
    public int getNumDimensions() {
        return numDims;
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#getClassName()
     */
    @Override
    protected String getClassName() {
        if (className == null) {
            className = toStringInternal(/* useSimpleNames = */ false);
        }
        return className;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#getClassInfo()
     */
    @Override
    protected ClassInfo getClassInfo() {
        return getArrayClassInfo();
    }

    /**
     * Return an {@link ArrayClassInfo} instance for the array class, cast to its superclass.
     *
     * @return the {@link ArrayClassInfo} instance.
     */
    public ArrayClassInfo getArrayClassInfo() {
        if (arrayClassInfo == null) {
            if (scanResult != null) {
                final String clsName = getClassName();
                // Cache ArrayClassInfo instances using scanResult.classNameToClassInfo, if scanResult is available
                arrayClassInfo = (ArrayClassInfo) scanResult.classNameToClassInfo.get(clsName);
                if (arrayClassInfo == null) {
                    scanResult.classNameToClassInfo.put(clsName, arrayClassInfo = new ArrayClassInfo(this));
                    arrayClassInfo.setScanResult(this.scanResult);
                }
            } else {
                // scanResult is not yet available, create an uncached instance of an ArrayClassInfo for this type
                arrayClassInfo = new ArrayClassInfo(this);
            }
        }
        return arrayClassInfo;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#setScanResult(io.github.classgraph.ScanResult)
     */
    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (elementTypeSignature != null) {
            elementTypeSignature.setScanResult(scanResult);
        }
        if (arrayClassInfo != null) {
            arrayClassInfo.setScanResult(scanResult);
        }
    }

    /**
     * Get the names of any classes referenced in the type signature.
     *
     * @param refdClassNames
     *            the referenced class names.
     */
    @Override
    protected void findReferencedClassNames(final Set<String> refdClassNames) {
        elementTypeSignature.findReferencedClassNames(refdClassNames);
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
        if (elementClassRef == null) {
            // Try resolving element type against base types (int, etc.)
            if (elementTypeSignature instanceof BaseTypeSignature) {
                elementClassRef = ((BaseTypeSignature) elementTypeSignature).getType();
            } else {
                if (scanResult != null) {
                    elementClassRef = elementTypeSignature.loadClass(ignoreExceptions);
                } else {
                    // Fallback, if scanResult is not set
                    final String elementTypeName = ((ClassRefTypeSignature) elementTypeSignature)
                            .getFullyQualifiedClassName();
                    try {
                        elementClassRef = Class.forName(elementTypeName);
                    } catch (final Throwable t) {
                        if (!ignoreExceptions) {
                            throw new IllegalArgumentException(
                                    "Could not load array element class " + elementTypeName, t);
                        }
                    }
                }
            }
        }
        return elementClassRef;
    }

    /**
     * Get a {@code Class<?>} reference for the array element type. Causes the ClassLoader to load the element
     * class, if it is not already loaded.
     *
     * @return a {@code Class<?>} reference for the array element type. Also works for arrays of primitive element
     *         type.
     */
    public Class<?> loadElementClass() {
        return loadElementClass(/* ignoreExceptions = */ false);
    }

    /**
     * Obtain a {@code Class<?>} reference for the array class named by this {@link ArrayClassInfo} object. Causes
     * the ClassLoader to load the element class, if it is not already loaded.
     *
     * @param ignoreExceptions
     *            Whether or not to ignore exceptions.
     * @return The class reference, or null, if ignoreExceptions is true and there was an exception or error loading
     *         the class.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false and there were problems loading the class.
     */
    @Override
    public Class<?> loadClass(final boolean ignoreExceptions) {
        if (classRef == null) {
            // Get the element type
            Class<?> eltClassRef = null;
            if (ignoreExceptions) {
                try {
                    eltClassRef = loadElementClass();
                } catch (final IllegalArgumentException e) {
                    return null;
                }
            } else {
                eltClassRef = loadElementClass();
            }
            if (eltClassRef == null) {
                throw new IllegalArgumentException("Could not load array element class " + elementTypeSignature);
            }
            // Create an array of the target number of dimensions, with size zero in each dimension
            final Object eltArrayInstance = Array.newInstance(eltClassRef, new int[numDims]);
            // Get the class reference from the array instance
            classRef = eltArrayInstance.getClass();
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
        return loadClass(/* ignoreExceptions = */ false);
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return elementTypeSignature.hashCode() + numDims * 15;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof ArrayTypeSignature)) {
            return false;
        }
        final ArrayTypeSignature other = (ArrayTypeSignature) obj;
        return other.elementTypeSignature.equals(this.elementTypeSignature) && other.numDims == this.numDims;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.TypeSignature#equalsIgnoringTypeParams(io.github.classgraph.TypeSignature)
     */
    @Override
    public boolean equalsIgnoringTypeParams(final TypeSignature other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ArrayTypeSignature)) {
            return false;
        }
        final ArrayTypeSignature o = (ArrayTypeSignature) other;
        return o.elementTypeSignature.equalsIgnoringTypeParams(this.elementTypeSignature)
                && o.numDims == this.numDims;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.TypeSignature#toStringInternal(boolean)
     */
    @Override
    protected String toStringInternal(final boolean useSimpleNames) {
        final StringBuilder buf = new StringBuilder();
        buf.append(
                useSimpleNames ? elementTypeSignature.toStringWithSimpleNames() : elementTypeSignature.toString());
        for (int i = 0; i < numDims; i++) {
            buf.append("[]");
        }
        return buf.toString();
    }

    /**
     * Parses the array type signature.
     *
     * @param parser
     *            the parser
     * @param definingClassName
     *            the defining class name
     * @return the array type signature
     * @throws ParseException
     *             if parsing fails
     */
    static ArrayTypeSignature parse(final Parser parser, final String definingClassName) throws ParseException {
        int numArrayDims = 0;
        final int begin = parser.getPosition();
        while (parser.peek() == '[') {
            numArrayDims++;
            parser.next();
        }
        if (numArrayDims > 0) {
            final TypeSignature elementTypeSignature = TypeSignature.parse(parser, definingClassName);
            if (elementTypeSignature == null) {
                throw new ParseException(parser, "elementTypeSignature == null");
            }
            final String typeSignatureStr = parser.getSubsequence(begin, parser.getPosition()).toString();
            return new ArrayTypeSignature(elementTypeSignature, numArrayDims, typeSignatureStr);
        } else {
            return null;
        }
    }
}