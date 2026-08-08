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
package nonapi.io.github.classgraph.reflection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Callable;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassGraph.CircumventEncapsulationMethod;

/** Reflection utility methods that can be used by ClassLoaderHandlers. */
public final class ReflectionUtils {
    /** The reflection driver to use. */
    public ReflectionDriver reflectionDriver;
    private Class<?> privilegedActionClass;
    private Method accessControllerDoPrivileged;

    /**
     * Call this if you change the value of
     * {@link ClassGraph#CIRCUMVENT_ENCAPSULATION}.
     */
    public ReflectionUtils() {
        if (ClassGraph.CIRCUMVENT_ENCAPSULATION == CircumventEncapsulationMethod.NARCISSUS) {
            try {
                reflectionDriver = new NarcissusReflectionDriver();
            } catch (final Throwable t) {
                System.err.println("Could not load Narcissus reflection driver: " + t);
                // Fall back to standard reflection driver
            }
        }
        if (reflectionDriver == null) {
            reflectionDriver = new StandardReflectionDriver();
        }
        try {
            final Class<?> accessControllerClass = reflectionDriver.findClass("java.security.AccessController");
            privilegedActionClass = reflectionDriver.findClass("java.security.PrivilegedAction");
            accessControllerDoPrivileged = reflectionDriver.findMethod(accessControllerClass, null, "doPrivileged",
                    privilegedActionClass);
        } catch (final Throwable t) {
            // Ignore
        }
    }

