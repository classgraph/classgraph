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
    /**
     * Get the value of the named field in the class of the given object or any of its superclasses. If an exception
     * is thrown while trying to read the field, and throwException is true, then IllegalArgumentException is thrown
     * wrapping the cause, otherwise this will return null. If passed a null object, returns null unless
     * throwException is true, then throws NullPointerException.
     */
    public static Object getFieldVal(final Object obj, final String fieldName, final boolean throwException) {
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
                } catch (final Throwable e) {
                    if (throwException) {
                        throw new IllegalArgumentException("Could not get value of field \"" + fieldName + "\"", e);
                    }
                }
            }
            if (throwException) {
                throw new IllegalArgumentException("Field \"" + fieldName + "\" doesn't exist");
            }
        } else if (throwException) {
            throw new NullPointerException("Can't get field value for null object");
        }
        return null;
    }

    /**
     * Get the value of the named static field in the given class or any of its superclasses. If an exception is
     * thrown while trying to read the field value, and throwException is true, then IllegalArgumentException is
     * thrown wrapping the cause, otherwise this will return null. If passed a null class reference, returns null
     * unless throwException is true, then throws NullPointerException.
     */
    public static Object getStaticFieldVal(final Class<?> cls, final String fieldName,
            final boolean throwException) {
        if (cls != null) {
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
                } catch (final Throwable e) {
                    if (throwException) {
                        throw new IllegalArgumentException("Could not get value of field \"" + fieldName + "\"", e);
                    }
                }
            }
            if (throwException) {
                throw new IllegalArgumentException("Field \"" + fieldName + "\" doesn't exist");
            }
        } else if (throwException) {
            throw new NullPointerException("Can't get field value for null class reference");
        }
        return null;
    }

    /**
     * Invoke the named method in the given object or its superclasses. If an exception is thrown while trying to
     * call the method, and throwException is true, then IllegalArgumentException is thrown wrapping the cause,
     * otherwise this will return null. If passed a null object, returns null unless throwException is true, then
     * throws NullPointerException.
     */
    public static Object invokeMethod(final Object obj, final String methodName, final boolean throwException) {
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
                } catch (final Throwable e) {
                    if (throwException) {
                        throw new IllegalArgumentException("Could not invoke method \"" + methodName + "\"", e);
                    }
                }
            }
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" doesn't exist");
            }
        } else if (throwException) {
            throw new NullPointerException("Can't get field value for null object");
        }
        return null;
    }

    /**
     * Invoke the named method in the given object or its superclasses. If an exception is thrown while trying to
     * call the method, and throwException is true, then IllegalArgumentException is thrown wrapping the cause,
     * otherwise this will return null. If passed a null object, returns null unless throwException is true, then
     * throws NullPointerException.
     */
    public static Object invokeMethod(final Object obj, final String methodName, final Class<?> argType,
            final Object arg, final boolean throwException) {
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
                } catch (final Throwable e) {
                    if (throwException) {
                        throw new IllegalArgumentException("Could not invoke method \"" + methodName + "\"", e);
                    }
                }
            }
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" doesn't exist");
            }
        } else if (throwException) {
            throw new NullPointerException("Can't get field value for null object");
        }
        return null;
    }

    /**
     * Invoke the named static method. If an exception is thrown while trying to call the method, and throwException
     * is true, then IllegalArgumentException is thrown wrapping the cause, otherwise this will return null. If
     * passed a null class reference, returns null unless throwException is true, then throws NullPointerException.
     */
    public static Object invokeStaticMethod(final Class<?> cls, final String methodName,
            final boolean throwException)
            throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        if (cls != null) {
            try {
                final Method method = cls.getDeclaredMethod(methodName);
                if (!method.isAccessible()) {
                    method.setAccessible(true);
                }
                return method.invoke(null);
            } catch (final Throwable e) {
                if (throwException) {
                    throw new IllegalArgumentException("Could not invoke method \"" + methodName + "\"", e);
                }
            }
        } else if (throwException) {
            throw new NullPointerException("Can't get field value for null class reference");
        }
        return null;
    }

    /**
     * Invoke the named static method. If an exception is thrown while trying to call the method, and throwException
     * is true, then IllegalArgumentException is thrown wrapping the cause, otherwise this will return null. If
     * passed a null class reference, returns null unless throwException is true, then throws NullPointerException.
     */
    public static Object invokeStaticMethod(final Class<?> cls, final String methodName, final Class<?> argType,
            final Object arg, final boolean throwException) {
        if (cls != null) {
            try {
                final Method method = cls.getDeclaredMethod(methodName, argType);
                if (!method.isAccessible()) {
                    method.setAccessible(true);
                }
                return method.invoke(null, arg);
            } catch (final Throwable e) {
                if (throwException) {
                    throw new IllegalArgumentException("Could not invoke method \"" + methodName + "\"", e);
                }
            }
        } else if (throwException) {
            throw new NullPointerException("Can't get field value for null class reference");
        }
        return null;
    }

    // -------------------------------------------------------------------------------------------------------------

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

    private static class ParseException extends Exception {
    }

    private static class ParseState {
        private final String string;
        private int position;
        private final StringBuilder token = new StringBuilder();

        public ParseState(final String string) {
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

        public void appendToToken(final String str) {
            token.append(str);
        }

        public void appendToToken(final char c) {
            token.append(c);
        }

        /** Get the current token, and reset the token to empty. */
        public String getToken() {
            final String tok = token.toString();
            token.setLength(0);
            return tok;
        }

        @Override
        public String toString() {
            return string + " (position: " + position + "; token: \"" + token + "\")";
        }
    }

    private static boolean parseIdentifier(final ParseState str, final char separator, final char separatorReplace)
            throws ParseException {
        boolean consumedChar = false;
        while (str.hasMore()) {
            final char c = str.peek();
            if (c == separator) {
                str.appendToToken(separatorReplace);
                str.next();
                consumedChar = true;
            } else if (c != ';' && c != '[' && c != '<' && c != '>' && c != ':' && c != '/' && c != '.') {
                str.appendToToken(c);
                str.next();
                consumedChar = true;
            } else {
                break;
            }
        }
        return consumedChar;
    }

    private static boolean parseIdentifier(final ParseState str) throws ParseException {
        return parseIdentifier(str, '\0', '\0');
    }

    private static boolean parseBaseType(final ParseState str) {
        switch (str.peek()) {
        case 'B':
            str.appendToToken("byte");
            str.next();
            return true;
        case 'C':
            str.appendToToken("char");
            str.next();
            return true;
        case 'D':
            str.appendToToken("double");
            str.next();
            return true;
        case 'F':
            str.appendToToken("float");
            str.next();
            return true;
        case 'I':
            str.appendToToken("int");
            str.next();
            return true;
        case 'J':
            str.appendToToken("long");
            str.next();
            return true;
        case 'S':
            str.appendToToken("short");
            str.next();
            return true;
        case 'Z':
            str.appendToToken("boolean");
            str.next();
            return true;
        case 'V':
            str.appendToToken("void");
            str.next();
            return true;
        default:
            return false;
        }
    }

    private static boolean parseJavaTypeSignature(final ParseState str) throws ParseException {
        return (parseReferenceTypeSignature(str) || parseBaseType(str));
    }

    private static boolean parseArrayTypeSignature(final ParseState str) throws ParseException {
        int numArrayDims = 0;
        while (str.peek() == '[') {
            numArrayDims++;
            str.next();
        }
        if (numArrayDims > 0) {
            if (!parseJavaTypeSignature(str)) {
                throw new IllegalArgumentException("Malformatted Java type signature");
            }
            for (int i = 0; i < numArrayDims; i++) {
                str.appendToToken("[]");
            }
            return true;
        } else {
            return false;
        }
    }

    private static boolean parseTypeVariableSignature(final ParseState str) throws ParseException {
        final char peek = str.peek();
        if (peek == 'T') {
            str.next();
            final boolean gotIdent = parseIdentifier(str);
            str.expect(';');
            return gotIdent;
        } else {
            return false;
        }
    }

    private static boolean parseTypeArgument(final ParseState str) throws ParseException {
        // Handle wildcard types
        final char peek = str.peek();
        if (peek == '*') {
            str.next();
            str.appendToToken("?");
            return true;
        } else if (peek == '+') {
            str.next();
            str.appendToToken("? extends ");
            return parseReferenceTypeSignature(str);
        } else if (peek == '-') {
            str.next();
            str.appendToToken("? super ");
            return parseReferenceTypeSignature(str);
        } else {
            return parseReferenceTypeSignature(str);
        }
    }

    private static boolean parseClassTypeSignature(final ParseState str) throws ParseException {
        if (str.peek() == 'L') {
            str.next();
            if (str.peekMatches("java/lang/") || str.peekMatches("java/util/")) {
                str.advance(10);
            }
            if (!parseIdentifier(str, /* separator = */ '/', /* separatorReplace = */ '.')) {
                throw new IllegalArgumentException("Malformed class name");
            }
            if (str.peek() == '<') {
                str.appendToToken(str.getc()); // '<'
                for (boolean isFirstTypeArg = true; str.peek() != '>'; isFirstTypeArg = false) {
                    if (!isFirstTypeArg) {
                        str.appendToToken(", ");
                    }
                    if (!parseTypeArgument(str)) {
                        throw new IllegalArgumentException("Bad type argument");
                    }
                }
                str.appendToToken(str.getc()); // '>'
            }
            while (str.peek() == '.') {
                // TODO: Figure out what to do with this (ClassTypeSignatureSuffix) -- where/how is it used?
                // (Is it used for enum constants?)
                str.appendToToken(str.getc()); // '.'
                parseIdentifier(str);
            }
            str.expect(';');
            return true;
        } else {
            return false;
        }
    }

    private static boolean parseReferenceTypeSignature(final ParseState str) throws ParseException {
        return parseClassTypeSignature(str) || parseTypeVariableSignature(str) || parseArrayTypeSignature(str);
    }

    /**
     * Parse a type descriptor into a type or list of types. For a single type (for a field), returns a list with
     * one item. For a method, returns a list of types, with the first N-1 items corresponding to the argument
     * types, and the last item corresponding to the method return type.
     */
    public static List<String> parseMethodTypeDescriptor(final String typeDescriptor) {
        final ParseState str = new ParseState(typeDescriptor);
        try {
            final List<String> typeParts = new ArrayList<>();
            while (str.hasMore()) {
                final char peek = str.peek();
                if (peek == '(' || peek == ')') {
                    str.next();
                } else {
                    if (!parseJavaTypeSignature(str)) {
                        throw new ParseException();
                    }
                    typeParts.add(str.getToken());
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
        final ParseState str = new ParseState(typeDescriptor);
        if (str.peek() == '(') {
            // This method is not for method signatures, use parseComplexTypeDescriptor() instead
            throw new RuntimeException("Got unexpected method signature");
        }
        String typeStr;
        try {
            if (!parseJavaTypeSignature(str)) {
                throw new ParseException();
            }
            typeStr = str.getToken();
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
