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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Reflection driver */
public abstract class ReflectionDriver {
    private final Map<String, List<Method>> methodNameToMethods = new HashMap<>();

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
    abstract Object invokeMethod(final Object object, final Method method, final Object... args) throws Exception;

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
    public abstract boolean makeAccessible(final AccessibleObject accessibleObject);

    /** Iterator applied to each method of a class and its superclasses/interfaces. */
    private static interface MethodIterator {
        /** @return true to stop iterating, or false to continue iterating */
        boolean foundMethod(Method m) throws Exception;
    }

    /**
     * Find an indexed method.
     * 
     * @param methodName
     *            The method name.
     * @param paramTypes
     *            The parameter types.
     * @return The method, if found.
     * @throws NoSuchMethodException
     *             If not found.
     */
    protected Method findDriverMethod(final String methodName, final Class<?>... paramTypes)
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

    /**
     * Index a list of methods.
     * 
     * @param methods
     *            The methods to index.
     */
    protected void indexMethods(final List<Method> methods) {
        // Index Narcissus methods by name
        for (final Method method : methods) {
            List<Method> methodsForName = methodNameToMethods.get(method.getName());
            if (methodsForName == null) {
                methodNameToMethods.put(method.getName(), methodsForName = new ArrayList<>());
            }
            methodsForName.add(method);
        }
    }

    /**
     * Iterate through all methods in the given class. Also iterates up through superclasses and interfaces, to
     * collect all methods of the class and its superclasses, and any default methods defined in interfaces.
     *
     * @param cls
     *            the class
     */
    void forAllMethods(final Class<?> cls, final ReflectionDriver.MethodIterator methodIter) throws Exception {
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
    Method findMethod(final Class<?> cls, final String methodName, final Class<?>... paramTypes) throws Exception {
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
     * Enumerate all methods in the given class, ignoring visibility and bypassing security checks. Also iterates up
     * through superclasses, to collect all methods of the class and its superclasses.
     *
     * @param cls
     *            the class
     * @return a list of {@link Method} objects representing all methods declared by the class or a superclass.
     */
    List<Method> enumerateDriverMethods(final Class<?> cls) throws Exception {
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