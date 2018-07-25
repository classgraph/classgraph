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
import io.github.lukehutch.fastclasspathscanner.utils.TypeUtils;

/** A type parameter. */
public class TypeParameter extends HierarchicalTypeSignature {
    /** The type parameter identifier. */
    final String identifier;

    /** Class bound -- may be null */
    final ReferenceTypeSignature classBound;

    /** Interface bounds -- may be empty */
    final List<ReferenceTypeSignature> interfaceBounds;

    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (this.classBound != null) {
            this.classBound.setScanResult(scanResult);
        }
        if (interfaceBounds != null) {
            for (final ReferenceTypeSignature referenceTypeSignature : interfaceBounds) {
                referenceTypeSignature.setScanResult(scanResult);
            }
        }
    }

    /**
     * @param identifier
     *            The type parameter identifier.
     * @param classBound
     *            The type parameter class bound.
     * @param interfaceBounds
     *            The type parameter interface bound.
     */
    public TypeParameter(final String identifier, final ReferenceTypeSignature classBound,
            final List<ReferenceTypeSignature> interfaceBounds) {
        this.identifier = identifier;
        this.classBound = classBound;
        this.interfaceBounds = interfaceBounds;
    }

    /**
     * Get the type parameter identifier.
     * 
     * @return The type parameter identifier.
     */
    public String getIdentifier() {
        return identifier;
    }

    /**
     * Get the type parameter class bound.
     * 
     * @return The type parameter class bound. May be null.
     */
    public ReferenceTypeSignature getClassBound() {
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
    public void getClassNamesFromTypeDescriptors(final Set<String> classNameListOut) {
        if (classBound != null) {
            classBound.getClassNamesFromTypeDescriptors(classNameListOut);
        }
        for (final ReferenceTypeSignature typeSignature : interfaceBounds) {
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
        return identifier.hashCode() + (classBound == null ? 0 : classBound.hashCode() * 7)
                + interfaceBounds.hashCode() * 15;
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof TypeParameter)) {
            return false;
        }
        final TypeParameter o = (TypeParameter) obj;
        return o.identifier.equals(this.identifier)
                && ((o.classBound == null && this.classBound == null)
                        || (o.classBound != null && o.classBound.equals(this.classBound)))
                && o.interfaceBounds.equals(this.interfaceBounds);
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        buf.append(identifier);
        String classBoundStr;
        if (classBound == null) {
            classBoundStr = null;
        } else {
            classBoundStr = classBound.toString();
            if (classBoundStr.equals("java.lang.Object")) {
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
        for (int i = 0; i < interfaceBounds.size(); i++) {
            if (i > 0 || classBoundStr != null) {
                buf.append(" &");
            }
            buf.append(' ');
            buf.append(interfaceBounds.get(i).toString());
        }
        return buf.toString();
    }

    /** Parse a list of {@link TypeParameter}s. */
    static List<TypeParameter> parseList(final Parser parser) throws ParseException {
        if (parser.peek() != '<') {
            return Collections.emptyList();
        }
        parser.expect('<');
        final List<TypeParameter> typeParams = new ArrayList<>(1);
        while (parser.peek() != '>') {
            if (!parser.hasMore()) {
                throw new ParseException(parser, "Missing '>'");
            }
            if (!TypeUtils.getIdentifierToken(parser)) {
                throw new ParseException(parser, "Could not parse identifier token");
            }
            final String identifier = parser.currToken();
            // classBound may be null
            final ReferenceTypeSignature classBound = ReferenceTypeSignature.parseClassBound(parser);
            List<ReferenceTypeSignature> interfaceBounds;
            if (parser.peek() == ':') {
                interfaceBounds = new ArrayList<>();
                while (parser.peek() == ':') {
                    parser.expect(':');
                    final ReferenceTypeSignature interfaceTypeSignature = ReferenceTypeSignature
                            .parseReferenceTypeSignature(parser);
                    if (interfaceTypeSignature == null) {
                        throw new ParseException(parser, "Missing interface type signature");
                    }
                    interfaceBounds.add(interfaceTypeSignature);
                }
            } else {
                interfaceBounds = Collections.emptyList();
            }
            typeParams.add(new TypeParameter(identifier, classBound, interfaceBounds));
        }
        parser.expect('>');
        return typeParams;
    }
}