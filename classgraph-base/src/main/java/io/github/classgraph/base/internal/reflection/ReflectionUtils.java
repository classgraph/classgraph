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
 * Copyright (c) 2026 Luke Hutchison
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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.jspecify.annotations.Nullable;

/**
 * Reflection utility methods used by {@code ClassLoaderHandler} implementations.
 *
 * <p>
 * Methods in this class return {@code null} or throw {@link IllegalArgumentException} on reflection failure, except
 * that any {@link VirtualMachineError} is always rethrown directly.
 */
public final class ReflectionUtils {
    /**
     * The active reflection driver ({@link NarcissusReflectionDriver} if available, otherwise
     * {@link StandardReflectionDriver}).
     */
    private static final ReflectionDriver REFLECTION_DRIVER = findReflectionDriver();

    /** Not instantiable. */
    private ReflectionUtils() {
        // Empty
    }

    /**
     * Instantiate {@link NarcissusReflectionDriver} if Narcissus is available, falling back to
     * {@link StandardReflectionDriver}.
     *
     * @return the reflection driver.
     */
    private static ReflectionDriver findReflectionDriver() {
        try {
            return new NarcissusReflectionDriver();
        } catch (final ClassNotFoundException | NoClassDefFoundError _) {
            // Narcissus is not on the classpath, and is not a resolved module -- this is the usual case, so don't
            // log anything. (The "requires static" in module-info.java does not resolve Narcissus: a Narcissus jar
            // on the module path is only resolved if the application requires it, or by --add-modules.)
        } catch (final Throwable t) {
            ReflectionDriver.rethrowIfVirtualMachineError(t);
            // Narcissus is present, but could not be used (most likely its native library has not been compiled
            // for this platform or architecture). This is worth reporting, since it was added deliberately.
            System.err.println("ClassGraph could not use the Narcissus reflection driver: " + t);
        }
        return new StandardReflectionDriver();
    }

    /**
     * Read a static or non-static field.
     *
     * @param obj
     *            the object to read the field from, if the field is not static.
     * @param field
     *            the field.
     * @return the field value.
     * @throws Exception
     *             if the field could not be read.
     */
    private static @Nullable Object read(final Object obj, final Field field) throws Exception {
        return Modifier.isStatic(field.getModifiers()) ? REFLECTION_DRIVER.getStaticField(field)
                : REFLECTION_DRIVER.getField(obj, field);
    }

    /**
     * Invoke a static or non-static method.
     *
     * @param obj
     *            the object to invoke the method on, if the method is not static.
     * @param method
     *            the method.
     * @param args
     *            the method arguments.
     * @return the return value of the method.
     * @throws Exception
     *             if the method could not be invoked, or if it threw.
     */
    private static @Nullable Object invoke(final Object obj, final Method method, final @Nullable Object... args)
            throws Exception {
        return Modifier.isStatic(method.getModifiers()) ? REFLECTION_DRIVER.invokeStaticMethod(method, args)
                : REFLECTION_DRIVER.invokeMethod(obj, method, args);
    }

    /**
     * Handle a reflection failure by rethrowing any {@link VirtualMachineError}, throwing
     * {@link IllegalArgumentException} if {@code throwException} is {@code true}, or returning {@code null}.
     *
     * @param throwException
     *            If {@code true}, throw an {@link IllegalArgumentException}; otherwise return {@code null}.
     * @param message
     *            The exception message.
     * @param cause
     *            The cause of the failure, or {@code null} if none.
     * @return {@code null}, if {@code throwException} is {@code false}.
     * @throws IllegalArgumentException
     *             if {@code throwException} is {@code true}.
     */
    private static @Nullable Object fail(final boolean throwException, final String message,
            final @Nullable Throwable cause) throws IllegalArgumentException {
        if (cause != null) {
            ReflectionDriver.rethrowIfVirtualMachineError(cause);
        }
        if (throwException) {
            throw new IllegalArgumentException(message, cause);
        }
        return null;
    }

