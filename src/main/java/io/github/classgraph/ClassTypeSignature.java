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

    /**
     * The throws signatures (usually null). These are only present in Scala classes, if the class is marked up with
     * {@code @throws}, and they violate the classfile spec (#495), but we parse them anyway.
     */
    private final List<ClassRefOrTypeVariableSignature> throwsSignatures;

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
     *            the throws signatures (these are actually invalid, but can be added by Scala: #495). Usually null.
     */
    private ClassTypeSignature(final ClassInfo classInfo, final List<TypeParameter> typeParameters,
            final ClassRefTypeSignature superclassSignature,
            final List<ClassRefTypeSignature> superinterfaceSignatures,
            final List<ClassRefOrTypeVariableSignature> throwsSignatures) {
        super();
        this.classInfo = classInfo;
        this.typeParameters = typeParameters;
        this.superclassSignature = superclassSignature;
        this.superinterfaceSignatures = superinterfaceSignatures;
        this.throwsSignatures = throwsSignatures;
    }

    /**
     * Constructor used to create synthetic class type descriptor (#662).
     * 
     * @param classInfo
     *            The class.
     * @param superclass
     *            The superclass.
     * @param interfaces
     *            The implemented interfaces.
     */
    ClassTypeSignature(final ClassInfo classInfo, final ClassInfo superclass, final ClassInfoList interfaces) {
        super();
        this.classInfo = classInfo;
        this.typeParameters = Collections.emptyList();
        ClassRefTypeSignature superclassSignature = null;
        try {
            superclassSignature = superclass == null ? null
                    : (ClassRefTypeSignature) TypeSignature
                            .parse("L" + superclass.getName().replace('.', '/') + ";", classInfo.getName());
        } catch (final ParseException e) {
            // Silently fail (should not happen)
        }
        this.superclassSignature = superclassSignature;
        this.superinterfaceSignatures = interfaces == null || interfaces.isEmpty()
                ? Collections.<ClassRefTypeSignature> emptyList()
                : new ArrayList<ClassRefTypeSignature>(interfaces.size());
        if (interfaces != null) {
            for (final ClassInfo iface : interfaces) {
                try {
                    final ClassRefTypeSignature ifaceSignature = (ClassRefTypeSignature) TypeSignature
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

    /**
     * Gets the throws signatures. These are invalid according to the classfile spec (so this method is currently
     * non-public), but may be added by the Scala compiler. (See bug #495.)
     *
     * @return the throws signatures
     */
    List<ClassRefOrTypeVariableSignature> getThrowsSignatures() {
        return throwsSignatures;
    }

    @Override
    protected void addTypeAnnotation(final List<TypePathNode> typePath, final AnnotationInfo annotationInfo) {
        // Individual parts of a class' type each have their own addTypeAnnotation methods
        throw new IllegalArgumentException(
                "Cannot call this method on " + ClassTypeSignature.class.getSimpleName());
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
        if (superinterfaceSignatures != null) {
            for (final ClassRefTypeSignature typeSignature : superinterfaceSignatures) {
                typeSignature.findReferencedClassNames(refdClassNames);
            }
        }
        if (throwsSignatures != null) {
            for (final ClassRefOrTypeVariableSignature typeSignature : throwsSignatures) {
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
    protected void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo, final LogNode log) {
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
        return typeParameters.hashCode() + (superclassSignature == null ? 1 : superclassSignature.hashCode()) * 7
                + (superinterfaceSignatures == null ? 1 : superinterfaceSignatures.hashCode()) * 15;
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
     *            the use simple names
     * @param modifiers
     *            The class modifiers.
     * @param isAnnotation
     *            True if the class is an annotation.
     * @param isInterface
     *            True if the class is an interface.
     * @param annotationsToExclude
     *            the annotations to exclude
     * @param buf
     *            the buf
     */
    void toStringInternal(final String className, final boolean useSimpleNames, final int modifiers,
            final boolean isAnnotation, final boolean isInterface, final AnnotationInfoList annotationsToExclude,
            final StringBuilder buf) {
        if (throwsSignatures != null) {
            for (final ClassRefOrTypeVariableSignature throwsSignature : throwsSignatures) {
                if (buf.length() > 0) {
                    buf.append(' ');
                }
                buf.append("@throws(").append(throwsSignature).append(")");
            }
        }
        if (modifiers != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            TypeUtils.modifiersToString(modifiers, ModifierType.CLASS, /* ignored */ false, buf);
        }
        if (buf.length() > 0) {
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
            for (int i = 0; i < typeParameters.size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                typeParameters.get(i).toStringInternal(useSimpleNames, null, buf);
            }
            buf.append('>');
        }
        if (superclassSignature != null) {
            final String superSig = superclassSignature.toString(useSimpleNames);
            // superSig could have a class type annotation even if the superclass is Object
            if (!superSig.equals("java.lang.Object")
                    && !(superSig.equals("Object") && superclassSignature.className.equals("java.lang.Object"))) {
                buf.append(" extends ");
                buf.append(superSig);
            }
        }
        if (superinterfaceSignatures != null && !superinterfaceSignatures.isEmpty()) {
            buf.append(isInterface ? " extends " : " implements ");
            for (int i = 0; i < superinterfaceSignatures.size(); i++) {
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
     *            the use simple names
     * @param annotationsToExclude
     *            the annotations to exclude
     * @param buf
     *            the buf
     */
    @Override
    protected void toStringInternal(final boolean useSimpleNames, final AnnotationInfoList annotationsToExclude,
            final StringBuilder buf) {
        toStringInternal(classInfo.getName(), useSimpleNames, classInfo.getModifiers(), classInfo.isAnnotation(),
                classInfo.isInterface(), annotationsToExclude, buf);
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
                if (parser.peek() == '^') {
                    // Illegal "throws" suffix in class type signature -- fall through
                    break;
                }
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
        List<ClassRefOrTypeVariableSignature> throwsSignatures;
        if (parser.peek() == '^') {
            // There is an illegal "throws" suffix at the end of this class type signature.
            // Scala adds these if you tag a class with "@throws" (#495).
            // Classes with this sort of type signature are rejected by javac and javap, and they will throw
            // GenericSignatureFormatError if you call getClass().getGenericSuperclass() on a subclass.
            // But the JVM ignores type signatures due to type erasure, and Scala seems to rely on this
            // -- or at the very least, the Scala team never noticed the issue, because the classes work
            // fine at runtime if you live in a Scala-only world.
            // Since this issue is probably widespread in the Scala world, it's probably better to accept
            // these invalid type signatures, and actually parse out any "throws" suffixes, rather than
            // throwing an exception and refusing to parse the type signature. 
            throwsSignatures = new ArrayList<>();
            while (parser.peek() == '^') {
                parser.expect('^');
                final ClassRefTypeSignature classTypeSignature = ClassRefTypeSignature.parse(parser,
                        classInfo.getName());
                if (classTypeSignature != null) {
                    throwsSignatures.add(classTypeSignature);
                } else {
                    final TypeVariableSignature typeVariableSignature = TypeVariableSignature.parse(parser,
                            classInfo.getName());
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