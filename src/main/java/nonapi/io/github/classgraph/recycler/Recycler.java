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

    /** True once {@link #forceClose()} has been called. */
    private volatile boolean forceClosed;

    /** Constructor. */
    public Recycler() {
    }

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
        return new RecycleOnClose<>(this, acquire());
    }

    /**
     * Recycle an instance for reuse by {@link #acquire()}, calling {@link Resettable#reset()} if it implements
     * {@link Resettable}, or closing it if {@link #forceClose()} has been called.
     *
     * @param instance
     *            the instance to recycle.
     * @throws IllegalArgumentException
     *             if the object instance was not obtained from this {@link Recycler}, or was already recycled.
     */
    public final void recycle(final T instance) {
        if (instance != null) {
            // (The usedInstances.remove() check is what catches an instance being recycled twice -- unusedInstances
            // is unbounded, so add() always returns true)
            final boolean wasInUse = usedInstances.remove(instance);
            if (forceClosed) {
                if (wasInUse) {
                    // The force-close did not see this instance, either because it was acquired afterwards, or
                    // because this call won the race to remove it, so nothing else is going to close it
                    closeInstance(instance);
                }
                return;
            }
            if (!wasInUse) {
                throw new IllegalArgumentException("Tried to recycle an instance that was not in use");
            }
            if (instance instanceof Resettable) {
                ((Resettable) instance).reset();
            }
            unusedInstances.add(instance);
            if (forceClosed) {
                // A force-close that began after the check above may already have drained the pool, and would then
                // never close this instance, so drain the pool again
                close();
            }
        }
    }

    /**
     * Close an instance, if it is {@link AutoCloseable}, ignoring any exception thrown while closing it.
     *
     * @param instance
     *            the instance to close.
     */
    private void closeInstance(final T instance) {
        if (instance instanceof AutoCloseable) {
            try {
                ((AutoCloseable) instance).close();
            } catch (final Exception e) {
                // Ignore
            }
        }
    }

    /**
     * Free all unused instances, calling {@link AutoCloseable#close()} on any that implement
     * {@link AutoCloseable}.
     */
    @Override
    public void close() {
        for (T unusedInstance; (unusedInstance = unusedInstances.poll()) != null;) {
            closeInstance(unusedInstance);
        }
    }

    /**
     * Force-close this {@link Recycler}, moving all active instances into the unused list and closing all
     * {@link AutoCloseable} instances.
     */
    public void forceClose() {
        // Set this before draining, so that anything racing to recycle an instance closes it rather than adding it
        // back to a pool that this method has already passed over
        forceClosed = true;
        // Move all elements from usedInstances to unusedInstances in a threadsafe way
        for (final T usedInstance : new ArrayList<>(usedInstances)) {
            if (usedInstances.remove(usedInstance)) {
                unusedInstances.add(usedInstance);
            }
        }
        close();
    }
}
