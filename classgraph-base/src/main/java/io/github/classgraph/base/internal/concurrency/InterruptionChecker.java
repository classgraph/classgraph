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
package io.github.classgraph.base.internal.concurrency;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

/**
 * Tracks interruption status, uncaught worker exceptions, and progress across a group of worker threads.
 */
public class InterruptionChecker {
    /** Constructor. */
    public InterruptionChecker() {
    }

    /** Set to true when a thread is interrupted. */
    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    /** The first {@link ExecutionException} that was thrown. */
    private final AtomicReference<@Nullable ExecutionException> thrownExecutionException = //
            new AtomicReference<>();

    /** The number of times any thread has reported progress. */
    private final AtomicLong progressCount = new AtomicLong();

    /**
     * Record that a worker thread has made progress, resetting any active {@link IdleTimeout}.
     */
    public void progress() {
        progressCount.incrementAndGet();
    }

    /**
     * Get the number of times {@link #progress()} has been called.
     *
     * @return the number of times {@link #progress()} has been called.
     */
    long getProgressCount() {
        return progressCount.get();
    }

    /**
     * Interrupt the current thread and mark all threads sharing this {@link InterruptionChecker} as interrupted.
     */
    public void interrupt() {
        interrupted.set(true);
        Thread.currentThread().interrupt();
    }

    /**
     * Record an {@link ExecutionException} thrown by a worker thread. If the root cause is an
     * {@link InterruptedException}, records an interruption instead.
     *
     * @param executionException
     *            the execution exception that was thrown
     */
    public void setExecutionException(final @Nullable ExecutionException executionException) {
        if (executionException == null) {
            return;
        }
        if (getCause(executionException) instanceof InterruptedException) {
            // Recording an interruption as an exception would make check() report the work as having failed rather
            // than as having been cancelled, since a recorded exception is thrown ahead of the interruption check.
            // It would also mask a genuine failure on another thread, since only the first exception is recorded
            interrupt();
            return;
        }
        // Only set the execution exception once
        if (thrownExecutionException.get() == null) {
            thrownExecutionException.compareAndSet(/* expectedValue = */ null, executionException);
        }
    }

    /**
     * Return the first {@link ExecutionException} thrown by a worker thread, or {@code null} if none.
     *
     * @return the {@link ExecutionException} thrown by a worker thread, or {@code null} if none.
     */
    public @Nullable ExecutionException getExecutionException() {
        return thrownExecutionException.get();
    }

    /**
     * Unwrap nested {@link ExecutionException} instances and return the root cause.
     *
     * @param throwable
     *            the Throwable
     * @return the root cause
     */
    public static Throwable getCause(final Throwable throwable) {
        // Unwrap possibly-nested ExecutionExceptions to get to root cause
        var cause = throwable;
        while (cause instanceof ExecutionException) {
            cause = cause.getCause();
        }
        return cause != null ? cause : new ExecutionException("ExecutionException with unknown cause", null);
    }

    /**
     * Check whether the current thread or any other thread sharing this {@link InterruptionChecker} has been
     * interrupted.
     *
     * @return {@code true} if any thread sharing this instance has been interrupted.
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
     * Throw {@link ExecutionException} if a worker thread has thrown an uncaught exception, or
     * {@link InterruptedException} if any thread sharing this instance has been interrupted.
     *
     * @throws InterruptedException
     *             if a thread has been interrupted.
     * @throws ExecutionException
     *             if a thread has thrown an uncaught exception.
     */
    public void check() throws InterruptedException, ExecutionException {
        // If a thread threw an uncaught exception, re-throw it.
        final var executionException = getExecutionException();
        if (executionException != null) {
            throw executionException;
        }
        // If this thread or another thread has been interrupted, throw InterruptedException
        if (checkAndReturn()) {
            throw new InterruptedException();
        }
    }
}
