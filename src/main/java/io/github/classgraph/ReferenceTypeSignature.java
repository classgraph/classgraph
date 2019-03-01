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
import nonapi.io.github.classgraph.types.Parser;

/**
 * A type signature for a reference type. Subclasses are {@link ClassRefOrTypeVariableSignature}
 * ({@link ClassRefTypeSignature} or {@link TypeVariableSignature}), and {@link ArrayTypeSignature}.
 */
public abstract class ReferenceTypeSignature extends TypeSignature {
    /** Constructor. */
    protected ReferenceTypeSignature() {
        super();
    }

    /**
     * Parse a reference type signature.
     * 
     * @param parser
     *            The parser
     * @param definingClassName
     *            The class containing the type descriptor.
     * @return The parsed type reference type signature.
     * @throws ParseException
     *             If the type signature could not be parsed.
     */
    static ReferenceTypeSignature parseReferenceTypeSignature(final Parser parser, final String definingClassName)
            throws ParseException {
        final ClassRefTypeSignature classTypeSignature = ClassRefTypeSignature.parse(parser, definingClassName);
        if (classTypeSignature != null) {
            return classTypeSignature;
        }
        final TypeVariableSignature typeVariableSignature = TypeVariableSignature.parse(parser, definingClassName);
        if (typeVariableSignature != null) {
            return typeVariableSignature;
        }
        final ArrayTypeSignature arrayTypeSignature = ArrayTypeSignature.parse(parser, definingClassName);
        if (arrayTypeSignature != null) {
            return arrayTypeSignature;
        }
        return null;
    }

    /**
     * Parse a class bound.
     * 
     * @param parser
     *            The parser.
     * @param definingClassName
     *            The class containing the type descriptor.
     * @return The parsed class bound.
     * @throws ParseException
     *             If the type signature could not be parsed.
     */
    static ReferenceTypeSignature parseClassBound(final Parser parser, final String definingClassName)
            throws ParseException {
        parser.expect(':');
        // May return null if there is no signature after ':' (class bound signature may be empty)
        return parseReferenceTypeSignature(parser, definingClassName);
    }
}