    /**
     * Get the value of the field in the class of the given object or any of its
     * superclasses. If an exception is thrown while trying to read the field, and
     * throwException is true, then IllegalArgumentException is thrown wrapping the
     * cause, otherwise this will return null. If passed a null object, returns null
     * unless throwException is true, then throws IllegalArgumentException.
     * 
     * @param throwException If true, throw an exception if the field value could
     *                       not be read.
     * @param obj            The object.
     * @param field          The field.
     * 
     * @return The field value.
     * @throws IllegalArgumentException If the field value could not be read.
     */
    public Object getFieldVal(final boolean throwException, final Object obj, final Field field)
            throws IllegalArgumentException {
        if (reflectionDriver == null) {
            throw new RuntimeException("Cannot use reflection after ScanResult has been closed");
        }
        if (obj == null || field == null) {
            if (throwException) {
                throw new NullPointerException();
            } else {
                return null;
            }
        }
        try {
            return reflectionDriver.getField(obj, field);
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException(
                        "Can't read field " + obj.getClass().getName() + "." + field.getName(), e);
            }
        }
        return null;
    }

    /**
     * Get the value of the named field in the class of the given object or any of
     * its superclasses. If an exception is thrown while trying to read the field,
     * and throwException is true, then IllegalArgumentException is thrown wrapping
     * the cause, otherwise this will return null. If passed a null object, returns
     * null unless throwException is true, then throws IllegalArgumentException.
     * 
     * @param throwException If true, throw an exception if the field value could
     *                       not be read.
     * @param obj            The object.
     * @param fieldName      The field name.
     * 
     * @return The field value.
     * @throws IllegalArgumentException If the field value could not be read.
     */
    public Object getFieldVal(final boolean throwException, final Object obj, final String fieldName)
            throws IllegalArgumentException {
        if (reflectionDriver == null) {
            throw new RuntimeException("Cannot use reflection after ScanResult has been closed");
        }
        if (obj == null || fieldName == null) {
            if (throwException) {
                throw new NullPointerException();
            } else {
                return null;
            }
        }
        try {
            return reflectionDriver.getField(obj, reflectionDriver.findInstanceField(obj, fieldName));
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException("Can't read field " + obj.getClass().getName() + "." + fieldName, e);
            }
        }
        return null;
    }

    /**
     * Get the value of the named field in the given class or any of its
     * superclasses. If an exception is thrown while trying to read the field value,
     * and throwException is true, then IllegalArgumentException is thrown wrapping
     * the cause, otherwise this will return null. If passed a null class reference,
     * returns null unless throwException is true, then throws
     * IllegalArgumentException.
     * 
     * @param throwException If true, throw an exception if the field value could
     *                       not be read.
     * @param cls            The class.
     * @param fieldName      The field name.
     * 
     * @return The field value.
     * @throws IllegalArgumentException If the field value could not be read.
     */
    public Object getStaticFieldVal(final boolean throwException, final Class<?> cls, final String fieldName)
            throws IllegalArgumentException {
        if (reflectionDriver == null) {
            throw new RuntimeException("Cannot use reflection after ScanResult has been closed");
        }
        if (cls == null || fieldName == null) {
            if (throwException) {
                throw new NullPointerException();
            } else {
                return null;
            }
        }
        try {
            return reflectionDriver.getStaticField(reflectionDriver.findStaticField(cls, fieldName));
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException("Can't read field " + cls.getName() + "." + fieldName, e);
            }
        }
        return null;
    }

    /**
     * Invoke the named method in the given object or its superclasses. If an
     * exception is thrown while trying to call the method, and throwException is
     * true, then IllegalArgumentException is thrown wrapping the cause, otherwise
     * this will return null. If passed a null object, returns null unless
     * throwException is true, then throws IllegalArgumentException.
     * 
     * @param throwException If true, throw an exception if the field value could
     *                       not be read.
     * @param obj            The object.
     * @param methodName     The method name.
     * 
     * @return The result of the method invocation.
     * @throws IllegalArgumentException If the method could not be invoked.
     */
    public Object invokeMethod(final boolean throwException, final Object obj, final String methodName)
            throws IllegalArgumentException {
        if (reflectionDriver == null) {
            throw new RuntimeException("Cannot use reflection after ScanResult has been closed");
        }
        if (obj == null || methodName == null) {
            if (throwException) {
                throw new IllegalArgumentException("Unexpected null argument");
            } else {
                return null;
            }
        }
        try {
            return reflectionDriver.invokeMethod(obj, reflectionDriver.findInstanceMethod(obj, methodName));
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" could not be invoked", e);
            }
            return null;
        }
    }

    /**
     * Invoke the named method in the given object or its superclasses. If an
     * exception is thrown while trying to call the method, and throwException is
     * true, then IllegalArgumentException is thrown wrapping the cause, otherwise
     * this will return null. If passed a null object, returns null unless
     * throwException is true, then throws IllegalArgumentException.
     * 
     * @param throwException Whether to throw an exception on failure.
     * @param obj            The object.
     * @param methodName     The method name.
     * @param argType        The type of the method argument.
     * @param param          The parameter value to use when invoking the method.
     * 
     * @return The result of the method invocation.
     * @throws IllegalArgumentException If the method could not be invoked.
     */
    public Object invokeMethod(final boolean throwException, final Object obj, final String methodName,
            final Class<?> argType, final Object param) throws IllegalArgumentException {
        if (reflectionDriver == null) {
            throw new RuntimeException("Cannot use reflection after ScanResult has been closed");
        }
        if (obj == null || methodName == null || argType == null) {
            if (throwException) {
                throw new IllegalArgumentException("Unexpected null argument");
            } else {
                return null;
            }
        }
        try {
            return reflectionDriver.invokeMethod(obj, reflectionDriver.findInstanceMethod(obj, methodName, argType),
                    param);
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" could not be invoked", e);
            }
            return null;
        }
    }

    /**
     * Invoke the named method in the given object or its superclasses. If an
     * exception is thrown while trying to call the method, and throwException is
     * true, then IllegalArgumentException is thrown wrapping the cause, otherwise
     * this will return null. If passed a null object, returns null unless
     * throwException is true, then throws IllegalArgumentException.
     *
     * @param throwException Whether to throw an exception on failure.
     * @param obj            The object.
     * @param methodName     The method name.
     * @param argTypes       The types of the method arguments.
     * @param params         The parameter values to use when invoking the method.
     *
     * @return The result of the method invocation.
     * @throws IllegalArgumentException If the method could not be invoked.
     */
    public Object invokeMethod(final boolean throwException, final Object obj, final String methodName,
            final Class<?>[] argTypes, final Object[] params) throws IllegalArgumentException {
        if (reflectionDriver == null) {
            throw new RuntimeException("Cannot use reflection after ScanResult has been closed");
        }
        if (obj == null || methodName == null || argTypes == null || params == null
                || argTypes.length != params.length) {
            if (throwException) {
                throw new IllegalArgumentException("Unexpected null argument");
            } else {
                return null;
            }
        }
        try {
            return reflectionDriver.invokeMethod(obj, reflectionDriver.findInstanceMethod(obj, methodName, argTypes),
                    params);
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" could not be invoked", e);
            }
            return null;
        }
    }

    /**
     * Invoke the named method. If an exception is thrown while trying to call the
     * method, and throwException is true, then IllegalArgumentException is thrown
     * wrapping the cause, otherwise this will return null. If passed a null class
     * reference, returns null unless throwException is true, then throws
     * IllegalArgumentException.
     * 
     * @param throwException Whether to throw an exception on failure.
     * @param cls            The class.
     * @param methodName     The method name.
     * 
     * @return The result of the method invocation.
     * @throws IllegalArgumentException If the method could not be invoked.
     */
    public Object invokeStaticMethod(final boolean throwException, final Class<?> cls, final String methodName)
            throws IllegalArgumentException {
        if (reflectionDriver == null) {
            throw new RuntimeException("Cannot use reflection after ScanResult has been closed");
        }
        if (cls == null || methodName == null) {
            if (throwException) {
                throw new IllegalArgumentException("Unexpected null argument");
            } else {
                return null;
            }
        }
        try {
            return reflectionDriver.invokeStaticMethod(reflectionDriver.findStaticMethod(cls, methodName));
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" could not be invoked", e);
            }
            return null;
        }
    }

    /**
     * Invoke the named method. If an exception is thrown while trying to call the
     * method, and throwException is true, then IllegalArgumentException is thrown
     * wrapping the cause, otherwise this will return null. If passed a null class
     * reference, returns null unless throwException is true, then throws
     * IllegalArgumentException.
     * 
     * @param throwException Whether to throw an exception on failure.
     * @param cls            The class.
     * @param methodName     The method name.
     * @param argType        The type of the method argument.
     * @param param          The parameter value to use when invoking the method.
     * 
     * @return The result of the method invocation.
     * @throws IllegalArgumentException If the method could not be invoked.
     */
    public Object invokeStaticMethod(final boolean throwException, final Class<?> cls, final String methodName,
            final Class<?> argType, final Object param) throws IllegalArgumentException {
        if (reflectionDriver == null) {
            throw new RuntimeException("Cannot use reflection after ScanResult has been closed");
        }
        if (cls == null || methodName == null || argType == null) {
            if (throwException) {
                throw new IllegalArgumentException("Unexpected null argument");
            } else {
                return null;
            }
        }
        try {
            return reflectionDriver.invokeStaticMethod(reflectionDriver.findStaticMethod(cls, methodName, argType),
                    param);
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" could not be invoked", e);
            }
            return null;
        }
    }

    /**
     * Call Class.forName(className), but return null if any exception is thrown.
     * 
     * @param className The class name to load.
     * @return The class of the requested name, or null if an exception was thrown
     *         while trying to load the class.
     */
    public Class<?> classForNameOrNull(final String className) {
        if (reflectionDriver == null) {
            throw new RuntimeException("Cannot use reflection after ScanResult has been closed");
        }
        try {
            return reflectionDriver.findClass(className);
        } catch (final Throwable e) {
            return null;
        }
    }

    /**
     * Get a static method by name, but return null if any exception is thrown.
     *
     * @param className        The name of the class declaring the method.
     * @param staticMethodName The name of the static method.
     * @return The requested static method, or null if an exception was thrown while
     *         trying to find the class or the method.
     */
    public Method staticMethodForNameOrNull(final String className, final String staticMethodName) {
        if (reflectionDriver == null) {
            throw new RuntimeException("Cannot use reflection after ScanResult has been closed");
        }
        try {
            return reflectionDriver.findStaticMethod(reflectionDriver.findClass(className), staticMethodName);
        } catch (final Throwable e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Call a method in the AccessController.doPrivileged(PrivilegedAction) context,
     * using reflection, if possible (AccessController is deprecated in JDK 17).
     *
     * @param <T>      the return type of the callable
     * @param callable the callable to invoke
     * @return the value returned by the callable
     * @throws Throwable if the callable throws.
     */
    @SuppressWarnings("unchecked")
    public <T> T doPrivileged(final Callable<T> callable) throws Throwable {
        if (accessControllerDoPrivileged != null) {
            final var privilegedAction = Proxy.newProxyInstance(privilegedActionClass.getClassLoader(),
                    new Class<?>[] { privilegedActionClass }, (proxy, method, args) -> callable.call());
            return (T) accessControllerDoPrivileged.invoke(null, privilegedAction);
        } else {
            // Fall back to invoking in a non-privileged context
            return callable.call();
        }
    }

}
