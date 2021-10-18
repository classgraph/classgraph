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

/**
 * Narcissus reflection driver (uses the <a href="https://github.com/toolfactory/narcissus">Narcissus</a> library,
 * if it is available, which allows access to non-public fields and methods, circumventing encapsulation and
 * visibility controls via JNI).
 */
class NarcissusReflectionDriver extends ReflectionDriver {
    private final Class<?> narcissusClass;
    private final Method getDeclaredMethods;
    private final Method findClass;
    private final Method getDeclaredConstructors;
    private final Method getDeclaredFields;
    private final Method getField;
    private final Method setField;
    private final Method getStaticField;
    private final Method setStaticField;
    private final Method invokeMethod;
    private final Method invokeStaticMethod;

    NarcissusReflectionDriver() throws Exception {
        // Load Narcissus class via reflection, so that there is no runtime dependency
        final StandardReflectionDriver drv = new StandardReflectionDriver();
        narcissusClass = drv.findClass("io.github.toolfactory.narcissus.Narcissus");
        if (!(Boolean) drv.getStaticField(drv.findStaticField(narcissusClass, "libraryLoaded"))) {
            throw new IllegalArgumentException("Could not load Narcissus native library");
        }

        // Look up needed methods
        findClass = drv.findStaticMethod(narcissusClass, "findClass", String.class);
        getDeclaredMethods = drv.findStaticMethod(narcissusClass, "getDeclaredMethods", Class.class);
        getDeclaredConstructors = drv.findStaticMethod(narcissusClass, "getDeclaredConstructors", Class.class);
        getDeclaredFields = drv.findStaticMethod(narcissusClass, "getDeclaredFields", Class.class);
        getField = drv.findStaticMethod(narcissusClass, "getField", Object.class, Field.class);
        setField = drv.findStaticMethod(narcissusClass, "setField", Object.class, Field.class, Object.class);
        getStaticField = drv.findStaticMethod(narcissusClass, "getStaticField", Field.class);
        setStaticField = drv.findStaticMethod(narcissusClass, "setStaticField", Field.class, Object.class);
        invokeMethod = drv.findStaticMethod(narcissusClass, "invokeMethod", Object.class, Method.class,
                Object[].class);
        invokeStaticMethod = drv.findStaticMethod(narcissusClass, "invokeStaticMethod", Method.class,
                Object[].class);
    }

    @Override
    public boolean isAccessible(final Object instance, final AccessibleObject obj) {
        return true;
    }

    @Override
    public boolean makeAccessible(final Object instance, final AccessibleObject accessibleObject) {
        return true;
    }

    @Override
    Class<?> findClass(final String className) throws Exception {
        return (Class<?>) findClass.invoke(null, className);
    }

    @Override
    Method[] getDeclaredMethods(final Class<?> cls) throws Exception {
        return (Method[]) getDeclaredMethods.invoke(null, cls);
    }

    @SuppressWarnings("unchecked")
    @Override
    <T> Constructor<T>[] getDeclaredConstructors(final Class<T> cls) throws Exception {
        return (Constructor<T>[]) getDeclaredConstructors.invoke(null, cls);
    }

    @Override
    Field[] getDeclaredFields(final Class<?> cls) throws Exception {
        return (Field[]) getDeclaredFields.invoke(null, cls);
    }

    @Override
    Object getField(final Object object, final Field field) throws Exception {
        return getField.invoke(null, object, field);
    }

    @Override
    void setField(final Object object, final Field field, final Object value) throws Exception {
        setField.invoke(null, object, field, value);
    }

    @Override
    Object getStaticField(final Field field) throws Exception {
        return getStaticField.invoke(null, field);
    }

    @Override
    void setStaticField(final Field field, final Object value) throws Exception {
        setStaticField.invoke(null, field, value);
    }

    @Override
    Object invokeMethod(final Object object, final Method method, final Object... args) throws Exception {
        return invokeMethod.invoke(null, object, method, args);
    }

    @Override
    Object invokeStaticMethod(final Method method, final Object... args) throws Exception {
        return invokeStaticMethod.invoke(null, method, args);
    }
}