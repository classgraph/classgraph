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

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.github.lukehutch.fastclasspathscanner.utils.Parser.ParseException;
import io.github.lukehutch.fastclasspathscanner.utils.TypeUtils;

/**
 * Holds metadata about fields of a class encountered during a scan. All values are taken directly out of the
 * classfile for the class.
 */
public class FieldInfo extends ScanResultObject implements Comparable<FieldInfo> {
    transient String className;
    String fieldName;
    int modifiers;
    String typeSignatureStr;
    String typeDescriptorStr;
    transient TypeSignature typeSignature;
    transient TypeSignature typeDescriptor;
    Object constValue;
    List<AnnotationInfo> annotationInfo;

    /** Default constructor for deserialization. */
    FieldInfo() {
    }

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
     * @param className
     *            The class the field is defined within.
     * @param fieldName
     *            The name of the field.
     * @param modifiers
     *            The field modifiers.
     * @param typeDescriptorStr
     *            The field type descriptor.
     * @param typeSignatureStr
     *            The field type signature.
     * @param constValue
     *            The static constant value the field is initialized to, if any.
     * @param annotationInfo
     *            {@link AnnotationInfo} for any annotations on the field.
     */
    public FieldInfo(final String className, final String fieldName, final int modifiers,
            final String typeDescriptorStr, final String typeSignatureStr, final Object constValue,
            final List<AnnotationInfo> annotationInfo) {
        if (fieldName == null) {
            throw new IllegalArgumentException();
        }
        this.className = className;
        this.fieldName = fieldName;
        this.modifiers = modifiers;
        this.typeDescriptorStr = typeDescriptorStr;
        this.typeSignatureStr = typeSignatureStr;

        this.constValue = constValue;
        this.annotationInfo = annotationInfo == null || annotationInfo.isEmpty() ? null : annotationInfo;
    }

    /**
     * Get the name of the class this field is defined within.
     * 
     * @return The name of the class this field is defined within.
     */
    public String getClassName() {
        return className;
    }

    /**
     * Returns the name of the field.
     * 
     * @return The name of the field.
     */
    public String getFieldName() {
        return fieldName;
    }

    /**
     * Deprecated, use getModifierStr() instead.
     * 
     * @return The modifiers as a string.
     */
    @Deprecated
    public String getModifierStrs() {
        return getModifierStr();
    }

