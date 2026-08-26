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
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * A driver that performs the reflective operations ClassGraph needs, using whichever reflection mechanism is
 * available and least restricted in the current runtime.
 */
abstract class ReflectionDriver {
    /**
     * Caches the methods and fields of each class that has been reflected on. No driver's
     * {@link #getDeclaredMethods(Class)} or {@link #getDeclaredFields(Class)} reflects on a class through this
     * cache, so building an entry can never recursively update the map.
     */
    private final Map<Class<?>, ClassMemberCache> classToClassMemberCache = new ConcurrentHashMap<>();

    /** Constructor. */
    ReflectionDriver() {
    }

    /** Caches class members. */
    public final class ClassMemberCache {
        /** The methods of the class, its superclasses and its interfaces, indexed by method name. */
        private final Map<String, List<Method>> methodNameToMethods = new HashMap<>();

        /** The fields of the class, its superclasses and its interfaces, indexed by field name. */
        private final Map<String, Field> fieldNameToField = new HashMap<>();

        /**
         * Constructor.
         *
         * @param cls
         *            the class to cache the members of
         */
        private ClassMemberCache(final Class<?> cls) {
            // Iterate from class to its superclasses, and find initial interfaces to start traversing from
            final Set<Class<?>> visited = new HashSet<>();
            final LinkedList<Class<?>> interfaceQueue = new LinkedList<>();
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                // The starting class can itself be an interface. Don't cache its members here -- it is queued
                // below like any other interface, and its members are cached when it is dequeued.
                if (c.isInterface()) {
                    if (visited.add(c)) {
                        interfaceQueue.add(c);
                    }
                } else {
                    cacheMembers(c);
                }
                // Find interfaces and superinterfaces implemented by this class or its superclasses
                for (final Class<?> iface : c.getInterfaces()) {
                    if (visited.add(iface)) {
                        interfaceQueue.add(iface);
                    }
                }
            }
            // Traverse through interfaces, looking for default methods and constants
            while (!interfaceQueue.isEmpty()) {
                final Class<?> iface = interfaceQueue.remove();
                cacheMembers(iface);
                for (final Class<?> superIface : iface.getInterfaces()) {
                    if (visited.add(superIface)) {
                        interfaceQueue.add(superIface);
                    }
                }
            }
        }

        /**
         * Cache the declared methods and fields of a class or interface. Methods and fields are read separately, so
         * that if one of the two cannot be read, the other is still cached.
         *
         * @param cls
         *            the class or interface to cache the declared members of
         */
        private void cacheMembers(final Class<?> cls) {
            try {
                for (final Method m : getDeclaredMethods(cls)) {
                    cacheMethod(m);
                }
            } catch (final Exception e) {
                // Skip
            }
            try {
                for (final Field f : getDeclaredFields(cls)) {
                    cacheField(f);
                }
            } catch (final Exception e) {
                // Skip
            }
        }

        /**
         * Add a method to the cache. Methods are not masked by name, since methods can be overloaded.
         *
         * @param method
         *            the method to cache
         */
        private void cacheMethod(final Method method) {
            methodNameToMethods.computeIfAbsent(method.getName(), name -> new ArrayList<>()).add(method);
        }

