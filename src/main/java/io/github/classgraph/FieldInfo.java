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

import java.lang.annotation.Repeatable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;

import io.github.classgraph.ClassInfo.RelType;
import nonapi.io.github.classgraph.types.ParseException;
import nonapi.io.github.classgraph.types.TypeUtils;
import nonapi.io.github.classgraph.types.TypeUtils.ModifierType;

/**
 * Holds metadata about fields of a class encountered during a scan. All values are taken directly out of the
 * classfile for the class.
 */
public class FieldInfo extends ScanResultObject implements Comparable<FieldInfo>, HasName {
    /** The declaring class name. */
    private String declaringClassName;

    /** The name of the field. */
    private String name;

    /** The modifiers. */
    private int modifiers;

    /** The type signature string. */
    private String typeSignatureStr;

    /** The type descriptor string. */
    private String typeDescriptorStr;

    /** The parsed type signature. */
    private transient TypeSignature typeSignature;

    /** The parsed type descriptor. */
    private transient TypeSignature typeDescriptor;

    /** The constant initializer value for the field, if any. */
    // This is transient because the constant initializer value is final, so the value doesn't need to be serialized
    private ObjectTypedValueWrapper constantInitializerValue;

    /** The annotation on the field, if any. */
    AnnotationInfoList annotationInfo;

    // -------------------------------------------------------------------------------------------------------------

