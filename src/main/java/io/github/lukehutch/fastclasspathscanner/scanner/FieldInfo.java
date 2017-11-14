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
import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;

/**
 * Holds metadata about fields of a class encountered during a scan. All values are taken directly out of the
 * classfile for the class.
 */
public class FieldInfo extends InfoObject implements Comparable<FieldInfo> {
    private final String className;
    private final String fieldName;
    private final int modifiers;
    private final String typeDescriptor;
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
            final String typeDescriptor, final Object constValue, final List<AnnotationInfo> annotationInfo) {
        this.className = className;
        this.fieldName = fieldName;
        this.modifiers = modifiers;
        this.typeDescriptor = typeDescriptor;

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
    // TODO: rename to getModifiersStr()
    public String getModifiers() {
        return ReflectionUtils.modifiersToString(modifiers, /* isMethod = */ false);
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
    // TODO: Rename to getModifiers()
    public int getAccessFlags() {
        return modifiers;
    }

    /** Returns the internal type descriptor for the field, e.g. "Ljava/lang/String;" */
    public String getTypeDescriptor() {
        return typeDescriptor;
    }

    /** Returns the type of the field, in string representation (e.g. "int[][]"). */
    public String getTypeStr() {
        final List<String> typeNames = ReflectionUtils.parseComplexTypeDescriptor(typeDescriptor);
        if (typeNames.size() != 1) {
            throw new IllegalArgumentException("Invalid type descriptor for field: " + typeDescriptor);
        }
        return typeNames.get(0);
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
        final String typeStr = getTypeStr();
        return ReflectionUtils.typeStrToClass(typeStr, scanResult);
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
                annotationClassRefs.add(ReflectionUtils.typeStrToClass(annotationName, scanResult));
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
        buf.append(getModifiers());

        if (buf.length() > 0) {
            buf.append(' ');
        }
        buf.append(getTypeStr());

        buf.append(' ');
        buf.append(fieldName);

        if (constValue != null) {
            buf.append(" = ");
            if (constValue instanceof String) {
                buf.append("\"" + constValue + "\"");
            } else if (constValue instanceof Character) {
                buf.append("'" + constValue + "'");
            } else {
                buf.append(constValue.toString());
            }
        }

        return buf.toString();
    }
}
