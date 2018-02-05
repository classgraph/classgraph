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
 * Copyright (c) 2016 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.scanner;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult.InfoObject;
import io.github.lukehutch.fastclasspathscanner.utils.TypeParser;
import io.github.lukehutch.fastclasspathscanner.utils.TypeParser.TypeSignature;

/**
 * Holds metadata about fields of a class encountered during a scan. All values are taken directly out of the
 * classfile for the class.
 */
public class FieldInfo extends InfoObject implements Comparable<FieldInfo> {
    private final String className;
    private final String fieldName;
    private final int modifiers;
    /** The JVM-internal type descriptor (missing type parameters). */
    private final String typeDescriptorInternal;
    private TypeSignature typeSignatureInternal;
    /**
     * The human-readable type descriptor (may have type parameter information included, if present and available).
     */
    private final String typeDescriptorHumanReadable;
    private TypeSignature typeSignatureHumanReadable;
    private final Object constValue;
    final List<AnnotationInfo> annotationInfo;
    private ScanResult scanResult;

    /** Sets back-reference to scan result after scan is complete. */
    @Override
    void setScanResult(final ScanResult scanResult) {
        this.scanResult = scanResult;
        if (this.annotationInfo != null) {
            for (final AnnotationInfo ai : this.annotationInfo) {
                ai.setScanResult(scanResult);
            }
        }
    }

    public FieldInfo(final String className, final String fieldName, final int modifiers,
            final String typeDescriptorInternal, final String typeDescriptorHumanReadable, final Object constValue,
            final List<AnnotationInfo> annotationInfo) {
        this.className = className;
        this.fieldName = fieldName;
        this.modifiers = modifiers;
        this.typeDescriptorInternal = typeDescriptorInternal;
        this.typeDescriptorHumanReadable = typeDescriptorHumanReadable;

        this.constValue = constValue;
        this.annotationInfo = annotationInfo == null || annotationInfo.isEmpty()
                ? Collections.<AnnotationInfo> emptyList()
                : annotationInfo;
    }

    /** Get the name of the class this method is part of. */
    public String getClassName() {
        return className;
    }

    /** Returns the name of the field. */
    public String getFieldName() {
        return fieldName;
    }

    /**
     * Get the field modifiers as a string, e.g. "public static final". For the modifier bits, call
     * getAccessFlags().
     */
    public String getModifierStrs() {
        return TypeParser.modifiersToString(modifiers, /* isMethod = */ false);
    }

    /** Returns true if this field is public. */
    public boolean isPublic() {
        return Modifier.isPublic(modifiers);
    }

    /** Returns true if this field is private. */
    public boolean isPrivate() {
        return Modifier.isPrivate(modifiers);
    }

    /** Returns true if this field is protected. */
    public boolean isProtected() {
        return Modifier.isProtected(modifiers);
    }

    /** Returns true if this field is package-private. */
    public boolean isPackagePrivate() {
        return !isPublic() && !isPrivate() && !isProtected();
    }

    /** Returns true if this field is static. */
    public boolean isStatic() {
        return Modifier.isStatic(modifiers);
    }

    /** Returns true if this field is final. */
    public boolean isFinal() {
        return Modifier.isFinal(modifiers);
    }

    /** Returns true if this field is a transient field. */
    public boolean isTransient() {
        return Modifier.isTransient(modifiers);
    }

    /** Returns the access flags of the field. */
    public int getModifiers() {
        return modifiers;
    }

    /**
     * Returns the internal type descriptor for the field, e.g. "Ljava/lang/String;". This is the internal type
     * descriptor used by the JVM, so does not include type parameters (due to type erasure). See also
     * {@link getTypeDescriptor()}.
     */
    public String getTypeDescriptorInternal() {
        return typeDescriptorInternal;
    }

    /**
     * Returns the type descriptor for the field, e.g. "Ljava/lang/String;". This is a machine-readable type string,
     * but it presents the programmer's view of the method type, in the sense that type parameters are included if
     * they are available. See also {@link getTypeDescriptorInternal()}.
     */
    public String getTypeDescriptor() {
        if (typeDescriptorHumanReadable == null) {
            return typeDescriptorInternal;
        }
        return typeDescriptorHumanReadable;
    }

    /**
     * Returns the internal Java type signature for the field. This is the internal type signature used by the JVM,
     * so does not include type parameters (due to type erasure). See also {@link getTypeSignature()}.
     */
    public TypeSignature getTypeSignatureInternal() {
        if (typeSignatureInternal == null) {
            typeSignatureInternal = TypeParser.parseTypeSignature(typeDescriptorInternal);
        }
        return typeSignatureInternal;
    }

    /**
     * Returns the Java type signature for the method. This is the programmer-visible type signature, in the sense
     * that type parameters are included if they are available. See also {@link getTypeSignatureInternal()}.
     */
    public TypeSignature getTypeSignature() {
        if (typeDescriptorHumanReadable == null) {
            return getTypeSignatureInternal();
        }
        if (typeSignatureHumanReadable == null) {
            typeSignatureHumanReadable = TypeParser.parseTypeSignature(typeDescriptorHumanReadable);
        }
        return typeSignatureHumanReadable;
    }

    /**
     * Returns the Class<?> reference for the field. Note that this calls Class.forName() on the field type, which
     * will cause the class to be loaded, and possibly initialized. If the class is initialized, this can trigger
     * side effects.
     *
     * @throws IllegalArgumentException
     *             if the field type could not be loaded.
     */
    public Class<?> getType() throws IllegalArgumentException {
        return getTypeSignature().instantiate(scanResult);
    }

    /** Returns the type of the field, in string representation (e.g. "int[][]"). */
    public String getTypeStr() {
        return getTypeSignature().toString();
    }

    /** Returns the constant final initializer value of the field, or null if none. */
    public Object getConstFinalValue() {
        return constValue;
    }

    /** Returns the names of unique annotations on the field, or the empty list if none. */
    public List<String> getAnnotationNames() {
        return Arrays.asList(AnnotationInfo.getUniqueAnnotationNamesSorted(annotationInfo));
    }

    /**
     * Returns Class references for the unique annotations on this field. Note that this calls Class.forName() on
     * the annotation types, which will cause each annotation class to be loaded.
     *
     * @throws IllegalArgumentException
     *             if the annotation type could not be loaded.
     */
    public List<Class<?>> getAnnotationTypes() throws IllegalArgumentException {
        if (annotationInfo.isEmpty()) {
            return Collections.<Class<?>> emptyList();
        } else {
            final List<Class<?>> annotationClassRefs = new ArrayList<>();
            for (final String annotationName : getAnnotationNames()) {
                annotationClassRefs.add(scanResult.classNameToClassRef(annotationName));
            }
            return annotationClassRefs;
        }
    }

    /**
     * Get a list of annotations on this field, along with any annotation parameter values, wrapped in
     * AnnotationInfo objects, or the empty list if none.
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

        if (buf.length() > 0) {
            buf.append(' ');
        }
        TypeParser.modifiersToString(modifiers, /* isMethod = */ false, buf);

        if (buf.length() > 0 && buf.charAt(buf.length() - 1) != ' ') {
            buf.append(' ');
        }
        buf.append(getTypeStr());

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
