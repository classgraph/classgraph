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
package io.github.classgraph.utils;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Recycler for instances of type T.
 * 
 * @param <T>
 *            The type to recycle.
 * @param <E>
 *            An exception type that can be thrown while acquiring an instance of the type to recycle.
 */
public abstract class Recycler<T, E extends Exception> implements AutoCloseable {
    /** Instances that have been allocated. */
    private final Set<T> usedInstances = Collections.newSetFromMap(new ConcurrentHashMap<T, Boolean>());

    /** Instances that have been allocated but are unused. */
    private final ConcurrentLinkedQueue<T> unusedInstances = new ConcurrentLinkedQueue<>();

    /**
     * Create a new instance.
     * 
     * @return The new instance.
     * @throws E
     *             If an exception of type E was thrown during instantiation.
     */
    public abstract T newInstance() throws E;

    /**
     * Acquire a Recyclable wrapper around an object instance. Use in try-with-resources.
     * 
     * @return Either a new or a recycled object instance.
     * @throws E
     *             If anything goes wrong when trying to allocate a new object instance.
     */
    public Recyclable acquire() throws E {
        return new Recyclable();
    }

    /** An AutoCloseable wrapper for a recyclable object instance. Use in try-with-resources. */
    public class Recyclable implements AutoCloseable {
        private final T instance;

        /**
         * Acquire or allocate an instance.
         * 
         * @throws E
         *             If an exception of type E was thrown during instantiation.
         */
        public Recyclable() throws E {
            final T recycledInstance = unusedInstances.poll();
            if (recycledInstance == null) {
                // Allocate a new instance
                final T newInstance = newInstance(); // May throw exception E
                if (newInstance == null) {
                    throw new RuntimeException("Failed to allocate a new recyclable instance");
                }
                instance = newInstance;
            } else {
                // Reuse an unused instance
                instance = recycledInstance;
            }
            usedInstances.add(instance);
        }

        /**
         * @return The new or recycled object instance.
         */
        public T get() {
            return instance;
        }

        /** Recycle an instance. Calls {@link Resettable#reset()} if the instance implements {@link Resettable}. */
        @Override
        public void close() {
            if (instance != null) {
                if (instance instanceof Resettable) {
                    try {
                        ((Resettable) instance).reset();
                    } catch (final Throwable e) {
                        // Ignore
                    }
                }
                usedInstances.remove(instance);
                unusedInstances.add(instance);
            }
        }
    }

    /**
     * An interface for recycleable objects that need to be reset when {@link Recycler.Recyclable#close()} is called
     * to recycle the object.
     */
    public static interface Resettable {
        /** Reset a recycleable object (called when the object is recycled). */
        public void reset();
    }

    /**
     * Free all unused instances. Calls {@link AutoCloseable#close()} on any unused instances that implement
     * {@link AutoCloseable}.
     * 
     * <p>
     * The {@link Recycler} may continue to be used to acquire new instances after calling close(), and close() may
     * be called as often as needed.
     */
    @Override
    public void close() {
        for (T unusedInstance; (unusedInstance = unusedInstances.poll()) != null;) {
            if (unusedInstance instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) unusedInstance).close();
                } catch (final Throwable e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Force-close this {@link Recycler}, by moving all used instances into the unused instances list, then calling
     * {@link #close()}.
     */
    public void forceClose() {
        unusedInstances.addAll(usedInstances);
        usedInstances.clear();
        close();
    }
}
