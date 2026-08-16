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
 * A type signature for a reference type. Subclasses are {@link ClassRefOrTypeVariableSignature}
 * ({@link ClassRefTypeSignature} or {@link TypeVariableSignature}), and {@link ArrayTypeSignature}. This
 * corresponds to the {@code ReferenceTypeSignature} production of the signature grammar in section 4.7.9.1 of the
 * JVM Specification.
 */
public abstract class ReferenceTypeSignature extends TypeSignature {
    /** Constructor. */
    ReferenceTypeSignature() {
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
     * @throws TypeSignatureParseException
     *             If the type signature could not be parsed.
     */
    static @Nullable ReferenceTypeSignature parseReferenceTypeSignature(final TypeSignatureParser parser,
            final @Nullable String definingClassName) throws TypeSignatureParseException {
        final var classTypeSignature = ClassRefTypeSignature.parse(parser, definingClassName);
        if (classTypeSignature != null) {
            return classTypeSignature;
        }
        final var typeVariableSignature = TypeVariableSignature.parse(parser, definingClassName);
        if (typeVariableSignature != null) {
            return typeVariableSignature;
        }
        final var arrayTypeSignature = ArrayTypeSignature.parse(parser, definingClassName);
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
     * @throws TypeSignatureParseException
     *             If the type signature could not be parsed.
     */
    static @Nullable ReferenceTypeSignature parseClassBound(final TypeSignatureParser parser,
            final @Nullable String definingClassName) throws TypeSignatureParseException {
        parser.expect(':');
        // May return null if there is no signature after ':' (class bound signature may be empty)
        return parseReferenceTypeSignature(parser, definingClassName);
    }
}
