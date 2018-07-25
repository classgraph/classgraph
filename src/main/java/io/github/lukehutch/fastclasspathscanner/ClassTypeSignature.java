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

/** A class type signature (called "ClassSignature" in the classfile documentation). */
public class ClassTypeSignature extends HierarchicalTypeSignature {
    /** The class type parameters. */
    final List<TypeParameter> typeParameters;

    /** The superclass type. */
    private final ClassRefTypeSignature superclassSignature;

    /** The superinterface signatures. */
    private final List<ClassRefTypeSignature> superinterfaceSignatures;

    /**
     * @param typeParameters
     *            The class type parameters.
     * @param superclassSignature
     *            The superclass signature.
     * @param superinterfaceSignatures
     *            The superinterface signature(s).
     */
    public ClassTypeSignature(final List<TypeParameter> typeParameters,
            final ClassRefTypeSignature superclassSignature,
            final List<ClassRefTypeSignature> superinterfaceSignatures) {
        this.typeParameters = typeParameters;
        this.superclassSignature = superclassSignature;
        this.superinterfaceSignatures = superinterfaceSignatures;
    }

    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (typeParameters != null) {
            for (final TypeParameter typeParameter : typeParameters) {
                typeParameter.setScanResult(scanResult);
            }
        }
        if (this.superclassSignature != null) {
            this.superclassSignature.setScanResult(scanResult);
        }
        if (superinterfaceSignatures != null) {
            for (final ClassRefTypeSignature classRefTypeSignature : superinterfaceSignatures) {
                classRefTypeSignature.setScanResult(scanResult);
            }
        }
    }

    /**
     * Get the type parameters for the class.
     * 
     * @return The type parameters for the class.
     */
    public List<TypeParameter> getTypeParameters() {
        return typeParameters;
    }

    /**
     * Get the type signature for the superclass (possibly null in the case of {@link java.lang.Object}, since it
     * doesn't have a superclass).
     * 
     * @return The type signature for the superclass, or null if no superclass (i.e. for {@link java.lang.Object}).
     */
    public ClassRefTypeSignature getSuperclassSignature() {
        return superclassSignature;
    }

    /**
     * Get the type signatures of any superinterfaces
     * 
     * @return The type signatures of any superinterfaces.
     */
    public List<ClassRefTypeSignature> getSuperinterfaceSignatures() {
        return superinterfaceSignatures;
    }

    @Override
    public void getClassNamesFromTypeDescriptors(final Set<String> classNameListOut) {
        for (final TypeParameter typeParameter : typeParameters) {
            typeParameter.getClassNamesFromTypeDescriptors(classNameListOut);
        }
        if (superclassSignature != null) {
            superclassSignature.getClassNamesFromTypeDescriptors(classNameListOut);
        }
        for (final ClassRefTypeSignature typeSignature : superinterfaceSignatures) {
            typeSignature.getClassNamesFromTypeDescriptors(classNameListOut);
        }
    }

    @Override
    protected String getClassName() {
        // getClassInfo() is disabled for this type, so getClassName() does not need to be implemented
        throw new IllegalArgumentException("getClassName() cannot be called here");
    }

    @Override
    protected ClassInfo getClassInfo() {
        // getClassInfo() is disabled for this type, since the class name is not part of a class type signature
        throw new IllegalArgumentException("getClassInfo() cannot be called here");
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

    /**
     * Render into String form.
     * 
     * @param modifiers
     *            The class modifiers.
     * @param isAnnotation
     *            True if the class is an annotation.
     * @param isInterface
     *            True if the class is an interface.
     * @param className
     *            The class name
     * @return The String representation.
     */
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
     * Parse a class type signature or class type descriptor.
     * 
     * @param className
     *            The name of the class (for resolving type variabless).
     * @param typeDescriptor
     *            The class type signature or class type descriptor to parse.
     * @param scanResult
     *            The {@link ScanResult} (for classloading).
     * @return The parsed class type signature or class type descriptor.
     * @throws ParseException
     *             If the class type signature could not be parsed.
     */
    public static ClassTypeSignature parse(final String className, final String typeDescriptor,
            final ScanResult scanResult) throws ParseException {
        final Parser parser = new Parser(typeDescriptor);
        final List<TypeParameter> typeParameters = TypeParameter.parseList(parser);
        final ClassRefTypeSignature superclassSignature = ClassRefTypeSignature.parse(parser);
        List<ClassRefTypeSignature> superinterfaceSignatures;
        if (parser.hasMore()) {
            superinterfaceSignatures = new ArrayList<>();
            while (parser.hasMore()) {
                final ClassRefTypeSignature superinterfaceSignature = ClassRefTypeSignature.parse(parser);
                if (superinterfaceSignature == null) {
                    throw new ParseException(parser, "Could not parse superinterface signature");
                }
                superinterfaceSignatures.add(superinterfaceSignature);
            }
        } else {
            superinterfaceSignatures = Collections.emptyList();
        }
        if (parser.hasMore()) {
            throw new ParseException(parser, "Extra characters at end of type descriptor");
        }
        final ClassTypeSignature classSignature = new ClassTypeSignature(typeParameters, superclassSignature,
                superinterfaceSignatures);
        // Add back-links from type variable signature to the class signature it is part of
        @SuppressWarnings("unchecked")
        final List<TypeVariableSignature> typeVariableSignatures = (List<TypeVariableSignature>) parser.getState();
        if (typeVariableSignatures != null) {
            for (final TypeVariableSignature typeVariableSignature : typeVariableSignatures) {
                typeVariableSignature.containingClassName = className;
            }
        }
        classSignature.setScanResult(scanResult);
        return classSignature;
    }
}