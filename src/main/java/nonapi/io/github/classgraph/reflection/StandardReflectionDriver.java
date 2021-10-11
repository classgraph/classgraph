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

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;

/**
 * Standard reflection driver (uses {@link AccessibleObject#setAccessible(boolean)} to access non-public fields if
 * necessary).
 */
class StandardReflectionDriver extends ReflectionDriver {
    private static Method isAccessibleMethod;
    private static Method setAccessibleMethod;
    private static Method trySetAccessibleMethod;

    static {
        // Find deprecated methods isAccessible/setAccessible, to remove compile-time warnings
        // TODO Switch to using  MethodHandles once this is fixed:
        // https://github.com/mojohaus/animal-sniffer/issues/67
        try {
            isAccessibleMethod = AccessibleObject.class.getDeclaredMethod("isAccessible");
        } catch (final Throwable t) {
            // Ignore
        }
        try {
            setAccessibleMethod = AccessibleObject.class.getDeclaredMethod("setAccessible", boolean.class);
        } catch (final Throwable t) {
            // Ignore
        }
        try {
            trySetAccessibleMethod = AccessibleObject.class.getDeclaredMethod("trySetAccessible");
        } catch (final Throwable t) {
            // Ignore
        }
    }

    private static boolean isAccessible(final AccessibleObject obj) {
        if (isAccessibleMethod != null) {
            // JDK 7/8: use isAccessible (deprecated in JDK 9+)
            try {
                if ((Boolean) isAccessibleMethod.invoke(obj)) {
                    return true;
                }
            } catch (final Throwable e) {
                // Ignore
            }
        }
        return false;
    }

    private static boolean tryMakeAccessible(final AccessibleObject obj) {
        if (setAccessibleMethod != null) {
            try {
                setAccessibleMethod.invoke(obj, true);
                return true;
            } catch (final Throwable e) {
                // Ignore
            }
        }
        if (trySetAccessibleMethod != null) {
            try {
                if ((Boolean) trySetAccessibleMethod.invoke(obj)) {
                    return true;
                }
            } catch (final Throwable e) {
                // Ignore
            }
        }
        return false;
    }

    @Override
    public boolean makeAccessible(final AccessibleObject obj) {
        if (isAccessible(obj) || tryMakeAccessible(obj)) {
            return true;
        }
        try {
            return ReflectionUtils.doPrivileged(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    return tryMakeAccessible(obj);
                }
            });
        } catch (final Throwable t) {
            // Fall through
        }
        return false;
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