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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import nonapi.io.github.classgraph.types.ParseException;
import nonapi.io.github.classgraph.types.Parser;
import nonapi.io.github.classgraph.types.TypeUtils;
import nonapi.io.github.classgraph.types.TypeUtils.ModifierType;
import nonapi.io.github.classgraph.utils.Join;

/** A class type signature (called "ClassSignature" in the classfile documentation). */
public final class ClassTypeSignature extends HierarchicalTypeSignature {

    /** The class info. */
    private final ClassInfo classInfo;

    /** The class type parameters. */
    final List<TypeParameter> typeParameters;

    /** The superclass type. */
    private final ClassRefTypeSignature superclassSignature;

    /** The superinterface signatures. */
    private final List<ClassRefTypeSignature> superinterfaceSignatures;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param classInfo
     *            the {@link ClassInfo} object of the class.
     * @param typeParameters
     *            The class type parameters.
     * @param superclassSignature
     *            The superclass signature.
     * @param superinterfaceSignatures
     *            The superinterface signature(s).
     */
    private ClassTypeSignature(final ClassInfo classInfo, final List<TypeParameter> typeParameters,
            final ClassRefTypeSignature superclassSignature,
            final List<ClassRefTypeSignature> superinterfaceSignatures) {
        super();
        this.classInfo = classInfo;
        this.typeParameters = typeParameters;
        this.superclassSignature = superclassSignature;
        this.superinterfaceSignatures = superinterfaceSignatures;
    }

    // -------------------------------------------------------------------------------------------------------------

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
     * Get the type signatures of any superinterfaces.
     * 
     * @return The type signatures of any superinterfaces.
     */
    public List<ClassRefTypeSignature> getSuperinterfaceSignatures() {
        return superinterfaceSignatures;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Parse a class type signature or class type descriptor.
     *
     * @param typeDescriptor
     *            The class type signature or class type descriptor to parse.
     * @param classInfo
     *            the class info
     * @return The parsed class type signature or class type descriptor.
     * @throws ParseException
     *             If the class type signature could not be parsed.
     */
    static ClassTypeSignature parse(final String typeDescriptor, final ClassInfo classInfo) throws ParseException {
        final Parser parser = new Parser(typeDescriptor);
        // The defining class name is used to resolve type variables using the defining class' type descriptor.
        // But here we are parsing the defining class' type descriptor, so it can't contain variables that
        // point to itself => just use null as the defining class name.
        final String definingClassNameNull = null;
        final List<TypeParameter> typeParameters = TypeParameter.parseList(parser, definingClassNameNull);
        final ClassRefTypeSignature superclassSignature = ClassRefTypeSignature.parse(parser,
                definingClassNameNull);
        List<ClassRefTypeSignature> superinterfaceSignatures;
        if (parser.hasMore()) {
            superinterfaceSignatures = new ArrayList<>();
            while (parser.hasMore()) {
                final ClassRefTypeSignature superinterfaceSignature = ClassRefTypeSignature.parse(parser,
                        definingClassNameNull);
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
        return new ClassTypeSignature(classInfo, typeParameters, superclassSignature, superinterfaceSignatures);
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#getClassName()
     */
    @Override
    protected String getClassName() {
        return classInfo != null ? classInfo.getName() : null;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#getClassInfo()
     */
    @Override
    protected ClassInfo getClassInfo() {
        return classInfo;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#setScanResult(io.github.classgraph.ScanResult)
     */
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
     * Get the names of any classes referenced in the type signature.
     *
     * @param refdClassNames
     *            the referenced class names.
     */
    protected void findReferencedClassNames(final Set<String> refdClassNames) {
        for (final TypeParameter typeParameter : typeParameters) {
            typeParameter.findReferencedClassNames(refdClassNames);
        }
        if (superclassSignature != null) {
            superclassSignature.findReferencedClassNames(refdClassNames);
        }
        for (final ClassRefTypeSignature typeSignature : superinterfaceSignatures) {
            typeSignature.findReferencedClassNames(refdClassNames);
        }
    }

    /**
     * Get {@link ClassInfo} objects for any classes referenced in the type descriptor or type signature.
     *
     * @param classNameToClassInfo
     *            the map from class name to {@link ClassInfo}.
     * @param refdClassInfo
     *            the referenced class info
     */
    @Override
    protected void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo) {
        final Set<String> refdClassNames = new HashSet<>();
        findReferencedClassNames(refdClassNames);
        for (final String refdClassName : refdClassNames) {
            final ClassInfo clsInfo = ClassInfo.getOrCreateClassInfo(refdClassName, classNameToClassInfo);
            clsInfo.scanResult = scanResult;
            refdClassInfo.add(clsInfo);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return typeParameters.hashCode() + superclassSignature.hashCode() * 7
                + superinterfaceSignatures.hashCode() * 15;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof ClassTypeSignature)) {
            return false;
        }
        final ClassTypeSignature o = (ClassTypeSignature) obj;
        return o.typeParameters.equals(this.typeParameters)
                && o.superclassSignature.equals(this.superclassSignature)
                && o.superinterfaceSignatures.equals(this.superinterfaceSignatures);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Render into String form.
     * 
     * @param className
     *            The class name
     * @param typeNameOnly
     *            If true, only return the type name (and generic type parameters).
     * @param modifiers
     *            The class modifiers.
     * @param isAnnotation
     *            True if the class is an annotation.
     * @param isInterface
     *            True if the class is an interface.
     * @return The String representation.
     */
    String toString(final String className, final boolean typeNameOnly, final int modifiers,
            final boolean isAnnotation, final boolean isInterface) {
        final StringBuilder buf = new StringBuilder();
        if (!typeNameOnly) {
            if (modifiers != 0) {
                TypeUtils.modifiersToString(modifiers, ModifierType.CLASS, /* ignored */ false, buf);
            }
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append(isAnnotation ? "@interface"
                    : isInterface ? "interface" : (modifiers & 0x4000) != 0 ? "enum" : "class");
            buf.append(' ');
        }
        if (className != null) {
            buf.append(className);
        }
        if (!typeParameters.isEmpty()) {
            Join.join(buf, "<", ", ", ">", typeParameters);
        }
        if (!typeNameOnly) {
            if (superclassSignature != null) {
                final String superSig = superclassSignature.toString();
                if (!superSig.equals("java.lang.Object")) {
                    buf.append(" extends ");
                    buf.append(superSig);
                }
            }
            if (!superinterfaceSignatures.isEmpty()) {
                buf.append(isInterface ? " extends " : " implements ");
                Join.join(buf, "", ", ", "", superinterfaceSignatures);
            }
        }
        return buf.toString();
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return toString(classInfo.getName(), /* typeNameOnly = */ false, classInfo.getModifiers(),
                classInfo.isAnnotation(), classInfo.isInterface());
    }
}