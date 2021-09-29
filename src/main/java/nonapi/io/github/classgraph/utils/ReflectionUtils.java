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
 * Copyright (c) 2019 Luke Hutchison
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
package nonapi.io.github.classgraph.utils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.github.toolfactory.jvm.DefaultDriver;
import io.github.toolfactory.jvm.Driver;

/** Reflection utility methods that can be used by ClassLoaderHandlers. */
public final class ReflectionUtils {
    /**
     * Use jvm-driver to bypass Java visibility restrictions, security manager limitations, and strong
     * encapsulation.
     */
    private static Driver reflectionDriver = new DefaultDriver();

    /**
     * Constructor.
     */
    private ReflectionUtils() {
        // Cannot be constructed
    }

    /**
     * Get the value of the named field in the class of the given object or any of its superclasses. If an exception
     * is thrown while trying to read the field, and throwException is true, then IllegalArgumentException is thrown
     * wrapping the cause, otherwise this will return null. If passed a null object, returns null unless
     * throwException is true, then throws IllegalArgumentException.
     *
     * @param cls
     *            The class.
     * @param obj
     *            The object, or null to get the value of a static field.
     * @param fieldName
     *            The field name.
     * @param throwException
     *            If true, throw an exception if the field value could not be read.
     * @return The field value.
     * @throws IllegalArgumentException
     *             If the field value could not be read.
     */
    private static Object getFieldVal(final Class<?> cls, final Object obj, final String fieldName,
            final boolean throwException) throws IllegalArgumentException {
        try {
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                for (Field field : reflectionDriver.getDeclaredFields(c)) {
                    if (field.getName().equals(fieldName)) {
                        return reflectionDriver.getFieldValue(obj, field);
                    }
                }
            }
        } catch (Exception e) {
            if (throwException) {
                throw new IllegalArgumentException(
                        "Can't read " + (obj == null ? "static " : "") + " field \"" + fieldName + "\": " + e);
            }
        }
        return null;
    }

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
        if (obj == null || fieldName == null) {
            if (throwException) {
                throw new NullPointerException();
            } else {
                return null;
            }
        }
        return getFieldVal(obj.getClass(), obj, fieldName, throwException);
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
        if (cls == null || fieldName == null) {
            if (throwException) {
                throw new NullPointerException();
            } else {
                return null;
            }
        }
        return getFieldVal(cls, null, fieldName, throwException);
    }

    /** Iterator applied to each method of a class and its superclasses/interfaces. */
    private static interface MethodIterator {
        /** @return true to stop iterating, or false to continue iterating */
        boolean foundMethod(Method m);
    }

    /**
     * Iterate through all methods in the given class, ignoring visibility and bypassing security checks. Also
     * iterates up through superclasses, to collect all methods of the class and its superclasses.
     *
     * @param cls
     *            the class
     */
    private static void forAllMethods(final Class<?> cls, final MethodIterator methodIter) {
        // Iterate from class to its superclasses, and find initial interfaces to start traversing from
        final Set<Class<?>> visited = new HashSet<>();
        final LinkedList<Class<?>> interfaceQueue = new LinkedList<>();
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (final Method m : reflectionDriver.getDeclaredMethods(c)) {
                if (methodIter.foundMethod(m)) {
                    return;
                }
            }
            // Find interfaces and superinterfaces implemented by this class or its superclasses
            if (c.isInterface() && visited.add(c)) {
                interfaceQueue.add(c);
            }
            for (final Class<?> iface : c.getInterfaces()) {
                if (visited.add(iface)) {
                    interfaceQueue.add(iface);
                }
            }
        }
        // Traverse through interfaces looking for default methods
        while (!interfaceQueue.isEmpty()) {
            final Class<?> iface = interfaceQueue.remove();
            for (final Method m : reflectionDriver.getDeclaredMethods(iface)) {
                if (methodIter.foundMethod(m)) {
                    return;
                }
            }
            for (final Class<?> superIface : iface.getInterfaces()) {
                if (visited.add(superIface)) {
                    interfaceQueue.add(superIface);
                }
            }
        }
    }

    /**
     * Find a method by name and parameter types in the given class, ignoring visibility and bypassing security
     * checks.
     *
     * @param cls
     *            the class
     * @param methodName
     *            the method name.
     * @param paramTypes
     *            the parameter types of the method.
     * @return the {@link Method}
     * @throws NoSuchMethodException
     *             if the class does not contain a method of the given name
     */
    private static Method findMethod(final Class<?> cls, final String methodName, final Class<?>... paramTypes)
            throws NoSuchMethodException {
        final AtomicReference<Method> method = new AtomicReference<>();
        forAllMethods(cls, new MethodIterator() {
            @Override
            public boolean foundMethod(final Method m) {
                if (m.getName().equals(methodName) && Arrays.equals(paramTypes, m.getParameterTypes())) {
                    method.set(m);
                    return true;
                }
                return false;
            }
        });
        final Method m = method.get();
        if (m != null) {
            return m;
        } else {
            throw new NoSuchMethodException(methodName);
        }
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
     * @return The result of the method invocation.
     * @throws IllegalArgumentException
     *             If the method could not be invoked.
     */
    public static Object invokeMethod(final Object obj, final String methodName, final boolean throwException)
            throws IllegalArgumentException {
        if (obj == null || methodName == null) {
            if (throwException) {
                throw new IllegalArgumentException("Unexpected null argument");
            } else {
                return null;
            }
        }
        try {
            return reflectionDriver.invoke(findMethod(obj.getClass(), methodName), obj, new Object[0]);
        } catch (Exception e) {
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" could not be invoked: " + e);
            }
            return null;
        }
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
     *            The type of the method argument.
     * @param param
     *            The parameter value to use when invoking the method.
     * @param throwException
     *            Whether to throw an exception on failure.
     * @return The result of the method invocation.
     * @throws IllegalArgumentException
     *             If the method could not be invoked.
     */
    public static Object invokeMethod(final Object obj, final String methodName, final Class<?> argType,
            final Object param, final boolean throwException) throws IllegalArgumentException {
        if (obj == null || methodName == null || argType == null) {
            if (throwException) {
                throw new IllegalArgumentException("Unexpected null argument");
            } else {
                return null;
            }
        }
        try {
            return reflectionDriver.invoke(findMethod(obj.getClass(), methodName, argType), obj,
                    new Object[] { param });
        } catch (Exception e) {
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" could not be invoked: " + e);
            }
            return null;
        }
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
        if (cls == null || methodName == null) {
            if (throwException) {
                throw new IllegalArgumentException("Unexpected null argument");
            } else {
                return null;
            }
        }
        try {
            return reflectionDriver.invoke(findMethod(cls, methodName), null, new Object[0]);
        } catch (Exception e) {
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" could not be invoked: " + e);
            }
            return null;
        }
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
     *            The type of the method argument.
     * @param param
     *            The parameter value to use when invoking the method.
     * @param throwException
     *            Whether to throw an exception on failure.
     * @return The result of the method invocation.
     * @throws IllegalArgumentException
     *             If the method could not be invoked.
     */
    public static Object invokeStaticMethod(final Class<?> cls, final String methodName, final Class<?> argType,
            final Object param, final boolean throwException) throws IllegalArgumentException {
        if (cls == null || methodName == null || argType == null) {
            if (throwException) {
                throw new IllegalArgumentException("Unexpected null argument");
            } else {
                return null;
            }
        }
        try {
            return reflectionDriver.invoke(findMethod(cls, methodName, argType), null, new Object[] { param });
        } catch (Exception e) {
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" could not be invoked: " + e);
            }
            return null;
        }
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
        } catch (final ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

}
