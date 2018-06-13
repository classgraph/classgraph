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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

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
    public static Object getFieldVal(final Object obj, final String fieldName, final boolean throwException)
            throws IllegalArgumentException, NullPointerException {
        if (obj != null) {
            for (Class<?> classOrSuperclass = obj.getClass(); classOrSuperclass != null; //
                    classOrSuperclass = classOrSuperclass.getSuperclass()) {
                try {
                    final Field field = classOrSuperclass.getDeclaredField(fieldName);
                    try {
                        if (!field.isAccessible()) {
                            field.setAccessible(true);
                        }
                    } catch (final Exception e) {
                        // In JDK9+, may throw InaccessibleObjectException.
                        // However, still try to invoke the method below.
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
    public static Object getStaticFieldVal(final Class<?> cls, final String fieldName, final boolean throwException)
            throws IllegalArgumentException, NullPointerException {
        if (cls != null) {
            for (Class<?> classOrSuperclass = cls; classOrSuperclass != null; //
                    classOrSuperclass = classOrSuperclass.getSuperclass()) {
                try {
                    final Field field = classOrSuperclass.getDeclaredField(fieldName);
                    try {
                        if (!field.isAccessible()) {
                            field.setAccessible(true);
                        }
                    } catch (final Exception e) {
                        // In JDK9+, may throw InaccessibleObjectException.
                        // However, still try to invoke the method below.
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
    public static Object invokeMethod(final Object obj, final String methodName, final boolean throwException)
            throws IllegalArgumentException, NullPointerException {
        if (obj != null) {
            IllegalAccessException illegalAccessException = null;
            // Try calling method in class or superclass
            final Class<? extends Object> cls = obj.getClass();
            for (Class<?> classOrSuperclass = cls; classOrSuperclass != null; //
                    classOrSuperclass = classOrSuperclass.getSuperclass()) {
                try {
                    final Method method = classOrSuperclass.getDeclaredMethod(methodName);
                    try {
                        if (!method.isAccessible()) {
                            method.setAccessible(true);
                        }
                    } catch (final Exception e) {
                        // In JDK9+, may throw InaccessibleObjectException.
                        // However, still try to invoke the method below.
                    }
                    return method.invoke(obj);
                } catch (final NoSuchMethodException e) {
                    // Try parent
                } catch (final IllegalAccessException e) {
                    // In Java 9+, need to try parent in case of illegal access, in case one of them succeeds
                    // (this is needed for the case where a subclass or implementation of an interface is not
                    // visible to the caller, but the superclass is)
                    illegalAccessException = e;
                } catch (final Throwable e) {
                    if (throwException) {
                        throw new IllegalArgumentException("Could not invoke method \"" + methodName + "\"", e);
                    }
                }
            }

            // If that failed, find interfaces implemented by this class or its superclasses
            final LinkedList<Class<?>> implementedIfacesQueue = new LinkedList<>();
            final Set<Class<?>> addedIfaces = new HashSet<>();
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                if (c.isInterface()) {
                    if (addedIfaces.add(c)) {
                        implementedIfacesQueue.add(c);
                    }
                }
                for (final Class<?> iface : c.getInterfaces()) {
                    if (addedIfaces.add(iface)) {
                        implementedIfacesQueue.add(iface);
                    }
                }
            }

            // Iterate through implemented interfaces
            while (!implementedIfacesQueue.isEmpty()) {
                final Class<?> iface = implementedIfacesQueue.remove();
                try {
                    // Try calling method on interface
                    final Method method = iface.getDeclaredMethod(methodName);
                    try {
                        if (!method.isAccessible()) {
                            method.setAccessible(true);
                        }
                    } catch (final Exception e) {
                    }
                    return method.invoke(obj);
                } catch (final NoSuchMethodException e) {
                    // Fall through if method not found, since a superinterface might have the method 
                } catch (final IllegalAccessException e) {
                    illegalAccessException = e;
                } catch (final Throwable e) {
                    if (throwException) {
                        throw new IllegalArgumentException(
                                "Exception while invoking default method \"" + methodName + "\"", e);
                    }
                }
                // Add any superinterfaces to end of queue
                final Class<?>[] superIfaces = iface.getInterfaces();
                if (superIfaces.length > 0) {
                    for (final Class<?> superIface : superIfaces) {
                        if (addedIfaces.add(superIface)) {
                            implementedIfacesQueue.add(superIface);
                        }
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
            throw new NullPointerException("Can't invoke method on null object");
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
            final Object arg, final boolean throwException) throws IllegalArgumentException, NullPointerException {
        if (obj != null) {
            // Try calling method in class or superclass
            IllegalAccessException illegalAccessException = null;
            final Class<? extends Object> cls = obj.getClass();
            for (Class<?> classOrSuperclass = cls; classOrSuperclass != null; //
                    classOrSuperclass = classOrSuperclass.getSuperclass()) {
                try {
                    final Method method = classOrSuperclass.getDeclaredMethod(methodName, argType);
                    try {
                        if (!method.isAccessible()) {
                            method.setAccessible(true);
                        }
                    } catch (final Exception e) {
                        // In JDK9+, may throw InaccessibleObjectException.
                        // However, still try to invoke the method below.
                    }
                    return method.invoke(obj, arg);
                } catch (final NoSuchMethodException e) {
                    // Try parent
                } catch (final IllegalAccessException e) {
                    // In Java 9+, need to try parent in case of illegal access, in case one of them succeeds
                    // (this is needed for the case where a subclass or implementation of an interface is not
                    // visible to the caller, but the superclass is)
                    illegalAccessException = e;
                } catch (final Throwable e) {
                    if (throwException) {
                        throw new IllegalArgumentException("Could not invoke method \"" + methodName + "\"", e);
                    }
                }
            }

            // If that failed, find interfaces implemented by this class or its superclasses
            final LinkedList<Class<?>> implementedIfacesQueue = new LinkedList<>();
            final Set<Class<?>> addedIfaces = new HashSet<>();
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                if (c.isInterface()) {
                    if (addedIfaces.add(c)) {
                        implementedIfacesQueue.add(c);
                    }
                }
                for (final Class<?> iface : c.getInterfaces()) {
                    if (addedIfaces.add(iface)) {
                        implementedIfacesQueue.add(iface);
                    }
                }
            }

            // Iterate through implemented interfaces
            while (!implementedIfacesQueue.isEmpty()) {
                final Class<?> iface = implementedIfacesQueue.remove();
                try {
                    // Try calling method on interface
                    final Method method = iface.getDeclaredMethod(methodName, argType);
                    try {
                        if (!method.isAccessible()) {
                            method.setAccessible(true);
                        }
                    } catch (final Exception e) {
                    }
                    return method.invoke(obj, arg);
                } catch (final NoSuchMethodException e) {
                    // Fall through if method not found, since a superinterface might have the method 
                } catch (final IllegalAccessException e) {
                    illegalAccessException = e;
                } catch (final Throwable e) {
                    if (throwException) {
                        throw new IllegalArgumentException(
                                "Exception while invoking default method \"" + methodName + "\"", e);
                    }
                }
                // Add any superinterfaces to end of queue
                final Class<?>[] superIfaces = iface.getInterfaces();
                if (superIfaces.length > 0) {
                    for (final Class<?> superIface : superIfaces) {
                        if (addedIfaces.add(superIface)) {
                            implementedIfacesQueue.add(superIface);
                        }
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
            throw new NullPointerException("Can't invoke method on null object");
        }
        return null;
    }

    /**
     * Invoke the named static method. If an exception is thrown while trying to call the method, and throwException
     * is true, then IllegalArgumentException is thrown wrapping the cause, otherwise this will return null. If
     * passed a null class reference, returns null unless throwException is true, then throws NullPointerException.
     */
    public static Object invokeStaticMethod(final Class<?> cls, final String methodName,
            final boolean throwException) throws IllegalArgumentException, NullPointerException {
        if (cls != null) {
            try {
                final Method method = cls.getDeclaredMethod(methodName);
                try {
                    if (!method.isAccessible()) {
                        method.setAccessible(true);
                    }
                } catch (final Exception e) {
                    // In JDK9+, may throw InaccessibleObjectException.
                    // However, still try to invoke the method below.
                }
                return method.invoke(null);
            } catch (final Throwable e) {
                if (throwException) {
                    throw new IllegalArgumentException("Could not invoke method \"" + methodName + "\"", e);
                }
            }
        } else if (throwException) {
            throw new NullPointerException("Can't invoke static method on null class reference");
        }
        return null;
    }

    /**
     * Invoke the named static method. If an exception is thrown while trying to call the method, and throwException
     * is true, then IllegalArgumentException is thrown wrapping the cause, otherwise this will return null. If
     * passed a null class reference, returns null unless throwException is true, then throws NullPointerException.
     */
    public static Object invokeStaticMethod(final Class<?> cls, final String methodName, final Class<?> argType,
            final Object arg, final boolean throwException) throws IllegalArgumentException, NullPointerException {
        if (cls != null) {
            try {
                final Method method = cls.getDeclaredMethod(methodName, argType);
                try {
                    if (!method.isAccessible()) {
                        method.setAccessible(true);
                    }
                } catch (final Exception e) {
                    // In JDK9+, may throw InaccessibleObjectException.
                    // However, still try to invoke the method below.
                }
                return method.invoke(null, arg);
            } catch (final Throwable e) {
                if (throwException) {
                    throw new IllegalArgumentException("Could not invoke method \"" + methodName + "\"", e);
                }
            }
        } else if (throwException) {
            throw new NullPointerException("Can't invoke static method on null class reference");
        }
        return null;
    }

    public static final String JAVA_VERSION = System.getProperty("java.version");
    public static final boolean JAVA_VERSION_IS_8 = JAVA_VERSION != null
            && (JAVA_VERSION.equals("8") || JAVA_VERSION.equals("1.8") || JAVA_VERSION.startsWith("1.8."));
    public static final boolean JAVA_VERSION_IS_9_PLUS = JAVA_VERSION != null
            && Integer.parseInt(JAVA_VERSION.indexOf('.') < 0 ? JAVA_VERSION
                    : JAVA_VERSION.substring(0, JAVA_VERSION.indexOf('.'))) >= 9;

    private static Object tryInvokeDefaultMethod(final Class<?> cls, final String methodName,
            final Class<?> returnType, final ClassLoader classLoader, final boolean throwException)
            throws Exception {
        final Object proxyInstance = Proxy.newProxyInstance(classLoader, new Class[] { cls },
                new InvocationHandler() {
                    @Override
                    public Object invoke(final Object proxy, final Method method, final Object[] args)
                            throws Throwable {
                        if (JAVA_VERSION_IS_8) {
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
     * NullPointerException.
     * 
     * Uses the solution in https://stackoverflow.com/a/49532492/3950982
     * 
     * N.B. This is not completely tested...
     */
    public static Object invokeDefaultMethod(final Class<?> cls, final String methodName, final Class<?> returnType,
            final ClassLoader classLoader, final boolean throwException)
            throws IllegalArgumentException, NullPointerException {
        if (cls != null) {
            if (!JAVA_VERSION_IS_8 && !JAVA_VERSION_IS_9_PLUS) {
                if (throwException) {
                    throw new NullPointerException("Can't invoke default method on JDK 1.7");
                }
            }

            // Find interfaces implemented by this class or its superclasses
            final LinkedList<Class<?>> implementedIfacesQueue = new LinkedList<>();
            final Set<Class<?>> addedIfaces = new HashSet<>();
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                if (c.isInterface()) {
                    if (addedIfaces.add(c)) {
                        implementedIfacesQueue.add(c);
                    }
                }
                for (final Class<?> iface : c.getInterfaces()) {
                    if (addedIfaces.add(iface)) {
                        implementedIfacesQueue.add(iface);
                    }
                }
            }

            // Iterate through implemented interfaces
            while (!implementedIfacesQueue.isEmpty()) {
                final Class<?> iface = implementedIfacesQueue.remove();
                try {
                    // Try calling default method on interface
                    return tryInvokeDefaultMethod(iface, methodName, returnType, classLoader, throwException);
                } catch (final NoSuchMethodException e) {
                    // Fall through if method not found, since a superinterface might have the method 
                } catch (final Throwable e) {
                    if (throwException) {
                        throw new IllegalArgumentException(
                                "Exception while invoking default method \"" + methodName + "\"", e);
                    }
                }
                // Add any superinterfaces to end of queue
                final Class<?>[] superIfaces = iface.getInterfaces();
                if (superIfaces.length > 0) {
                    for (final Class<?> superIface : superIfaces) {
                        if (addedIfaces.add(superIface)) {
                            implementedIfacesQueue.add(superIface);
                        }
                    }
                }
            }
            if (throwException) {
                throw new IllegalArgumentException("Default method \"" + methodName + "\" not found");
            }
        } else if (throwException) {
            throw new NullPointerException(
                    "Can't invoke default method \"" + methodName + "\" on null class reference");
        }
        return null;
    }

}
