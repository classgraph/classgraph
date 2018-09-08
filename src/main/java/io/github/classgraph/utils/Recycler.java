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

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Recycle instances of type T. The method T#close() is called when this class' own close() method is called. Use
 * RuntimeException for type E if the newInstance() method does not throw an exception.
 * 
 * Example usage:
 * 
 * <code>
 *       // Autoclose the Recycler when last instance has been released
 *       try (Recycler&lt;ZipFile, IOException&gt; recycler = new Recycler&lt;&gt;() {
 *               &#64;Override
 *               public ZipFile newInstance() throws IOException {
 *                   return new ZipFile(zipFilePath);
 *               }
 *           }) {
 *           // Repeat the following as many times as needed, on as many threads as needed 
 *           try {
 *               ZipFile zipFile = recycler.acquire();
 *               try {
 *                   // Read from zipFile -- don't put recycler.acquire() in this try block, otherwise the
 *                   // finally block will try to release the zipfile even when recycler.acquire() failed
 *                   // [...]
 *               } finally {
 *                   recycler.release(zipFile);
 *                   zipFile = null;
 *               }
 *           } catch (IOException e) {
 *               // May be thrown by recycler.acquire()
 *           }
 *       }
 * </code>
 * 
 * @param <T>
 *            The type to recycle.
 * @param <E>
 *            An exception type that can be thrown while acquiring an instance of the type to recycle.
 */
public abstract class Recycler<T extends Closeable, E extends Exception> implements AutoCloseable {
    /** Instances that have been allocated. */
    private final ConcurrentLinkedQueue<T> allocatedInstances = new ConcurrentLinkedQueue<>();

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
            if (recycledInstance != null) {
                // Use an unused instance
                instance = recycledInstance;
            } else {
                // Allocate a new instance
                final T newInstance = newInstance(); // May throw exception E
                if (newInstance == null) {
                    throw new RuntimeException("Failed to allocate a new recyclable instance");
                } else {
                    allocatedInstances.add(newInstance);
                    instance = newInstance;
                }
            }
        }

        /**
         * @return The new or recycled object instance.
         */
        public T get() {
            return instance;
        }

        /**
         * Release/recycle an instance.
         */
        @Override
        public void close() {
            if (instance != null) {
                unusedInstances.add(instance);
            }
        }
    }

    /**
     * Calls close() on all the unused instances. May be called multiple times, if {@link #acquire()} is called
     * again after {@link #close()}.
     */
    @Override
    public void close() {
        final Set<T> closedInstances = new HashSet<>();
        for (T unusedInstance; (unusedInstance = unusedInstances.poll()) != null;) {
            try {
                unusedInstance.close();
            } catch (final Throwable e) {
                // Ignore
            }
            closedInstances.add(unusedInstance);
        }
        final List<T> unclosedInstances = new ArrayList<>();
        for (T allocatedInstance; (allocatedInstance = allocatedInstances.poll()) != null;) {
            if (!closedInstances.contains(allocatedInstance)) {
                unclosedInstances.add(allocatedInstance);
            }
        }
        allocatedInstances.addAll(unclosedInstances);
    }
}
