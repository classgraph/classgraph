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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Callable;

/**
 * Standard reflection driver (uses {@link AccessibleObject#setAccessible(boolean)} to access non-public fields if
 * necessary).
 */
class StandardReflectionDriver extends ReflectionDriver {
    private static Method setAccessibleMethod;
    private static Method trySetAccessibleMethod;
    private static Class<?> accessControllerClass;
    private static Class<?> privilegedActionClass;
    private static Method accessControllerDoPrivileged;

    static {
        // Find deprecated methods to remove compile-time warnings
        // TODO Switch to using  MethodHandles once this is fixed:
        // https://github.com/mojohaus/animal-sniffer/issues/67
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
        try {
            accessControllerClass = Class.forName("java.security.AccessController");
            privilegedActionClass = Class.forName("java.security.PrivilegedAction");
            accessControllerDoPrivileged = accessControllerClass.getMethod("doPrivileged", privilegedActionClass);
        } catch (final Throwable t) {
            // Ignore
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    private class PrivilegedActionInvocationHandler<T> implements InvocationHandler {
        private final Callable<T> callable;

        public PrivilegedActionInvocationHandler(final Callable<T> callable) {
            this.callable = callable;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
            return callable.call();
        }
    }

    /**
     * Call a method in the AccessController.doPrivileged(PrivilegedAction) context, using reflection, if possible
     * (AccessController is deprecated in JDK 17).
     */
    @SuppressWarnings("unchecked")
    private <T> T doPrivileged(final Callable<T> callable) throws Throwable {
        if (accessControllerDoPrivileged != null) {
            final Object privilegedAction = Proxy.newProxyInstance(privilegedActionClass.getClassLoader(),
                    new Class<?>[] { privilegedActionClass }, new PrivilegedActionInvocationHandler<T>(callable));
            return (T) accessControllerDoPrivileged.invoke(null, privilegedAction);
        } else {
            // Fall back to invoking in a non-privileged context
            return callable.call();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    private static boolean tryMakeAccessible(final AccessibleObject obj) {
        if (trySetAccessibleMethod != null) {
            // JDK 9+
            try {
                return (Boolean) trySetAccessibleMethod.invoke(obj);
            } catch (final Throwable e) {
                // Ignore
            }
        }
        if (setAccessibleMethod != null) {
            // JDK 7/8
            try {
                setAccessibleMethod.invoke(obj, true);
                return true;
            } catch (final Throwable e) {
                // Ignore
            }
        }
        return false;
    }

    @Override
    public boolean makeAccessible(final Object instance, final AccessibleObject obj) {
        if (isAccessible(instance, obj)) {
            return true;
        }
        try {
            return doPrivileged(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    return tryMakeAccessible(obj);
                }
            });
        } catch (final Throwable t) {
            // Fall through
            return tryMakeAccessible(obj);
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

    @Override
    Object getField(final Object object, final Field field) throws Exception {
        makeAccessible(object, field);
        return field.get(object);
    }

    @Override
    void setField(final Object object, final Field field, final Object value) throws Exception {
        makeAccessible(object, field);
        field.set(object, value);
    }

    @Override
    Object getStaticField(final Field field) throws Exception {
        makeAccessible(null, field);
        return field.get(null);
    }

    @Override
    void setStaticField(final Field field, final Object value) throws Exception {
        makeAccessible(null, field);
        field.set(null, value);
    }

    @Override
    Object invokeMethod(final Object object, final Method method, final Object... args) throws Exception {
        makeAccessible(object, method);
        return method.invoke(object, args);
    }

    @Override
    Object invokeStaticMethod(final Method method, final Object... args) throws Exception {
        makeAccessible(null, method);
        return method.invoke(null, args);
    }
}