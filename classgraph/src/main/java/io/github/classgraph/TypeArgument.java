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
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.classgraph.Classfile.TypePathNode;
import io.github.classgraph.base.internal.parser.ParseException;
import io.github.classgraph.base.internal.parser.Parser;
import org.jspecify.annotations.Nullable;

/**
 * A type argument of a parameterized type, e.g. the {@code ? extends Number} in {@code List<? extends Number>}.
 * This corresponds to the {@code TypeArgument} production of the signature grammar in section 4.7.9.1 of the JVM
 * Specification.
 */
public final class TypeArgument extends HierarchicalTypeSignature {
    /** The kind of wildcard bound on a {@link TypeArgument}. */
    public enum Wildcard {
        /** No wildcard: the type argument is a plain type, e.g. the {@code String} in {@code List<String>}. */
        NONE,

        /** The unbounded {@code ?} wildcard, e.g. in {@code List<?>}. */
        ANY,

        /** An upper-bounded wildcard, e.g. the {@code ? extends Number} in {@code List<? extends Number>}. */
        EXTENDS,

        /** A lower-bounded wildcard, e.g. the {@code ? super Integer} in {@code List<? super Integer>}. */
        SUPER
    }

    /** A wildcard type. */
    private final Wildcard wildcard;

    /** Type signature (will be null if wildcard == ANY). */
    private final @Nullable ReferenceTypeSignature typeSignature;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param wildcard
     *            The wildcard type
     * @param typeSignature
     *            The type signature
     */
    private TypeArgument(final Wildcard wildcard, final @Nullable ReferenceTypeSignature typeSignature) {
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
    public @Nullable ReferenceTypeSignature getTypeSignature() {
        return typeSignature;
    }

    /**
     * Substitute type variables in this type argument, using a substitution map built by
     * {@link TypeSignature#resolveTypeVariables(ClassInfo)}.
     *
     * @param substitutions
     *            the substitution map.
     * @return the substituted type argument, or this type argument itself if nothing was substituted.
     */
    // #735
    TypeArgument substituteTypeVariables(final Map<String, TypeArgument> substitutions) {
        final var typeSig = typeSignature;
        if (typeSig == null) {
            // A "?" wildcard has no type signature to substitute into
            return this;
        }
        if (wildcard == Wildcard.NONE && typeSig instanceof final TypeVariableSignature typeVariable) {
            // A type variable in type argument position can be replaced by the substituted type argument as a
            // whole, so that a wildcard type argument keeps its wildcard, e.g. substituting T := "? extends Number"
            // into "List<T>" gives "List<? extends Number>"
            final var substitution = typeVariable.substitution(substitutions);
            if (substitution != null) {
                return substitution;
            }
            return this;
        }
        final var substitutedTypeSignature = typeSig.substituteTypeVariables(substitutions);
        return substitutedTypeSignature != typeSig
                && substitutedTypeSignature instanceof final ReferenceTypeSignature referenceTypeSignature
                        ? new TypeArgument(wildcard, referenceTypeSignature)
                        : this;
    }

    /**
     * Substitute type variables in a list of type arguments.
     *
     * @param typeArguments
     *            the type arguments.
     * @param substitutions
     *            the substitution map.
     * @return the substituted type arguments, or the same list if nothing was substituted.
     */
    static List<TypeArgument> substituteTypeVariables(final List<TypeArgument> typeArguments,
            final Map<String, TypeArgument> substitutions) {
        List<TypeArgument> substituted = null;
        for (var i = 0; i < typeArguments.size(); i++) {
            final var typeArgument = typeArguments.get(i);
            final var substitutedTypeArgument = typeArgument.substituteTypeVariables(substitutions);
            if (substitutedTypeArgument != typeArgument && substituted == null) {
                substituted = new ArrayList<>(typeArguments.subList(0, i));
            }
            if (substituted != null) {
                substituted.add(substitutedTypeArgument);
            }
        }
        return substituted == null ? typeArguments : substituted;
    }

    @Override
    void addTypeAnnotation(final List<TypePathNode> typePath, final AnnotationInfo annotationInfo) {
        if (typePath.isEmpty() && wildcard != Wildcard.NONE) {
            // Annotation before wildcard
            addTypeAnnotation(annotationInfo);
        } else if (!typePath.isEmpty() && typePath.get(0).typePathKind() == 2) {
            // Annotation is on the bound of a wildcard type argument of a parameterized type. TypeSignature can be
            // null in a corrupt classfile (#758).
            final var typeSig = typeSignature;
            if (typeSig != null) {
                typeSig.addTypeAnnotation(typePath.subList(1, typePath.size()), annotationInfo);
            }
        } else {
            // Annotation is on a type argument of a parameterized type. TypeSignature can be null in a corrupt
            // classfile (#758).
            final var typeSig = typeSignature;
            if (typeSig != null) {
                typeSig.addTypeAnnotation(typePath, annotationInfo);
            }
        }
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
    private static TypeArgument parse(final Parser parser, final @Nullable String definingClassName)
            throws ParseException {
        final var peek = parser.peek();
        if (peek == '*') {
            parser.expect('*');
            return new TypeArgument(Wildcard.ANY, null);
        } else if (peek == '+') {
            parser.expect('+');
            final var typeSignature = ReferenceTypeSignature.parseReferenceTypeSignature(parser, definingClassName);
            if (typeSignature == null) {
                throw new ParseException(parser, "Missing '+' type bound");
            }
            return new TypeArgument(Wildcard.EXTENDS, typeSignature);
        } else if (peek == '-') {
            parser.expect('-');
            final var typeSignature = ReferenceTypeSignature.parseReferenceTypeSignature(parser, definingClassName);
            if (typeSignature == null) {
                throw new ParseException(parser, "Missing '-' type bound");
            }
            return new TypeArgument(Wildcard.SUPER, typeSignature);
        } else {
            final var typeSignature = ReferenceTypeSignature.parseReferenceTypeSignature(parser, definingClassName);
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
    static List<TypeArgument> parseList(final Parser parser, final @Nullable String definingClassName)
            throws ParseException {
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
            return List.of();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    protected String getClassName() {
        // getClassInfo() is not valid for this type, so getClassName() does not need to be implemented
        throw new UnsupportedOperationException("getClassName() cannot be called here");
    }

    @Override
    protected ClassInfo getClassInfo() {
        throw new UnsupportedOperationException("getClassInfo() cannot be called here");
    }

    @Override
    void setScanResult(final @Nullable ScanResult scanResult) {
        super.setScanResult(scanResult);
        final var typeSig = this.typeSignature;
        if (typeSig != null) {
            typeSig.setScanResult(scanResult);
        }
    }

    /**
     * Get the names of any classes referenced in the type signature.
     *
     * @param refdClassNames
     *            the referenced class names.
     */
    protected void findReferencedClassNames(final Set<String> refdClassNames) {
        final var typeSig = typeSignature;
        if (typeSig != null) {
            typeSig.findReferencedClassNames(refdClassNames);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public int hashCode() {
        return (typeSignature != null ? typeSignature.hashCode() : 0) + 7 * wildcard.hashCode();
    }

    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final TypeArgument other)) {
            return false;
        }
        return Objects.equals(this.typeAnnotationInfo, other.typeAnnotationInfo)
                && (Objects.equals(this.typeSignature, other.typeSignature) && other.wildcard == this.wildcard);
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    protected void toStringInternal(final boolean useSimpleNames,
            final @Nullable AnnotationInfoList annotationsToExclude, final StringBuilder buf) {
        final var typeAnnotations = typeAnnotationInfo;
        if (typeAnnotations != null) {
            for (final AnnotationInfo annotationInfo : typeAnnotations) {
                if (annotationsToExclude == null || !annotationsToExclude.contains(annotationInfo)) {
                    annotationInfo.toString(useSimpleNames, buf);
                    buf.append(' ');
                }
            }
        }
        switch (wildcard) {
        case ANY -> buf.append('?');
        case EXTENDS -> {
            final var typeSigStr = Objects.requireNonNull(typeSignature).toString(useSimpleNames);
            buf.append("java.lang.Object".equals(typeSigStr) ? "?" : "? extends " + typeSigStr);
        }
        case SUPER -> {
            buf.append("? super ");
            Objects.requireNonNull(typeSignature).toString(useSimpleNames, buf);
        }
        // Wildcard.NONE
        default -> Objects.requireNonNull(typeSignature).toString(useSimpleNames, buf);
        }
    }
}
