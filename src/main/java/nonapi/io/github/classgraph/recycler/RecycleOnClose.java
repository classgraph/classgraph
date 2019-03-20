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

/**
 * An AutoCloseable wrapper for a recyclable object instance. Obtained by calling
 * {@link Recycler#acquireRecycleOnClose()} in a try-with-resources statement, so that when the try block exits, the
 * acquired instance is recycled.
 *
 * @param <T>
 *            the type to recycle
 * @param <E>
 *            the exception type that may be thrown when a recyclable item is acquired.
 */
public class RecycleOnClose<T, E extends Exception> implements AutoCloseable {
    /** The recycler. */
    private final Recycler<T, E> recycler;

    /** The instance. */
    private final T instance;

    /**
     * Acquire or allocate an instance.
     *
     * @param recycler
     *            The {@link Recycler}.
     * @param instance
     *            An object instance that was obtained by calling {@link Recycler#acquire()} on the recycler.
     * @throws IllegalArgumentException
     *             If {@link Recycler#newInstance()} returned null.
     */
    RecycleOnClose(final Recycler<T, E> recycler, final T instance) {
        this.recycler = recycler;
        this.instance = instance;
    }

    /**
     * Get the object instance.
     *
     * @return The object instance.
     */
    public T get() {
        return instance;
    }

    /** Recycle an instance. Calls {@link Resettable#reset()} if the instance implements {@link Resettable}. */
    @Override
    public void close() {
        recycler.recycle(instance);
    }
}