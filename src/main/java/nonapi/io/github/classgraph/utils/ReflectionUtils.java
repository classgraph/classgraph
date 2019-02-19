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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/** Reflection utility methods that can be used by ClassLoaderHandlers. */
public final class ReflectionUtils {

    // In JDK 9+, could use MethodHandles.privateLookupIn
    // And then use getter lookup to get fields (which works even if there is no getter function defined):
    // https://stackoverflow.com/q/19135218/3950982

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
        Field field = null;
        for (Class<?> currClass = cls; currClass != null; currClass = currClass.getSuperclass()) {
            try {
                field = currClass.getDeclaredField(fieldName);
                // Field found
                break;
            } catch (final ReflectiveOperationException | SecurityException e) {
                // Try parent
            }
        }
        if (field == null) {
            if (throwException) {
                throw new IllegalArgumentException((obj == null ? "Static field " : "Field ") + "\"" + fieldName
                        + "\" not found or not accessible");
            }
        } else {
            try {
                field.setAccessible(true);
            } catch (final RuntimeException e) { // JDK 9+: InaccessibleObjectException | SecurityException
                // Ignore
            }
            try {
                return field.get(obj);
            } catch (final IllegalAccessException e) {
                if (throwException) {
                    throw new IllegalArgumentException(
                            "Can't read " + (obj == null ? "static " : "") + " field \"" + fieldName + "\": " + e);
                }
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
     * Iterate through implemented interfaces, top-down, then superclass to subclasses, top-down (since higher-up
     * superclasses and superinterfaces have the highest chance of being visible).
     *
     * @param cls
     *            the class
     * @return the reverse of the order in which method calls would be attempted by the JRE.
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
            if (c.isInterface() && addedIfaces.add(c)) {
                ifaceQueue.add(c);
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
     * @param cls
     *            The class.
     * @param obj
     *            The object, or null to invoke a static method.
     * @param methodName
     *            The method name.
     * @param oneArg
     *            If true, look for a method with one argument of type argType. If false, look for method with no
     *            arguments.
     * @param argType
     *            The type of the first argument to the method.
     * @param param
     *            The value of the first parameter to invoke the method with.
     * @param throwException
     *            If true, throw an exception if the field value could not be read.
     * @return The field value.
     * @throws IllegalArgumentException
     *             If the field value could not be read.
     */
    private static Object invokeMethod(final Class<?> cls, final Object obj, final String methodName,
            final boolean oneArg, final Class<?> argType, final Object param, final boolean throwException)
            throws IllegalArgumentException {
        Method method = null;
        final List<Class<?>> reverseAttemptOrder = getReverseMethodAttemptOrder(cls);
        for (int i = reverseAttemptOrder.size() - 1; i >= 0; i--) {
            final Class<?> classOrInterface = reverseAttemptOrder.get(i);
            try {
                method = oneArg ? classOrInterface.getDeclaredMethod(methodName, argType)
                        : classOrInterface.getDeclaredMethod(methodName);
                // Method found
                break;
            } catch (final ReflectiveOperationException | SecurityException e) {
                // Try next interface or superclass 
            }
        }
        if (method == null) {
            if (throwException) {
                throw new IllegalArgumentException((obj == null ? "Static method " : "Method ") + "\"" + methodName
                        + "\" not found or not accesible");
            }
        } else {
            try {
                method.setAccessible(true);
            } catch (final RuntimeException e) { // JDK 9+: InaccessibleObjectException | SecurityException
                // Ignore
            }
            try {
                return oneArg ? method.invoke(obj, param) : method.invoke(obj);
            } catch (final IllegalAccessException e) {
                if (throwException) {
                    throw new IllegalArgumentException(
                            "Can't call " + (obj == null ? "static " : "") + "method \"" + methodName + "\": " + e);
                }
            } catch (final InvocationTargetException e) {
                if (throwException) {
                    throw new IllegalArgumentException("Exception while invoking " + (obj == null ? "static " : "")
                            + "method \"" + methodName + "\"", e);
                }
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
     * @param throwException
     *            If true, throw an exception if the field value could not be read.
     * @return The field value.
     * @throws IllegalArgumentException
     *             If the field value could not be read.
     */
    public static Object invokeMethod(final Object obj, final String methodName, final boolean throwException)
            throws IllegalArgumentException {
        if (obj == null || methodName == null) {
            if (throwException) {
                throw new NullPointerException();
            } else {
                return null;
            }
        }
        return invokeMethod(obj.getClass(), obj, methodName, false, null, null, throwException);
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
        if (obj == null || methodName == null) {
            if (throwException) {
                throw new NullPointerException();
            } else {
                return null;
            }
        }
        return invokeMethod(obj.getClass(), obj, methodName, true, argType, param, throwException);
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
                throw new NullPointerException();
            } else {
                return null;
            }
        }
        return invokeMethod(cls, null, methodName, false, null, null, throwException);
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
        if (cls == null || methodName == null) {
            if (throwException) {
                throw new NullPointerException();
            } else {
                return null;
            }
        }
        return invokeMethod(cls, null, methodName, true, argType, param, throwException);
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
