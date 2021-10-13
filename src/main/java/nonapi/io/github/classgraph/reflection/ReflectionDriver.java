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
    private static Method isAccessibleMethod;
    private static Method canAccessMethod;

    static {
        // TODO Switch to using  MethodHandles once this is fixed:
        // https://github.com/mojohaus/animal-sniffer/issues/67
        try {
            isAccessibleMethod = AccessibleObject.class.getDeclaredMethod("isAccessible");
        } catch (final Throwable t) {
            // Ignore
        }
        try {
            canAccessMethod = AccessibleObject.class.getDeclaredMethod("canAccess", Object.class);
        } catch (final Throwable t) {
            // Ignore
        }
    }

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
     * Check whether a field or method is accessible.
     * 
     * @param instance
     *            the object instance, or null if static.
     * @param fieldOrMethod
     *            the field or method.
     * 
     * @return true if accessible.
     */
    public boolean isAccessible(final Object instance, final AccessibleObject fieldOrMethod) {
        if (canAccessMethod != null) {
            // JDK 9+: use canAccess
            try {
                return (Boolean) canAccessMethod.invoke(fieldOrMethod, instance);
            } catch (final Throwable e) {
                // Ignore
            }
        }
        if (isAccessibleMethod != null) {
            // JDK 7/8: use isAccessible (deprecated in JDK 9+)
            try {
                return (Boolean) isAccessibleMethod.invoke(fieldOrMethod);
            } catch (final Throwable e) {
                // Ignore
            }
        }
        return false;
    }

    /**
     * Make a field or method accessible.
     * 
     * @param instance
     *            the object instance, or null if static.
     * @param fieldOrMethod
     *            the field or method.
     * 
     * @return true if successful.
     */
    public abstract boolean makeAccessible(final Object instance, final AccessibleObject fieldOrMethod);

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
    protected Method findIndexedDriverMethod(final String methodName, final Class<?>... paramTypes)
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
    protected void indexDriverMethods(final List<Method> methods) {
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
     * @param includeInterfaceDefaultMethods
     *            iterate through default methods in interfaces
     * @param methodIter
     *            The {@link MethodIter} to apply for each declared method
     */
    void forAllMethods(final Class<?> cls, final boolean includeInterfaceDefaultMethods,
            final ReflectionDriver.MethodIterator methodIter) throws Exception {
        // Iterate from class to its superclasses, and find initial interfaces to start traversing from
        final Set<Class<?>> visited = new HashSet<>();
        final LinkedList<Class<?>> interfaceQueue = includeInterfaceDefaultMethods ? new LinkedList<Class<?>>()
                : null;
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (final Method m : getDeclaredMethods(c)) {
                if (methodIter.foundMethod(m)) {
                    return;
                }
            }
            if (interfaceQueue != null) {
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
        }
        if (interfaceQueue != null) {
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
    }

    /**
     * Find a static method by name and parameter types in the given class.
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
    Method findStaticMethod(final Class<?> cls, final String methodName, final Class<?>... paramTypes)
            throws Exception {
        final AtomicReference<Boolean> methodFound = new AtomicReference<>(false);
        final AtomicReference<Method> accessibleMethod = new AtomicReference<>();
        // First try to find an accessible version of the method, without calling setAccessible
        // (this is needed for JPMS, since the implementing subclass of ModuleReference
        // is not accessible, but its superclass is)
        forAllMethods(cls, /* includeInterfaceDefaultMethods = */ false, new MethodIterator() {
            @Override
            public boolean foundMethod(final Method m) {
                if (m.getName().equals(methodName) && Arrays.equals(paramTypes, m.getParameterTypes())) {
                    methodFound.set(true);
                    if (isAccessible(null, m)) {
                        accessibleMethod.set(m);
                        // Stop iterating through methods
                        return true;
                    }
                }
                return false;
            }
        });
        if (accessibleMethod.get() != null) {
            return accessibleMethod.get();
        }
        if (methodFound.get()) {
            // Method was found, but was not accessible -- try making method accessible
            forAllMethods(cls, /* includeInterfaceDefaultMethods = */ false, new MethodIterator() {
                @Override
                public boolean foundMethod(final Method m) {
                    if (m.getName().equals(methodName) && Arrays.equals(paramTypes, m.getParameterTypes())) {
                        if (makeAccessible(null, m)) {
                            accessibleMethod.set(m);
                            // Stop iterating through methods
                            return true;
                        }
                    }
                    return false;
                }
            });
            if (accessibleMethod.get() != null) {
                return accessibleMethod.get();
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    /**
     * Find a method by name and parameter types in the class of the given object.
     *
     * @param obj
     *            the object
     * @param methodName
     *            the method name.
     * @param paramTypes
     *            the parameter types of the method.
     * @return the {@link Method}
     * @throws NoSuchMethodException
     *             if the class does not contain a method of the given name and parameter types
     */
    Method findInstanceMethod(final Object obj, final String methodName, final Class<?>... paramTypes)
            throws Exception {
        final AtomicReference<Boolean> methodFound = new AtomicReference<>(false);
        final AtomicReference<Method> accessibleMethod = new AtomicReference<>();
        // First try to find an accessible version of the method, without calling setAccessible
        // (this is needed for JPMS, since the implementing subclass of ModuleReference
        // is not accessible, but its superclass is)
        forAllMethods(obj.getClass(), /* includeInterfaceDefaultMethods = */ true, new MethodIterator() {
            @Override
            public boolean foundMethod(final Method m) {
                if (m.getName().equals(methodName) && Arrays.equals(paramTypes, m.getParameterTypes())) {
                    methodFound.set(true);
                    if (isAccessible(obj, m)) {
                        accessibleMethod.set(m);
                        // Stop iterating through methods
                        return true;
                    }
                }
                return false;
            }
        });
        if (accessibleMethod.get() != null) {
            return accessibleMethod.get();
        }
        if (methodFound.get()) {
            // Method was found, but was not accessible -- try making method accessible
            forAllMethods(obj.getClass(), /* includeInterfaceDefaultMethods = */ true, new MethodIterator() {
                @Override
                public boolean foundMethod(final Method m) {
                    if (m.getName().equals(methodName) && Arrays.equals(paramTypes, m.getParameterTypes())) {
                        if (makeAccessible(obj, m)) {
                            accessibleMethod.set(m);
                            // Stop iterating through methods
                            return true;
                        }
                    }
                    return false;
                }
            });
            if (accessibleMethod.get() != null) {
                return accessibleMethod.get();
            }
        }
        throw new NoSuchMethodException(methodName);
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
        forAllMethods(cls, /* includeInterfaceDefaultMethods = */ true, new MethodIterator() {
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
    Field findStaticField(final Class<?> cls, final String fieldName) throws Exception {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (final Field field : getDeclaredFields(c)) {
                if (field.getName().equals(fieldName)) {
                    makeAccessible(null, field);
                    return field;
                }
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    /**
     * Find a field by name in the class of the given object.
     *
     * @param obj
     *            the object
     * @param fieldName
     *            the field name.
     * @return the {@link Field}
     * @throws NoSuchFieldException
     *             if the class does not contain a field of the given name
     */
    Field findInstanceField(final Object obj, final String fieldName) throws Exception {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            for (final Field field : getDeclaredFields(c)) {
                if (field.getName().equals(fieldName)) {
                    makeAccessible(obj, field);
                    return field;
                }
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}