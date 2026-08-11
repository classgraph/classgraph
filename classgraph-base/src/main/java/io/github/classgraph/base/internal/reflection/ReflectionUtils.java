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
package io.github.classgraph.base.internal.reflection;

import java.lang.reflect.Field;

import org.jspecify.annotations.Nullable;

/** Reflection utility methods that can be used by ClassLoaderHandlers. */
public final class ReflectionUtils {
    /**
     * The reflection driver, chosen once per JVM: the Narcissus driver if the Narcissus library is on the classpath
     * or module path and its native library loaded, otherwise the standard reflection driver.
     */
    private static final ReflectionDriver REFLECTION_DRIVER = findReflectionDriver();

    /** Constructor. */
    public ReflectionUtils() {
        // Empty
    }

    /**
     * Find the reflection driver to use.
     *
     * <p>
     * On JDK 16+, the JDK enforces strong encapsulation, so the standard reflection driver may be unable to read
     * the classpath from a classloader that does not expose it via a public method or field. Adding the
     * <a href="https://github.com/toolfactory/narcissus">Narcissus</a> library to the project works around this:
     * Narcissus reads fields and invokes methods through JNI, which is not subject to Java's access controls.
     * Narcissus is looked up reflectively, so ClassGraph has no compile-time or runtime dependency on it.
     *
     * @return the reflection driver.
     */
    private static ReflectionDriver findReflectionDriver() {
        try {
            return new NarcissusReflectionDriver();
        } catch (final ClassNotFoundException | NoClassDefFoundError e) {
            // Narcissus is not on the classpath or module path -- this is the usual case, so don't log anything
        } catch (final Throwable t) {
            // Narcissus is present, but could not be used (most likely its native library has not been compiled
            // for this platform or architecture). This is worth reporting, since it was added deliberately.
            System.err.println("ClassGraph could not use the Narcissus reflection driver: " + t);
        }
        return new StandardReflectionDriver();
    }

