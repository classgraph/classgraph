/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
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
package io.github.classgraph.utils;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/** Reflection utility methods that can be used by ClassLoaderHandlers. */
public class ReflectionUtils {
    /**
     * Get the value of the named field in the class of the given object or any of its superclasses. If an exception
     * is thrown while trying to read the field, and throwException is true, then IllegalArgumentException is thrown
     * wrapping the cause, otherwise this will return null. If passed a null object, returns null unless
     * throwException is true, then throws IllegalArgumentException.
     * 
     * @param obj
     *            The object.
     * @param fieldName
     *            The field name.
     * @param throwException
     *            If true, throw an exception if the field value could not be read.
     * @return The field value.
     * @throws IllegalArgumentException
     *             If the field value could not be read.
     */
    public static Object getFieldVal(final Object obj, final String fieldName, final boolean throwException)
            throws IllegalArgumentException {
        if (obj != null) {
            for (Class<?> classOrSuperclass = obj.getClass(); classOrSuperclass != null; //
                    classOrSuperclass = classOrSuperclass.getSuperclass()) {
                try {
                    final Field field = classOrSuperclass.getDeclaredField(fieldName);
                    try {
                        field.setAccessible(true);
                    } catch (final Exception e) {
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
            throw new IllegalArgumentException("Can't get field value for null object");
        }
        return null;
    }

    /**
     * Get the value of the named static field in the given class or any of its superclasses. If an exception is
     * thrown while trying to read the field value, and throwException is true, then IllegalArgumentException is
     * thrown wrapping the cause, otherwise this will return null. If passed a null class reference, returns null
     * unless throwException is true, then throws IllegalArgumentException.
     * 
     * @param cls
     *            The class.
     * @param fieldName
     *            The field name.
     * @param throwException
     *            If true, throw an exception if the field value could not be read.
     * @return The field value.
     * @throws IllegalArgumentException
     *             If the field value could not be read.
     */
    public static Object getStaticFieldVal(final Class<?> cls, final String fieldName, final boolean throwException)
            throws IllegalArgumentException {
        if (cls != null) {
            for (Class<?> classOrSuperclass = cls; classOrSuperclass != null; //
                    classOrSuperclass = classOrSuperclass.getSuperclass()) {
                try {
                    final Field field = classOrSuperclass.getDeclaredField(fieldName);
                    try {
                        field.setAccessible(true);
                    } catch (final Exception e) {
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
            throw new IllegalArgumentException("Can't get field value for null class reference");
        }
        return null;
    }

    /**
     * Iterate through implemented interfaces, top-down, then superclass to subclasses, top-down (since higher-up
     * superclasses and superinterfaces have the highest chance of being visible).
     */
    private static List<Class<?>> getReverseMethodAttemptOrder(final Class<?> cls) {
        final List<Class<?>> reverseAttemptOrder = new ArrayList<>();

        // Iterate from class to its superclasses
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            reverseAttemptOrder.add(c);
        }

        // Find interfaces and superinterfaces implemented by this class or its superclasses
        final Set<Class<?>> addedIfaces = new HashSet<>();
        final LinkedList<Class<?>> ifaceQueue = new LinkedList<>();
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            if (c.isInterface()) {
                if (addedIfaces.add(c)) {
                    ifaceQueue.add(c);
                }
            }
            for (final Class<?> iface : c.getInterfaces()) {
                if (addedIfaces.add(iface)) {
                    ifaceQueue.add(iface);
                }
            }
        }
        while (!ifaceQueue.isEmpty()) {
            final Class<?> iface = ifaceQueue.remove();
            reverseAttemptOrder.add(iface);
            final Class<?>[] superIfaces = iface.getInterfaces();
            if (superIfaces.length > 0) {
                for (final Class<?> superIface : superIfaces) {
                    if (addedIfaces.add(superIface)) {
                        ifaceQueue.add(superIface);
                    }
                }
            }
        }
        return reverseAttemptOrder;
    }

    /**
     * Invoke the named method in the given object or its superclasses. If an exception is thrown while trying to
     * call the method, and throwException is true, then IllegalArgumentException is thrown wrapping the cause,
     * otherwise this will return null. If passed a null object, returns null unless throwException is true, then
     * throws IllegalArgumentException.
     * 
     * @param obj
     *            The object.
     * @param methodName
     *            The method name.
     * @param throwException
     *            If true, throw an exception if the field value could not be read.
     * @return The field value.
     * @throws IllegalArgumentException
     *             If the field value could not be read.
     */
    public static Object invokeMethod(final Object obj, final String methodName, final boolean throwException)
            throws IllegalArgumentException {
        if (obj != null) {
            final Class<? extends Object> cls = obj.getClass();

            // Iterate through implemented interfaces, top-down, then superclass to subclasses, top-down
            // (since higher-up superclasses and superinterfaces have the highest chance of being visible)
            final List<Class<?>> reverseAttemptOrder = getReverseMethodAttemptOrder(cls);
            IllegalAccessException illegalAccessException = null;
            for (int i = reverseAttemptOrder.size() - 1; i >= 0; i--) {
                final Class<?> iface = reverseAttemptOrder.get(i);
                try {
                    // Try calling method on interface
                    final Method method = iface.getDeclaredMethod(methodName);
                    try {
                        method.setAccessible(true);
                    } catch (final Exception e) {
                    }
                    return method.invoke(obj);
                } catch (final NoSuchMethodException e) {
                    // Fall through if method not found, since a superinterface might have the method 
                } catch (final IllegalAccessException e) {
                    illegalAccessException = e;
                } catch (final Throwable e) {
                    if (throwException) {
                        throw new IllegalArgumentException("Exception while invoking method \"" + methodName + "\"",
                                e);
                    }
                }
            }
            if (throwException) {
                if (illegalAccessException != null) {
                    throw new IllegalArgumentException("Method \"" + methodName + "\" is not accessible",
                            illegalAccessException);
                } else {
                    throw new IllegalArgumentException("Method \"" + methodName + "\" doesn't exist");
                }
            }
        } else if (throwException) {
            throw new IllegalArgumentException("Can't invoke method on null object");
        }
        return null;
    }

    /**
     * Invoke the named method in the given object or its superclasses. If an exception is thrown while trying to
     * call the method, and throwException is true, then IllegalArgumentException is thrown wrapping the cause,
     * otherwise this will return null. If passed a null object, returns null unless throwException is true, then
     * throws IllegalArgumentException.
     * 
     * @param obj
     *            The object.
     * @param methodName
     *            The method name.
     * @param argType
     *            The type of the parameter.
     * @param arg
     *            The argument value.
     * @param throwException
     *            Whether to throw an exception on failure.
     * @return The result of the method invocation.
     * @throws IllegalArgumentException
     *             If the method could not be invoked.
     */
    public static Object invokeMethod(final Object obj, final String methodName, final Class<?> argType,
            final Object arg, final boolean throwException) throws IllegalArgumentException {
        if (obj != null) {
            final Class<? extends Object> cls = obj.getClass();

            // Iterate through implemented interfaces, top-down, then superclass to subclasses, top-down
            // (since higher-up superclasses and superinterfaces have the highest chance of being visible)
            final List<Class<?>> reverseAttemptOrder = getReverseMethodAttemptOrder(cls);
            IllegalAccessException illegalAccessException = null;
            for (int i = reverseAttemptOrder.size() - 1; i >= 0; i--) {
                final Class<?> iface = reverseAttemptOrder.get(i);
                try {
                    // Try calling method on interface
                    final Method method = iface.getDeclaredMethod(methodName, argType);
                    try {
                        method.setAccessible(true);
                    } catch (final Exception e) {
                    }
                    return method.invoke(obj, arg);
                } catch (final NoSuchMethodException e) {
                    // Fall through if method not found, since a superinterface might have the method 
                } catch (final IllegalAccessException e) {
                    illegalAccessException = e;
                } catch (final Throwable e) {
                    if (throwException) {
                        throw new IllegalArgumentException("Exception while invoking method \"" + methodName + "\"",
                                e);
                    }
                }
            }
            if (throwException) {
                if (illegalAccessException != null) {
                    throw new IllegalArgumentException("Method \"" + methodName + "\" is not accessible",
                            illegalAccessException);
                } else {
                    throw new IllegalArgumentException("Method \"" + methodName + "\" doesn't exist");
                }
            }
        } else if (throwException) {
            throw new IllegalArgumentException("Can't invoke method on null object");
        }
        return null;
    }

    /**
     * Invoke the named static method. If an exception is thrown while trying to call the method, and throwException
     * is true, then IllegalArgumentException is thrown wrapping the cause, otherwise this will return null. If
     * passed a null class reference, returns null unless throwException is true, then throws
     * IllegalArgumentException.
     * 
     * @param cls
     *            The class.
     * @param methodName
     *            The method name.
     * @param throwException
     *            Whether to throw an exception on failure.
     * @return The result of the method invocation.
     * @throws IllegalArgumentException
     *             If the method could not be invoked.
     */
    public static Object invokeStaticMethod(final Class<?> cls, final String methodName,
            final boolean throwException) throws IllegalArgumentException {
        if (cls != null) {
            try {
                final Method method = cls.getDeclaredMethod(methodName);
                try {
                    method.setAccessible(true);
                } catch (final Exception e) {
                }
                return method.invoke(null);
            } catch (final Throwable e) {
                if (throwException) {
                    throw new IllegalArgumentException("Could not invoke method \"" + methodName + "\"", e);
                }
            }
        } else if (throwException) {
            throw new IllegalArgumentException("Can't invoke static method on null class reference");
        }
        return null;
    }

    /**
     * Invoke the named static method. If an exception is thrown while trying to call the method, and throwException
     * is true, then IllegalArgumentException is thrown wrapping the cause, otherwise this will return null. If
     * passed a null class reference, returns null unless throwException is true, then throws
     * IllegalArgumentException.
     * 
     * @param cls
     *            The class.
     * @param methodName
     *            The method name.
     * @param argType
     *            The type of the parameter.
     * @param arg
     *            The argument value.
     * @param throwException
     *            Whether to throw an exception on failure.
     * @return The result of the method invocation.
     * @throws IllegalArgumentException
     *             If the method could not be invoked.
     */
    public static Object invokeStaticMethod(final Class<?> cls, final String methodName, final Class<?> argType,
            final Object arg, final boolean throwException) throws IllegalArgumentException {
        if (cls != null) {
            try {
                final Method method = cls.getDeclaredMethod(methodName, argType);
                try {
                    method.setAccessible(true);
                } catch (final Exception e) {
                }
                return method.invoke(null, arg);
            } catch (final Throwable e) {
                if (throwException) {
                    throw new IllegalArgumentException("Could not invoke method \"" + methodName + "\"", e);
                }
            }
        } else if (throwException) {
            throw new IllegalArgumentException("Can't invoke static method on null class reference");
        }
        return null;
    }

    private static Object tryInvokeDefaultMethod(final Class<?> cls, final String methodName,
            final Class<?> returnType, final ClassLoader classLoader, final boolean throwException)
            throws Exception {
        final Object proxyInstance = Proxy.newProxyInstance(classLoader, new Class[] { cls },
                new InvocationHandler() {
                    @Override
                    public Object invoke(final Object proxy, final Method method, final Object[] args)
                            throws Throwable {
                        if (VersionFinder.JAVA_MAJOR_VERSION == 8) {
                            final Constructor<Lookup> constructor = Lookup.class
                                    .getDeclaredConstructor(Class.class);
                            constructor.setAccessible(true);
                            constructor.newInstance(cls).in(cls).unreflectSpecial(method, cls).bindTo(proxy)
                                    .invokeWithArguments();
                        } else {
                            // N.B. in testing, this didn't work...
                            MethodHandles
                                    .lookup().findSpecial(cls, methodName,
                                            MethodType.methodType(returnType, new Class[0]), cls)
                                    .bindTo(proxy).invokeWithArguments();
                        }
                        return null;
                    }
                });
        final Method method = proxyInstance.getClass().getDeclaredMethod(methodName);
        return method.invoke(proxyInstance);
    }

    /**
     * Invoke the named default interface method. If an exception is thrown while trying to call the method, and
     * throwException is true, then IllegalArgumentException is thrown wrapping the cause, otherwise this will
     * return null. If passed a null class reference, returns null unless throwException is true, then throws
     * IllegalArgumentException.
     * 
     * Uses the solution in https://stackoverflow.com/a/49532492/3950982
     * 
     * TODO: This is not tested...
     * 
     * @param cls
     *            The object.
     * @param methodName
     *            The method name.
     * @param returnType
     *            The return type of the method.
     * @param classLoader
     *            The ClassLoader.
     * @param throwException
     *            Whether to throw an exception on failure.
     * @return The result of the method invokation.
     * @throws IllegalArgumentException
     *             If the method could not be invoked.
     */
    public static Object invokeDefaultMethod(final Class<?> cls, final String methodName, final Class<?> returnType,
            final ClassLoader classLoader, final boolean throwException) throws IllegalArgumentException {
        if (cls != null) {
            if (VersionFinder.JAVA_MAJOR_VERSION < 8) {
                if (throwException) {
                    throw new IllegalArgumentException("Can't invoke default method on JDK 1.7");
                }
            }

            // Iterate through implemented interfaces, top-down, then superclass to subclasses, top-down
            // (since higher-up superclasses and superinterfaces have the highest chance of being visible)
            final List<Class<?>> reverseAttemptOrder = getReverseMethodAttemptOrder(cls);
            IllegalAccessException illegalAccessException = null;
            for (int i = reverseAttemptOrder.size() - 1; i >= 0; i--) {
                final Class<?> iface = reverseAttemptOrder.get(i);
                try {
                    // Try calling default method on interface
                    return tryInvokeDefaultMethod(iface, methodName, returnType, classLoader, throwException);
                } catch (final NoSuchMethodException e) {
                    // Fall through if method not found, since a superinterface might have the method 
                } catch (final IllegalAccessException e) {
                    illegalAccessException = e;
                } catch (final Throwable e) {
                    if (throwException) {
                        throw new IllegalArgumentException("Exception while invoking method \"" + methodName + "\"",
                                e);
                    }
                }
            }
            if (throwException) {
                if (illegalAccessException != null) {
                    throw new IllegalArgumentException("Default method \"" + methodName + "\" not found",
                            illegalAccessException);
                } else {
                    throw new IllegalArgumentException("Default method \"" + methodName + "\" not found");
                }
            }
        } else if (throwException) {
            throw new IllegalArgumentException(
                    "Can't invoke default method \"" + methodName + "\" on null class reference");
        }
        return null;
    }

    /**
     * Call Class.forName(className), but return null if any exception is thrown.
     * 
     * @param className
     *            The class name to load.
     * @return The class of the requested name, or null if an exception was thrown while trying to load the class.
     */
    public static Class<?> classForNameOrNull(final String className) {
        try {
            return Class.forName(className);
        } catch (final Exception e) {
            return null;
        }
    }

}
