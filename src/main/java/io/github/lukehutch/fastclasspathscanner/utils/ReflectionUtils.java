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

/** Reflection utility methods that can be used by ClassLoaderHandlers. */
//isAccessible() is deprecated in JDK9 because the name is confusing, not because it is broken
@SuppressWarnings("deprecation")
public class ReflectionUtils {
    // This unused method includes a deprecated method call, so that the @SuppressWarnings("deprecation") above
    // does not give a warning on JDK versions before 9 (where the isAccessible() method is not yet deprecated),
    // but can still suppress the deprecation warnings for JDK9+. 
    @SuppressWarnings("unused")
    private static void unusedMethod() {
        new Thread().destroy();
    }

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
}
