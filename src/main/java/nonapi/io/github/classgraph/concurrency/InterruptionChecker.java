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
package nonapi.io.github.classgraph.concurrency;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Check if this thread or any other thread that shares this InterruptionChecker instance has been interrupted or
 * has thrown an exception.
 */
public class InterruptionChecker {
    /** Set to true when a thread is interrupted. */
    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    /** The first {@link Throwable} that resulted in an {@link ExecutionException}. */
    private final AtomicReference<Throwable> executionExceptionCause = new AtomicReference<Throwable>();

    /** Interrupt all threads that share this InterruptionChecker. */
    public void interrupt() {
        interrupted.set(true);
        Thread.currentThread().interrupt();
    }

    /**
     * Set the {@link Throwable} that resulted in an {@link ExecutionException}.
     * 
     * @param throwable
     *            the {@link Throwable}, or the {@link ExecutionException} that wraps it.
     */
    public void setExecutionExceptionCause(final Throwable throwable) {
        if (throwable != null && //
        // Only set the execution exception once
                executionExceptionCause.get() == null && //
                // Don't store CancellationException or InterruptedException
                !(throwable instanceof CancellationException) && !(throwable instanceof InterruptedException)) {
            // Get the root cause, if throwable is an ExecutionException
            Throwable t = throwable;
            while (t instanceof ExecutionException) {
                t = t.getCause();
            }
            if (t != null) {
                // Only set the execution exception once
                executionExceptionCause.compareAndSet(/* expectedValue = */ null, t);
            }
        }
    }

    /**
     * Get the {@link Throwable} that resulted in an {@link ExecutionException}.
     * 
     * @return the {@link Throwable} that resulted in an {@link ExecutionException}.
     */
    public Throwable getExecutionExceptionCause() {
        return executionExceptionCause.get();
    }

    /**
     * Check for interruption and return interruption status.
     *
     * @return true if this thread or any other thread that shares this InterruptionChecker instance has been
     *         interrupted or has thrown an exception.
     */
    public boolean checkAndReturn() {
        if (interrupted.get()) {
            return true;
        }
        if (Thread.currentThread().isInterrupted()) {
            interrupted.set(true);
            return true;
        }
        return false;
    }

    /**
     * Check if this thread or any other thread that shares this InterruptionChecker instance has been interrupted
     * or has thrown an exception, and if so, throw InterruptedException.
     * 
     * @throws InterruptedException
     *             If this thread or any other thread that shares this InterruptionChecker instance has been
     *             interrupted.
     */
    public void check() throws InterruptedException {
        if (checkAndReturn()) {
            throw new InterruptedException();
        }
    }
}
