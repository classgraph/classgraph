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
package io.github.lukehutch.fastclasspathscanner.json;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.lukehutch.fastclasspathscanner.json.JSONMapper.Id;

/** Utils for Java serialization and deserialization. */
class JSONUtils {
    // See http://www.json.org/ under "string"
    private static final String[] JSON_CHAR_REPLACEMENTS = new String[256];
    static {
        for (int c = 0; c < 256; c++) {
            if (c == 32) {
                c = 127;
            }
            final int nibble1 = c >> 4;
            final char hexDigit1 = nibble1 <= 9 ? (char) ('0' + nibble1) : (char) ('A' + nibble1 - 10);
            final int nibble0 = c & 0xf;
            final char hexDigit0 = nibble0 <= 9 ? (char) ('0' + nibble0) : (char) ('A' + nibble0 - 10);
            JSON_CHAR_REPLACEMENTS[c] = "\\u00" + Character.toString(hexDigit1) + Character.toString(hexDigit0);
        }
        JSON_CHAR_REPLACEMENTS['"'] = "\\\"";
        JSON_CHAR_REPLACEMENTS['\\'] = "\\\\";
        JSON_CHAR_REPLACEMENTS['\n'] = "\\n";
        JSON_CHAR_REPLACEMENTS['\r'] = "\\r";
        JSON_CHAR_REPLACEMENTS['\t'] = "\\t";
        JSON_CHAR_REPLACEMENTS['\b'] = "\\b";
        JSON_CHAR_REPLACEMENTS['\f'] = "\\f";
    }