    /**
     * Get the value of a field on an object.
     *
     * @param throwException
     *            If {@code true}, throw {@link IllegalArgumentException} on failure instead of returning
     *            {@code null}.
     * @param obj
     *            The object.
     * @param field
     *            The field.
     * @return The field value, or {@code null}.
     * @throws IllegalArgumentException
     *             If the field value could not be read and {@code throwException} is {@code true}.
     */
    public static @Nullable Object getFieldVal(final boolean throwException, final @Nullable Object obj,
            final @Nullable Field field) throws IllegalArgumentException {
        if (obj == null || field == null) {
            return fail(throwException, "Unexpected null argument", null);
        }
        try {
            return read(obj, field);
        } catch (final Throwable e) {
            return fail(throwException, "Can't read field " + obj.getClass().getName() + "." + field.getName(), e);
        }
    }

    /**
     * Get the value of a named field in the class of {@code obj} or any of its supertypes.
     *
     * @param throwException
     *            If {@code true}, throw {@link IllegalArgumentException} on failure instead of returning
     *            {@code null}.
     * @param obj
     *            The object.
     * @param fieldName
     *            The field name.
     * @return The field value, or {@code null}.
     * @throws IllegalArgumentException
     *             If the field value could not be read and {@code throwException} is {@code true}.
     */
    public static @Nullable Object getFieldVal(final boolean throwException, final @Nullable Object obj,
            final @Nullable String fieldName) throws IllegalArgumentException {
        if (obj == null || fieldName == null) {
            return fail(throwException, "Unexpected null argument", null);
        }
        try {
            return read(obj, REFLECTION_DRIVER.findField(obj.getClass(), obj, fieldName));
        } catch (final Throwable e) {
            return fail(throwException, "Can't read field " + obj.getClass().getName() + "." + fieldName, e);
        }
    }

    /**
     * Invoke a zero-argument method by name on {@code obj}.
     *
     * @param throwException
     *            If {@code true}, throw {@link IllegalArgumentException} on failure instead of returning
     *            {@code null}.
     * @param obj
     *            The object.
     * @param methodName
     *            The method name.
     * @return The result of the method invocation, or {@code null}.
     * @throws IllegalArgumentException
     *             If the method could not be invoked and {@code throwException} is {@code true}.
     */
    public static @Nullable Object invokeMethod(final boolean throwException, final @Nullable Object obj,
            final @Nullable String methodName) throws IllegalArgumentException {
        if (obj == null || methodName == null) {
            return fail(throwException, "Unexpected null argument", null);
        }
        try {
            return invoke(obj, REFLECTION_DRIVER.findMethod(obj.getClass(), obj, methodName));
        } catch (final Throwable e) {
            return fail(throwException, "Can't invoke method " + obj.getClass().getName() + "." + methodName, e);
        }
    }

    /**
     * Invoke a single-argument method by name on {@code obj}.
     *
     * @param throwException
     *            If {@code true}, throw {@link IllegalArgumentException} on failure instead of returning
     *            {@code null}.
     * @param obj
     *            The object.
     * @param methodName
     *            The method name.
     * @param argType
     *            The parameter type of the method.
     * @param param
     *            The argument value.
     * @return The result of the method invocation, or {@code null}.
     * @throws IllegalArgumentException
     *             If the method could not be invoked and {@code throwException} is {@code true}.
     */
    public static @Nullable Object invokeMethod(final boolean throwException, final @Nullable Object obj,
            final @Nullable String methodName, final @Nullable Class<?> argType, final @Nullable Object param)
            throws IllegalArgumentException {
        if (obj == null || methodName == null || argType == null) {
            return fail(throwException, "Unexpected null argument", null);
        }
        try {
            return invoke(obj, REFLECTION_DRIVER.findMethod(obj.getClass(), obj, methodName, argType), param);
        } catch (final Throwable e) {
            return fail(throwException, "Can't invoke method " + obj.getClass().getName() + "." + methodName, e);
        }
    }