    /**
     * Get the field modifiers as a string, e.g. "public static final". For the modifier bits, call getModifiers().
     * 
     * @return The field modifiers, as a string.
     */
    public String getModifierStr() {
        return TypeUtils.modifiersToString(modifiers, /* isMethod = */ false);
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
     * Returns true if this field is private.
     * 
     * @return True if the field is private.
     */
    public boolean isPrivate() {
        return Modifier.isPrivate(modifiers);
    }

    /**
     * Returns true if this field is protected.
     * 
     * @return True if the field is protected.
     */
    public boolean isProtected() {
        return Modifier.isProtected(modifiers);
    }

    /**
     * Returns true if this field is package-private.
     * 
     * @return True if the field is package-private.
     */
    public boolean isPackagePrivate() {
        return !isPublic() && !isPrivate() && !isProtected();
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
     * Returns the low-level internal type descriptor string for the field, without type parameters, e.g.
     * "Ljava/util/List;".
     * 
     * @return The low-level type descriptor for the field.
     */
    public String getTypeDescriptorStr() {
        return typeDescriptorStr;
    }

    /**
     * Returns the parsed type descriptor for the field, if available.
     * 
     * @return The parsed type descriptor for the field, if available, else returns null.
     */
    public TypeSignature getTypeDescriptor() {
        if (typeDescriptorStr == null) {
            return null;
        }
        if (typeDescriptor == null) {
            try {
                typeDescriptor = TypeSignature.parse(typeDescriptorStr);
            } catch (final ParseException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return typeDescriptor;
    }

    /**
     * Returns the low-level internal type signature string for the method, possibly with type parameters.
     * 
     * @return The low-level internal type descriptor for the field.
     */
    public String getTypeSignatureStr() {
        return typeSignatureStr;
    }

    /**
     * Returns the parsed type signature for the field, if available.
     * 
     * @return The parsed type signature for the field, if available, else returns null.
     */
    public TypeSignature getTypeSignature() {
        if (typeSignatureStr == null) {
            return null;
        }
        if (typeSignature == null) {
            try {
                typeSignature = TypeSignature.parse(typeSignatureStr);
            } catch (final ParseException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return typeSignature;
    }

    /**
     * Returns the parsed type signature for the field, possibly including type parameters. If the type signature is
     * null, indicating that no type signature information is available for this field, returns the parsed type
     * descriptor instead.
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
     * Returns the {@code Class<?>} reference for the field. Note that this calls Class.forName() on the field type,
     * which will cause the class to be loaded, and possibly initialized. If the class is initialized, this can
     * trigger side effects.
     * 
     * @return The{@code Class<?>} reference for the field.
     * @throws IllegalArgumentException
     *             if the field type could not be loaded.
     */
    public Class<?> getType() throws IllegalArgumentException {
        return getTypeDescriptor().instantiate(/* ignoreExceptions = */ false);
    }

    /**
     * Returns the constant final initializer value of the field.
     * 
     * @return The constant final initializer value of the field, or null if none.
     */
    public Object getConstFinalValue() {
        return constValue;
    }

    /**
     * Returns the names of unique annotations on the field.
     * 
     * @return The names of unique annotations on the field, or the empty list if none.
     */
    public List<String> getAnnotationNames() {
        return annotationInfo == null ? Collections.<String> emptyList()
                : Arrays.asList(AnnotationInfo.getUniqueAnnotationNamesSorted(annotationInfo));
    }

    /**
     * Returns {@code Class<?>} references for the unique annotations on this field. Note that this calls
     * Class.forName() on the annotation types, which will cause each annotation class to be loaded.
     *
     * @return {@code Class<?>} references for the unique annotations on this field.
     * @throws IllegalArgumentException
     *             if the annotation type could not be loaded.
     */
    public List<Class<?>> getAnnotationTypes() throws IllegalArgumentException {
        if (annotationInfo == null || annotationInfo.isEmpty()) {
            return Collections.<Class<?>> emptyList();
        } else {
            final List<Class<?>> annotationClassRefs = new ArrayList<>();
            for (final String annotationName : getAnnotationNames()) {
                annotationClassRefs.add(scanResult.loadClass(annotationName, /* ignoreExceptions = */ false));
            }
            return annotationClassRefs;
        }
    }

    /**
     * Get a list of annotations on this field, along with any annotation parameter values, wrapped in
     * {@link AnnotationInfo} objects.
     * 
     * @return A list of annotations on this field, along with any annotation parameter values, wrapped in
     *         {@link AnnotationInfo} objects, or the empty list if none.
     */
    public List<AnnotationInfo> getAnnotationInfo() {
        return annotationInfo == null ? Collections.<AnnotationInfo> emptyList() : annotationInfo;
    }

    /** Use class name and field name for equals(). */
    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (this.getClass() != obj.getClass()) {
            return false;
        }
        final FieldInfo other = (FieldInfo) obj;
        return className.equals(other.className) && fieldName.equals(other.fieldName);
    }

    /** Use hash code of class name and field name. */
    @Override
    public int hashCode() {
        return fieldName.hashCode() + className.hashCode() * 11;
    }

    /** Sort in order of class name then field name */
    @Override
    public int compareTo(final FieldInfo other) {
        final int diff = className.compareTo(other.className);
        if (diff != 0) {
            return diff;
        }
        return fieldName.compareTo(other.fieldName);
    }

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
            TypeUtils.modifiersToString(modifiers, /* isMethod = */ false, buf);
        }

        if (buf.length() > 0) {
            buf.append(' ');
        }
        buf.append(getTypeSignatureOrTypeDescriptor().toString());

        buf.append(' ');
        buf.append(fieldName);

        if (constValue != null) {
            buf.append(" = ");
            if (constValue instanceof String) {
                buf.append("\"" + ((String) constValue).replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
            } else if (constValue instanceof Character) {
                buf.append("'" + ((Character) constValue).toString().replace("\\", "\\\\").replaceAll("'", "\\'")
                        + "'");
            } else {
                buf.append(constValue.toString());
            }
        }

        return buf.toString();
    }
}
