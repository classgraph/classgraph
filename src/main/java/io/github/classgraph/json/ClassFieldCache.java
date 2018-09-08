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
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.classgraph.json;

import java.lang.reflect.Constructor;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractQueue;
import java.util.AbstractSequentialList;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TransferQueue;

class ClassFieldCache {
    private final Map<Class<?>, ClassFields> classToClassFields = new HashMap<>();
    private final boolean resolveTypes;
    private final boolean onlySerializePublicFields;

    private final Map<Class<?>, Constructor<?>> defaultConstructorForConcreteType = new HashMap<>();
    private final Map<Class<?>, Constructor<?>> constructorForConcreteTypeWithSizeHint = new HashMap<>();

    /** Placeholder constructor to signify no constructor was found previously. */
    private static final Constructor<?> NO_CONSTRUCTOR;

    static {
        try {
            NO_CONSTRUCTOR = NoConstructor.class.getDeclaredConstructor();
        } catch (NoSuchMethodException | SecurityException e) {
            throw new RuntimeException(e);
        }
    }

    /** Placeholder class to signify no constructor was found previously. */
    private static class NoConstructor {
        @SuppressWarnings("unused")
        public NoConstructor() {
        }
    }

    /**
     * Create a class field cache.
     * 
     * @param forDeserialization
     *            Set this to true if the cache will be used for deserialization (or both serialization and
     *            deserialization), or false if just used for serialization (for speed).
     * @param onlySerializePublicFields
     *            Set this to true if you only want to serialize public fields (ignored for deserialization).
     */
    ClassFieldCache(final boolean forDeserialization, final boolean onlySerializePublicFields) {
        this.resolveTypes = forDeserialization;
        this.onlySerializePublicFields = !forDeserialization && onlySerializePublicFields;
    }

    /**
     * For a given resolved type, find the visible and accessible fields, resolve the types of any generically typed
     * fields, and return the resolved fields.
     */
    ClassFields get(final Class<?> cls) {
        ClassFields classFields = classToClassFields.get(cls);
        if (classFields == null) {
            classToClassFields.put(cls,
                    classFields = new ClassFields(cls, resolveTypes, onlySerializePublicFields, this));
        }
        return classFields;
    }

    /** Get the concrete type for a map or collection whose raw type is an interface or abstract class. */
    private static Class<?> getConcreteType(final Class<?> rawType, final boolean returnNullIfNotMapOrCollection) {
        if (rawType == Map.class || rawType == AbstractMap.class) {
            return HashMap.class;
        } else if (rawType == ConcurrentMap.class) {
            return ConcurrentHashMap.class;
        } else if (rawType == SortedMap.class || rawType == NavigableMap.class) {
            return TreeMap.class;
        } else if (rawType == ConcurrentNavigableMap.class) {
            return ConcurrentSkipListMap.class;
        } else if (rawType == List.class || rawType == AbstractList.class) {
            return ArrayList.class;
        } else if (rawType == AbstractSequentialList.class) {
            return LinkedList.class;
        } else if (rawType == Set.class || rawType == AbstractSet.class) {
            return HashSet.class;
        } else if (rawType == SortedSet.class) {
            return TreeSet.class;
        } else if (rawType == Queue.class || rawType == AbstractQueue.class || rawType == Deque.class) {
            return ArrayDeque.class;
        } else if (rawType == BlockingQueue.class) {
            return LinkedBlockingQueue.class;
        } else if (rawType == BlockingDeque.class) {
            return LinkedBlockingDeque.class;
        } else if (rawType == TransferQueue.class) {
            return LinkedTransferQueue.class;
        } else {
            return rawType;
        }
    }

    /**
     * Get the concrete type of the given class, then return the default constructor for that type.
     * 
     * @throws IllegalArgumentException
     *             if no default constructor is both found and accessible.
     */
    Constructor<?> getDefaultConstructorForConcreteTypeOf(final Class<?> cls) {
        // Check cache
        final Constructor<?> constructor = defaultConstructorForConcreteType.get(cls);
        if (constructor != null) {
            return constructor;
        }
        final Class<?> concreteType = getConcreteType(cls, /* returnNullIfNotMapOrCollection = */ false);
        for (Class<?> c = concreteType; c != null
                && (c != Object.class || cls == Object.class); c = c.getSuperclass()) {
            try {
                final Constructor<?> defaultConstructor = c.getDeclaredConstructor();
                JSONUtils.isAccessibleOrMakeAccessible(defaultConstructor);
                // Store found constructor in cache
                defaultConstructorForConcreteType.put(cls, defaultConstructor);
                return defaultConstructor;
            } catch (final Exception e) {
            }
        }
        throw new IllegalArgumentException(
                "Class " + cls.getName() + " does not have an accessible default (no-arg) constructor");
    }

    /**
     * Get the concrete type of the given class, then return the constructor for that type that takes a single
     * integer parameter (the initial size hint, for Collection or Map). Returns null if not a Collection or Map, or
     * there is no constructor with size hint.
     */
    Constructor<?> getConstructorWithSizeHintForConcreteTypeOf(final Class<?> cls) {
        // Check cache
        final Constructor<?> constructor = constructorForConcreteTypeWithSizeHint.get(cls);
        if (constructor == NO_CONSTRUCTOR) {
            return null;
        } else if (constructor != null) {
            return constructor;
        }
        final Class<?> concreteType = getConcreteType(cls, /* returnNullIfNotMapOrCollection = */ true);
        if (concreteType != null) {
            for (Class<?> c = concreteType; c != null
                    && (c != Object.class || cls == Object.class); c = c.getSuperclass()) {
                try {
                    final Constructor<?> constructorWithSizeHint = c.getDeclaredConstructor(Integer.TYPE);
                    JSONUtils.isAccessibleOrMakeAccessible(constructorWithSizeHint);
                    // Store found constructor in cache
                    constructorForConcreteTypeWithSizeHint.put(cls, constructorWithSizeHint);
                    return constructorWithSizeHint;
                } catch (final Exception e) {
                }
            }
        }
        constructorForConcreteTypeWithSizeHint.put(cls, NO_CONSTRUCTOR);
        return null;
    }

}