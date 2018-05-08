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
package io.github.lukehutch.fastclasspathscanner.typesignature;

import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;
import io.github.lukehutch.fastclasspathscanner.typesignature.TypeUtils.ParseException;
import io.github.lukehutch.fastclasspathscanner.typesignature.TypeUtils.ParseState;

/**
 * A type signature for a reference type or base type. Subclasses are ReferenceTypeSignature (ClassTypeSignature,
 * TypeVariableSignature, or ArrayTypeSignature) and BaseTypeSignature.
 */
public abstract class TypeSignature extends HierarchicalTypeSignature {
    /**
     * Instantiate the type signature into a class reference. The ScanResult is used to ensure the correct
     * classloader is used to load the class.
     * 
     * @param scanResult
     *            The scan result.
     * @return The instantiation of the type signature as a {@code Class<?>}.
     */
    public abstract Class<?> instantiate(final ScanResult scanResult);

    /**
     * Compare base types, ignoring generic type parameters.
     * 
     * @param other
     *            the other {@link TypeSignature} to compare to.
     * @return True if the two {@link TypeSignature} objects are equal, ignoring type parameters.
     */
    public abstract boolean equalsIgnoringTypeParams(final TypeSignature other);

    /** Parse a type signature. */
    static TypeSignature parse(final ParseState parseState) throws ParseException {
        final ReferenceTypeSignature referenceTypeSignature = ReferenceTypeSignature
                .parseReferenceTypeSignature(parseState);
        if (referenceTypeSignature != null) {
            return referenceTypeSignature;
        }
        final BaseTypeSignature baseTypeSignature = BaseTypeSignature.parse(parseState);
        if (baseTypeSignature != null) {
            return baseTypeSignature;
        }
        return null;
    }

    /**
     * Parse a type signature.
     * 
     * @param typeDescriptor
     *            The type descriptor or type signature to parse.
     * @return The parsed type descriptor or type signature.
     */
    public static TypeSignature parse(final String typeDescriptor) {
        final ParseState parseState = new ParseState(typeDescriptor);
        TypeSignature typeSignature;
        try {
            typeSignature = TypeSignature.parse(parseState);
            if (typeSignature == null) {
                throw new ParseException();
            }
        } catch (final Exception e) {
            throw new IllegalArgumentException("Type signature could not be parsed: " + parseState, e);
        }
        if (parseState.hasMore()) {
            throw new IllegalArgumentException("Extra characters at end of type descriptor: " + parseState);
        }
        return typeSignature;
    }
}