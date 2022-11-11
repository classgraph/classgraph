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

import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.lang.reflect.Modifier;

import nonapi.io.github.classgraph.utils.Assert;

/**
 * Holds metadata about class members of a class encountered during a scan. All values are taken directly out of the
 * classfile for the class.
 */
public abstract class ClassMemberInfo extends ScanResultObject implements HasName {
    /** Defining class name. */
    protected String declaringClassName;

    /** The name of the class member. */
    protected String name;

    /** Method modifiers. */
    protected int modifiers;

    /**
     * The JVM-internal type descriptor (missing type parameters, but including types for synthetic and mandated
     * method parameters).
     */
    protected String typeDescriptorStr;

    /**
     * The type signature (may have type parameter information included, if present and available). Method parameter
     * types are unaligned.
     */
    protected String typeSignatureStr;

    /** The annotation on the class member, if any. */
    protected AnnotationInfoList annotationInfo;

    /** Default constructor for deserialization. */
    ClassMemberInfo() {
        super();
    }

    /**
     * Constructor.
     *
     * @param definingClassName
     *            The class the member is defined within.
     * @param memberName
     *            The name of the class member.
     * @param modifiers
     *            The field modifiers.
     * @param typeDescriptorStr
     *            The field type descriptor.
     * @param typeSignatureStr
     *            The field type signature.
     * @param annotationInfo
     *            {@link AnnotationInfo} for any annotations on the field.
     */
    public ClassMemberInfo(final String definingClassName, final String memberName, final int modifiers,
            final String typeDescriptorStr, final String typeSignatureStr,
            final AnnotationInfoList annotationInfo) {
        super();
        this.declaringClassName = definingClassName;
        this.name = memberName;
        this.modifiers = modifiers;
        this.typeDescriptorStr = typeDescriptorStr;
        this.typeSignatureStr = typeSignatureStr;
        this.annotationInfo = annotationInfo == null || annotationInfo.isEmpty() ? null : annotationInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the {@link ClassInfo} object for the class that declares this method.
     *
     * @return The {@link ClassInfo} object for the declaring class.
     *
     * @see #getClassName()
     */
    @Override
    public ClassInfo getClassInfo() {
        return super.getClassInfo();
    }

    /**
     * Get the name of the class that declares this member.
     *
     * @return The name of the declaring class.
     *
     * @see #getClassInfo()
     */
    @Override
    public String getClassName() {
        return declaringClassName;
    }

    /**
     * Get the name of the field.
     *
     * @return The name of the field.
     */
    @Override
    public String getName() {
        return name;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the modifier bits for the method.
     *
     * @return The modifier bits for the method.
     */
    public int getModifiers() {
        return modifiers;
    }

    /**
     * Get the modifiers as a string, e.g. "public static final". For the modifier bits, call getModifiers().
     *
     * @return The modifiers modifiers, as a string.
     */
    public abstract String getModifiersStr();

    /**
     * Returns true if this class member is public.
     *
     * @return True if the class member is public.
     */
    public boolean isPublic() {
        return Modifier.isPublic(modifiers);
    }

    /**
     * Returns true if this class member is private.
     *
     * @return True if the class member is private.
     */
    public boolean isPrivate() {
        return Modifier.isPrivate(modifiers);
    }

    /**
     * Returns true if this class member is protected.
     *
     * @return True if the class member is protected.
     */
    public boolean isProtected() {
        return Modifier.isProtected(modifiers);
    }

    /**
     * Returns true if this class member is static.
     *
     * @return True if the class member is static.
     */
    public boolean isStatic() {
        return Modifier.isStatic(modifiers);
    }

    /**
     * Returns true if this class member is final.
     *
     * @return True if the class member is final.
     */
    public boolean isFinal() {
        return Modifier.isFinal(modifiers);
    }

    /**
     * Returns true if this class member is synthetic.
     *
     * @return True if the class member is synthetic.
     */
    public boolean isSynthetic() {
        return (modifiers & 0x1000) != 0;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the parsed type descriptor for the class member, which will not include type parameters. If you need
     * generic type parameters, call {@link #getTypeSignature()} instead.
     *
     * @return The parsed type descriptor string for the class member.
     */
    public abstract HierarchicalTypeSignature getTypeDescriptor();

    /**
     * Returns the type descriptor string for the class member, which will not include type parameters. If you need
     * generic type parameters, call {@link #getTypeSignatureStr()} instead.
     *
     * @return The type descriptor string for the class member.
     */
    public String getTypeDescriptorStr() {
        return typeDescriptorStr;
    }

    /**
     * Returns the parsed type signature for the class member, possibly including type parameters. If this returns
     * null, that no type signature information is available for this class member, call
     * {@link #getTypeDescriptor()} instead.
     *
     * @return The parsed type signature for the class member, or null if not available.
     * @throws IllegalArgumentException
     *             if the class member type signature cannot be parsed (this should only be thrown in the case of
     *             classfile corruption, or a compiler bug that causes an invalid type signature to be written to
     *             the classfile).
     */
    public abstract HierarchicalTypeSignature getTypeSignature();

    /**
     * Returns the type signature string for the class member, possibly including type parameters. If this returns
     * null, indicating that no type signature information is available for this class member, call
     * {@link #getTypeDescriptorStr()} instead.
     *
     * @return The type signature string for the class member, or null if not available.
     */
    public String getTypeSignatureStr() {
        return typeSignatureStr;
    }

    /**
     * Returns the type signature for the class member, possibly including type parameters. If the type signature is
     * null, indicating that no type signature information is available for this class member, returns the type
     * descriptor instead.
     *
     * @return The parsed type signature for the class member, or if not available, the parsed type descriptor for
     *         the class member.
     */
    public abstract HierarchicalTypeSignature getTypeSignatureOrTypeDescriptor();

    /**
     * Returns the type signature string for the class member, possibly including type parameters. If the type
     * signature string is null, indicating that no type signature information is available for this class member,
     * returns the type descriptor string instead.
     *
     * @return The type signature string for the class member, or if not available, the type descriptor string for
     *         the class member.
     */
    public String getTypeSignatureOrTypeDescriptorStr() {
        if (typeSignatureStr != null) {
            return typeSignatureStr;
        }
        return typeDescriptorStr;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get a list of annotations on this class member, along with any annotation parameter values, wrapped in
     * {@link AnnotationInfo} objects.
     *
     * @return A list of annotations on this class member, along with any annotation parameter values, wrapped in
     *         {@link AnnotationInfo} objects, or the empty list if none.
     */
    public AnnotationInfoList getAnnotationInfo() {
        if (!scanResult.scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableAnnotationInfo() before #scan()");
        }
        return annotationInfo == null ? AnnotationInfoList.EMPTY_LIST
                : AnnotationInfoList.getIndirectAnnotations(annotationInfo, /* annotatedClass = */ null);
    }

    /**
     * Get a the non-{@link Repeatable} annotation on this class member, or null if the class member does not have
     * the annotation. (Use {@link #getAnnotationInfoRepeatable(Class)} for {@link Repeatable} annotations.)
     *
     * @param annotation
     *            The annotation.
     * @return An {@link AnnotationInfo} object representing the annotation on this class member, or null if the
     *         class member does not have the annotation.
     */
    public AnnotationInfo getAnnotationInfo(final Class<? extends Annotation> annotation) {
        Assert.isAnnotation(annotation);
        return getAnnotationInfo(annotation.getName());
    }

    /**
     * Get a the named non-{@link Repeatable} annotation on this class member, or null if the class member does not
     * have the named annotation. (Use {@link #getAnnotationInfoRepeatable(String)} for {@link Repeatable}
     * annotations.)
     *
     * @param annotationName
     *            The annotation name.
     * @return An {@link AnnotationInfo} object representing the named annotation on this class member, or null if
     *         the class member does not have the named annotation.
     */
    public AnnotationInfo getAnnotationInfo(final String annotationName) {
        return getAnnotationInfo().get(annotationName);
    }

    /**
     * Get a the {@link Repeatable} annotation on this class member, or the empty list if the class member does not
     * have the annotation.
     *
     * @param annotation
     *            The annotation.
     * @return An {@link AnnotationInfoList} of all instances of the annotation on this class member, or the empty
     *         list if the class member does not have the annotation.
     */
    public AnnotationInfoList getAnnotationInfoRepeatable(final Class<? extends Annotation> annotation) {
        Assert.isAnnotation(annotation);
        return getAnnotationInfoRepeatable(annotation.getName());
    }

    /**
     * Get a the named {@link Repeatable} annotation on this class member, or the empty list if the class member
     * does not have the named annotation.
     *
     * @param annotationName
     *            The annotation name.
     * @return An {@link AnnotationInfoList} of all instances of the named annotation on this class member, or the
     *         empty list if the class member does not have the named annotation.
     */
    public AnnotationInfoList getAnnotationInfoRepeatable(final String annotationName) {
        return getAnnotationInfo().getRepeatable(annotationName);
    }

    /**
     * Check if the class member has a given annotation.
     *
     * @param annotation
     *            The annotation.
     * @return true if this class member has the annotation.
     */
    public boolean hasAnnotation(final Class<? extends Annotation> annotation) {
        Assert.isAnnotation(annotation);
        return hasAnnotation(annotation.getName());
    }

    /**
     * Check if the class member has a given named annotation.
     *
     * @param annotationName
     *            The name of an annotation.
     * @return true if this class member has the named annotation.
     */
    public boolean hasAnnotation(final String annotationName) {
        return getAnnotationInfo().containsName(annotationName);
    }
}
