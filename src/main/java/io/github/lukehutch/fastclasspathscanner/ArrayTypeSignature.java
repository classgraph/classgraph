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

import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.utils.Parser;
import io.github.lukehutch.fastclasspathscanner.utils.Parser.ParseException;

/** An array type. */
public class ArrayTypeSignature extends ReferenceTypeSignature {
    /** The array element type signature. */
    final TypeSignature elementTypeSignature;

    /** The number of array dimensions. */
    final int numDims;

    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (elementTypeSignature != null) {
            elementTypeSignature.setScanResult(scanResult);
        }
    }

    /**
     * @param elementTypeSignature
     *            The type signature of the array elements.
     * @param numDims
     *            The number of array dimensions.
     */
    public ArrayTypeSignature(final TypeSignature elementTypeSignature, final int numDims) {
        this.elementTypeSignature = elementTypeSignature;
        this.numDims = numDims;
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

    @Override
    void getClassNamesFromTypeDescriptors(final Set<String> classNameListOut) {
        elementTypeSignature.getClassNamesFromTypeDescriptors(classNameListOut);
    }

    @Override
    protected String getClassName() {
        // getClassInfo() is not valid for this type, so getClassName() does not need to be implemented
        throw new IllegalArgumentException("getClassName() cannot be called here");
    }

    @Override
    protected ClassInfo getClassInfo() {
        throw new IllegalArgumentException("getClassInfo() cannot be called here");
    }

    @Override
    public int hashCode() {
        return elementTypeSignature.hashCode() + numDims * 15;
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof ArrayTypeSignature)) {
            return false;
        }
        final ArrayTypeSignature o = (ArrayTypeSignature) obj;
        return o.elementTypeSignature.equals(this.elementTypeSignature) && o.numDims == this.numDims;
    }

    @Override
    public boolean equalsIgnoringTypeParams(final TypeSignature other) {
        if (!(other instanceof ArrayTypeSignature)) {
            return false;
        }
        final ArrayTypeSignature o = (ArrayTypeSignature) other;
        return o.elementTypeSignature.equalsIgnoringTypeParams(this.elementTypeSignature)
                && o.numDims == this.numDims;
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        buf.append(elementTypeSignature.toString());
        for (int i = 0; i < numDims; i++) {
            buf.append("[]");
        }
        return buf.toString();
    }

    static ArrayTypeSignature parse(final Parser parser) throws ParseException {
        int numArrayDims = 0;
        while (parser.peek() == '[') {
            numArrayDims++;
            parser.next();
        }
        if (numArrayDims > 0) {
            final TypeSignature elementTypeSignature = TypeSignature.parse(parser);
            if (elementTypeSignature == null) {
                throw new ParseException(parser, "elementTypeSignature == null");
            }
            return new ArrayTypeSignature(elementTypeSignature, numArrayDims);
        } else {
            return null;
        }
    }
}