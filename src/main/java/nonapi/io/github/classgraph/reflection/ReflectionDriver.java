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

import nonapi.io.github.classgraph.concurrency.SingletonMap;
import nonapi.io.github.classgraph.utils.LogNode;

/** Reflection driver */
abstract class ReflectionDriver {
    private final SingletonMap<Class<?>, ClassMemberCache, Exception> classToClassMemberCache //
            = new SingletonMap<Class<?>, ClassMemberCache, Exception>() {
                @Override
                public ClassMemberCache newInstance(final Class<?> cls, final LogNode log)
                        throws Exception, InterruptedException {
                    return new ClassMemberCache(cls);
                }
            };

    private static Method isAccessibleMethod;
    private static Method canAccessMethod;

    static {
        // Find deprecated methods to remove compile-time warnings
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

    /** Caches class members. */
    public class ClassMemberCache {
        private final Map<String, List<Method>> methodNameToMethods = new HashMap<>();
        private final Map<String, Field> fieldNameToField = new HashMap<>();

        private ClassMemberCache(final Class<?> cls) throws Exception {
            // Iterate from class to its superclasses, and find initial interfaces to start traversing from
            final Set<Class<?>> visited = new HashSet<>();
            final LinkedList<Class<?>> interfaceQueue = new LinkedList<Class<?>>();
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                try {
                    // Cache any declared methods and fields
                    for (final Method m : getDeclaredMethods(c)) {
                        cacheMethod(m);
                    }
                    for (final Field f : getDeclaredFields(c)) {
                        cacheField(f);
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
                } catch (final Exception e) {
                    // Skip
                }
            }
            // Traverse through interfaces looking for default methods
            while (!interfaceQueue.isEmpty()) {
                final Class<?> iface = interfaceQueue.remove();
                try {
                    for (final Method m : getDeclaredMethods(iface)) {
                        cacheMethod(m);
                    }
                } catch (final Exception e) {
                    // Skip
                }
                for (final Class<?> superIface : iface.getInterfaces()) {
                    if (visited.add(superIface)) {
                        interfaceQueue.add(superIface);
                    }
                }
            }
        }

        private void cacheMethod(final Method method) {
            List<Method> methodsForName = methodNameToMethods.get(method.getName());
            if (methodsForName == null) {
                methodNameToMethods.put(method.getName(), methodsForName = new ArrayList<>());
            }
            methodsForName.add(method);
        }

        private void cacheField(final Field field) {
            // Only put a field name to field mapping if it is absent, so that subclasses mask fields 
            // of the same name in superclasses
            if (!fieldNameToField.containsKey(field.getName())) {
                fieldNameToField.put(field.getName(), field);
            }
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
     * @param value
     *            the value to set
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
     * @param instance
     *            the object instance, or null if static.
     * @param fieldOrMethod
     *            the field or method.
     * 
     * @return true if successful.
     */
    abstract boolean makeAccessible(final Object instance, final AccessibleObject fieldOrMethod);

    /**
     * Check whether a field or method is accessible.
     * 
     * <p>
     * N.B. this is overridden in Narcissus driver to just return true, since everything is accessible to JNI.
     * 
     * @param instance
     *            the object instance, or null if static.
     * @param fieldOrMethod
     *            the field or method.
     * 
     * @return true if accessible.
     */
    boolean isAccessible(final Object instance, final AccessibleObject fieldOrMethod) {
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
     * Get the field of the class that has a given field name.
     * 
     * @param cls
     *            the class.
     * @param obj
     *            the object instance, or null for a static field.
     * @param fieldName
     *            The name of the field.
     * @return The {@link Field} object for the requested field name, or null if no such field was found in the
     *         class.
     * @throws Exception
     *             if the field could not be found
     */
    protected Field findField(final Class<?> cls, final Object obj, final String fieldName) throws Exception {
        final Field field = classToClassMemberCache.get(cls, /* log = */ null).fieldNameToField.get(fieldName);
        if (field != null) {
            if (!isAccessible(obj, field)) {
                // If field was found but is not accessible, try making it accessible and then returning it
                // (may result in a reflective access warning on stderr)
                makeAccessible(obj, field);
            }
            return field;
        }
        throw new NoSuchFieldException("Could not find field " + cls.getName() + "." + fieldName);
    }

    /**
     * Get the static field of the class that has a given field name.
     * 
     * @param cls
     *            the class.
     * @param fieldName
     *            The name of the field.
     * @return The {@link Field} object for the requested field name, or null if no such field was found in the
     *         class.
     * @throws Exception
     *             if the field could not be found
     */
    protected Field findStaticField(final Class<?> cls, final String fieldName) throws Exception {
        return findField(cls, null, fieldName);
    }

    /**
     * Get the non-static field of the class that has a given field name.
     * 
     * @param obj
     *            the object instance, or null for a static field.
     * @param fieldName
     *            The name of the field.
     * @return The {@link Field} object for the requested field name, or null if no such field was found in the
     *         class.
     * @throws Exception
     *             if the field could not be found
     */
    protected Field findInstanceField(final Object obj, final String fieldName) throws Exception {
        if (obj == null) {
            throw new IllegalArgumentException("obj cannot be null");
        }
        return findField(obj.getClass(), obj, fieldName);
    }

    /**
     * Get a method by name and parameter types.
     * 
     * @param cls
     *            the class.
     * @param obj
     *            the object instance, or null for a static method.
     * @param methodName
     *            The name of the method.
     * @param paramTypes
     *            The types of the parameters of the method. For primitive-typed parameters, use e.g. Integer.TYPE.
     * @return The {@link Method} object for the matching method, or null if no such method was found in the class.
     * @throws Exception
     *             if the method could not be found.
     */
    protected Method findMethod(final Class<?> cls, final Object obj, final String methodName,
            final Class<?>... paramTypes) throws Exception {
        final List<Method> methodsForName = classToClassMemberCache.get(cls, null).methodNameToMethods
                .get(methodName);
        if (methodsForName != null) {
            // Return the first method that matches the signature that is already accessible
            boolean found = false;
            for (final Method method : methodsForName) {
                if (Arrays.equals(method.getParameterTypes(), paramTypes)) {
                    found = true;
                    if (isAccessible(obj, method)) {
                        return method;
                    }
                }
            }
            // If method was found but is not accessible, try making it accessible and then returning it
            // (may result in a reflective access warning on stderr)
            if (found) {
                for (final Method method : methodsForName) {
                    if (Arrays.equals(method.getParameterTypes(), paramTypes) && makeAccessible(obj, method)) {
                        return method;
                    }
                }
            }
            throw new NoSuchMethodException(
                    "Could not make method accessible: " + cls.getName() + "." + methodName);
        }
        throw new NoSuchMethodException("Could not find method " + cls.getName() + "." + methodName);
    }

    /**
     * Get a static method by name and parameter types.
     * 
     * @param cls
     *            the class.
     * @param methodName
     *            The name of the method.
     * @param paramTypes
     *            The types of the parameters of the method. For primitive-typed parameters, use e.g. Integer.TYPE.
     * @return The {@link Method} object for the matching method, or null if no such method was found in the class.
     * @throws Exception
     *             if the method could not be found.
     */
    protected Method findStaticMethod(final Class<?> cls, final String methodName, final Class<?>... paramTypes)
            throws Exception {
        return findMethod(cls, null, methodName, paramTypes);
    }

    /**
     * Get a non-static method by name and parameter types.
     * 
     * @param obj
     *            the object instance, or null for a static method.
     * @param methodName
     *            The name of the method.
     * @param paramTypes
     *            The types of the parameters of the method. For primitive-typed parameters, use e.g. Integer.TYPE.
     * @return The {@link Method} object for the matching method, or null if no such method was found in the class.
     * @throws Exception
     *             if the method could not be found.
     */
    protected Method findInstanceMethod(final Object obj, final String methodName, final Class<?>... paramTypes)
            throws Exception {
        if (obj == null) {
            throw new IllegalArgumentException("obj cannot be null");
        }
        return findMethod(obj.getClass(), obj, methodName, paramTypes);
    }
}
