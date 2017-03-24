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

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Reflection utility methods that can be used by ClassLoaderHandlers. */
public class ReflectionUtils {
    /** Get the named field in the given object or any of its superclasses. */
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

    /**
     * Parse a type descriptor into a type or list of types. For a single type (for a field), returns a list with
     * one item. For a method, returns a list of types, with the first N-1 items corresponding to the argument
     * types, and the last item corresponding to the method return type.
     */
    public static List<String> parseTypeDescriptor(final String typeDescriptor) {
        final List<String> types = new ArrayList<>();
        final StringBuilder buf = new StringBuilder();
        for (int i = 0; i < typeDescriptor.length(); i++) {
            int numDims = 0;
            char c = typeDescriptor.charAt(i);
            if (c == '(' || c == ')') {
                // Beginning or end of arg list, ignore
                continue;
            } else if (c == '[') {
                // Beginning of array, count the number of dimensions
                numDims = 1;
                for (i++; i < typeDescriptor.length(); i++) {
                    c = typeDescriptor.charAt(i);
                    if (c == '[') {
                        numDims++;
                    } else if (i == typeDescriptor.length() - 1) {
                        // No type after '['
                        throw new RuntimeException("Invalid type descriptor: " + typeDescriptor);
                    } else {
                        break;
                    }
                }
            }
            switch (c) {
            case 'B':
                buf.append("byte");
                break;
            case 'C':
                buf.append("char");
                break;
            case 'D':
                buf.append("double");
                break;
            case 'F':
                buf.append("float");
                break;
            case 'I':
                buf.append("int");
                break;
            case 'J':
                buf.append("long");
                break;
            case 'S':
                buf.append("short");
                break;
            case 'Z':
                buf.append("boolean");
                break;
            case 'V':
                buf.append("void");
                break;
            case 'L':
                final int semicolonIdx = typeDescriptor.indexOf(';', i + 1);
                if (semicolonIdx < 0) {
                    // Missing ';' after class name
                    throw new RuntimeException("Invalid type descriptor: " + typeDescriptor);
                }
                String className = typeDescriptor.substring(i + 1, semicolonIdx).replace('/', '.');
                if (className.isEmpty()) {
                    throw new RuntimeException("Invalid type descriptor: " + typeDescriptor);
                }
                if (className.startsWith("java.lang.") || className.startsWith("java.util.")) {
                    // Strip common Java prefixes
                    className = className.substring(10);
                }
                buf.append(className);
                i = semicolonIdx;
                break;
            }
            for (int j = 0; j < numDims; j++) {
                buf.append("[]");
            }
            types.add(buf.toString());
            buf.setLength(0);
        }
        return types;
    }
}
