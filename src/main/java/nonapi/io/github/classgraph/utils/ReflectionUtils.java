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

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.github.classgraph.ClassGraph;

/** Reflection utility methods that can be used by ClassLoaderHandlers. */
public final class ReflectionUtils {
    /** The reflection driver to use. */
    private static ReflectionDriver reflectionDriver;

    static {
        if (ClassGraph.CIRCUMVENT_ENCAPSULATION) {
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
    }

    /** Reflection driver */
    private static abstract class ReflectionDriver {
        /**
         * Find a class by name.
         *
         * @param className
         *            the class name
         * @return the class reference
         */
        abstract Class<?> findClass(final String className) throws Exception;

        /**
         * Get declared methods for class.
         *
         * @param cls
         *            the class
         * @return the declared methods
         */
        abstract Method[] getDeclaredMethods(Class<?> cls) throws Exception;

        /**
         * Get declared constructors for class.
         *
         * @param <T>
         *            the generic type
         * @param cls
         *            the class
         * @return the declared constructors
         */
        abstract <T> Constructor<T>[] getDeclaredConstructors(Class<T> cls) throws Exception;

        /**
         * Get declared fields for class.
         *
         * @param cls
         *            the class
         * @return the declared fields
         */
        abstract Field[] getDeclaredFields(Class<?> cls) throws Exception;

        /**
         * Get the value of a non-static field, boxing the value if necessary.
         *
         * @param object
         *            the object instance to get the field value from
         * @param field
         *            the non-static field
         * @return the value of the field
         */
        abstract Object getField(final Object object, final Field field) throws Exception;

        /**
         * Set the value of a non-static field, unboxing the value if necessary.
         *
         * @param object
         *            the object instance to get the field value from
         * @param field
         *            the non-static field
         * @param value
         *            the value to set
         */
        abstract void setField(final Object object, final Field field, Object value) throws Exception;

        /**
         * Get the value of a static field, boxing the value if necessary.
         *
         * @param field
         *            the static field
         * @return the static field
         */
        abstract Object getStaticField(final Field field) throws Exception;

        /**
         * Set the value of a static field, unboxing the value if necessary.
         *
         * @param field
         *            the static field
         * @param the
         *            value to set
         */
        abstract void setStaticField(final Field field, Object value) throws Exception;

        /**
         * Invoke a non-static method, boxing the result if necessary.
         *
         * @param object
         *            the object instance to invoke the method on
         * @param method
         *            the non-static method
         * @param args
         *            the method arguments (or {@code new Object[0]} if there are no args)
         * @return the return value (possibly a boxed value)
         */
        abstract Object invokeMethod(final Object object, final Method method, final Object... args)
                throws Exception;

        /**
         * Invoke a static method, boxing the result if necessary.
         *
         * @param method
         *            the static method
         * @param args
         *            the method arguments (or {@code new Object[0]} if there are no args)
         * @return the return value (possibly a boxed value)
         */
        abstract Object invokeStaticMethod(final Method method, final Object... args) throws Exception;

        /**
         * Make a field or method accessible.
         * 
         * @param accessibleObject
         *            the field or method.
         * @return true if successful.
         */
        abstract boolean makeAccessible(final AccessibleObject accessibleObject);

        /** Iterator applied to each method of a class and its superclasses/interfaces. */
        private static interface MethodIterator {
            /** @return true to stop iterating, or false to continue iterating */
            boolean foundMethod(Method m) throws Exception;
        }

        /**
         * Iterate through all methods in the given class. Also iterates up through superclasses and interfaces, to
         * collect all methods of the class and its superclasses, and any default methods defined in interfaces.
         *
         * @param cls
         *            the class
         */
        void forAllMethods(final Class<?> cls, final MethodIterator methodIter) throws Exception {
            // Iterate from class to its superclasses, and find initial interfaces to start traversing from
            final Set<Class<?>> visited = new HashSet<>();
            final LinkedList<Class<?>> interfaceQueue = new LinkedList<>();
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                for (final Method m : getDeclaredMethods(c)) {
                    if (methodIter.foundMethod(m)) {
                        return;
                    }
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
                for (final Method m : getDeclaredMethods(iface)) {
                    if (methodIter.foundMethod(m)) {
                        return;
                    }
                }
                for (final Class<?> superIface : iface.getInterfaces()) {
                    if (visited.add(superIface)) {
                        interfaceQueue.add(superIface);
                    }
                }
            }
        }

        /**
         * Find a method by name and parameter types in the given class.
         *
         * @param cls
         *            the class
         * @param methodName
         *            the method name.
         * @param paramTypes
         *            the parameter types of the method.
         * @return the {@link Method}
         * @throws NoSuchMethodException
         *             if the class does not contain a method of the given name and parameter types
         */
        Method findMethod(final Class<?> cls, final String methodName, final Class<?>... paramTypes)
                throws Exception {
            final AtomicReference<Method> method = new AtomicReference<>();
            forAllMethods(cls, new MethodIterator() {
                @Override
                public boolean foundMethod(final Method m) {
                    if (m.getName().equals(methodName) && Arrays.equals(paramTypes, m.getParameterTypes())
                    // If method is not accessible, fall through and try superclass method of same
                    // name and paramTypes
                            && makeAccessible(m)) {
                        method.set(m);
                        return true;
                    }
                    return false;
                }
            });
            final Method m = method.get();
            if (m != null) {
                return m;
            } else {
                throw new NoSuchMethodException(methodName);
            }
        }

        /**
         * Enumerate all methods in the given class, ignoring visibility and bypassing security checks. Also
         * iterates up through superclasses, to collect all methods of the class and its superclasses.
         *
         * @param cls
         *            the class
         * @return a list of {@link Method} objects representing all methods declared by the class or a superclass.
         */
        List<Method> enumerateMethods(final Class<?> cls) throws Exception {
            final List<Method> methodOrder = new ArrayList<>();
            forAllMethods(cls, new MethodIterator() {
                @Override
                public boolean foundMethod(final Method m) {
                    methodOrder.add(m);
                    return false;
                }
            });
            return methodOrder;
        }

        /**
         * Find a field by name in the given class.
         *
         * @param cls
         *            the class
         * @param fieldName
         *            the field name.
         * @return the {@link Field}
         * @throws NoSuchFieldException
         *             if the class does not contain a field of the given name
         */
        Field findField(final Class<?> cls, final String fieldName) throws Exception {
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                for (final Field field : getDeclaredFields(c)) {
                    if (field.getName().equals(fieldName)) {
                        return field;
                    }
                }
            }
            throw new NoSuchFieldException(fieldName);
        }
    }

