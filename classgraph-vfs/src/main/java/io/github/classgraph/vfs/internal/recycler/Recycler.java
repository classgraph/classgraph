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
package io.github.classgraph.vfs.internal.recycler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe object pool for instances of type {@code T}.
 *
 * @param <T>
 *            the pooled instance type.
 * @param <E>
 *            the exception type that may be thrown when allocating an instance, or {@link RuntimeException} if
 *            none.
 */
public abstract class Recycler<T, E extends Exception> implements AutoCloseable {
    /** Currently acquired instances. */
    private final Set<T> usedInstances = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Pooled unused instances available for reuse. */
    private final Queue<T> unusedInstances = new ConcurrentLinkedQueue<>();

    /** True once {@link #forceClose()} has been called. */
    private volatile boolean forceClosed;

    /** Constructor. */
    public Recycler() {
    }

    /**
     * Allocate a new instance of type {@code T}.
     *
     * @return the new non-null instance.
     * @throws E
     *             if instantiation fails.
     */
    public abstract T newInstance() throws E;

    /**
     * Reset an instance before returning it to the pool. Called by {@link #recycle(Object)}; no-op by default.
     *
     * @param instance
     *            the instance to reset.
     */
    protected void resetInstance(final T instance) {
        // Nothing to reset by default
    }

    /**
     * Close a discarded instance. By default, calls {@link AutoCloseable#close()} if {@code instance} implements
     * {@link AutoCloseable}.
     *
     * @param instance
     *            the instance to close.
     * @throws Exception
     *             if closing the instance fails.
     */
    protected void closeInstance(final T instance) throws Exception {
        if (instance instanceof final AutoCloseable closeable) {
            closeable.close();
        }
    }

    /**
     * Acquire an instance of type {@code T}, reusing a pooled instance if available or allocating a new one via
     * {@link #newInstance()}.
     *
     * @return a new or recycled instance.
     * @throws E
     *             if {@link #newInstance()} throws {@code E}.
     * @throws NullPointerException
     *             if {@link #newInstance()} returns null.
     */
    public T acquire() throws E {
        final T instance;
        final var recycledInstance = unusedInstances.poll();
        if (recycledInstance == null) {
            // Allocate a new instance -- may throw an exception of type E
            final var newInstance = newInstance();
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
     * Return an acquired instance to the pool after resetting it with {@link #resetInstance(Object)}, or close it
     * if {@link #forceClose()} has been called.
     *
     * @param instance
     *            the instance to recycle.
     * @throws IllegalArgumentException
     *             if {@code instance} was not acquired from this {@link Recycler} or was already recycled.
     */
    public final void recycle(final T instance) {
        if (instance != null) {
            // (The usedInstances.remove() check is what catches an instance being recycled twice -- unusedInstances
            // is unbounded, so add() always returns true)
            final var wasInUse = usedInstances.remove(instance);
            if (forceClosed) {
                if (wasInUse) {
                    // The force-close did not see this instance, either because it was acquired afterwards, or
                    // because this call won the race to remove it, so nothing else is going to close it
                    closeQuietly(instance);
                }
                return;
            }
            if (!wasInUse) {
                throw new IllegalArgumentException("Tried to recycle an instance that was not in use");
            }
            resetInstance(instance);
            unusedInstances.add(instance);
            if (forceClosed) {
                // A force-close that began after the check above may already have drained the pool, and would then
                // never close this instance, so drain the pool again
                close();
            }
        }
    }

    /**
     * Close an instance with {@link #closeInstance(Object)}, ignoring any exception thrown.
     *
     * @param instance
     *            the instance to close.
     */
    private void closeQuietly(final T instance) {
        try {
            closeInstance(instance);
        } catch (final Exception _) {
            // Ignore
        }
    }

    /**
     * Close and discard all currently unused instances in the pool. New instances may still be acquired and
     * recycled after calling this method.
     */
    @Override
    public void close() {
        for (T unusedInstance; (unusedInstance = unusedInstances.poll()) != null;) {
            closeQuietly(unusedInstance);
        }
    }

    /**
     * Terminally close this {@link Recycler}, closing and discarding both unused and currently acquired instances.
     * Any instance passed to {@link #recycle(Object)} after this call is closed immediately rather than pooled.
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
