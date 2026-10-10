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

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A class type or type variable, i.e. a reference type that is not an array type. Subclasses are
 * {@link ClassRefTypeSignature} and {@link TypeVariableSignature}.
 */
public abstract sealed class ClassRefOrTypeVariableSignature extends ReferenceTypeSignature
        permits ClassRefTypeSignature, TypeVariableSignature {
    /** Constructor. */
    ClassRefOrTypeVariableSignature() {
        super();
    }

    /**
     * Parse the throws suffix at the end of a method type signature, or at the end of a class type signature
     * emitted by the Scala compiler: zero or more {@code '^'} characters, each followed by a class reference type
     * signature or a type variable signature.
     *
     * @param parser
     *            the parser
     * @param definingClassName
     *            the name of the defining class (for resolving type variables), or null if the defining class is
     *            not known
     * @return the thrown types, in the order they appear in, or the empty list if there is no throws suffix
     * @throws TypeSignatureParseException
     *             if a {@code '^'} is followed by neither a class reference type signature nor a type variable
     *             signature
     */
    static List<ClassRefOrTypeVariableSignature> parseThrowsSignatures(final TypeSignatureParser parser,
            final @Nullable String definingClassName) throws TypeSignatureParseException {
        if (parser.peek() != '^') {
            return List.of();
        }
        final List<ClassRefOrTypeVariableSignature> throwsSignatures = new ArrayList<>();
        while (parser.peek() == '^') {
            parser.expect('^');
            final var classTypeSignature = ClassRefTypeSignature.parse(parser, definingClassName);
            if (classTypeSignature != null) {
                throwsSignatures.add(classTypeSignature);
            } else {
                final var typeVariableSignature = TypeVariableSignature.parse(parser, definingClassName);
                if (typeVariableSignature != null) {
                    throwsSignatures.add(typeVariableSignature);
                } else {
                    throw new TypeSignatureParseException(parser, "Missing type variable signature");
                }
            }
        }
        return throwsSignatures;
    }
}
