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
import java.util.Collection;
import java.util.Map;

/** Utils for Java serialization and deserialization. */
public class JSONUtils {
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
    public static String escapeJSONString(final String unsafeStr) {
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

    /** Get a field value, appropriately handling primitive-typed fields. */
    static Object getFieldValue(final Field field, final Object obj)
            throws IllegalArgumentException, IllegalAccessException {
        final Class<?> fieldType = field.getType();
        if (fieldType == Integer.TYPE) {
            return Integer.valueOf(field.getInt(obj));
        } else if (fieldType == Long.TYPE) {
            return Long.valueOf(field.getLong(obj));
        } else if (fieldType == Short.TYPE) {
            return Short.valueOf(field.getShort(obj));
        } else if (fieldType == Double.TYPE) {
            return Double.valueOf(field.getDouble(obj));
        } else if (fieldType == Float.TYPE) {
            return Float.valueOf(field.getFloat(obj));
        } else if (fieldType == Boolean.TYPE) {
            return Boolean.valueOf(field.getBoolean(obj));
        } else if (fieldType == Byte.TYPE) {
            return Byte.valueOf(field.getByte(obj));
        } else if (fieldType == Character.TYPE) {
            return Character.valueOf(field.getChar(obj));
        } else {
            return field.get(obj);
        }
    }

    /** Set a field value, appropriately handling primitive-typed fields. */
    static void setFieldValue(final Field field, final Object obj, final Object value)
            throws IllegalArgumentException, IllegalAccessException {
        final Class<?> fieldType = field.getType();
        if (fieldType == Integer.TYPE) {
            if (value == null) {
                throw new IllegalArgumentException("Tried to set primitive int-typed field to null value");
            }
            if (!(value instanceof Integer)) {
                throw new IllegalArgumentException(
                        "Expected value of type Integer; got " + value.getClass().getName());
            }
            field.setInt(obj, ((Integer) value).intValue());
        } else if (fieldType == Long.TYPE) {
            if (value == null) {
                throw new IllegalArgumentException("Tried to set primitive long-typed field to null value");
            }
            if (!(value instanceof Long)) {
                throw new IllegalArgumentException(
                        "Expected value of type Long; got " + value.getClass().getName());
            }
            field.setLong(obj, ((Long) value).longValue());
        } else if (fieldType == Short.TYPE) {
            if (value == null) {
                throw new IllegalArgumentException("Tried to set primitive short-typed field to null value");
            }
            if (!(value instanceof Short)) {
                throw new IllegalArgumentException(
                        "Expected value of type Short; got " + value.getClass().getName());
            }
            field.setShort(obj, ((Short) value).shortValue());
        } else if (fieldType == Double.TYPE) {
            if (value == null) {
                throw new IllegalArgumentException("Tried to set primitive double-typed field to null value");
            }
            if (!(value instanceof Double)) {
                throw new IllegalArgumentException(
                        "Expected value of type Double; got " + value.getClass().getName());
            }
            field.setDouble(obj, ((Double) value).doubleValue());
        } else if (fieldType == Float.TYPE) {
            if (value == null) {
                throw new IllegalArgumentException("Tried to set primitive float-typed field to null value");
            }
            if (!(value instanceof Float)) {
                throw new IllegalArgumentException(
                        "Expected value of type Float; got " + value.getClass().getName());
            }
            field.setFloat(obj, ((Float) value).floatValue());
        } else if (fieldType == Boolean.TYPE) {
            if (value == null) {
                throw new IllegalArgumentException("Tried to set primitive boolean-typed field to null value");
            }
            if (!(value instanceof Boolean)) {
                throw new IllegalArgumentException(
                        "Expected value of type Boolean; got " + value.getClass().getName());
            }
            field.setBoolean(obj, ((Boolean) value).booleanValue());
        } else if (fieldType == Byte.TYPE) {
            if (value == null) {
                throw new IllegalArgumentException("Tried to set primitive byte-typed field to null value");
            }
            if (!(value instanceof Byte)) {
                throw new IllegalArgumentException(
                        "Expected value of type Byte; got " + value.getClass().getName());
            }
            field.setByte(obj, ((Byte) value).byteValue());
        } else if (fieldType == Character.TYPE) {
            if (value == null) {
                throw new IllegalArgumentException("Tried to set primitive char-typed field to null value");
            }
            if (!(value instanceof Character)) {
                throw new IllegalArgumentException(
                        "Expected value of type Character; got " + value.getClass().getName());
            }
            field.setChar(obj, ((Character) value).charValue());
        } else {
            field.set(obj, value);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Return true for classes that can be equal to a basic value type (types that can be converted directly to and
     * from string representation).
     */
    static boolean isBasicValueType(final Class<?> cls) {
        return cls == String.class //
                || cls == Integer.class || cls == Integer.TYPE //
                || cls == Boolean.class || cls == Boolean.TYPE //
                || cls == Long.class || cls == Long.TYPE //
                || cls == Float.class || cls == Float.TYPE //
                || cls == Double.class || cls == Double.TYPE //
                || cls == Short.class || cls == Short.TYPE //
                || cls == Byte.class || cls == Byte.TYPE //
                || cls == Character.class || cls == Character.TYPE //
                || cls.isEnum();
    }

    /** Return true for objects that can be converted directly to and from string representation. */
    static boolean isBasicValueType(final Object obj) {
        return obj == null || obj instanceof String || obj instanceof Integer || obj instanceof Boolean
                || obj instanceof Long || obj instanceof Float || obj instanceof Double || obj instanceof Short
                || obj instanceof Byte || obj instanceof Character || obj.getClass().isEnum();
    }

    /**
     * Return true for classes that are collections or arrays (i.e. objects that are convertible to a JSON array).
     */
    static boolean isCollectionOrArray(final Class<?> cls) {
        return Collection.class.isAssignableFrom(cls) || cls.isArray();
    }

    /**
     * Return true for objects that are collections or arrays (i.e. objects that are convertible to a JSON array).
     */
    static boolean isCollectionOrArray(final Object obj) {
        final Class<? extends Object> cls = obj.getClass();
        return Collection.class.isAssignableFrom(cls) || cls.isArray();
    }

    /**
     * Return true for objects that are collections, arrays, or Maps (i.e. objects that may have type parameters,
     * but that are handled specially).
     */
    static boolean isCollectionOrArrayOrMap(final Object obj) {
        final Class<? extends Object> cls = obj.getClass();
        return Collection.class.isAssignableFrom(cls) || cls.isArray() || Map.class.isAssignableFrom(cls);
    }
}