    /**
     * Get the value of the field in the class of the given object or any of its superclasses. If an exception is
     * thrown while trying to read the field, and throwException is true, then IllegalArgumentException is thrown
     * wrapping the cause, otherwise this will return null. If passed a null object, returns null unless
     * throwException is true, then throws IllegalArgumentException.
     *
     * @param throwException
     *            If true, throw an exception if the field value could not be read.
     * @param obj
     *            The object.
     * @param field
     *            The field.
     *
     * @return The field value.
     * @throws IllegalArgumentException
     *             If the field value could not be read.
     */
    public @Nullable Object getFieldVal(final boolean throwException, final @Nullable Object obj,
            final @Nullable Field field) throws IllegalArgumentException {
        if (obj == null || field == null) {
            if (throwException) {
                throw new IllegalArgumentException("Unexpected null argument");
            } else {
                return null;
            }
        }
        try {
            return REFLECTION_DRIVER.getField(obj, field);
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException(
                        "Can't read field " + obj.getClass().getName() + "." + field.getName(), e);
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
     * @param throwException
     *            If true, throw an exception if the field value could not be read.
     * @param obj
     *            The object.
     * @param fieldName
     *            The field name.
     *
     * @return The field value.
     * @throws IllegalArgumentException
     *             If the field value could not be read.
     */
    public @Nullable Object getFieldVal(final boolean throwException, final @Nullable Object obj,
            final @Nullable String fieldName) throws IllegalArgumentException {
        if (obj == null || fieldName == null) {
            if (throwException) {
                throw new IllegalArgumentException("Unexpected null argument");
            } else {
                return null;
            }
        }
        try {
            return REFLECTION_DRIVER.getField(obj, REFLECTION_DRIVER.findInstanceField(obj, fieldName));
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException("Can't read field " + obj.getClass().getName() + "." + fieldName,
                        e);
            }
        }
        return null;
    }

    /**
     * Get the value of the named field in the given class or any of its superclasses. If an exception is thrown
     * while trying to read the field value, and throwException is true, then IllegalArgumentException is thrown
     * wrapping the cause, otherwise this will return null. If passed a null class reference, returns null unless
     * throwException is true, then throws IllegalArgumentException.
     *
     * @param throwException
     *            If true, throw an exception if the field value could not be read.
     * @param cls
     *            The class.
     * @param fieldName
     *            The field name.
     *
     * @return The field value.
     * @throws IllegalArgumentException
     *             If the field value could not be read.
     */
    public @Nullable Object getStaticFieldVal(final boolean throwException, final @Nullable Class<?> cls,
            final @Nullable String fieldName) throws IllegalArgumentException {
        if (cls == null || fieldName == null) {
            if (throwException) {
                throw new IllegalArgumentException("Unexpected null argument");
            } else {
                return null;
            }
        }
        try {
            return REFLECTION_DRIVER.getStaticField(REFLECTION_DRIVER.findStaticField(cls, fieldName));
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException("Can't read field " + cls.getName() + "." + fieldName, e);
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
     * @param throwException
     *            If true, throw an exception if the field value could not be read.
     * @param obj
     *            The object.
     * @param methodName
     *            The method name.
     *
     * @return The result of the method invocation.
     * @throws IllegalArgumentException
     *             If the method could not be invoked.
     */
    public @Nullable Object invokeMethod(final boolean throwException, final @Nullable Object obj,
            final @Nullable String methodName) throws IllegalArgumentException {
        if (obj == null || methodName == null) {
            if (throwException) {
                throw new IllegalArgumentException("Unexpected null argument");
            } else {
                return null;
            }
        }
        try {
            return REFLECTION_DRIVER.invokeMethod(obj, REFLECTION_DRIVER.findInstanceMethod(obj, methodName));
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" could not be invoked", e);
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
     * @param throwException
     *            Whether to throw an exception on failure.
     * @param obj
     *            The object.
     * @param methodName
     *            The method name.
     * @param argType
     *            The type of the method argument.
     * @param param
     *            The parameter value to use when invoking the method.
     *
     * @return The result of the method invocation.
     * @throws IllegalArgumentException
     *             If the method could not be invoked.
     */
    public @Nullable Object invokeMethod(final boolean throwException, final @Nullable Object obj,
            final @Nullable String methodName, final @Nullable Class<?> argType, final @Nullable Object param)
            throws IllegalArgumentException {
        if (obj == null || methodName == null || argType == null) {
            if (throwException) {
                throw new IllegalArgumentException("Unexpected null argument");
            } else {
                return null;
            }
        }
        try {
            return REFLECTION_DRIVER.invokeMethod(obj,
                    REFLECTION_DRIVER.findInstanceMethod(obj, methodName, argType), param);
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" could not be invoked", e);
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
     * @param throwException
     *            Whether to throw an exception on failure.
     * @param obj
     *            The object.
     * @param methodName
     *            The method name.
     * @param argTypes
     *            The types of the method arguments.
     * @param params
     *            The parameter values to use when invoking the method.
     *
     * @return The result of the method invocation.
     * @throws IllegalArgumentException
     *             If the method could not be invoked.
     */
    public @Nullable Object invokeMethod(final boolean throwException, final @Nullable Object obj,
            final @Nullable String methodName, final Class<?> @Nullable [] argTypes,
            final @Nullable Object @Nullable [] params) throws IllegalArgumentException {
        if (obj == null || methodName == null || argTypes == null || params == null
                || argTypes.length != params.length) {
            if (throwException) {
                throw new IllegalArgumentException("Unexpected null argument");
            } else {
                return null;
            }
        }
        try {
            return REFLECTION_DRIVER.invokeMethod(obj,
                    REFLECTION_DRIVER.findInstanceMethod(obj, methodName, argTypes), params);
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" could not be invoked", e);
            }
            return null;
        }
    }

    /**
     * Invoke the named method. If an exception is thrown while trying to call the method, and throwException is
     * true, then IllegalArgumentException is thrown wrapping the cause, otherwise this will return null. If passed
     * a null class reference, returns null unless throwException is true, then throws IllegalArgumentException.
     *
     * @param throwException
     *            Whether to throw an exception on failure.
     * @param cls
     *            The class.
     * @param methodName
     *            The method name.
     *
     * @return The result of the method invocation.
     * @throws IllegalArgumentException
     *             If the method could not be invoked.
     */
    public @Nullable Object invokeStaticMethod(final boolean throwException, final @Nullable Class<?> cls,
            final @Nullable String methodName) throws IllegalArgumentException {
        if (cls == null || methodName == null) {
            if (throwException) {
                throw new IllegalArgumentException("Unexpected null argument");
            } else {
                return null;
            }
        }
        try {
            return REFLECTION_DRIVER.invokeStaticMethod(REFLECTION_DRIVER.findStaticMethod(cls, methodName));
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" could not be invoked", e);
            }
            return null;
        }
    }

    /**
     * Invoke the named method. If an exception is thrown while trying to call the method, and throwException is
     * true, then IllegalArgumentException is thrown wrapping the cause, otherwise this will return null. If passed
     * a null class reference, returns null unless throwException is true, then throws IllegalArgumentException.
     *
     * @param throwException
     *            Whether to throw an exception on failure.
     * @param cls
     *            The class.
     * @param methodName
     *            The method name.
     * @param argType
     *            The type of the method argument.
     * @param param
     *            The parameter value to use when invoking the method.
     *
     * @return The result of the method invocation.
     * @throws IllegalArgumentException
     *             If the method could not be invoked.
     */
    public @Nullable Object invokeStaticMethod(final boolean throwException, final @Nullable Class<?> cls,
            final @Nullable String methodName, final @Nullable Class<?> argType, final @Nullable Object param)
            throws IllegalArgumentException {
        if (cls == null || methodName == null || argType == null) {
            if (throwException) {
                throw new IllegalArgumentException("Unexpected null argument");
            } else {
                return null;
            }
        }
        try {
            return REFLECTION_DRIVER
                    .invokeStaticMethod(REFLECTION_DRIVER.findStaticMethod(cls, methodName, argType), param);
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
     * @param className
     *            The class name to load.
     * @return The class of the requested name, or null if an exception was thrown while trying to load the class.
     */
    public @Nullable Class<?> classForNameOrNull(final String className) {
        try {
            return REFLECTION_DRIVER.findClass(className);
        } catch (final Throwable e) {
            return null;
        }
    }
}