    /** Escape a string to be surrounded in double quotes in JSON. */
    static void escapeJSONString(final String unsafeStr, final StringBuilder buf) {
        if (unsafeStr == null) {
            return;
        }
        // Fast path
        boolean needsEscaping = false;
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            final char c = unsafeStr.charAt(i);
            if (c > 0xff || JSON_CHAR_REPLACEMENTS[c] != null) {
                needsEscaping = true;
                break;
            }
        }
        if (!needsEscaping) {
            buf.append(unsafeStr);
            return;
        }
        // Slow path
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            final char c = unsafeStr.charAt(i);
            if (c > 0xff) {
                buf.append("\\u");
                final int nibble3 = ((c) & 0xf000) >> 12;
                buf.append(nibble3 <= 9 ? (char) ('0' + nibble3) : (char) ('A' + nibble3 - 10));
                final int nibble2 = ((c) & 0xf00) >> 8;
                buf.append(nibble2 <= 9 ? (char) ('0' + nibble2) : (char) ('A' + nibble2 - 10));
                final int nibble1 = ((c) & 0xf0) >> 4;
                buf.append(nibble1 <= 9 ? (char) ('0' + nibble1) : (char) ('A' + nibble1 - 10));
                final int nibble0 = ((c) & 0xf);
                buf.append(nibble0 <= 9 ? (char) ('0' + nibble0) : (char) ('A' + nibble0 - 10));
            } else {
                final String replacement = JSON_CHAR_REPLACEMENTS[c];
                if (replacement == null) {
                    buf.append(c);
                } else {
                    buf.append(replacement);
                }
            }
        }
    }

    /** Escape a string to be surrounded in double quotes in JSON. */
    static String escapeJSONString(final String unsafeStr) {
        if (unsafeStr == null) {
            return unsafeStr;
        }
        // Fast path
        boolean needsEscaping = false;
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            final char c = unsafeStr.charAt(i);
            if (c > 0xff || JSON_CHAR_REPLACEMENTS[c] != null) {
                needsEscaping = true;
                break;
            }
        }
        if (!needsEscaping) {
            return unsafeStr;
        }
        // Slow path
        final StringBuilder buf = new StringBuilder(unsafeStr.length() * 2);
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            final char c = unsafeStr.charAt(i);
            if (c > 0xff) {
                buf.append("\\u");
                final int nibble3 = ((c) & 0xf000) >> 12;
                buf.append(nibble3 <= 9 ? (char) ('0' + nibble3) : (char) ('A' + nibble3 - 10));
                final int nibble2 = ((c) & 0xf00) >> 8;
                buf.append(nibble2 <= 9 ? (char) ('0' + nibble2) : (char) ('A' + nibble2 - 10));
                final int nibble1 = ((c) & 0xf0) >> 4;
                buf.append(nibble1 <= 9 ? (char) ('0' + nibble1) : (char) ('A' + nibble1 - 10));
                final int nibble0 = ((c) & 0xf);
                buf.append(nibble0 <= 9 ? (char) ('0' + nibble0) : (char) ('A' + nibble0 - 10));
            } else {
                final String replacement = JSON_CHAR_REPLACEMENTS[c];
                if (replacement == null) {
                    buf.append(c);
                } else {
                    buf.append(replacement);
                }
            }
        }
        return buf.toString();
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Lookup table for fast indenting */
    private static final String[] INDENT_LEVELS = new String[17];
    static {
        final StringBuilder buf = new StringBuilder();
        for (int i = 0; i < INDENT_LEVELS.length; i++) {
            INDENT_LEVELS[i] = buf.toString();
            buf.append(' ');
        }
    }

    /** Indent (depth * indentWidth) spaces. */
    static void indent(final int depth, final int indentWidth, final StringBuilder buf) {
        final int maxIndent = INDENT_LEVELS.length - 1;
        for (int d = depth * indentWidth; d > 0;) {
            final int n = Math.min(d, maxIndent);
            buf.append(INDENT_LEVELS[n]);
            d -= n;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    static class SerializableFieldInfo {
        List<Field> serializableFields = new ArrayList<>();
        List<Type> fieldElementType0 = new ArrayList<>(); // Can be cast to Class<?>
        List<Type> fieldElementType1 = new ArrayList<>();
        Field idField;
    }

    @SuppressWarnings("deprecation")
    static SerializableFieldInfo getSerializableFieldInfo(
            final Map<Class<?>, SerializableFieldInfo> classToSerializableFieldInfo,
            final Class<? extends Object> cls) {
        SerializableFieldInfo serializableFieldInfo = classToSerializableFieldInfo.get(cls);
        if (serializableFieldInfo == null) {
            classToSerializableFieldInfo.put(cls, serializableFieldInfo = new SerializableFieldInfo());
            final Field[] fields = cls.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                final Field field = fields[i];
                final boolean hasIdAnnotation = field.isAnnotationPresent(Id.class);
                // Don't serialize transient, final or synthetic fields
                final int modifiers = field.getModifiers();
                if (!Modifier.isTransient(modifiers) && !Modifier.isFinal(modifiers)
                        && ((modifiers & 0x1000 /* synthetic */) == 0)) {

                    // Make field accessible if needed
                    if (!field.isAccessible()) {
                        try {
                            field.setAccessible(true);
                        } catch (final Exception e) {
                            AccessController.doPrivileged(new PrivilegedAction<Void>() {
                                @Override
                                public Void run() {
                                    try {
                                        field.setAccessible(true);
                                    } catch (final Exception e) {
                                    }
                                    return null;
                                }
                            });
                        }
                    }

                    // Save field among serializable fields
                    serializableFieldInfo.serializableFields.add(field);

                    // Store ID field if necessary
                    if (hasIdAnnotation) {
                        serializableFieldInfo.idField = field;
                    }

                    final Class<?> fieldType = field.getType();
                    if (fieldType.isArray()) {
                        // Store component type of an array
                        serializableFieldInfo.fieldElementType0.add(fieldType.getComponentType());
                        serializableFieldInfo.fieldElementType1.add(null);
                    } else {
                        // Store any generic parameter type (for collections) or types (for maps)
                        final Type genericFieldType = field.getGenericType();
                        Type type0 = null;
                        Type type1 = null;
                        if (genericFieldType instanceof ParameterizedType) {
                            final ParameterizedType aType = (ParameterizedType) genericFieldType;
                            final Type[] fieldArgTypes = aType.getActualTypeArguments();
                            type0 = fieldArgTypes.length > 0 ? fieldArgTypes[0] : null;
                            type1 = fieldArgTypes.length > 1 ? fieldArgTypes[1] : null;
                        }
                        serializableFieldInfo.fieldElementType0.add(type0);
                        serializableFieldInfo.fieldElementType1.add(type1);
                    }
                } else if (hasIdAnnotation) {
                    throw new IllegalArgumentException("Cannot use @Id annotation on transient or final field: "
                            + cls + "." + field.getName());
                }
            }
        }
        return serializableFieldInfo;

    }
}
