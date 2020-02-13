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
package nonapi.io.github.classgraph.recycler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Recycler for instances of type T, where instantiating this type may throw checked exception E.
 * 
 * @param <T>
 *            The type to recycle.
 * @param <E>
 *            An exception that can be thrown while acquiring an instance of the type to recycle, or
 *            {@link RuntimeException} if none.
 */
public abstract class Recycler<T, E extends Exception> implements AutoCloseable {
    /** Instances that have been allocated. */
    private final Set<T> usedInstances = Collections.newSetFromMap(new ConcurrentHashMap<T, Boolean>());

    /** Instances that have been allocated but are unused. */
    private final Queue<T> unusedInstances = new ConcurrentLinkedQueue<>();

    /**
     * Create a new instance. This should either return a non-null instance of type T, or throw an exception of type
     * E.
     * 
     * @return The new instance.
     * @throws E
     *             If an exception of type E was thrown during instantiation.
     */
    public abstract T newInstance() throws E;

    /**
     * Acquire on object instance of type T, either by reusing a previously recycled instance if possible, or if
     * there are no currently-unused instances, by allocating a new instance.
     * 
     * @return Either a new or a recycled object instance.
     * @throws E
     *             if {@link #newInstance()} threw an exception of type E.
     * @throws NullPointerException
     *             if {@link #newInstance()} returned null.
     */
    public T acquire() throws E {
        final T instance;
        final T recycledInstance = unusedInstances.poll();
        if (recycledInstance == null) {
            // Allocate a new instance -- may throw an exception of type E
            final T newInstance = newInstance();
            if (newInstance == null) {
                throw new NullPointerException("Failed to allocate a new recyclable instance");
            }
            instance = newInstance;
        } else {
            // Reuse an unused instance
            instance = recycledInstance;
        }
        usedInstances.add(instance);
        return instance;
    }

    /**
     * Acquire a Recyclable wrapper around an object instance, which can be used to recycle object instances at the
     * end of a try-with-resources block.
     * 
     * @return Either a new or a recycled object instance.
     * @throws E
     *             If anything goes wrong when trying to allocate a new object instance.
     */
    public RecycleOnClose<T, E> acquireRecycleOnClose() throws E {
        return new RecycleOnClose<T, E>(this, acquire());
    }

    /**
     * Recycle an object for reuse by a subsequent call to {@link #acquire()}. If the object is an instance of
     * {@link Resettable}, then {@link Resettable#reset()} will be called on the instance before recycling it.
     *
     * @param instance
     *            the instance to recycle.
     * @throws IllegalArgumentException
     *             if the object instance was not originally obtained from this {@link Recycler}.
     */
    public final void recycle(final T instance) {
        if (instance != null) {
            if (!usedInstances.remove(instance)) {
                throw new IllegalArgumentException("Tried to recycle an instance that was not in use");
            }
            if (instance instanceof Resettable) {
                ((Resettable) instance).reset();
            }
            if (!unusedInstances.add(instance)) {
                throw new IllegalArgumentException("Tried to recycle an instance twice");
            }
        }
    }

    /**
     * Free all unused instances. Calls {@link AutoCloseable#close()} on any unused instances that implement
     * {@link AutoCloseable}.
     * 
     * <p>
     * The {@link Recycler} may continue to be used to acquire new instances after calling this close method, and
     * then this close method may be called again in future, i.e. the effect of calling this method is to simply
     * clear out the recycler of unused instances, closing any {@link AutoCloseable} instances.
     */
    @Override
    public void close() {
        for (T unusedInstance; (unusedInstance = unusedInstances.poll()) != null;) {
            if (unusedInstance instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) unusedInstance).close();
                } catch (final Exception e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Force-close this {@link Recycler}, by forcibly moving any instances that have been acquired but not yet
     * recycled into the unused instances list, then calling {@link #close()} to close any {@link AutoCloseable}
     * instances and discard all instances.
     */
    public void forceClose() {
        // Move all elements from usedInstances to unusedInstances in a threadsafe way
        for (final T usedInstance : new ArrayList<>(usedInstances)) {
            if (usedInstances.remove(usedInstance)) {
                unusedInstances.add(usedInstance);
            }
        }
        close();
    }
}