    /**
     * Standard reflection driver (uses {@link AccessibleObject#setAccessible(boolean)} to access non-public fields
     * if necessary).
     */
    private static class StandardReflectionDriver extends ReflectionDriver {
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
        boolean makeAccessible(final AccessibleObject obj) {
            if (!obj.isAccessible()) {
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
                        obj.setAccessible(true);
                    } catch (final Throwable e2) {
                        return false;
                    }
                }
                return obj.isAccessible();
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

    /**
     * Narcissus reflection driver (uses the <a href="https://github.com/toolfactory/narcissus">Narcissus</a>
     * library, if it is available, which allows access to non-public fields and methods, circumventing
     * encapsulation and visibility controls via JNI).
     */
    private static class NarcissusReflectionDriver extends ReflectionDriver {
        private final Map<String, List<Method>> methodNameToMethods = new HashMap<>();
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

        private Method findIndexedMethod(final String methodName, final Class<?>... paramTypes)
                throws NoSuchMethodException {
            final List<Method> methods = methodNameToMethods.get(methodName);
            if (methods != null) {
                for (final Method method : methods) {
                    if (Arrays.equals(method.getParameterTypes(), paramTypes)) {
                        return method;
                    }
                }
            }
            throw new NoSuchMethodException(methodName);
        }

        private void indexMethods(final List<Method> methods) {
            // Index Narcissus methods by name
            for (final Method method : methods) {
                List<Method> methodsForName = methodNameToMethods.get(method.getName());
                if (methodsForName == null) {
                    methodNameToMethods.put(method.getName(), methodsForName = new ArrayList<>());
                }
                methodsForName.add(method);
            }
        }

        NarcissusReflectionDriver() throws Exception {
            // Load Narcissus class via reflection, so that there is no runtime dependency
            final StandardReflectionDriver drv = new StandardReflectionDriver();
            narcissusClass = drv.findClass("io.github.toolfactory.narcissus.Narcissus");
            if (!(Boolean) drv.getStaticField(drv.findField(narcissusClass, "libraryLoaded"))) {
                throw new IllegalArgumentException("Could not load Narcissus native library");
            }

            // Look up needed methods
            indexMethods(drv.enumerateMethods(narcissusClass));
            findClass = findIndexedMethod("findClass", String.class);
            getDeclaredMethods = findIndexedMethod("getDeclaredMethods", Class.class);
            getDeclaredConstructors = findIndexedMethod("getDeclaredConstructors", Class.class);
            getDeclaredFields = findIndexedMethod("getDeclaredFields", Class.class);
            getField = findIndexedMethod("getField", Object.class, Field.class);
            setField = findIndexedMethod("setField", Object.class, Field.class, Object.class);
            getStaticField = findIndexedMethod("getStaticField", Field.class);
            setStaticField = findIndexedMethod("setStaticField", Field.class, Object.class);
            invokeMethod = findIndexedMethod("invokeMethod", Object.class, Method.class, Object[].class);
            invokeStaticMethod = findIndexedMethod("invokeStaticMethod", Method.class, Object[].class);
        }

        @Override
        boolean makeAccessible(final AccessibleObject accessibleObject) {
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
        try {
            return reflectionDriver.getField(obj, reflectionDriver.findField(obj.getClass(), fieldName));
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException(
                        "Can't read field " + obj.getClass().getName() + "." + fieldName + ": " + e);
            }
        }
        return null;
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
        try {
            return reflectionDriver.getStaticField(reflectionDriver.findField(cls, fieldName));
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException(
                        "Can't read static field " + cls.getName() + "." + fieldName + ": " + e);
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
     * @return The result of the method invocation.
     * @throws IllegalArgumentException
     *             If the method could not be invoked.
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
            return reflectionDriver.invokeMethod(obj, reflectionDriver.findMethod(obj.getClass(), methodName),
                    new Object[0]);
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" could not be invoked: " + e);
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
            return reflectionDriver.invokeMethod(obj,
                    reflectionDriver.findMethod(obj.getClass(), methodName, argType), param);
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException("Method \"" + methodName + "\" could not be invoked: " + e);
            }
            return null;
        }
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
            return reflectionDriver.invokeStaticMethod(reflectionDriver.findMethod(cls, methodName));
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException(
                        "Static method \"" + methodName + "\" could not be invoked: " + e);
            }
            return null;
        }
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
            return reflectionDriver.invokeStaticMethod(reflectionDriver.findMethod(cls, methodName, argType),
                    param);
        } catch (final Throwable e) {
            if (throwException) {
                throw new IllegalArgumentException(
                        "Static method \"" + methodName + "\" could not be invoked: " + e);
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
    public static Class<?> classForNameOrNull(final String className) {
        try {
            return Class.forName(className);
        } catch (final ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

}
