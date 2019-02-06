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

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Check if this thread or any other thread that shares this InterruptionChecker instance has been interrupted or
 * has thrown an exception.
 */
public class InterruptionChecker {

    /** The minimum interval between calls to {@link Thread#isInterrupted()} to check for interruption. */
    private static final long MIN_CHECK_INTERVAL_NANOS = (long) (0.1 * 1.0e9);

    /** The timestamp of the last check for interruption. */
    private long lastCheckTimeNanos = 0L;

    /** Set to true when a thread is interrupted. */
    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    /** Non-null if an execution exception was encountered. */
    private ExecutionException executionException;

    /** Interrupt all threads that share this InterruptionChecker. */
    public void interrupt() {
        interrupted.set(true);
    }

    /**
     * Check for interruption and return interruption status.
     *
     * @return true if this thread or any other thread that shares this InterruptionChecker instance has been
     *         interrupted or has thrown an exception.
     */
    public boolean checkAndReturn() {
        final long time = System.nanoTime();
        if (time - lastCheckTimeNanos > MIN_CHECK_INTERVAL_NANOS) {
            lastCheckTimeNanos = time;
            if (Thread.currentThread().isInterrupted()) {
                interrupt();
            }
            return interrupted.get() || executionException != null;
        } else {
            return false;
        }
    }

    /**
     * Check if this thread or any other thread that shares this InterruptionChecker instance has been interrupted
     * or has thrown an exception, and if so, throw InterruptedException.
     * 
     * @throws InterruptedException
     *             If this thread or any other thread that shares this InterruptionChecker instance has been
     *             interrupted.
     * @throws ExecutionException
     *             If this thread or any other thread that shares this InterruptionChecker instance has thrown an
     *             exception.
     */
    public void check() throws InterruptedException, ExecutionException {
        if (checkAndReturn()) {
            if (executionException != null) {
                throw executionException;
            } else {
                throw new InterruptedException();
            }
        }
    }

    /**
     * Stop all threads that share this InterruptionChecker due to an exception being thrown in one of them.
     * 
     * @param e
     *            The exception that was thrown.
     * @return A new {@link ExecutionException}.
     */
    public ExecutionException executionException(final Exception e) {
        executionException = e instanceof ExecutionException ? (ExecutionException) e : new ExecutionException(e);
        return executionException;
    }
}
