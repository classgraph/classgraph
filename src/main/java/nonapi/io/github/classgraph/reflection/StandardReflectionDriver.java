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
 * Copyright (c) 2021 Luke Hutchison
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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;

/**
 * Standard reflection driver (uses {@link AccessibleObject#setAccessible(boolean)} to access non-public fields if
 * necessary).
 */
class StandardReflectionDriver extends ReflectionDriver {
    private MethodHandle isAccessible;
    private MethodHandle setAccessible;

    {
        // Find deprecated methods, to remove compile-time warnings
        final MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            isAccessible = lookup.findVirtual(AccessibleObject.class, "isAccessible",
                    MethodType.methodType(boolean.class));
            setAccessible = lookup.findVirtual(AccessibleObject.class, "setAccessible",
                    MethodType.methodType(void.class, boolean.class));
        } catch (final Exception e) {
            // StandardReflectionDriver will stop working eventually, in some future version of Java
        }
    }

    @Override
    Class<?> findClass(final String className) throws Exception {
        return Class.forName(className);
    }

    @Override
    Method[] getDeclaredMethods(final Class<?> cls) throws Exception {
        return cls.getDeclaredMethods();
    }

    @SuppressWarnings("unchecked")
    @Override
    <T> Constructor<T>[] getDeclaredConstructors(final Class<T> cls) throws Exception {
        return (Constructor<T>[]) cls.getDeclaredConstructors();
    }

    @Override
    Field[] getDeclaredFields(final Class<?> cls) throws Exception {
        return cls.getDeclaredFields();
    }

    private boolean isAccessible(final Object obj) {
        try {
            return (Boolean) isAccessible.invoke();
        } catch (final Throwable t) {
            return false;
        }
    }

    private void setAccessible(final Object obj, final boolean flag) throws Throwable {
        setAccessible.invoke(obj, flag);
    }

    @Override
    boolean makeAccessible(final AccessibleObject obj) {
        if (!isAccessible(obj)) {
            try {
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        obj.setAccessible(true);
                        return null;
                    }
                });
            } catch (final Throwable e) {
                try {
                    setAccessible(obj, true);
                } catch (final Throwable e2) {
                    return false;
                }
            }
            return isAccessible(obj);
        }
        return true;
    }

    @Override
    Object getField(final Object object, final Field field) throws Exception {
        makeAccessible(field);
        return field.get(object);
    }

    @Override
    void setField(final Object object, final Field field, final Object value) throws Exception {
        makeAccessible(field);
        field.set(object, value);
    }

    @Override
    Object getStaticField(final Field field) throws Exception {
        makeAccessible(field);
        return field.get(null);
    }

    @Override
    void setStaticField(final Field field, final Object value) throws Exception {
        makeAccessible(field);
        field.set(null, value);
    }

    @Override
    Object invokeMethod(final Object object, final Method method, final Object... args) throws Exception {
        makeAccessible(method);
        return method.invoke(object, args);
    }

    @Override
    Object invokeStaticMethod(final Method method, final Object... args) throws Exception {
        makeAccessible(method);
        return method.invoke(null, args);
    }
}