    /**
     * Invoke a method by name and parameter types on {@code obj}.
     *
     * @param throwException
     *            If {@code true}, throw {@link IllegalArgumentException} on failure instead of returning
     *            {@code null}.
     * @param obj
     *            The object.
     * @param methodName
     *            The method name.
     * @param argTypes
     *            The parameter types of the method.
     * @param params
     *            The argument values.
     * @return The result of the method invocation, or {@code null}.
     * @throws IllegalArgumentException
     *             If the method could not be invoked and {@code throwException} is {@code true}.
     */
    public static @Nullable Object invokeMethod(final boolean throwException, final @Nullable Object obj,
            final @Nullable String methodName, final Class<?> @Nullable [] argTypes,
            final @Nullable Object @Nullable [] params) throws IllegalArgumentException {
        if (obj == null || methodName == null || argTypes == null || params == null) {
            return fail(throwException, "Unexpected null argument", null);
        }
        if (argTypes.length != params.length) {
            return fail(throwException,
                    "Got " + argTypes.length + " argument types but " + params.length + " parameter values", null);
        }
        try {
            return invoke(obj, REFLECTION_DRIVER.findMethod(obj.getClass(), obj, methodName, argTypes), params);
        } catch (final Throwable e) {
            return fail(throwException, "Can't invoke method " + obj.getClass().getName() + "." + methodName, e);
        }
    }

    /**
     * Invoke a zero-argument static method by name on {@code cls}.
     *
     * @param throwException
     *            If {@code true}, throw {@link IllegalArgumentException} on failure instead of returning
     *            {@code null}.
     * @param cls
     *            The class.
     * @param methodName
     *            The method name.
     * @return The result of the method invocation, or {@code null}.
     * @throws IllegalArgumentException
     *             If the method could not be invoked and {@code throwException} is {@code true}.
     */
    public static @Nullable Object invokeStaticMethod(final boolean throwException, final @Nullable Class<?> cls,
            final @Nullable String methodName) throws IllegalArgumentException {
        if (cls == null || methodName == null) {
            return fail(throwException, "Unexpected null argument", null);
        }
        try {
            return REFLECTION_DRIVER.invokeStaticMethod(REFLECTION_DRIVER.findStaticMethod(cls, methodName));
        } catch (final Throwable e) {
            return fail(throwException, "Can't invoke method " + cls.getName() + "." + methodName, e);
        }
    }

    /**
     * Invoke a single-argument static method by name on {@code cls}.
     *
     * @param throwException
     *            If {@code true}, throw {@link IllegalArgumentException} on failure instead of returning
     *            {@code null}.
     * @param cls
     *            The class.
     * @param methodName
     *            The method name.
     * @param argType
     *            The parameter type of the method.
     * @param param
     *            The argument value.
     * @return The result of the method invocation, or {@code null}.
     * @throws IllegalArgumentException
     *             If the method could not be invoked and {@code throwException} is {@code true}.
     */
    public static @Nullable Object invokeStaticMethod(final boolean throwException, final @Nullable Class<?> cls,
            final @Nullable String methodName, final @Nullable Class<?> argType, final @Nullable Object param)
            throws IllegalArgumentException {
        if (cls == null || methodName == null || argType == null) {
            return fail(throwException, "Unexpected null argument", null);
        }
        try {
            return REFLECTION_DRIVER
                    .invokeStaticMethod(REFLECTION_DRIVER.findStaticMethod(cls, methodName, argType), param);
        } catch (final Throwable e) {
            return fail(throwException, "Can't invoke method " + cls.getName() + "." + methodName, e);
        }
    }

    /**
     * Find a class by name in ClassGraph's classloader, returning {@code null} if it could not be found or loaded.
     *
     * @param className
     *            The class name to load.
     * @return The loaded {@link Class}, or {@code null} if it could not be found or loaded.
     * @throws VirtualMachineError
     *             if one was thrown while loading or initializing the class.
     */
    public static @Nullable Class<?> classForNameOrNull(final String className) {
        try {
            return REFLECTION_DRIVER.findClass(className);
        } catch (final Throwable t) {
            ReflectionDriver.rethrowIfVirtualMachineError(t);
            return null;
        }
    }

    /**
     * Find a class by name in {@code classLoader} without initializing it, returning {@code null} if it could not
     * be found or loaded.
     *
     * @param className
     *            The class name to load.
     * @param classLoader
     *            The classloader to find the class in, or {@code null} to use the bootstrap classloader.
     * @return The loaded {@link Class}, or {@code null} if it could not be found or loaded.
     * @throws VirtualMachineError
     *             if one was thrown while loading the class.
     */
    public static @Nullable Class<?> classForNameOrNull(final String className,
            final @Nullable ClassLoader classLoader) {
        try {
            return REFLECTION_DRIVER.findClass(className, classLoader);
        } catch (final Throwable t) {
            ReflectionDriver.rethrowIfVirtualMachineError(t);
            return null;
        }
    }
}
