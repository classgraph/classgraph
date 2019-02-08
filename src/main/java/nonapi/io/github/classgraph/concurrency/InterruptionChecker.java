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

    /** The first {@link ExecutionException} that was thrown. */
    private final AtomicReference<ExecutionException> thrownExecutionException = //
            new AtomicReference<ExecutionException>();

    /** Interrupt all threads that share this InterruptionChecker. */
    public void interrupt() {
        interrupted.set(true);
        Thread.currentThread().interrupt();
    }

    /**
     * Set the {@link ExecutionException} that was thrown by a worker.
     *
     * @param executionException
     *            the execution exception that was thrown
     */
    public void setExecutionException(final ExecutionException executionException) {
        // Only set the execution exception once
        if (executionException != null && thrownExecutionException.get() == null) {
            thrownExecutionException.compareAndSet(/* expectedValue = */ null, executionException);
        }
    }

    /**
     * Get the {@link ExecutionException} that was thrown by a worker, or null if none.
     * 
     * @return the {@link ExecutionException} that was thrown by a worker, or null if none.
     */
    public ExecutionException getExecutionException() {
        return thrownExecutionException.get();
    }

    /**
     * Get the cause of an {@link ExecutionException}.
     *
     * @param throwable
     *            the Throwable
     * @return the cause
     */
    public static Throwable getCause(final Throwable throwable) {
        // Unwrap possibly-nested ExecutionExceptions to get to root cause
        Throwable cause = throwable;
        while (cause instanceof ExecutionException) {
            cause = cause.getCause();
        }
        return cause != null ? cause : new ExecutionException("ExecutionException with unknown cause", null);
    }

    /**
     * Check for interruption and return interruption status.
     *
     * @return true if this thread or any other thread that shares this InterruptionChecker instance has been
     *         interrupted or has thrown an exception.
     */
    public boolean checkAndReturn() {
        // Check if any thread has been interrupted
        if (interrupted.get()) {
            // If so, interrupt this thread
            interrupt();
            return true;
        }
        // Check if this thread has been interrupted
        if (Thread.currentThread().isInterrupted()) {
            // If so, interrupt other threads
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
     *             If a thread has been interrupted.
     * @throws ExecutionException
     *             if a thread has thrown an uncaught exception.
     */
    public void check() throws InterruptedException, ExecutionException {
        // If a thread threw an uncaught exception, re-throw it.
        final ExecutionException executionException = getExecutionException();
        if (executionException != null) {
            throw executionException;
        }
        // If this thread or another thread has been interrupted, throw InterruptedException
        if (checkAndReturn()) {
            throw new InterruptedException();
        }
    }
}
