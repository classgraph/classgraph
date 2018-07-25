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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.utils.Parser;
import io.github.lukehutch.fastclasspathscanner.utils.Parser.ParseException;

/** A type argument. */
public class TypeArgument extends HierarchicalTypeSignature {
    /** A type wildcard. */
    public static enum Wildcard {
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
    private final Wildcard wildcard;

    /** Type signature (will be null if wildcard == ANY). */
    private final ReferenceTypeSignature typeSignature;

    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (this.typeSignature != null) {
            this.typeSignature.setScanResult(scanResult);
        }
    }

    /**
     * @param wildcard
     *            The wildcard type
     * @param typeSignature
     *            The type signature
     */
    public TypeArgument(final Wildcard wildcard, final ReferenceTypeSignature typeSignature) {
        this.wildcard = wildcard;
        this.typeSignature = typeSignature;
    }

    /**
     * Get the type wildcard, which is one of {NONE, ANY, EXTENDS, SUPER}.
     * 
     * @return The type wildcard.
     */
    public Wildcard getWildcard() {
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
    public void getClassNamesFromTypeDescriptors(final Set<String> classNameListOut) {
        if (typeSignature != null) {
            typeSignature.getClassNamesFromTypeDescriptors(classNameListOut);
        }
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
    static TypeArgument parse(final Parser parser) throws ParseException {
        final char peek = parser.peek();
        if (peek == '*') {
            parser.expect('*');
            return new TypeArgument(Wildcard.ANY, null);
        } else if (peek == '+') {
            parser.expect('+');
            final ReferenceTypeSignature typeSignature = ReferenceTypeSignature.parseReferenceTypeSignature(parser);
            if (typeSignature == null) {
                throw new ParseException(parser, "Missing '+' type bound");
            }
            return new TypeArgument(Wildcard.EXTENDS, typeSignature);
        } else if (peek == '-') {
            parser.expect('-');
            final ReferenceTypeSignature typeSignature = ReferenceTypeSignature.parseReferenceTypeSignature(parser);
            if (typeSignature == null) {
                throw new ParseException(parser, "Missing '-' type bound");
            }
            return new TypeArgument(Wildcard.SUPER, typeSignature);
        } else {
            final ReferenceTypeSignature typeSignature = ReferenceTypeSignature.parseReferenceTypeSignature(parser);
            if (typeSignature == null) {
                throw new ParseException(parser, "Missing type bound");
            }
            return new TypeArgument(Wildcard.NONE, typeSignature);
        }
    }

    /** Parse a list of type arguments. */
    static List<TypeArgument> parseList(final Parser parser) throws ParseException {
        if (parser.peek() == '<') {
            parser.expect('<');
            final List<TypeArgument> typeArguments = new ArrayList<>(2);
            while (parser.peek() != '>') {
                if (!parser.hasMore()) {
                    throw new ParseException(parser, "Missing '>'");
                }
                typeArguments.add(parse(parser));
            }
            parser.expect('>');
            return typeArguments;
        } else {
            return Collections.emptyList();
        }
    }
}