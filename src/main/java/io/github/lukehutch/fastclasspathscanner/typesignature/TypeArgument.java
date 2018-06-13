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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.typesignature.TypeUtils.ParseException;
import io.github.lukehutch.fastclasspathscanner.typesignature.TypeUtils.ParseState;

/** A type argument. */
public class TypeArgument extends HierarchicalTypeSignature {
    /** A type wildcard. */
    public static enum WILDCARD {
    /** No wildcard. */
    NONE,
    /** The '?' wildcard */
    ANY,
    /** extends */
    EXTENDS,
    /** super */
    SUPER
    };

    /** A wildcard type. */
    private final TypeArgument.WILDCARD wildcard;

    /** Type signature (will be null if wildcard == ANY). */
    private final ReferenceTypeSignature typeSignature;

    /**
     * @param wildcard
     *            The wildcard type
     * @param typeSignature
     *            The type signature
     */
    public TypeArgument(final TypeArgument.WILDCARD wildcard, final ReferenceTypeSignature typeSignature) {
        this.wildcard = wildcard;
        this.typeSignature = typeSignature;
    }

    /**
     * Get the type wildcard, which is one of {NONE, ANY, EXTENDS, SUPER}.
     * 
     * @return The type wildcard.
     */
    public TypeArgument.WILDCARD getWildcard() {
        return wildcard;
    }

    /**
     * Get the type signature associated with the wildcard (or null, if the wildcard is ANY).
     * 
     * @return The type signature.
     */
    public ReferenceTypeSignature getTypeSignature() {
        return typeSignature;
    }

    @Override
    public void getAllReferencedClassNames(final Set<String> classNameListOut) {
        if (typeSignature != null) {
            typeSignature.getAllReferencedClassNames(classNameListOut);
        }
    }

    @Override
    public int hashCode() {
        return typeSignature.hashCode() + 7 * wildcard.hashCode();
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof TypeArgument)) {
            return false;
        }
        final TypeArgument o = (TypeArgument) obj;
        return (o.typeSignature.equals(this.typeSignature) && o.wildcard.equals(this.wildcard));
    }

    @Override
    public String toString() {
        final String typeSigStr = typeSignature == null ? null : typeSignature.toString();
        switch (wildcard) {
        case ANY:
            return "?";
        case EXTENDS:
            return typeSigStr.equals("java.lang.Object") ? "?" : "? extends " + typeSigStr;
        case SUPER:
            return "? super " + typeSigStr;
        case NONE:
            return typeSigStr;
        default:
            throw new RuntimeException("Unknown wildcard type");
        }
    }

    /** Parse a type argument. */
    static TypeArgument parse(final ParseState parseState) throws ParseException {
        final char peek = parseState.peek();
        if (peek == '*') {
            parseState.expect('*');
            return new TypeArgument(WILDCARD.ANY, null);
        } else if (peek == '+') {
            parseState.expect('+');
            final ReferenceTypeSignature typeSignature = ReferenceTypeSignature
                    .parseReferenceTypeSignature(parseState);
            if (typeSignature == null) {
                throw new ParseException();
            }
            return new TypeArgument(WILDCARD.EXTENDS, typeSignature);
        } else if (peek == '-') {
            parseState.expect('-');
            final ReferenceTypeSignature typeSignature = ReferenceTypeSignature
                    .parseReferenceTypeSignature(parseState);
            if (typeSignature == null) {
                throw new ParseException();
            }
            return new TypeArgument(WILDCARD.SUPER, typeSignature);
        } else {
            final ReferenceTypeSignature typeSignature = ReferenceTypeSignature
                    .parseReferenceTypeSignature(parseState);
            if (typeSignature == null) {
                throw new ParseException();
            }
            return new TypeArgument(WILDCARD.NONE, typeSignature);
        }
    }

    /** Parse a list of type arguments. */
    static List<TypeArgument> parseList(final ParseState parseState) throws ParseException {
        if (parseState.peek() == '<') {
            parseState.expect('<');
            final List<TypeArgument> typeArguments = new ArrayList<>(2);
            while (parseState.peek() != '>') {
                if (!parseState.hasMore()) {
                    throw new ParseException();
                }
                typeArguments.add(parse(parseState));
            }
            parseState.expect('>');
            return typeArguments;
        } else {
            return Collections.emptyList();
        }
    }
}