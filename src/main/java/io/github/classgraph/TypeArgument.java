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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import nonapi.io.github.classgraph.types.ParseException;
import nonapi.io.github.classgraph.types.Parser;

/** A type argument. */
public final class TypeArgument extends HierarchicalTypeSignature {
    /** A type wildcard. */
    public enum Wildcard {
        /** No wildcard. */
        NONE,

        /** The '?' wildcard. */
        ANY,

        /** extends. */
        EXTENDS,

        /** super. */
        SUPER
    }

    /** A wildcard type. */
    private final Wildcard wildcard;

    /** Type signature (will be null if wildcard == ANY). */
    private final ReferenceTypeSignature typeSignature;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param wildcard
     *            The wildcard type
     * @param typeSignature
     *            The type signature
     */
    private TypeArgument(final Wildcard wildcard, final ReferenceTypeSignature typeSignature) {
        super();
        this.wildcard = wildcard;
        this.typeSignature = typeSignature;
    }

    // -------------------------------------------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Parse a type argument.
     * 
     * @param parser
     *            The parser.
     * @param definingClassName
     *            The name of the defining class (for resolving type variables).
     * @return The parsed method type signature.
     * @throws ParseException
     *             If method type signature could not be parsed.
     */
    private static TypeArgument parse(final Parser parser, final String definingClassName) throws ParseException {
        final char peek = parser.peek();
        if (peek == '*') {
            parser.expect('*');
            return new TypeArgument(Wildcard.ANY, null);
        } else if (peek == '+') {
            parser.expect('+');
            final ReferenceTypeSignature typeSignature = ReferenceTypeSignature.parseReferenceTypeSignature(parser,
                    definingClassName);
            if (typeSignature == null) {
                throw new ParseException(parser, "Missing '+' type bound");
            }
            return new TypeArgument(Wildcard.EXTENDS, typeSignature);
        } else if (peek == '-') {
            parser.expect('-');
            final ReferenceTypeSignature typeSignature = ReferenceTypeSignature.parseReferenceTypeSignature(parser,
                    definingClassName);
            if (typeSignature == null) {
                throw new ParseException(parser, "Missing '-' type bound");
            }
            return new TypeArgument(Wildcard.SUPER, typeSignature);
        } else {
            final ReferenceTypeSignature typeSignature = ReferenceTypeSignature.parseReferenceTypeSignature(parser,
                    definingClassName);
            if (typeSignature == null) {
                throw new ParseException(parser, "Missing type bound");
            }
            return new TypeArgument(Wildcard.NONE, typeSignature);
        }
    }

    /**
     * Parse a list of type arguments.
     * 
     * @param parser
     *            The parser.
     * @param definingClassName
     *            The name of the defining class (for resolving type variables).
     * @return The list of type arguments.
     * @throws ParseException
     *             If type signature could not be parsed.
     */
    static List<TypeArgument> parseList(final Parser parser, final String definingClassName) throws ParseException {
        if (parser.peek() == '<') {
            parser.expect('<');
            final List<TypeArgument> typeArguments = new ArrayList<>(2);
            while (parser.peek() != '>') {
                if (!parser.hasMore()) {
                    throw new ParseException(parser, "Missing '>'");
                }
                typeArguments.add(parse(parser, definingClassName));
            }
            parser.expect('>');
            return typeArguments;
        } else {
            return Collections.emptyList();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#getClassName()
     */
    @Override
    protected String getClassName() {
        // getClassInfo() is not valid for this type, so getClassName() does not need to be implemented
        throw new IllegalArgumentException("getClassName() cannot be called here");
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#getClassInfo()
     */
    @Override
    protected ClassInfo getClassInfo() {
        throw new IllegalArgumentException("getClassInfo() cannot be called here");
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#setScanResult(io.github.classgraph.ScanResult)
     */
    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (this.typeSignature != null) {
            this.typeSignature.setScanResult(scanResult);
        }
    }

    /**
     * Get the names of any classes referenced in the type signature.
     *
     * @param refdClassNames
     *            the referenced class names.
     */
    public void findReferencedClassNames(final Set<String> refdClassNames) {
        if (typeSignature != null) {
            typeSignature.findReferencedClassNames(refdClassNames);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return typeSignature.hashCode() + 7 * wildcard.hashCode();
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof TypeArgument)) {
            return false;
        }
        final TypeArgument o = (TypeArgument) obj;
        return (o.typeSignature.equals(this.typeSignature) && o.wildcard.equals(this.wildcard));
    }

    /**
     * {@link #toString()} internal method.
     *
     * @param useSimpleNames
     *            whether to use simple names for classes.
     * @return the string
     */
    private String toStringInternal(final boolean useSimpleNames) {
        switch (wildcard) {
        case ANY:
            return "?";
        case EXTENDS:
            final String typeSigStr = typeSignature.toString();
            return typeSigStr.equals("java.lang.Object") ? "?" : "? extends " + typeSigStr;
        case SUPER:
            return "? super " + typeSignature.toString();
        case NONE:
            return useSimpleNames ? typeSignature.toStringWithSimpleNames() : typeSignature.toString();
        default:
            // Should not happen
            throw ClassGraphException.newClassGraphException("Unknown wildcard type " + wildcard);
        }
    }

    /**
     * {@link #toString()} with simple names for classes.
     *
     * @return the string
     */
    public String toStringWithSimpleNames() {
        return toStringInternal(true);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return toStringInternal(false);
    }
}
