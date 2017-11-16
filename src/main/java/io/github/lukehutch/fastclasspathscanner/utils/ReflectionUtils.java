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
package io.github.lukehutch.fastclasspathscanner.utils;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;

/** Reflection utility methods that can be used by ClassLoaderHandlers. */
public class ReflectionUtils {
    /** Get the value of the named field in the class of the given object or any of its superclasses. */
    public static Object getFieldVal(final Object obj, final String fieldName)
            throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException {
        if (obj != null) {
            for (Class<?> classOrSuperclass = obj.getClass(); classOrSuperclass != null; //
                    classOrSuperclass = classOrSuperclass.getSuperclass()) {
                try {
                    final Field field = classOrSuperclass.getDeclaredField(fieldName);
                    if (!field.isAccessible()) {
                        field.setAccessible(true);
                    }
                    return field.get(obj);
                } catch (final NoSuchFieldException e) {
                    // Try parent
                }
            }
        }
        return null;
    }

    /** Get the value of the named static field in the given class or any of its superclasses. */
    public static Object getStaticFieldVal(final Class<?> cls, final String fieldName)
            throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException {
        for (Class<?> classOrSuperclass = cls; classOrSuperclass != null; //
                classOrSuperclass = classOrSuperclass.getSuperclass()) {
            try {
                final Field field = classOrSuperclass.getDeclaredField(fieldName);
                if (!field.isAccessible()) {
                    field.setAccessible(true);
                }
                return field.get(null);
            } catch (final NoSuchFieldException e) {
                // Try parent
            }
        }
        return null;
    }

