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
import io.github.lukehutch.fastclasspathscanner.utils.AdditionOrderedSet;

/** A class type signature (called "ClassSignature" in the classfile documentation). */
public class ClassTypeSignature extends HierarchicalTypeSignature {
    /** The class type parameters. */
    final List<TypeParameter> typeParameters;

    /** The superclass type. */
    private final ClassRefTypeSignature superclassSignature;

    /** The superinterface signatures. */
    private final List<ClassRefTypeSignature> superinterfaceSignatures;

    public ClassTypeSignature(final List<TypeParameter> typeParameters,
            final ClassRefTypeSignature superclassSignature,
            final List<ClassRefTypeSignature> superinterfaceSignatures) {
        this.typeParameters = typeParameters;
        this.superclassSignature = superclassSignature;
        this.superinterfaceSignatures = superinterfaceSignatures;
    }

    /** Get the type parameters for the class. */
    public List<TypeParameter> getTypeParameters() {
        return typeParameters;
    }

    /**
     * Get the type signature for the superclass (possibly null in the case of java.lang.Object, since it doesn't
     * have a superclass).
     */
    public ClassRefTypeSignature getSuperclassSignature() {
        return superclassSignature;
    }

    /**
     * Get the type signatures of any superinterfaces
     */
    public List<ClassRefTypeSignature> getSuperinterfaceSignatures() {
        return superinterfaceSignatures;
    }

    @Override
    public void getAllReferencedClassNames(final Set<String> classNameListOut) {
        for (final TypeParameter typeParameter : typeParameters) {
            typeParameter.getAllReferencedClassNames(classNameListOut);
        }
        if (superclassSignature != null) {
            superclassSignature.getAllReferencedClassNames(classNameListOut);
        }
        for (final ClassRefTypeSignature typeSignature : superinterfaceSignatures) {
            typeSignature.getAllReferencedClassNames(classNameListOut);
        }
    }

