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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

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

    /**
     * Get order in which to attempt invoking methods.
     *
     * @param cls
     *            the class
     * @return the order in which method calls would be attempted by the JRE.
     */
    private static List<Method> enumerateMethods(final Class<?> cls) {
        // Iterate from class to its superclasses, and find initial interfaces to start traversing from
        final List<Method> methodOrder = new ArrayList<>();
        final Set<Class<?>> visited = new HashSet<>();
        final LinkedList<Class<?>> interfaceQueue = new LinkedList<>();
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : reflectionDriver.getDeclaredMethods(c)) {
                methodOrder.add(m);
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
            for (Method m : reflectionDriver.getDeclaredMethods(iface)) {
                methodOrder.add(m);
            }
            for (final Class<?> superIface : iface.getInterfaces()) {
                if (visited.add(superIface)) {
                    interfaceQueue.add(superIface);
                }
            }
        }
        return methodOrder;
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
     *             If the field value could not be read.
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
            for (Method m : enumerateMethods(obj.getClass())) {
                if (m.getName().equals(methodName) && m.getParameterTypes().length == 0) {
                    return reflectionDriver.invoke(m, obj, new Object[0]);
                }
            }
        } catch (Exception e) {
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" could not be invoked: " + e);
            }
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
            for (Method m : enumerateMethods(obj.getClass())) {
                if (m.getName().equals(methodName) && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == argType) {
                    return reflectionDriver.invoke(m, obj, new Object[] { param });
                }
            }
        } catch (Exception e) {
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" could not be invoked: " + e);
            }
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
        if (cls == null || methodName == null) {
            if (throwException) {
                throw new IllegalArgumentException("Unexpected null argument");
            } else {
                return null;
            }
        }
        try {
            for (Method m : enumerateMethods(cls)) {
                if (m.getName().equals(methodName) && m.getParameterTypes().length == 0) {
                    return reflectionDriver.invoke(m, null, new Object[0]);
                }
            }
        } catch (Exception e) {
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" could not be invoked: " + e);
            }
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
            for (Method m : enumerateMethods(cls)) {
                if (m.getName().equals(methodName) && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == argType) {
                    return reflectionDriver.invoke(m, null, new Object[] { param });
                }
            }
        } catch (Exception e) {
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" could not be invoked: " + e);
            }
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
        } catch (final ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

}