    /** Default constructor for deserialization. */
    FieldInfo() {
        super();
    }

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
     */
    FieldInfo(final String definingClassName, final String fieldName, final int modifiers,
            final String typeDescriptorStr, final String typeSignatureStr, final Object constantInitializerValue,
            final AnnotationInfoList annotationInfo) {
        super();
        if (fieldName == null) {
            throw new IllegalArgumentException();
        }
        this.declaringClassName = definingClassName;
        this.name = fieldName;
        this.modifiers = modifiers;
        this.typeDescriptorStr = typeDescriptorStr;
        this.typeSignatureStr = typeSignatureStr;

        this.constantInitializerValue = constantInitializerValue == null ? null
                : new ObjectTypedValueWrapper(constantInitializerValue);
        this.annotationInfo = annotationInfo == null || annotationInfo.isEmpty() ? null : annotationInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the name of the field.
     *
     * @return The name of the field.
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * Get the {@link ClassInfo} object for the declaring class (i.e. the class that declares this field).
     *
     * @return The {@link ClassInfo} object for the declaring class (i.e. the class that declares this field), or
     *         null if the class representing the type of the field was not encountered during scanning.
     */
    @Override
    public ClassInfo getClassInfo() {
        return super.getClassInfo();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the field modifiers as a string, e.g. "public static final". For the modifier bits, call getModifiers().
     * 
     * @return The field modifiers, as a string.
     */
    public String getModifierStr() {
        final StringBuilder buf = new StringBuilder();
        TypeUtils.modifiersToString(modifiers, ModifierType.FIELD, /* ignored */ false, buf);
        return buf.toString();
    }

    /**
     * Returns true if this field is public.
     * 
     * @return True if the field is public.
     */
    public boolean isPublic() {
        return Modifier.isPublic(modifiers);
    }

    /**
     * Returns true if this field is static.
     * 
     * @return True if the field is static.
     */
    public boolean isStatic() {
        return Modifier.isStatic(modifiers);
    }

    /**
     * Returns true if this field is final.
     * 
     * @return True if the field is final.
     */
    public boolean isFinal() {
        return Modifier.isFinal(modifiers);
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
     * Returns the modifier bits for the field.
     * 
     * @return The modifier bits.
     */
    public int getModifiers() {
        return modifiers;
    }

    /**
     * Returns the parsed type descriptor for the field, which will not include type parameters. If you need generic
     * type parameters, call {@link #getTypeSignature()} instead.
     * 
     * @return The parsed type descriptor string for the field.
     */
    public TypeSignature getTypeDescriptor() {
        if (typeDescriptorStr == null) {
            return null;
        }
        if (typeDescriptor == null) {
            try {
                typeDescriptor = TypeSignature.parse(typeDescriptorStr, declaringClassName);
                typeDescriptor.setScanResult(scanResult);
            } catch (final ParseException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return typeDescriptor;
    }

    /**
     * Returns the type descriptor string for the field, which will not include type parameters. If you need generic
     * type parameters, call {@link #getTypeSignatureStr()} instead.
     * 
     * @return The type descriptor string for the field.
     */
    public String getTypeDescriptorStr() {
        return typeDescriptorStr;
    }

    /**
     * Returns the parsed type signature for the field, possibly including type parameters. If this returns null,
     * indicating that no type signature information is available for this field, call {@link #getTypeDescriptor()}
     * instead.
     * 
     * @return The parsed type signature for the field, or null if not available.
     */
    public TypeSignature getTypeSignature() {
        if (typeSignatureStr == null) {
            return null;
        }
        if (typeSignature == null) {
            try {
                typeSignature = TypeSignature.parse(typeSignatureStr, declaringClassName);
                typeSignature.setScanResult(scanResult);
            } catch (final ParseException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return typeSignature;
    }

    /**
     * Returns the type signature string for the field, possibly including type parameters. If this returns null,
     * indicating that no type signature information is available for this field, call
     * {@link #getTypeDescriptorStr()} instead.
     * 
     * @return The type signature string for the field, or null if not available.
     */
    public String getTypeSignatureStr() {
        return typeSignatureStr;
    }

    /**
     * Returns the type signature for the field, possibly including type parameters. If the type signature is null,
     * indicating that no type signature information is available for this field, returns the type descriptor
     * instead.
     * 
     * @return The parsed type signature for the field, or if not available, the parsed type descriptor for the
     *         field.
     */
    public TypeSignature getTypeSignatureOrTypeDescriptor() {
        final TypeSignature typeSig = getTypeSignature();
        if (typeSig != null) {
            return typeSig;
        } else {
            return getTypeDescriptor();
        }
    }

    /**
     * Returns the type signature string for the field, possibly including type parameters. If the type signature
     * string is null, indicating that no type signature information is available for this field, returns the type
     * descriptor string instead.
     * 
     * @return The type signature string for the field, or if not available, the type descriptor string for the
     *         method.
     */
    public String getTypeSignatureOrTypeDescriptorStr() {
        if (typeSignatureStr != null) {
            return typeSignatureStr;
        } else {
            return typeDescriptorStr;
        }
    }

    /**
     * Returns the constant initializer value of a field. Requires
     * {@link ClassGraph#enableStaticFinalFieldConstantInitializerValues()} to have been called. Will only return
     * non-null for fields that have constant initializers, which is usually only fields of primitive type, or
     * String constants. Also note that it is up to the compiler as to whether or not a constant-valued field is
     * assigned as a constant in the field definition itself, or whether it is assigned manually in static or
     * non-static class initializer blocks or the constructor -- so your mileage may vary in being able to extract
     * constant initializer values.
     * 
     * @return The initializer value, if this field has a constant initializer value, or null if none.
     */
    public Object getConstantInitializerValue() {
        if (!scanResult.scanSpec.enableStaticFinalFieldConstantInitializerValues) {
            throw new IllegalArgumentException(
                    "Please call ClassGraph#enableStaticFinalFieldConstantInitializerValues() " + "before #scan()");
        }
        return constantInitializerValue == null ? null : constantInitializerValue.get();
    }

    /**
     * Get a list of annotations on this field, along with any annotation parameter values, wrapped in
     * {@link AnnotationInfo} objects.
     * 
     * @return A list of annotations on this field, along with any annotation parameter values, wrapped in
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
     * Get a the named non-{@link Repeatable} annotation on this field, or null if the field does not have the named
     * annotation. (Use {@link #getAnnotationInfoRepeatable(String)} for {@link Repeatable} annotations.)
     * 
     * @param annotationName
     *            The annotation name.
     * @return An {@link AnnotationInfo} object representing the named annotation on this field, or null if the
     *         field does not have the named annotation.
     */
    public AnnotationInfo getAnnotationInfo(final String annotationName) {
        return getAnnotationInfo().get(annotationName);
    }

    /**
     * Get a the named {@link Repeatable} annotation on this field, or the empty list if the field does not have the
     * named annotation.
     * 
     * @param annotationName
     *            The annotation name.
     * @return An {@link AnnotationInfoList} of all instances of the named annotation on this field, or the empty
     *         list if the field does not have the named annotation.
     */
    public AnnotationInfoList getAnnotationInfoRepeatable(final String annotationName) {
        return getAnnotationInfo().getRepeatable(annotationName);
    }

    /**
     * Check if the field has a given named annotation.
     *
     * @param annotationName
     *            The name of an annotation.
     * @return true if this field has the named annotation.
     */
    public boolean hasAnnotation(final String annotationName) {
        return getAnnotationInfo().containsName(annotationName);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Load the class this field is associated with, and get the {@link Field} reference for this field.
     * 
     * @return The {@link Field} reference for this field.
     * @throws IllegalArgumentException
     *             if the field does not exist.
     */
    public Field loadClassAndGetField() throws IllegalArgumentException {
        try {
            return loadClass().getField(getName());
        } catch (final NoSuchFieldException e1) {
            try {
                return loadClass().getDeclaredField(getName());
            } catch (final NoSuchFieldException e2) {
                throw new IllegalArgumentException("No such field: " + getClassName() + "." + getName());
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Handle {@link Repeatable} annotations.
     *
     * @param allRepeatableAnnotationNames
     *            the names of all repeatable annotations
     */
    void handleRepeatableAnnotations(final Set<String> allRepeatableAnnotationNames) {
        if (annotationInfo != null) {
            annotationInfo.handleRepeatableAnnotations(allRepeatableAnnotationNames, getClassInfo(),
                    RelType.FIELD_ANNOTATIONS, RelType.CLASSES_WITH_FIELD_ANNOTATION,
                    RelType.CLASSES_WITH_NONPRIVATE_FIELD_ANNOTATION);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the name of the declaring class, so that super.getClassInfo() returns the {@link ClassInfo} object
     * for the declaring class.
     *
     * @return the name of the declaring class.
     */
    @Override
    protected String getClassName() {
        return declaringClassName;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#setScanResult(io.github.classgraph.ScanResult)
     */
    @Override
    void setScanResult(final ScanResult scanResult) {
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
     *            the map from class name to {@link ClassInfo}.
     * @param refdClassInfo
     *            the referenced class info
     */
    @Override
    protected void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo) {
        final TypeSignature methodSig = getTypeSignature();
        if (methodSig != null) {
            methodSig.findReferencedClassInfo(classNameToClassInfo, refdClassInfo);
        }
        final TypeSignature methodDesc = getTypeDescriptor();
        if (methodDesc != null) {
            methodDesc.findReferencedClassInfo(classNameToClassInfo, refdClassInfo);
        }
        if (annotationInfo != null) {
            for (final AnnotationInfo ai : annotationInfo) {
                ai.findReferencedClassInfo(classNameToClassInfo, refdClassInfo);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Use class name and field name for equals().
     *
     * @param obj
     *            the object to compare to
     * @return true if equal
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof FieldInfo)) {
            return false;
        }
        final FieldInfo other = (FieldInfo) obj;
        return declaringClassName.equals(other.declaringClassName) && name.equals(other.name);
    }

    /**
     * Use hash code of class name and field name.
     *
     * @return the hashcode
     */
    @Override
    public int hashCode() {
        return name.hashCode() + declaringClassName.hashCode() * 11;
    }

    /**
     * Sort in order of class name then field name.
     *
     * @param other
     *            the other FieldInfo object to compare to.
     * @return the result of comparison.
     */
    @Override
    public int compareTo(final FieldInfo other) {
        final int diff = declaringClassName.compareTo(other.declaringClassName);
        if (diff != 0) {
            return diff;
        }
        return name.compareTo(other.name);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();

        if (annotationInfo != null) {
            for (final AnnotationInfo annotation : annotationInfo) {
                if (buf.length() > 0) {
                    buf.append(' ');
                }
                buf.append(annotation.toString());
            }
        }

        if (modifiers != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            TypeUtils.modifiersToString(modifiers, ModifierType.FIELD, /* ignored */ false, buf);
        }

        if (buf.length() > 0) {
            buf.append(' ');
        }
        buf.append(getTypeSignatureOrTypeDescriptor().toString());

        buf.append(' ');
        buf.append(name);

        if (constantInitializerValue != null) {
            final Object val = constantInitializerValue.get();
            buf.append(" = ");
            if (val instanceof String) {
                buf.append('"').append(((String) val).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            } else if (val instanceof Character) {
                buf.append('\'').append(((Character) val).toString().replace("\\", "\\\\").replaceAll("'", "\\'"))
                        .append('\'');
            } else {
                buf.append(val.toString());
            }
        }

        return buf.toString();
    }
}