    /** Invoke the named method in the given object or its superclasses. */
    public static Object invokeMethod(final Object obj, final String methodName)
            throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        if (obj != null) {
            for (Class<?> classOrSuperclass = obj.getClass(); classOrSuperclass != null; //
                    classOrSuperclass = classOrSuperclass.getSuperclass()) {
                try {
                    final Method method = classOrSuperclass.getDeclaredMethod(methodName);
                    if (!method.isAccessible()) {
                        method.setAccessible(true);
                    }
                    return method.invoke(obj);
                } catch (final NoSuchMethodException e) {
                    // Try parent
                }
            }
        }
        return null;
    }

    /** Invoke the named method in the given object or its superclasses. */
    public static Object invokeMethod(final Object obj, final String methodName, final Class<?> argType,
            final Object arg) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        if (obj != null) {
            for (Class<?> classOrSuperclass = obj.getClass(); classOrSuperclass != null; //
                    classOrSuperclass = classOrSuperclass.getSuperclass()) {
                try {
                    final Method method = classOrSuperclass.getDeclaredMethod(methodName, argType);
                    if (!method.isAccessible()) {
                        method.setAccessible(true);
                    }
                    return method.invoke(obj, arg);
                } catch (final NoSuchMethodException e) {
                    // Try parent
                }
            }
        }
        return null;
    }

    /** Invoke the named static method. */
    public static Object invokeStaticMethod(final Class<?> cls, final String methodName)
            throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        if (cls != null) {
            try {
                final Method method = cls.getDeclaredMethod(methodName);
                if (!method.isAccessible()) {
                    method.setAccessible(true);
                }
                return method.invoke(null);
            } catch (final NoSuchMethodException e) {
                // Try parent
            }
        }
        return null;
    }

    /** Invoke the named static method. */
    public static Object invokeStaticMethod(final Class<?> cls, final String methodName, final Class<?> argType,
            final Object arg) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        if (cls != null) {
            try {
                final Method method = cls.getDeclaredMethod(methodName, argType);
                if (!method.isAccessible()) {
                    method.setAccessible(true);
                }
                return method.invoke(null, arg);
            } catch (final NoSuchMethodException e) {
                // Try parent
            }
        }
        return null;
    }

    /** Convert field or method modifiers into a string representation, e.g. "public static final". */
    public static String modifiersToString(final int modifiers, final boolean isMethod) {
        final StringBuilder buf = new StringBuilder();
        if ((modifiers & Modifier.PUBLIC) != 0) {
            buf.append("public");
        } else if ((modifiers & Modifier.PROTECTED) != 0) {
            buf.append("protected");
        } else if ((modifiers & Modifier.PRIVATE) != 0) {
            buf.append("private");
        }
        if ((modifiers & Modifier.STATIC) != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append("static");
        }
        if ((modifiers & Modifier.ABSTRACT) != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append("abstract");
        }
        if ((modifiers & Modifier.SYNCHRONIZED) != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append("synchronized");
        }
        if (!isMethod && (modifiers & Modifier.TRANSIENT) != 0) {
            // TRANSIENT has the same value as VARARGS, since they are mutually exclusive
            // (TRANSIENT applies only to fields, VARARGS applies only to methods)
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append("transient");
        } else if ((modifiers & Modifier.VOLATILE) != 0) {
            // VOLATILE has the same value as BRIDGE, since they are mutually exclusive
            // (VOLATILE applies only to fields, BRIDGE applies only to methods)
            if (buf.length() > 0) {
                buf.append(' ');
            }
            if (!isMethod) {
                buf.append("volatile");
            } else {
                buf.append("bridge");
            }
        }
        if ((modifiers & Modifier.FINAL) != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append("final");
        }
        if ((modifiers & Modifier.NATIVE) != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append("native");
        }
        return buf.toString();
    }

    // -------------------------------------------------------------------------------------------------------------

    private static class ParseException extends Exception {
    }

    private static class StringAndPosition {
        String string;
        int position;

        public StringAndPosition(final String string) {
            this.string = string;
        }

        public char getc() {
            if (position >= string.length()) {
                return '\0';
            }
            return string.charAt(position++);
        }

        public char peek() {
            return string.charAt(position);
        }

        public boolean peekMatches(final String strMatch) {
            return string.regionMatches(position, strMatch, 0, strMatch.length());
        }

        public void next() {
            position++;
        }

        public void advance(final int n) {
            position += n;
        }

        @Override
        public String toString() {
            return string + " (position: " + position + ")";
        }

        public boolean hasMore() {
            return position < string.length();
        }

        public void expect(final char c) {
            final int next = getc();
            if (next != c) {
                throw new IllegalArgumentException(
                        "Got character '" + (char) next + "', expected '" + c + "' in string: " + this);
            }
        }
    }

    private static boolean parseIdentifier(final StringAndPosition str, final char separator,
            final char separatorReplace, final StringBuilder buf) throws ParseException {
        boolean consumedChar = false;
        while (str.hasMore()) {
            final char c = str.peek();
            if (c == separator) {
                buf.append(separatorReplace);
                str.next();
                consumedChar = true;
            } else if (c != ';' && c != '[' && c != '<' && c != '>' && c != ':' && c != '/' && c != '.') {
                buf.append(c);
                str.next();
                consumedChar = true;
            } else {
                break;
            }
        }
        return consumedChar;
    }

    private static boolean parseIdentifier(final StringAndPosition str, final StringBuilder buf)
            throws ParseException {
        return parseIdentifier(str, '\0', '\0', buf);
    }

    private static boolean parseBaseType(final StringAndPosition str, final StringBuilder buf) {
        switch (str.peek()) {
        case 'B':
            buf.append("byte");
            str.next();
            return true;
        case 'C':
            buf.append("char");
            str.next();
            return true;
        case 'D':
            buf.append("double");
            str.next();
            return true;
        case 'F':
            buf.append("float");
            str.next();
            return true;
        case 'I':
            buf.append("int");
            str.next();
            return true;
        case 'J':
            buf.append("long");
            str.next();
            return true;
        case 'S':
            buf.append("short");
            str.next();
            return true;
        case 'Z':
            buf.append("boolean");
            str.next();
            return true;
        case 'V':
            buf.append("void");
            str.next();
            return true;
        default:
            return false;
        }
    }

    private static boolean parseJavaTypeSignature(final StringAndPosition str, final StringBuilder buf)
            throws ParseException {
        return (parseReferenceTypeSignature(str, buf) || parseBaseType(str, buf));
    }

    private static boolean parseArrayTypeSignature(final StringAndPosition str, final StringBuilder buf)
            throws ParseException {
        int numArrayDims = 0;
        while (str.peek() == '[') {
            numArrayDims++;
            str.next();
        }
        if (numArrayDims > 0) {
            if (!parseJavaTypeSignature(str, buf)) {
                throw new IllegalArgumentException("Malformatted Java type signature");
            }
            for (int i = 0; i < numArrayDims; i++) {
                buf.append("[]");
            }
            return true;
        } else {
            return false;
        }
    }

    private static boolean parseTypeVariableSignature(final StringAndPosition str, final StringBuilder buf)
            throws ParseException {
        final char peek = str.peek();
        if (peek == 'T') {
            str.next();
            final boolean gotIdent = parseIdentifier(str, buf);
            str.expect(';');
            return gotIdent;
        } else {
            return false;
        }
    }

    private static boolean parseTypeArgument(final StringAndPosition str, final StringBuilder buf)
            throws ParseException {
        // Handle wildcard types
        final char peek = str.peek();
        if (peek == '*') {
            str.next();
            buf.append("?");
            return true;
        } else if (peek == '+') {
            str.next();
            buf.append("? extends ");
            return parseReferenceTypeSignature(str, buf);
        } else if (peek == '-') {
            str.next();
            buf.append("? super ");
            return parseReferenceTypeSignature(str, buf);
        } else {
            return parseReferenceTypeSignature(str, buf);
        }
    }

    private static boolean parseClassTypeSignature(final StringAndPosition str, final StringBuilder buf)
            throws ParseException {
        if (str.peek() == 'L') {
            str.next();
            if (str.peekMatches("java/lang/") || str.peekMatches("java/util/")) {
                str.advance(10);
            }
            if (!parseIdentifier(str, /* separator = */ '/', /* separatorReplace = */ '.', buf)) {
                throw new IllegalArgumentException("Malformed class name");
            }
            if (str.peek() == '<') {
                buf.append(str.getc()); // '<'
                for (boolean isFirstTypeArg = true; str.peek() != '>'; isFirstTypeArg = false) {
                    if (!isFirstTypeArg) {
                        buf.append(", ");
                    }
                    if (!parseTypeArgument(str, buf)) {
                        throw new IllegalArgumentException("Bad type argument");
                    }
                }
                buf.append(str.getc()); // '>'
            }
            while (str.peek() == '.') {
                // TODO: Figure out what to do with this (ClassTypeSignatureSuffix) -- where/how is it used?
                // (Is it used for enum constants?)
                buf.append(str.getc()); // '.'
                parseIdentifier(str, buf);
            }
            str.expect(';');
            return true;
        } else {
            return false;
        }
    }

    private static boolean parseReferenceTypeSignature(final StringAndPosition str, final StringBuilder buf)
            throws ParseException {
        return parseClassTypeSignature(str, buf) || parseTypeVariableSignature(str, buf)
                || parseArrayTypeSignature(str, buf);
    }

    /**
     * Parse a type descriptor into a type or list of types. For a single type (for a field), returns a list with
     * one item. For a method, returns a list of types, with the first N-1 items corresponding to the argument
     * types, and the last item corresponding to the method return type.
     */
    public static List<String> parseComplexTypeDescriptor(final String typeDescriptor) {
        final StringAndPosition str = new StringAndPosition(typeDescriptor);
        try {
            final StringBuilder buf = new StringBuilder();
            final List<String> typeParts = new ArrayList<>();
            while (str.hasMore()) {
                final char peek = str.peek();
                if (peek == '(' || peek == ')') {
                    str.next();
                } else {
                    if (!parseJavaTypeSignature(str, buf)) {
                        throw new ParseException();
                    }
                    typeParts.add(buf.toString());
                    buf.setLength(0);
                }
            }
            return typeParts;
        } catch (final Exception e) {
            throw new RuntimeException("Type signature could not be parsed: " + str, e);
        }
    }

    /**
     * Parse a simple Java type descriptor (for a single type -- methods are not supported), and turn it into a type
     * string, e.g. "[[[Ljava/lang/String;" -> "String[][][]".
     */
    public static String parseSimpleTypeDescriptor(final String typeDescriptor) {
        final StringAndPosition str = new StringAndPosition(typeDescriptor);
        final char peek = str.peek();
        if (peek == '(') {
            // This method is not for method signatures, use parseComplexTypeDescriptor() instead
            throw new RuntimeException("Got unexpected method signature");
        }
        String typeStr;
        try {
            final StringBuilder buf = new StringBuilder();
            if (!parseJavaTypeSignature(str, buf)) {
                throw new ParseException();
            }
            typeStr = buf.toString();
        } catch (final Exception e) {
            throw new RuntimeException("Type signature could not be parsed: " + str, e);
        }
        if (str.hasMore()) {
            throw new RuntimeException("Unused characters in type signature: " + str);
        }
        return typeStr;
    }

    // -------------------------------------------------------------------------------------------------------------

    private static Class<?> arrayify(final Class<?> cls, final int arrayDims) {
        if (arrayDims == 0) {
            return cls;
        } else {
            final int[] zeroes = (int[]) Array.newInstance(int.class, arrayDims);
            return Array.newInstance(cls, zeroes).getClass();
        }
    }

    /**
     * Parse a type string (e.g. "int[][]" or "com.xyz.Widget"; java.lang and java.util only need the class name,
     * e.g. "String") and return the corresponding Class reference.
     * 
     * @param typeStr
     *            The type string.
     * @param scanResult
     *            the ScanResult (used to call the correct ClassLoader(s)).
     * @return the type reference.
     * @throws IllegalArgumentException
     *             if the class could not be found or loaded.
     */
    public static Class<?> typeStrToClass(final String typeStr, final ScanResult scanResult)
            throws IllegalArgumentException {
        int end = typeStr.length();
        int arrayDims = 0;
        while (end >= 2 && typeStr.charAt(end - 2) == '[' && typeStr.charAt(end - 1) == ']') {
            end -= 2;
            arrayDims++;
        }
        final int typeParamIdx = typeStr.indexOf('<');
        if (typeParamIdx > 0) {
            end = Math.min(end, typeParamIdx);
        }
        final String bareType = typeStr.substring(0, end);
        switch (bareType) {
        case "byte":
            return arrayify(byte.class, arrayDims);
        case "char":
            return arrayify(char.class, arrayDims);
        case "double":
            return arrayify(double.class, arrayDims);
        case "float":
            return arrayify(float.class, arrayDims);
        case "int":
            return arrayify(int.class, arrayDims);
        case "long":
            return arrayify(long.class, arrayDims);
        case "short":
            return arrayify(short.class, arrayDims);
        case "boolean":
            return arrayify(boolean.class, arrayDims);
        case "void":
            return arrayify(void.class, arrayDims);
        default:
            final int dotIdx = bareType.indexOf('.');
            if (dotIdx < 0) {
                // Try prepending "java.lang." or "java.util."
                try {
                    return arrayify(Class.forName("java.lang." + bareType), arrayDims);
                } catch (final Exception e) {
                    // ignore
                }
                try {
                    return arrayify(Class.forName("java.util." + bareType), arrayDims);
                } catch (final Exception e) {
                    // ignore
                }
            }
            // Throws IllegalArgumentException if class could not be loaded
            return arrayify(scanResult.classNameToClassRef(bareType), arrayDims);
        }
    }
}