        /**
         * Add a field to the cache.
         *
         * @param field
         *            the field to cache
         */
        private void cacheField(final Field field) {
            // Only put a field name to field mapping if it is absent, so that subclasses mask fields of the same
            // name in superclasses, and classes mask constants of the same name in the interfaces they implement
            fieldNameToField.putIfAbsent(field.getName(), field);
        }
    }

    /**
     * Get the cached methods and fields of a class, building the cache entry if this is the first time the class
     * has been reflected on.
     *
     * @param cls
     *            the class
     * @return the class' cached members
     */
    private ClassMemberCache classMemberCache(final Class<?> cls) {
        return classToClassMemberCache.computeIfAbsent(cls, ClassMemberCache::new);
    }

    /**
     * Find a class by name.
     *
     * @param className
     *            the class name
     * @return the class reference
     * @throws Exception
     *             if the class could not be found or loaded
     */
    abstract Class<?> findClass(final String className) throws Exception;

    /**
     * Get declared methods for class.
     *
     * @param cls
     *            the class
     * @return the declared methods
     * @throws Exception
     *             if the methods could not be read
     */
    abstract Method[] getDeclaredMethods(Class<?> cls) throws Exception;

    /**
     * Get declared fields for class.
     *
     * @param cls
     *            the class
     * @return the declared fields
     * @throws Exception
     *             if the fields could not be read
     */
    abstract Field[] getDeclaredFields(Class<?> cls) throws Exception;

    /**
     * Check that a field or method is not static, so that it can be passed to an operation that needs an object
     * instance to act on.
     *
     * @param member
     *            the field or method
     * @param alternative
     *            the name of the operation the caller should have used instead
     * @throws IllegalArgumentException
     *             if the member is static
     */
    private static void checkNotStatic(final Member member, final String alternative)
            throws IllegalArgumentException {
        if (Modifier.isStatic(member.getModifiers())) {
            throw new IllegalArgumentException(member + " is static -- call " + alternative + " instead");
        }
    }

    /**
     * Check that a field or method is static, so that it can be passed to an operation that acts on a class rather
     * than on an object instance.
     *
     * @param member
     *            the field or method
     * @param alternative
     *            the name of the operation the caller should have used instead
     * @throws IllegalArgumentException
     *             if the member is not static
     */
    private static void checkStatic(final Member member, final String alternative) throws IllegalArgumentException {
        if (!Modifier.isStatic(member.getModifiers())) {
            throw new IllegalArgumentException(member + " is not static -- call " + alternative + " instead");
        }
    }

    /**
     * Get the value of a non-static field, boxing the value if necessary.
     *
     * <p>
     * The six member-access operations are final, and check that the field or method they are passed is of the kind
     * they act on before handing it to the driver, so that every driver rejects a mismatched member in the same
     * way. Without the check the drivers disagree: JNI rejects a static member passed to a non-static operation,
     * whereas {@link Method#invoke(Object, Object...)} silently ignores the receiver.
     *
     * @param object
     *            the object instance to get the field value from
     * @param field
     *            the non-static field
     * @return the value of the field
     * @throws IllegalArgumentException
     *             if the field is static
     * @throws Exception
     *             if the field could not be read
     */
    final @Nullable Object getField(final Object object, final Field field) throws Exception {
        checkNotStatic(field, "getStaticField()");
        return getFieldImpl(object, field);
    }

    /**
     * Get the value of a non-static field that has already been checked not to be static.
     *
     * @param object
     *            the object instance to get the field value from
     * @param field
     *            the non-static field
     * @return the value of the field
     * @throws Exception
     *             if the field could not be read
     */
    abstract @Nullable Object getFieldImpl(final Object object, final Field field) throws Exception;

    /**
     * Set the value of a non-static field, unboxing the value if necessary.
     *
     * @param object
     *            the object instance to get the field value from
     * @param field
     *            the non-static field
     * @param value
     *            the value to set
     * @throws Exception
     *             if the field could not be written
     */
    final void setField(final Object object, final Field field, final @Nullable Object value) throws Exception {
        checkNotStatic(field, "setStaticField()");
        setFieldImpl(object, field, value);
    }

    /**
     * Set the value of a non-static field that has already been checked not to be static.
     *
     * @param object
     *            the object instance to set the field value on
     * @param field
     *            the non-static field
     * @param value
     *            the value to set
     * @throws Exception
     *             if the field could not be written
     */
    abstract void setFieldImpl(final Object object, final Field field, @Nullable Object value) throws Exception;

    /**
     * Get the value of a static field, boxing the value if necessary.
     *
     * @param field
     *            the static field
     * @return the static field
     * @throws Exception
     *             if the field could not be read
     */
    final @Nullable Object getStaticField(final Field field) throws Exception {
        checkStatic(field, "getField()");
        return getStaticFieldImpl(field);
    }

    /**
     * Get the value of a static field that has already been checked to be static.
     *
     * @param field
     *            the static field
     * @return the value of the field
     * @throws Exception
     *             if the field could not be read
     */
    abstract @Nullable Object getStaticFieldImpl(final Field field) throws Exception;

    /**
     * Set the value of a static field, unboxing the value if necessary.
     *
     * @param field
     *            the static field
     * @param value
     *            the value to set
     * @throws Exception
     *             if the field could not be written
     */
    final void setStaticField(final Field field, final @Nullable Object value) throws Exception {
        checkStatic(field, "setField()");
        setStaticFieldImpl(field, value);
    }

    /**
     * Set the value of a static field that has already been checked to be static.
     *
     * @param field
     *            the static field
     * @param value
     *            the value to set
     * @throws Exception
     *             if the field could not be written
     */
    abstract void setStaticFieldImpl(final Field field, @Nullable Object value) throws Exception;

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
     * @throws Exception
     *             if the method could not be invoked, or if it threw
     */
    final @Nullable Object invokeMethod(final Object object, final Method method, final @Nullable Object... args)
            throws Exception {
        checkNotStatic(method, "invokeStaticMethod()");
        return invokeMethodImpl(object, method, args);
    }

    /**
     * Invoke a non-static method that has already been checked not to be static.
     *
     * @param object
     *            the object instance to invoke the method on
     * @param method
     *            the non-static method
     * @param args
     *            the method arguments (or {@code new Object[0]} if there are no args)
     * @return the return value (possibly a boxed value)
     * @throws Exception
     *             if the method could not be invoked, or if it threw
     */
    abstract @Nullable Object invokeMethodImpl(final Object object, final Method method,
            final @Nullable Object... args) throws Exception;

    /**
     * Invoke a static method, boxing the result if necessary.
     *
     * @param method
     *            the static method
     * @param args
     *            the method arguments (or {@code new Object[0]} if there are no args)
     * @return the return value (possibly a boxed value)
     * @throws Exception
     *             if the method could not be invoked, or if it threw
     */
    final @Nullable Object invokeStaticMethod(final Method method, final @Nullable Object... args)
            throws Exception {
        checkStatic(method, "invokeMethod()");
        return invokeStaticMethodImpl(method, args);
    }

    /**
     * Invoke a static method that has already been checked to be static.
     *
     * @param method
     *            the static method
     * @param args
     *            the method arguments (or {@code new Object[0]} if there are no args)
     * @return the return value (possibly a boxed value)
     * @throws Exception
     *             if the method could not be invoked, or if it threw
     */
    abstract @Nullable Object invokeStaticMethodImpl(final Method method, final @Nullable Object... args)
            throws Exception;

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
    abstract boolean makeAccessible(final @Nullable Object instance, final AccessibleObject fieldOrMethod);

    /**
     * Get the instance to check accessibility against for a given field or method: the object instance for a
     * non-static member, or null for a static member, since {@link AccessibleObject#canAccess(Object)} requires
     * null for a static member and throws {@link IllegalArgumentException} if it is passed an object instance.
     *
     * @param member
     *            the field or method.
     * @param obj
     *            the object instance, or null.
     * @return the instance to check accessibility against.
     */
    private static @Nullable Object accessInstance(final Member member, final @Nullable Object obj) {
        return Modifier.isStatic(member.getModifiers()) ? null : obj;
    }

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
    boolean isAccessible(final @Nullable Object instance, final AccessibleObject fieldOrMethod) {
        try {
            return fieldOrMethod.canAccess(instance);
        } catch (final Throwable e) {
            // canAccess throws IllegalArgumentException if the instance does not match the member
            return false;
        }
    }

    /**
     * Get the field of the class that has a given field name, whether static or not.
     *
     * @param cls
     *            the class.
     * @param obj
     *            the object instance, or null if there is none. This is only used to check accessibility, and is
     *            ignored if the field turns out to be static.
     * @param fieldName
     *            The name of the field.
     * @return The {@link Field} object for the requested field name (never null).
     * @throws Exception
     *             if the field could not be found, or could not be made accessible
     */
    protected Field findField(final Class<?> cls, final @Nullable Object obj, final String fieldName)
            throws Exception {
        final var field = classMemberCache(cls).fieldNameToField.get(fieldName);
        if (field != null) {
            final var accessInstance = accessInstance(field, obj);
            // If field was found but is not accessible, try making it accessible and then returning it (may result
            // in a reflective access warning on stderr)
            if (isAccessible(accessInstance, field) || makeAccessible(accessInstance, field)) {
                return field;
            }
            throw new NoSuchFieldException("Could not make field accessible: " + cls.getName() + "." + fieldName);
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
     * @return The {@link Field} object for the requested field name (never null).
     * @throws Exception
     *             if the field could not be found, could not be made accessible, or is not static
     */
    protected Field findStaticField(final Class<?> cls, final String fieldName) throws Exception {
        final var field = findField(cls, null, fieldName);
        if (!Modifier.isStatic(field.getModifiers())) {
            throw new NoSuchFieldException("Field " + cls.getName() + "." + fieldName + " is not static");
        }
        return field;
    }

    /**
     * Get a method by name and parameter types, whether static or not.
     *
     * @param cls
     *            the class.
     * @param obj
     *            the object instance, or null if there is none. This is only used to check accessibility, and is
     *            ignored if the method turns out to be static.
     * @param methodName
     *            The name of the method.
     * @param paramTypes
     *            The types of the parameters of the method. For primitive-typed parameters, use e.g. Integer.TYPE.
     * @return The {@link Method} object for the matching method (never null).
     * @throws Exception
     *             if the method could not be found, or could not be made accessible.
     */
    protected Method findMethod(final Class<?> cls, final @Nullable Object obj, final String methodName,
            final Class<?>... paramTypes) throws Exception {
        final var methodsForName = classMemberCache(cls).methodNameToMethods.get(methodName);
        if (methodsForName != null) {
            // Return the first method that matches the signature that is already accessible
            var found = false;
            for (final Method method : methodsForName) {
                if (Arrays.equals(method.getParameterTypes(), paramTypes)) {
                    found = true;
                    if (isAccessible(accessInstance(method, obj), method)) {
                        return method;
                    }
                }
            }
            // If method was found but is not accessible, try making it accessible and then returning it (may result
            // in a reflective access warning on stderr)
            if (found) {
                for (final Method method : methodsForName) {
                    if (Arrays.equals(method.getParameterTypes(), paramTypes)
                            && makeAccessible(accessInstance(method, obj), method)) {
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
     * @return The {@link Method} object for the matching method (never null).
     * @throws Exception
     *             if the method could not be found, could not be made accessible, or is not static.
     */
    protected Method findStaticMethod(final Class<?> cls, final String methodName, final Class<?>... paramTypes)
            throws Exception {
        final var method = findMethod(cls, null, methodName, paramTypes);
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException("Method " + cls.getName() + "." + methodName + " is not static");
        }
        return method;
    }
}
