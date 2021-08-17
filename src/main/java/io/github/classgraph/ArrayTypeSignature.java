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
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.github.classgraph.Classfile.TypePathNode;
import nonapi.io.github.classgraph.types.ParseException;
import nonapi.io.github.classgraph.types.Parser;

/** An array type signature. */
public class ArrayTypeSignature extends ReferenceTypeSignature {
    /** The raw type signature string for the array type. */
    private final String typeSignatureStr;

    /** Human-readable class name, e.g. "java.lang.String[]". */
    private String className;

    /** Array class info. */
    private ArrayClassInfo arrayClassInfo;

    /** The element class. */
    private Class<?> elementClassRef;

    /** The nested type (another {@link ArrayTypeSignature}, or the base element type). */
    private final TypeSignature nestedType;

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
        final boolean typeSigHasTwoOrMoreDims = typeSignatureStr.startsWith("[[");
        if (numDims < 1) {
            throw new IllegalArgumentException("numDims < 1");
        } else if ((numDims >= 2) != typeSigHasTwoOrMoreDims) {
            throw new IllegalArgumentException("numDims does not match type signature");
        }
        this.typeSignatureStr = typeSignatureStr;
        this.nestedType = typeSigHasTwoOrMoreDims
                // Strip one array dimension for nested type
                ? new ArrayTypeSignature(elementTypeSignature, numDims - 1, typeSignatureStr.substring(1))
                // Nested type for innermost dimension is element type 
                : elementTypeSignature;
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
     * Get the type signature of the innermost element type of the array.
     *
     * @return The type signature of the innermost element type.
     */
    public TypeSignature getElementTypeSignature() {
        ArrayTypeSignature curr = this;
        while (curr.nestedType instanceof ArrayTypeSignature) {
            curr = (ArrayTypeSignature) curr.nestedType;
        }
        return curr.getNestedType();
    }

    /**
     * Get the number of dimensions of the array.
     *
     * @return The number of dimensions of the array.
     */
    public int getNumDimensions() {
        int numDims = 1;
        ArrayTypeSignature curr = this;
        while (curr.nestedType instanceof ArrayTypeSignature) {
            curr = (ArrayTypeSignature) curr.nestedType;
            numDims++;
        }
        return numDims;
    }

    /**
     * Get the nested type, which is another {@link ArrayTypeSignature} with one dimension fewer, if this array has
     * 2 or more dimensions, otherwise this returns the element type.
     * 
     * @return The nested type.
     */
    public TypeSignature getNestedType() {
        return nestedType;
    }

    @Override
    protected void addTypeAnnotation(final List<TypePathNode> typePath, final AnnotationInfo annotationInfo) {
        if (typePath.isEmpty()) {
            addTypeAnnotation(annotationInfo);
        } else {
            final TypePathNode head = typePath.get(0);
            if (head.typePathKind != 0 || head.typeArgumentIdx != 0) {
                throw new IllegalArgumentException("typePath element contains bad values: " + head);
            }
            nestedType.addTypeAnnotation(typePath.subList(1, typePath.size()), annotationInfo);
        }
    }

    /**
     * Get a list of {@link AnnotationInfo} objects for the type annotations on this array type, or null if none.
     * 
     * @see #getNestedType() if you want to read for type annotations on inner (nested) dimensions of the array
     *      type.
     * @return a list of {@link AnnotationInfo} objects for the type annotations of on this array type, or null if
     *         none.
     */
    @Override
    public AnnotationInfoList getTypeAnnotationInfo() {
        return typeAnnotationInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#getClassName()
     */
    @Override
    protected String getClassName() {
        if (className == null) {
            className = toString();
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
        nestedType.setScanResult(scanResult);
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
        nestedType.findReferencedClassNames(refdClassNames);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get a {@code Class<?>} reference for the innermost array element type. Causes the ClassLoader to load the
     * class, if it is not already loaded.
     *
     * @param ignoreExceptions
     *            Whether or not to ignore exceptions.
     * @return a {@code Class<?>} reference for the innermost array element type. Also works for arrays of primitive
     *         element type.
     */
    public Class<?> loadElementClass(final boolean ignoreExceptions) {
        if (elementClassRef == null) {
            // Try resolving element type against base types (int, etc.)
            final TypeSignature elementTypeSignature = getElementTypeSignature();
            if (elementTypeSignature instanceof BaseTypeSignature) {
                elementClassRef = ((BaseTypeSignature) elementTypeSignature).getType();
            } else {
                if (scanResult != null) {
                    elementClassRef = elementTypeSignature.loadClass(ignoreExceptions);
                } else {
                    // Fallback, if scanResult is not set
                    final String elementTypeName = elementTypeSignature.getClassName();
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
                throw new IllegalArgumentException(
                        "Could not load array element class " + getElementTypeSignature());
            }
            // Create an array of the target number of dimensions, with size zero in each dimension
            final Object eltArrayInstance = Array.newInstance(eltClassRef, new int[getNumDimensions()]);
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
        return 1 + nestedType.hashCode();
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
        return Objects.equals(this.typeAnnotationInfo, other.typeAnnotationInfo)
                && this.nestedType.equals(other.nestedType);
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
        return this.nestedType.equalsIgnoringTypeParams(o.nestedType);
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    protected void toStringInternal(final boolean useSimpleNames, final AnnotationInfoList annotationsToExclude,
            final StringBuilder buf) {
        // Start with innermost array element type
        getElementTypeSignature().toStringInternal(useSimpleNames, annotationsToExclude, buf);

        // Append array dimensions
        for (ArrayTypeSignature curr = this;;) {
            if (curr.typeAnnotationInfo != null && !curr.typeAnnotationInfo.isEmpty()) {
                for (final AnnotationInfo annotationInfo : curr.typeAnnotationInfo) {
                    if (buf.length() == 0 || buf.charAt(buf.length() - 1) != ' ') {
                        buf.append(' ');
                    }
                    annotationInfo.toString(useSimpleNames, buf);
                }
                buf.append(' ');
            }

            buf.append("[]");

            if (curr.nestedType instanceof ArrayTypeSignature) {
                curr = (ArrayTypeSignature) curr.nestedType;
            } else {
                break;
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

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