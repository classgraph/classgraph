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

/** A type parameter. */
public class TypeParameter extends HierarchicalTypeSignature {
    /** The type parameter identifier. */
    final String identifier;

    /** Class bound -- may be null */
    final ReferenceTypeSignature classBound;

    /** Interface bounds -- may be empty */
    final List<ReferenceTypeSignature> interfaceBounds;

    public TypeParameter(final String identifier, final ReferenceTypeSignature classBound,
            final List<ReferenceTypeSignature> interfaceBounds) {
        this.identifier = identifier;
        this.classBound = classBound;
        this.interfaceBounds = interfaceBounds;
    }

    /** Get the type parameter identifier. */
    public String getIdentifier() {
        return identifier;
    }

    /** Get the class bound, which may be null. */
    public ReferenceTypeSignature getClassBound() {
        return classBound;
    }

    /** Get the interface bound(s), which may be the empty list. */
    public List<ReferenceTypeSignature> getInterfaceBounds() {
        return interfaceBounds;
    }

    @Override
    public void getAllReferencedClassNames(final Set<String> classNameListOut) {
        if (classBound != null) {
            classBound.getAllReferencedClassNames(classNameListOut);
        }
        for (final ReferenceTypeSignature typeSignature : interfaceBounds) {
            typeSignature.getAllReferencedClassNames(classNameListOut);
        }
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

    private static TypeParameter parse(final ParseState parseState) throws ParseException {
        if (!parseState.parseIdentifier()) {
            throw new ParseException();
        }
        final String identifier = parseState.currToken();
        // classBound may be null
        final ReferenceTypeSignature classBound = ReferenceTypeSignature.parseClassBound(parseState);
        List<ReferenceTypeSignature> interfaceBounds;
        if (parseState.peek() == ':') {
            interfaceBounds = new ArrayList<>();
            while (parseState.peek() == ':') {
                parseState.expect(':');
                final ReferenceTypeSignature interfaceTypeSignature = ReferenceTypeSignature
                        .parseReferenceTypeSignature(parseState);
                if (interfaceTypeSignature == null) {
                    throw new ParseException();
                }
                interfaceBounds.add(interfaceTypeSignature);
            }
        } else {
            interfaceBounds = Collections.emptyList();
        }
        return new TypeParameter(identifier, classBound, interfaceBounds);
    }

    static List<TypeParameter> parseList(final ParseState parseState) throws ParseException {
        if (parseState.peek() != '<') {
            return Collections.emptyList();
        }
        parseState.expect('<');
        final List<TypeParameter> typeParams = new ArrayList<>(1);
        while (parseState.peek() != '>') {
            if (!parseState.hasMore()) {
                throw new ParseException();
            }
            typeParams.add(TypeParameter.parse(parseState));
        }
        parseState.expect('>');
        return typeParams;
    }
}