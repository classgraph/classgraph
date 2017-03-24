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
import java.util.Collections;
import java.util.List;

import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;

/**
 * Holds metadata about fields of a class encountered during a scan. All values are taken directly out of the
 * classfile for the class.
 */
public class FieldInfo {
    private final String fieldName;
    private final int modifiers;
    private final String typeStr;
    private final Object constValue;
    private final List<String> annotationNames;

    public FieldInfo(final String fieldName, final int modifiers, final String typeDescriptor,
            final Object constValue, final List<String> annotationNames) {
        this.fieldName = fieldName;
        this.modifiers = modifiers;

        final List<String> typeNames = ReflectionUtils.parseTypeDescriptor(typeDescriptor);
        if (typeNames.size() != 1) {
            throw new IllegalArgumentException("Invalid type descriptor for field: " + typeDescriptor);
        }
        this.typeStr = typeNames.get(0);

        this.constValue = constValue;
        this.annotationNames = annotationNames.isEmpty() ? Collections.<String>emptyList() : annotationNames;
    }

    /** Get the field modifiers as a string, e.g. "public static final". */
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

    /** Returns the name of the field. */
    public String getFieldName() {
        return fieldName;
    }

    /** Returns the access flags of the field. */
    public int getAccessFlags() {
        return modifiers;
    }

    /** Returns the type of the field, in string representation (e.g. "int[][]"). */
    public String getTypeStr() {
        return typeStr;
    }

    /** Returns the constant final initializer value of the field, or null if none. */
    public Object getConstFinalValue() {
        return constValue;
    }

    /** Returns the names of annotations on the field, or the empty list if none. */
    public List<String> getAnnotationNames() {
        return annotationNames;
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();

        if (!annotationNames.isEmpty()) {
            for (final String annotationName : annotationNames) {
                if (buf.length() > 0) {
                    buf.append(' ');
                }
                buf.append("@" + annotationName);
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
