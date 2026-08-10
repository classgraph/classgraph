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
import java.util.Objects;
import java.util.Set;

import io.github.classgraph.Classfile.TypePathNode;
import nonapi.io.github.classgraph.types.ParseException;
import nonapi.io.github.classgraph.types.Parser;
import nonapi.io.github.classgraph.types.TypeUtils;
import nonapi.io.github.classgraph.types.TypeUtils.ModifierType;
import nonapi.io.github.classgraph.utils.LogNode;
import org.jspecify.annotations.Nullable;

/**
 * The generic type signature of a class declaration: its type parameters, its superclass, and its superinterfaces.
 * This corresponds to the {@code ClassSignature} production of the signature grammar in section 4.7.9.1 of the JVM
 * Specification. (A reference to a class type, as it appears in the signature of a field, method or superclass, is
 * modeled by {@link ClassRefTypeSignature} instead.)
 */
public final class ClassTypeSignature extends HierarchicalTypeSignature {

    /** The class info. */
    private final ClassInfo classInfo;

    /** The class type parameters. */
    final List<TypeParameter> typeParameters;

    /** The superclass type. */
    private final @Nullable ClassRefTypeSignature superclassSignature;

    /** The superinterface signatures. */
    private final List<ClassRefTypeSignature> superinterfaceSignatures;

    /**
     * The throws signatures (usually null). These are only present in Scala classes, if the class is marked up with
     * {@code @throws}, and they violate the classfile spec, but we parse them anyway.
     */
    // #495
    private final @Nullable List<ClassRefOrTypeVariableSignature> throwsSignatures;

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
     * @param throwsSignatures
     *            the throws signatures (these are actually invalid, but can be added by Scala). Usually null.
     */
    // #495
    private ClassTypeSignature(final ClassInfo classInfo, final List<TypeParameter> typeParameters,
            final @Nullable ClassRefTypeSignature superclassSignature,
            final List<ClassRefTypeSignature> superinterfaceSignatures,
            final @Nullable List<ClassRefOrTypeVariableSignature> throwsSignatures) {
        super();
        this.classInfo = classInfo;
        this.typeParameters = typeParameters;
        this.superclassSignature = superclassSignature;
        this.superinterfaceSignatures = superinterfaceSignatures;
        this.throwsSignatures = throwsSignatures;
    }

