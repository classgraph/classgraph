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
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.github.classgraph.Classfile.TypePathNode;
import nonapi.io.github.classgraph.types.ParseException;
import nonapi.io.github.classgraph.types.Parser;
import nonapi.io.github.classgraph.types.TypeUtils;
import org.jspecify.annotations.Nullable;

/** A type parameter. */
public final class TypeParameter extends HierarchicalTypeSignature {
    /** The type parameter identifier. */
    final String name;

    /** Class bound -- may be null. */
    final @Nullable ReferenceTypeSignature classBound;

    /** Interface bounds -- may be empty. */
    final List<ReferenceTypeSignature> interfaceBounds;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param identifier
     *            The type parameter identifier.
     * @param classBound
     *            The type parameter class bound.
     * @param interfaceBounds
     *            The type parameter interface bound.
     */
    protected TypeParameter(final String identifier, final @Nullable ReferenceTypeSignature classBound,
            final List<ReferenceTypeSignature> interfaceBounds) {
        super();
        this.name = identifier;
        this.classBound = classBound;
        this.interfaceBounds = interfaceBounds;
    }

    /**
     * Get the type parameter identifier.
     *
     * @return The type parameter identifier.
     */
    public String getName() {
        return name;
    }

    /**
     * Get the type parameter class bound.
     *
     * @return The type parameter class bound. May be null.
     */
    public @Nullable ReferenceTypeSignature getClassBound() {
        return classBound;
    }

    /**
     * Get the type parameter interface bound(s).
     *
     * @return Get the type parameter interface bound(s), which may be the empty list.
     */
    public List<ReferenceTypeSignature> getInterfaceBounds() {
        return interfaceBounds;
    }

    @Override
    void addTypeAnnotation(final List<TypePathNode> typePath, final AnnotationInfo annotationInfo) {
        if (typePath.isEmpty()) {
            addTypeAnnotation(annotationInfo);
        } else {
            throw new IllegalArgumentException("Type parameter should have empty typePath");
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Parse a list of type parameters into {@link TypeParameter} objects.
     *
     * @param parser
     *            the parser
     * @param definingClassName
     *            the defining class name
     * @return the list of {@link TypeParameter} objects.
     * @throws ParseException
     *             if parsing fails
     */
    static List<TypeParameter> parseList(final Parser parser, final @Nullable String definingClassName)
            throws ParseException {
        if (parser.peek() != '<') {
            return List.of();
        }
        parser.expect('<');
        final List<TypeParameter> typeParams = new ArrayList<>(1);
        while (parser.peek() != '>') {
            if (!parser.hasMore()) {
                throw new ParseException(parser, "Missing '>'");
            }
            // Scala can contain '$' in type parameter names (#495)
            if (!TypeUtils.getIdentifierToken(parser, /* stopAtDollarSign = */ false, /* stopAtDot = */ true)) {
                throw new ParseException(parser, "Could not parse identifier token");
            }
            final var identifier = parser.currToken();
            // classBound may be null
            final var classBound = ReferenceTypeSignature.parseClassBound(parser, definingClassName);
            List<ReferenceTypeSignature> interfaceBounds;
            if (parser.peek() == ':') {
                interfaceBounds = new ArrayList<>();
                while (parser.peek() == ':') {
                    parser.expect(':');
                    final var interfaceTypeSignature = ReferenceTypeSignature.parseReferenceTypeSignature(parser,
                            definingClassName);
                    if (interfaceTypeSignature == null) {
                        throw new ParseException(parser, "Missing interface type signature");
                    }
                    interfaceBounds.add(interfaceTypeSignature);
                }
            } else {
                interfaceBounds = List.of();
            }
            typeParams.add(new TypeParameter(identifier, classBound, interfaceBounds));
        }
        parser.expect('>');
        return typeParams;
    }

    // -------------------------------------------------------------------------------------------------------------

    /*
     * (non-Javadoc)
     *
     * @see io.github.classgraph.ScanResultObject#getClassName()
     */
    @Override
    protected String getClassName() {
        // getClassInfo() is not valid for this type, so getClassName() does not need to be implemented
        throw new UnsupportedOperationException("getClassName() cannot be called here");
    }

    /*
     * (non-Javadoc)
     *
     * @see io.github.classgraph.ScanResultObject#getClassInfo()
     */
    @Override
    protected ClassInfo getClassInfo() {
        throw new UnsupportedOperationException("getClassInfo() cannot be called here");
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * io.github.classgraph.ScanResultObject#setScanResult(io.github.classgraph.
     * ScanResult)
     */
    @Override
    void setScanResult(final @Nullable ScanResult scanResult) {
        super.setScanResult(scanResult);
        final var bound = this.classBound;
        if (bound != null) {
            bound.setScanResult(scanResult);
        }
        if (interfaceBounds != null) {
            for (final ReferenceTypeSignature referenceTypeSignature : interfaceBounds) {
                referenceTypeSignature.setScanResult(scanResult);
            }
        }
    }

    /**
     * Get the names of any classes referenced in the type signature.
     *
     * @param refdClassNames
     *            the referenced class names.
     */
    protected void findReferencedClassNames(final Set<String> refdClassNames) {
        final var bound = classBound;
        if (bound != null) {
            bound.findReferencedClassNames(refdClassNames);
        }
        for (final ReferenceTypeSignature typeSignature : interfaceBounds) {
            typeSignature.findReferencedClassNames(refdClassNames);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return name.hashCode() + (classBound == null ? 0 : classBound.hashCode() * 7)
                + interfaceBounds.hashCode() * 15;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final TypeParameter other)) {
            return false;
        }
        return other.name.equals(this.name) && Objects.equals(other.typeAnnotationInfo, this.typeAnnotationInfo)
                && ((other.classBound == null && this.classBound == null)
                        || (other.classBound != null && other.classBound.equals(this.classBound)))
                && other.interfaceBounds.equals(this.interfaceBounds);
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
        buf.append(useSimpleNames ? ClassInfo.getSimpleName(name) : name);
        final var bound = classBound;
        String classBoundStr;
        if (bound == null) {
            classBoundStr = null;
        } else {
            classBoundStr = bound.toString(useSimpleNames);
            // A type parameter may itself be named "Object", in which case a bound referring to it also renders as
            // "Object" but is a TypeVariableSignature, not java.lang.Object -- so check the type too
            if ("java.lang.Object".equals(classBoundStr) || ("Object".equals(classBoundStr)
                    && bound instanceof final ClassRefTypeSignature classRefTypeSignature
                    && "java.lang.Object".equals(classRefTypeSignature.className))) {
                // Don't add "extends java.lang.Object"
                classBoundStr = null;
            }
        }
        if (classBoundStr != null || !interfaceBounds.isEmpty()) {
            buf.append(" extends");
        }
        if (classBoundStr != null) {
            buf.append(' ');
            buf.append(classBoundStr);
        }
        for (var i = 0; i < interfaceBounds.size(); i++) {
            if (i > 0 || classBoundStr != null) {
                buf.append(" &");
            }
            buf.append(' ');
            interfaceBounds.get(i).toString(useSimpleNames, buf);
        }
    }
}