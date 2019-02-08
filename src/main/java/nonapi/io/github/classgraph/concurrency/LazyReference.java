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
package nonapi.io.github.classgraph.concurrency;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Assign a reference atomically, like {@link AtomicReference}, except that initialization of the reference value is
 * separated from assignment of the reference, and the reference value is generated lazily by calling
 * {@link #newInstance()} on the first call to {@link #get()}. All threads but the first getter will block on the
 * completion of execution of {@link #newInstance()} by the first getter.
 *
 * @param <V>
 *            the reference type
 * @param <E>
 *            an exception type that may be thrown by newInstance(), or {@link RuntimeException} if none.
 */
public abstract class LazyReference<V, E extends Exception> {
    /** The reference. */
    private V reference;

    /** A single lease that is obtained by the first getter. */
    private final Semaphore firstGetter = new Semaphore(1);

    /** Whether or not the singleton has been initialized (the count will have reached 0 if so). */
    private final CountDownLatch initialized = new CountDownLatch(1);

    /**
     * Get the singleton value.
     *
     * @return the singleton value.
     * @throws E
     *             if {@link #newInstance()} throws E.
     * @throws InterruptedException
     *             if the thread was interrupted while waiting for the value to be set.
     * @throws NullPointerException
     *             if {@link #newInstance()} returns null.
     */
    public V get() throws E, InterruptedException {
        if (firstGetter.tryAcquire()) {
            // This is the first thread that has called get()
            try {
                // Initialize the reference by calling newInstance()
                reference = newInstance();
            } finally {
                // Release all getter threads
                initialized.countDown();
            }
        }
        // Wait for initialization to complete
        initialized.await();
        if (reference == null) {
            throw new NullPointerException("newInstance() returned null");
        }
        return reference;
    }

    /**
     * Create a new instance of the reference type.
     *
     * @return the new instance (must not be null).
     * @throws E
     *             the e
     */
    public abstract V newInstance() throws E;
}