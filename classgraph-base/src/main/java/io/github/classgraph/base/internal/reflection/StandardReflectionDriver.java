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

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.jspecify.annotations.Nullable;

/**
 * Standard reflection driver (uses {@link AccessibleObject#setAccessible(boolean)} to access non-public fields if
 * necessary).
 */
class StandardReflectionDriver extends ReflectionDriver {
    /** Constructor. */
    StandardReflectionDriver() {
    }

    /**
     * Try to make a field, method or constructor accessible, without throwing an exception if this is not
     * permitted.
     *
     * @param obj
     *            the field, method or constructor
     * @return true if the object was made accessible
     */
    private static boolean tryMakeAccessible(final AccessibleObject obj) {
        try {
            return obj.trySetAccessible();
        } catch (final Throwable e) {
            // Ignore
        }
        try {
            obj.setAccessible(true);
            return true;
        } catch (final Throwable e) {
            // Ignore
        }
        return false;
    }

    @Override
    public boolean makeAccessible(final @Nullable Object instance, final AccessibleObject obj) {
        if (isAccessible(instance, obj)) {
            return true;
        }
        return tryMakeAccessible(obj);
    }

    @Override
    Class<?> findClass(final String className) throws Exception {
        return Class.forName(className);
    }

    @Override
    Method[] getDeclaredMethods(final Class<?> cls) throws Exception {
        return cls.getDeclaredMethods();
    }

    @Override
    Field[] getDeclaredFields(final Class<?> cls) throws Exception {
        return cls.getDeclaredFields();
    }

    @Override
    @Nullable
    Object getFieldImpl(final Object object, final Field field) throws Exception {
        makeAccessible(object, field);
        return field.get(object);
    }

    @Override
    void setFieldImpl(final Object object, final Field field, final @Nullable Object value) throws Exception {
        makeAccessible(object, field);
        field.set(object, value);
    }

    @Override
    @Nullable
    Object getStaticFieldImpl(final Field field) throws Exception {
        makeAccessible(null, field);
        return field.get(null);
    }

    @Override
    void setStaticFieldImpl(final Field field, final @Nullable Object value) throws Exception {
        makeAccessible(null, field);
        field.set(null, value);
    }

    @Override
    @Nullable
    Object invokeMethodImpl(final Object object, final Method method, final @Nullable Object... args)
            throws Exception {
        makeAccessible(object, method);
        return method.invoke(object, args);
    }

    @Override
    @Nullable
    Object invokeStaticMethodImpl(final Method method, final @Nullable Object... args) throws Exception {
        makeAccessible(null, method);
        return method.invoke(null, args);
    }
}
