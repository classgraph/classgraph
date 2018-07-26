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
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.utils.Parser.ParseException;
import io.github.lukehutch.fastclasspathscanner.utils.TypeUtils;

/**
 * Holds metadata about fields of a class encountered during a scan. All values are taken directly out of the
 * classfile for the class.
 */
public class FieldInfo extends ScanResultObject implements Comparable<FieldInfo> {
    String definingClassName;
    String name;
    int modifiers;
    String typeSignatureStr;
    String typeDescriptorStr;
    transient TypeSignature typeSignature;
    transient TypeSignature typeDescriptor;
    ObjectTypedValueWrapper constantInitializerValue;
    AnnotationInfoList annotationInfo;

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
     * @param scanSpec
     *            The {@link ScanSpec}.
     */
    FieldInfo(final String definingClassName, final String fieldName, final int modifiers,
            final String typeDescriptorStr, final String typeSignatureStr, final Object constantInitializerValue,
            final AnnotationInfoList annotationInfo) {
        if (fieldName == null) {
            throw new IllegalArgumentException();
        }
        this.definingClassName = definingClassName;
        this.name = fieldName;
        this.modifiers = modifiers;
        this.typeDescriptorStr = typeDescriptorStr;
        this.typeSignatureStr = typeSignatureStr;

        this.constantInitializerValue = constantInitializerValue == null ? null
                : new ObjectTypedValueWrapper(constantInitializerValue);
        this.annotationInfo = annotationInfo == null || annotationInfo.isEmpty() ? null : annotationInfo;
    }

    /**
     * Returns the name of the field.
     * 
     * @return The name of the field.
     */
    public String getName() {
        return name;
    }

    /**
     * Get the name of the class this field is defined within.
     * 
     * @return The name of the class this field is defined within.
     */
    public String getDefiningClassName() {
        return definingClassName;
    }

    /**
     * Returns the defining class name, so that {@link #getClassInfo()} returns the {@link ClassInfo} object for the
     * defining class.
     */
    @Override
    protected String getClassName() {
        return definingClassName;
    }

    /**
     * Get the class this field is defined within.
     * 
     * @return The class this field is defined within.
     */
    public ClassInfo getDefiningClassInfo() {
        return getClassInfo();
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
                typeDescriptor.setScanResult(scanResult);
            } catch (final ParseException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return typeDescriptor;
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
                typeSignature.setScanResult(scanResult);
            } catch (final ParseException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return typeSignature;
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

    /** Get the names of any classes in the type descriptor or type signature. */
    @Override
    protected void getClassNamesFromTypeDescriptors(final Set<String> classNames) {
        final TypeSignature methodSig = getTypeSignature();
        if (methodSig != null) {
            methodSig.getClassNamesFromTypeDescriptors(classNames);
        }
        final TypeSignature methodDesc = getTypeDescriptor();
        if (methodDesc != null) {
            methodDesc.getClassNamesFromTypeDescriptors(classNames);
        }
        if (annotationInfo != null) {
            for (final AnnotationInfo annotationInfo : annotationInfo) {
                annotationInfo.getClassNamesFromTypeDescriptors(classNames);
            }
        }
    }

    /**
     * Returns the constant final initializer value of the field. Requires
     * {@link FastClasspathScanner#enableStaticFinalFieldConstantInitializerValues()} to have been called.
     * 
     * @return The constant final initializer value of the field, or null if none.
     */
    public Object getConstantInitializerValue() {
        if (!scanResult.scanSpec.enableStaticFinalFieldConstantInitializerValues) {
            throw new IllegalArgumentException(
                    "Please call FastClasspathScanner#enableStaticFinalFieldConstantInitializerValues() "
                            + "before #scan()");
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
            throw new IllegalArgumentException(
                    "Please call FastClasspathScanner#enableAnnotationInfo() before #scan()");
        }
        return annotationInfo == null ? AnnotationInfoList.EMPTY_LIST : annotationInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

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
        return definingClassName.equals(other.definingClassName) && name.equals(other.name);
    }

    /** Use hash code of class name and field name. */
    @Override
    public int hashCode() {
        return name.hashCode() + definingClassName.hashCode() * 11;
    }

    /** Sort in order of class name then field name */
    @Override
    public int compareTo(final FieldInfo other) {
        final int diff = definingClassName.compareTo(other.definingClassName);
        if (diff != 0) {
            return diff;
        }
        return name.compareTo(other.name);
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
        buf.append(name);

        if (constantInitializerValue != null) {
            final Object val = constantInitializerValue.get();
            buf.append(" = ");
            if (val instanceof String) {
                buf.append("\"" + ((String) val).replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
            } else if (val instanceof Character) {
                buf.append("'" + ((Character) val).toString().replace("\\", "\\\\").replaceAll("'", "\\'") + "'");
            } else {
                buf.append(val.toString());
            }
        }

        return buf.toString();
    }
}
