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

import java.util.concurrent.TimeUnit;

/**
 * Tracks an idle timeout while waiting for worker threads that report progress via
 * {@link InterruptionChecker#progress()}. The timeout expires only after no progress has been reported for the full
 * timeout duration, measured in bounded wait slices.
 *
 * <p>
 * Not thread-safe; each waiting thread must use its own instance.
 */
public final class IdleTimeout {
    /** Maximum duration of a single wait slice in nanoseconds. */
    private static final long MAX_SLICE_NANOS = TimeUnit.SECONDS.toNanos(1);

    /** The {@link InterruptionChecker} to which worker threads report progress. */
    private final InterruptionChecker interruptionChecker;

    /** The idle timeout in nanoseconds. */
    private final long timeoutNanos;

    /** The duration of a single wait slice in nanoseconds. */
    private final long sliceNanos;

    /** The progress count at the last observed progress update. */
    private long progressCount;

    /** Accumulated idle wait time in nanoseconds since progress was last observed. */
    private long idleNanos;

    /** The {@link System#nanoTime()} timestamp when the current wait slice started. */
    private long sliceStartNanos;

    /**
     * Constructor.
     *
     * @param interruptionChecker
     *            the {@link InterruptionChecker} to which worker threads report progress.
     * @param timeoutNanos
     *            the maximum time in nanoseconds that worker threads may go without reporting progress.
     */
    public IdleTimeout(final InterruptionChecker interruptionChecker, final long timeoutNanos) {
        this.interruptionChecker = interruptionChecker;
        this.timeoutNanos = timeoutNanos;
        this.sliceNanos = Math.max(1L, Math.min(timeoutNanos / 10, MAX_SLICE_NANOS));
        this.progressCount = interruptionChecker.getProgressCount();
    }

    /**
     * Get the idle timeout in nanoseconds.
     *
     * @return the idle timeout in nanoseconds.
     */
    public long getTimeoutNanos() {
        return timeoutNanos;
    }

    /**
     * Start the next wait slice and return its duration in nanoseconds.
     *
     * @return the duration of the next wait slice in nanoseconds.
     */
    public long nextWaitNanos() {
        sliceStartNanos = System.nanoTime();
        return Math.min(sliceNanos, timeoutNanos - idleNanos);
    }

    /**
     * Update the accumulated idle time after an elapsed wait slice and check whether the timeout has expired.
     *
     * @return {@code true} if no progress has been reported for the full timeout duration.
     */
    public boolean hasRunOut() {
        final var newProgressCount = interruptionChecker.getProgressCount();
        if (newProgressCount != progressCount) {
            progressCount = newProgressCount;
            idleNanos = 0L;
            return false;
        }
        idleNanos += Math.max(0L, Math.min(System.nanoTime() - sliceStartNanos, sliceNanos));
        return idleNanos >= timeoutNanos;
    }
}