    @Override
    public int hashCode() {
        return typeParameters.hashCode() + superclassSignature.hashCode() * 7
                + superinterfaceSignatures.hashCode() * 15;
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof ClassTypeSignature)) {
            return false;
        }
        final ClassTypeSignature o = (ClassTypeSignature) obj;
        return o.typeParameters.equals(this.typeParameters)
                && o.superclassSignature.equals(this.superclassSignature)
                && o.superinterfaceSignatures.equals(this.superinterfaceSignatures);
    }

    public String toString(final int modifiers, final boolean isAnnotation, final boolean isInterface,
            final String className) {
        final StringBuilder buf = new StringBuilder();
        if (modifiers != 0) {
            TypeUtils.modifiersToString(modifiers, /* isMethod = */ false, buf);
        }
        if (!typeParameters.isEmpty()) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append('<');
            for (int i = 0; i < typeParameters.size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                buf.append(typeParameters.get(i).toString());
            }
            buf.append('>');
        }
        if (buf.length() > 0) {
            buf.append(' ');
        }
        buf.append(isAnnotation ? "@interface"
                : isInterface ? "interface" : (modifiers & 0x4000) != 0 ? "enum" : "class");
        if (className != null) {
            buf.append(' ');
            buf.append(className);
        }
        if (superclassSignature != null) {
            final String superSig = superclassSignature.toString();
            if (!superSig.equals("java.lang.Object")) {
                buf.append(" extends ");
                buf.append(superSig);
            }
        }
        if (!superinterfaceSignatures.isEmpty()) {
            buf.append(" implements");
            for (int i = 0; i < superinterfaceSignatures.size(); i++) {
                if (i > 0) {
                    buf.append(',');
                }
                buf.append(' ');
                buf.append(superinterfaceSignatures.get(i).toString());
            }
        }
        return buf.toString();
    }

    @Override
    public String toString() {
        return toString(/* modifiers = */ 0, /* isAnnotation = */ false, /* isInterface = */ false,
                /* methodName = */ null);
    }

    /**
     * Merge together two class type signatures (used for combining base classes and auxiliary classes in Scala).
     */
    public static ClassTypeSignature merge(final String className, final ClassTypeSignature classSignature0,
            final ClassTypeSignature classSignature1) {
        ClassRefTypeSignature superclassSig;
        if (classSignature0.superclassSignature == null
                || classSignature0.superclassSignature.className.equals("java.lang.Object")) {
            superclassSig = classSignature1.superclassSignature;
        } else if (classSignature1.superclassSignature == null
                || classSignature1.superclassSignature.className.equals("java.lang.Object")) {
            superclassSig = classSignature0.superclassSignature;
        } else {
            // A class and its auxiliary class have different superclasses. Really should not happen?? 
            throw new IllegalArgumentException(
                    "Class " + className + " and its auxiliary class have different superclasses: "
                            + classSignature0 + " ; " + classSignature1);
        }
        List<ClassRefTypeSignature> allSuperinterfaces;
        if (classSignature0.superinterfaceSignatures.isEmpty()) {
            allSuperinterfaces = classSignature1.superinterfaceSignatures;
        } else if (classSignature1.superinterfaceSignatures.isEmpty()) {
            allSuperinterfaces = classSignature0.superinterfaceSignatures;
        } else {
            final AdditionOrderedSet<ClassRefTypeSignature> superinterfacesUniq = new AdditionOrderedSet<>(
                    classSignature0.superinterfaceSignatures);
            superinterfacesUniq.addAll(classSignature1.superinterfaceSignatures);
            allSuperinterfaces = superinterfacesUniq.toList();
        }
        List<TypeParameter> allTypeParams;
        if (classSignature0.typeParameters.isEmpty()) {
            allTypeParams = classSignature1.typeParameters;
        } else if (classSignature1.typeParameters.isEmpty()) {
            allTypeParams = classSignature0.typeParameters;
        } else {
            final AdditionOrderedSet<TypeParameter> typeParamsUniq = new AdditionOrderedSet<>(
                    classSignature0.typeParameters);
            typeParamsUniq.addAll(classSignature1.typeParameters);
            allTypeParams = typeParamsUniq.toList();
        }
        return new ClassTypeSignature(allTypeParams, superclassSig, allSuperinterfaces);
    }

    /** Parse a class signature. */
    public static ClassTypeSignature parse(final String typeDescriptor) {
        final ParseState parseState = new ParseState(typeDescriptor);
        try {
            final List<TypeParameter> typeParameters = TypeParameter.parseList(parseState);
            final ClassRefTypeSignature superclassSignature = ClassRefTypeSignature.parse(parseState);
            List<ClassRefTypeSignature> superinterfaceSignatures;
            if (parseState.hasMore()) {
                superinterfaceSignatures = new ArrayList<>();
                while (parseState.hasMore()) {
                    final ClassRefTypeSignature superinterfaceSignature = ClassRefTypeSignature.parse(parseState);
                    if (superinterfaceSignature == null) {
                        throw new ParseException();
                    }
                    superinterfaceSignatures.add(superinterfaceSignature);
                }
            } else {
                superinterfaceSignatures = Collections.emptyList();
            }
            if (parseState.hasMore()) {
                throw new IllegalArgumentException("Extra characters at end of type descriptor: " + parseState);
            }
            final ClassTypeSignature classSignature = new ClassTypeSignature(typeParameters, superclassSignature,
                    superinterfaceSignatures);
            // Add back-links from type variable signature to the class signature it is part of
            for (final TypeVariableSignature typeVariableSignature : parseState.getTypeVariableSignatures()) {
                typeVariableSignature.containingClassSignature = classSignature;
            }
            return classSignature;
        } catch (final Exception e) {
            throw new IllegalArgumentException("Type signature could not be parsed: " + parseState, e);
        }
    }
}