    /**
     * Constructor used to create synthetic class type descriptor.
     *
     * @param classInfo
     *            The class.
     * @param superclass
     *            The superclass.
     * @param interfaces
     *            The implemented interfaces.
     */
    // #662
    ClassTypeSignature(final ClassInfo classInfo, final @Nullable ClassInfo superclass,
            final ClassInfoList interfaces) {
        super();
        this.classInfo = classInfo;
        this.typeParameters = List.of();
        ClassRefTypeSignature superclassSignature = null;
        try {
            superclassSignature = superclass == null ? null
                    : (ClassRefTypeSignature) TypeSignature
                            .parse("L" + superclass.getName().replace('.', '/') + ";", classInfo.getName());
        } catch (final ParseException e) {
            // Silently fail (should not happen)
        }
        this.superclassSignature = superclassSignature;
        this.superinterfaceSignatures = interfaces == null || interfaces.isEmpty() ? List.of()
                : new ArrayList<>(interfaces.size());
        if (interfaces != null) {
            for (final ClassInfo iface : interfaces) {
                try {
                    final var ifaceSignature = (ClassRefTypeSignature) TypeSignature
                            .parse("L" + iface.getName().replace('.', '/') + ";", classInfo.getName());
                    this.superinterfaceSignatures.add(ifaceSignature);
                } catch (final ParseException e) {
                    // Silently fail (should not happen)
                }
            }
        }
        this.throwsSignatures = null;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the type parameters for the class.
     *
     * @return The type parameters for the class.
     */
    public List<TypeParameter> getTypeParameters() {
        return Collections.unmodifiableList(typeParameters);
    }

    /**
     * Get the type signature for the superclass (possibly null in the case of {@link java.lang.Object}, since it
     * doesn't have a superclass).
     *
     * @return The type signature for the superclass, or null if no superclass (i.e. for {@link java.lang.Object}).
     */
    public @Nullable ClassRefTypeSignature getSuperclassSignature() {
        return superclassSignature;
    }

    /**
     * Get the type signatures of any superinterfaces.
     *
     * @return The type signatures of any superinterfaces.
     */
    public List<ClassRefTypeSignature> getSuperinterfaceSignatures() {
        return Collections.unmodifiableList(superinterfaceSignatures);
    }

    /**
     * Gets the throws signatures. These are invalid according to the classfile spec (so this method is currently
     * non-public), but may be added by the Scala compiler.
     *
     * @return the throws signatures
     */
    // #495
    @Nullable
    List<ClassRefOrTypeVariableSignature> getThrowsSignatures() {
        return throwsSignatures;
    }

    @Override
    void addTypeAnnotation(final List<TypePathNode> typePath, final AnnotationInfo annotationInfo) {
        // Individual parts of a class' type each have their own addTypeAnnotation methods
        throw new UnsupportedOperationException(
                "Cannot call this method on " + ClassTypeSignature.class.getSimpleName());
    }

    // -------------------------------------------------------------------------------------------------------------

    /*
     * (non-Javadoc)
     *
     * @see io.github.classgraph.ScanResultObject#getClassName()
     */
    @Override
    protected @Nullable String getClassName() {
        return classInfo != null ? classInfo.getName() : null;
    }

    /*
     * (non-Javadoc)
     *
     * @see io.github.classgraph.ScanResultObject#getClassInfo()
     */
    @Override
    protected ClassInfo getClassInfo() {
        return classInfo;
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
        if (typeParameters != null) {
            for (final TypeParameter typeParameter : typeParameters) {
                typeParameter.setScanResult(scanResult);
            }
        }
        final var superclassSig = this.superclassSignature;
        if (superclassSig != null) {
            superclassSig.setScanResult(scanResult);
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
        final var superclassSig = superclassSignature;
        if (superclassSig != null) {
            superclassSig.findReferencedClassNames(refdClassNames);
        }
        if (superinterfaceSignatures != null) {
            for (final ClassRefTypeSignature typeSignature : superinterfaceSignatures) {
                typeSignature.findReferencedClassNames(refdClassNames);
            }
        }
        final var throwsSigs = throwsSignatures;
        if (throwsSigs != null) {
            for (final ClassRefOrTypeVariableSignature typeSignature : throwsSigs) {
                typeSignature.findReferencedClassNames(refdClassNames);
            }
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
    void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo, final @Nullable LogNode log) {
        final Set<String> refdClassNames = new HashSet<>();
        findReferencedClassNames(refdClassNames);
        for (final String refdClassName : refdClassNames) {
            final var clsInfo = ClassInfo.getOrCreateClassInfo(refdClassName, classNameToClassInfo);
            clsInfo.scanResult = scanResult;
            refdClassInfo.add(clsInfo);
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
        return typeParameters.hashCode() + (superclassSignature == null ? 1 : superclassSignature.hashCode()) * 7
                + (superinterfaceSignatures == null ? 1 : superinterfaceSignatures.hashCode()) * 15;
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
        if (!(obj instanceof final ClassTypeSignature o)) {
            return false;
        }
        return Objects.equals(o.typeParameters, this.typeParameters)
                && Objects.equals(o.superclassSignature, this.superclassSignature)
                && Objects.equals(o.superinterfaceSignatures, this.superinterfaceSignatures);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Render into String form.
     *
     * @param className
     *            The class name
     * @param useSimpleNames
     *            if true, strip package and outer class names from class names
     * @param modifiers
     *            The class modifiers.
     * @param isAnnotation
     *            True if the class is an annotation.
     * @param isInterface
     *            True if the class is an interface.
     * @param buf
     *            the buffer to append to
     */
    void toStringInternal(final String className, final boolean useSimpleNames, final int modifiers,
            final boolean isAnnotation, final boolean isInterface, final StringBuilder buf) {
        final var throwsSigs = throwsSignatures;
        if (throwsSigs != null) {
            for (final ClassRefOrTypeVariableSignature throwsSignature : throwsSigs) {
                if (!buf.isEmpty()) {
                    buf.append(' ');
                }
                buf.append("@throws(").append(throwsSignature).append(")");
            }
        }
        if (modifiers != 0) {
            if (!buf.isEmpty()) {
                buf.append(' ');
            }
            TypeUtils.modifiersToString(modifiers, ModifierType.CLASS, /* ignored */ false, buf);
        }
        if (!buf.isEmpty()) {
            buf.append(' ');
        }
        buf.append(isAnnotation ? "@interface"
                : isInterface ? "interface" : (modifiers & 0x4000) != 0 ? "enum" : "class");
        buf.append(' ');
        if (className != null) {
            buf.append(useSimpleNames ? ClassInfo.getSimpleName(className) : className);
        }
        if (!typeParameters.isEmpty()) {
            buf.append('<');
            for (var i = 0; i < typeParameters.size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                typeParameters.get(i).toStringInternal(useSimpleNames, null, buf);
            }
            buf.append('>');
        }
        final var superclassSig = superclassSignature;
        if (superclassSig != null) {
            final var superSig = superclassSig.toString(useSimpleNames);
            // superSig could have a class type annotation even if the superclass is Object
            if (!"java.lang.Object".equals(superSig)
                    && !("Object".equals(superSig) && "java.lang.Object".equals(superclassSig.className))) {
                buf.append(" extends ");
                buf.append(superSig);
            }
        }
        if (superinterfaceSignatures != null && !superinterfaceSignatures.isEmpty()) {
            buf.append(isInterface ? " extends " : " implements ");
            for (var i = 0; i < superinterfaceSignatures.size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                superinterfaceSignatures.get(i).toStringInternal(useSimpleNames, null, buf);
            }
        }
    }

    /**
     * To string internal.
     *
     * @param useSimpleNames
     *            if true, strip package and outer class names from class names
     * @param annotationsToExclude
     *            the annotations to exclude
     * @param buf
     *            the buffer to append to
     */
    @Override
    protected void toStringInternal(final boolean useSimpleNames,
            final @Nullable AnnotationInfoList annotationsToExclude, final StringBuilder buf) {
        toStringInternal(classInfo.getName(), useSimpleNames, classInfo.getModifiers(), classInfo.isAnnotation(),
                classInfo.isInterface(), buf);
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
        // A class type signature can refer to the class' own type variables, both in the bounds of its type
        // parameters (e.g. "class C<T extends Number, U extends T>") and in the type arguments of its superclass
        // and superinterfaces (e.g. "class C<T> extends ArrayList<T>"), so the class itself is the defining class
        // of any type variable in the signature
        final var definingClassName = classInfo.getName();
        final var typeParameters = TypeParameter.parseList(parser, definingClassName);
        final var superclassSignature = ClassRefTypeSignature.parse(parser, definingClassName);
        final List<ClassRefTypeSignature> superinterfaceSignatures;
        if (parser.hasMore()) {
            superinterfaceSignatures = new ArrayList<>();
            while (parser.hasMore()) {
                if (parser.peek() == '^') {
                    // Illegal "throws" suffix in class type signature -- fall through
                    break;
                }
                final var superinterfaceSignature = ClassRefTypeSignature.parse(parser, definingClassName);
                if (superinterfaceSignature == null) {
                    throw new ParseException(parser, "Could not parse superinterface signature");
                }
                superinterfaceSignatures.add(superinterfaceSignature);
            }
        } else {
            superinterfaceSignatures = List.of();
        }
        final List<ClassRefOrTypeVariableSignature> throwsSignatures;
        if (parser.peek() == '^') {
            // There is an illegal "throws" suffix at the end of this class type signature. The JVMS ClassSignature
            // production has no ThrowsSignature, but the Scala compiler emits one when a class is tagged with
            // "@throws" (#495). javac and javap reject such a signature, and getClass().getGenericSuperclass() on a
            // subclass throws GenericSignatureFormatError -- but the JVM itself never reads type signatures, so the
            // classes run fine, and Scala has kept emitting them. ClassGraph therefore parses the suffix, records
            // the classes it names as referenced, and renders it as "@throws(...)", rather than refusing to parse a
            // signature that a real compiler produces.
            throwsSignatures = new ArrayList<>();
            while (parser.peek() == '^') {
                parser.expect('^');
                final var classTypeSignature = ClassRefTypeSignature.parse(parser, classInfo.getName());
                if (classTypeSignature != null) {
                    throwsSignatures.add(classTypeSignature);
                } else {
                    final var typeVariableSignature = TypeVariableSignature.parse(parser, classInfo.getName());
                    if (typeVariableSignature != null) {
                        throwsSignatures.add(typeVariableSignature);
                    } else {
                        throw new ParseException(parser, "Missing type variable signature");
                    }
                }
            }
        } else {
            throwsSignatures = null;
        }
        if (parser.hasMore()) {
            throw new ParseException(parser, "Extra characters at end of type descriptor");
        }
        return new ClassTypeSignature(classInfo, typeParameters, superclassSignature, superinterfaceSignatures,
                throwsSignatures);
    }
}