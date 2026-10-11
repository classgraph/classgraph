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

import java.lang.annotation.Repeatable;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.classgraph.ClassInfo.RelType;
import io.github.classgraph.Classfile.TypeAnnotation;
import io.github.classgraph.TypeUtils.ModifierType;
import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.utils.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * Holds metadata about fields of a class encountered during a scan. All values are taken directly out of the
 * classfile for the class.
 */
public final class FieldInfo extends ClassMemberInfo implements Comparable<FieldInfo> {
    /** The parsed type signature. */
    private @Nullable TypeSignature typeSignature;

    /** The parsed type descriptor. */
    private @Nullable TypeSignature typeDescriptor;

    /** The constant initializer value for the field, if any. */
    private final @Nullable Object constantInitializerValue;

    /**
     * The type annotations to add to the {@link TypeSignature} instance of this field.
     */
    private @Nullable List<TypeAnnotation<TypeSignature>> typeAnnotationsToApply;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param definingClassName
     *            The class the field is defined within.
     * @param fieldName
     *            The name of the field.
     * @param modifiers
     *            The field modifiers.
     * @param typeDescriptorStr
     *            The field type descriptor.
     * @param typeSignatureStr
     *            The field type signature.
     * @param constantInitializerValue
     *            The static constant value the field is initialized to, if any.
     * @param annotationInfo
     *            {@link AnnotationInfo} for any annotations on the field.
     * @param typeAnnotationsToApply
     *            the type annotations to add to the parsed field type, or null if none.
     */
    FieldInfo(final String definingClassName, final String fieldName, final int modifiers,
            final String typeDescriptorStr, final @Nullable String typeSignatureStr,
            final @Nullable Object constantInitializerValue, final @Nullable List<AnnotationInfo> annotationInfo,
            final @Nullable List<TypeAnnotation<TypeSignature>> typeAnnotationsToApply) {
        super(definingClassName, fieldName, modifiers, typeDescriptorStr, typeSignatureStr, annotationInfo);
        this.constantInitializerValue = constantInitializerValue;
        this.typeAnnotationsToApply = typeAnnotationsToApply;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the field modifiers as a string, e.g. "public static final". For the modifier bits, call getModifiers().
     *
     * @return The field modifiers, as a string.
     */
    @Override
    public String getModifiersString() {
        final StringBuilder buf = new StringBuilder();
        TypeUtils.modifiersToString(modifiers, ModifierType.FIELD, /* ignored */ false, buf);
        return buf.toString();
    }

    /**
     * Returns true if this field is a transient field.
     *
     * @return True if the field is transient.
     */
    public boolean isTransient() {
        return Modifier.isTransient(modifiers);
    }

    /**
     * Returns true if this field is an enum constant.
     *
     * @return True if the field is an enum constant.
     */
    public boolean isEnum() {
        return (modifiers & 0x4000) != 0;
    }

    /**
     * Returns the parsed type descriptor for the field, which will not include type parameters. If you need generic
     * type parameters, call {@link #getTypeSignature()} instead.
     *
     * @return The parsed type descriptor for the field.
     * @throws IllegalArgumentException
     *             if the field type descriptor cannot be parsed (this should only be thrown in the case of
     *             classfile corruption).
     */
    @Override
    public TypeSignature getTypeDescriptor() {
        synchronized (this) {
            if (typeDescriptor == null) {
                try {
                    typeDescriptor = TypeSignature.parse(typeDescriptorStr, declaringClassName);
                    typeDescriptor.setScanResult(this.scanResult);
                    applyTypeAnnotations(typeDescriptor);
                } catch (final TypeSignatureParseException e) {
                    throw new IllegalArgumentException(e);
                }
            }
            return typeDescriptor;
        }
    }

    /**
     * Add the field's type annotations to the given parsed field type. Any individual type annotation that cannot
     * be matched to the type (e.g. an unresolvable nested type, or a compiler bug) is skipped rather than being
     * allowed to abort parsing of the whole field type.
     *
     * @param fieldType
     *            the parsed field type signature or descriptor to add the type annotations to.
     */
    // #897
    private void applyTypeAnnotations(final TypeSignature fieldType) {
        TypeAnnotation.applyAll(typeAnnotationsToApply, fieldType);
    }

    /**
     * Returns the parsed type signature for the field, possibly including type parameters. If this returns null,
     * indicating that no type signature information is available for this field, call {@link #getTypeDescriptor()}
     * instead.
     *
     * @return The parsed type signature for the field, or null if not available.
     * @throws IllegalArgumentException
     *             if the field type signature cannot be parsed (this should only be thrown in the case of classfile
     *             corruption, or a compiler bug that causes an invalid type signature to be written to the
     *             classfile).
     */
    @Override
    public @Nullable TypeSignature getTypeSignature() {
        synchronized (this) {
            if (typeSignatureStr == null) {
                return null;
            }
            if (typeSignature == null) {
                try {
                    typeSignature = TypeSignature.parse(typeSignatureStr, declaringClassName);
                    typeSignature.setScanResult(this.scanResult);
                    applyTypeAnnotations(typeSignature);
                } catch (final TypeSignatureParseException e) {
                    throw new IllegalArgumentException(
                            "Invalid type signature for field " + getClassName() + "." + getName()
                                    + (getClassInfo() != null
                                            ? " in classpath element " + getClassInfo().getClasspathElementURI()
                                            : "")
                                    + " : " + typeSignatureStr,
                            e);
                }
            }
            return typeSignature;
        }
    }

    /**
     * Returns the type signature for the field, possibly including type parameters. If the type signature is null,
     * indicating that no type signature information is available for this field, or if the type signature cannot be
     * parsed, returns the type descriptor instead.
     *
     * @return The parsed type signature for the field, or if not available, the parsed type descriptor for the
     *         field.
     * @throws IllegalArgumentException
     *             if the type descriptor is needed and cannot be parsed (this should only be thrown in the case of
     *             classfile corruption).
     */
    @Override
    public TypeSignature getTypeSignatureOrTypeDescriptor() {
        TypeSignature typeSig = null;
        try {
            typeSig = getTypeSignature();
            if (typeSig != null) {
                return typeSig;
            }
        } catch (final Exception _) {
            // Ignore
        }
        return getTypeDescriptor();
    }

    /**
     * Returns the constant initializer value of a field, which is the value stored in the field's
     * {@code ConstantValue} attribute in the classfile. Requires
     * {@link ClassGraph#enableStaticFinalFieldConstantInitializerValues()} to have been called. javac stores a
     * constant initializer value for a final field of primitive or String type whose initializer is a constant
     * expression, e.g. {@code static final int X = 5;}. A field that is initialized any other way, or that is
     * assigned in an initializer block or a constructor, has no constant initializer value. See
     * {@link ClassGraph#enableStaticFinalFieldConstantInitializerValues()} for non-static fields.
     *
     * @return The constant initializer value (a boxed primitive or a String), or null if the field has none, or if
     *         the classfile stores a value that does not fit the field's type.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableStaticFinalFieldConstantInitializerValues()} was not called prior to
     *             initiating the scan.
     */
    public @Nullable Object getConstantInitializerValue() {
        scanResult().scanSpec.checkStaticFinalFieldConstantInitializerValuesEnabled();
        return constantInitializerValue;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Handle {@link Repeatable} annotations.
     *
     * @param allRepeatableAnnotationNames
     *            the names of the container annotations of all repeatable annotations
     */
    void handleRepeatableAnnotations(final Set<String> allRepeatableAnnotationNames) {
        if (annotationInfo != null) {
            final var classInfo = getClassInfo();
            AnnotationInfoList.handleRepeatableAnnotations(annotationInfo, allRepeatableAnnotationNames, classInfo,
                    RelType.FIELD_ANNOTATIONS, RelType.CLASSES_WITH_FIELD_ANNOTATION,
                    classInfo != null && classInfo.isInheritedMember(getModifiers(), /* isField = */ true)
                            ? RelType.CLASSES_WITH_NONPRIVATE_FIELD_ANNOTATION
                            : null);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    void setScanResult(final @Nullable ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (this.typeSignature != null) {
            this.typeSignature.setScanResult(scanResult);
        }
        if (this.typeDescriptor != null) {
            this.typeDescriptor.setScanResult(scanResult);
        }
        if (this.annotationInfo != null) {
            for (final AnnotationInfo ai : this.annotationInfo) {
                ai.setScanResult(scanResult);
            }
        }
    }

    /**
     * Get {@link ClassInfo} objects for any classes referenced in the type descriptor or type signature.
     *
     * @param classNameToClassInfo
     *            the map to add a {@link ClassInfo} object to, for a referenced class that has none in the scan
     *            result.
     * @param refdClassInfo
     *            the referenced class info
     * @param log
     *            the log node, or null to skip logging
     */
    @Override
    void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo, final @Nullable LogNode log) {
        try {
            final var fieldSig = getTypeSignature();
            if (fieldSig != null) {
                fieldSig.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
            }
        } catch (final IllegalArgumentException _) {
            if (log != null) {
                log.log("Illegal type signature for field " + getClassName() + "." + getName() + ": "
                        + getTypeSignatureString());
            }
        }
        try {
            getTypeDescriptor().findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
        } catch (final IllegalArgumentException _) {
            if (log != null) {
                log.log("Illegal type descriptor for field " + getClassName() + "." + getName() + ": "
                        + getTypeDescriptorString());
            }
        }
        if (annotationInfo != null) {
            for (final AnnotationInfo ai : annotationInfo) {
                ai.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Use class name, field name and type descriptor for equals(). A classfile may declare two fields with the same
     * name if their types differ (javac never does this, but obfuscators do), and those are two different fields.
     *
     * @param obj
     *            the object to compare to
     * @return true if equal
     */
    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final FieldInfo other)) {
            return false;
        }
        return declaringClassName.equals(other.declaringClassName)
                && typeDescriptorStr.equals(other.typeDescriptorStr) && name.equals(other.name);
    }

    /**
     * Use hash code of class name, field name and type descriptor.
     *
     * @return the hashcode
     */
    @Override
    public int hashCode() {
        return name.hashCode() + typeDescriptorStr.hashCode() * 11 + declaringClassName.hashCode() * 57;
    }

    /**
     * Sort in order of class name, field name, then type descriptor.
     *
     * @param other
     *            the other FieldInfo object to compare to.
     * @return the result of comparison.
     */
    @Override
    public int compareTo(final FieldInfo other) {
        final var diff0 = declaringClassName.compareTo(other.declaringClassName);
        if (diff0 != 0) {
            return diff0;
        }
        final var diff1 = name.compareTo(other.name);
        if (diff1 != 0) {
            return diff1;
        }
        return typeDescriptorStr.compareTo(other.typeDescriptorStr);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Append a string representation of the field to a buffer. The modifiers can be omitted, so that the same code
     * can render a record component, which has the same shape as a field but no modifiers of its own. If the type
     * descriptor is needed but cannot be parsed, the raw type descriptor is shown in place of the type.
     *
     * @param includeModifiers
     *            if true, include the field's modifiers
     * @param useSimpleNames
     *            if true, strip the package name from class names
     * @param buf
     *            the buffer to append to
     */
    void toString(final boolean includeModifiers, final boolean useSimpleNames, final StringBuilder buf) {
        if (annotationInfo != null) {
            for (final AnnotationInfo annotation : annotationInfo) {
                // There can be a paren in the previous position if this field is a record parameter
                if (!buf.isEmpty() && buf.charAt(buf.length() - 1) != ' ' && buf.charAt(buf.length() - 1) != '(') {
                    buf.append(' ');
                }
                annotation.toString(useSimpleNames, buf);
            }
        }

        if (modifiers != 0 && includeModifiers) {
            if (!buf.isEmpty() && buf.charAt(buf.length() - 1) != ' ' && buf.charAt(buf.length() - 1) != '(') {
                buf.append(' ');
            }
            TypeUtils.modifiersToString(modifiers, ModifierType.FIELD, /* ignored */ false, buf);
        }

        if (!buf.isEmpty() && buf.charAt(buf.length() - 1) != ' ' && buf.charAt(buf.length() - 1) != '(') {
            buf.append(' ');
        }
        TypeSignature fieldType = null;
        try {
            fieldType = getTypeSignatureOrTypeDescriptor();
        } catch (final IllegalArgumentException _) {
            // Only a corrupt classfile has a type descriptor that does not parse. Show the raw descriptor in
            // place of the type rather than throwing from toString()
        }
        if (fieldType != null) {
            fieldType.toStringInternal(useSimpleNames, /* annotationsToExclude = */ annotationInfo, buf);
        } else {
            buf.append(typeDescriptorStr);
        }

        buf.append(' ');
        buf.append(name);

        if (constantInitializerValue != null) {
            final var val = constantInitializerValue;
            buf.append(" = ");
            StringUtils.appendSourceLiteral(val, buf);
        }
    }

    @Override
    protected void toString(final boolean useSimpleNames, final StringBuilder buf) {
        toString(true, useSimpleNames, buf);
    }
}
