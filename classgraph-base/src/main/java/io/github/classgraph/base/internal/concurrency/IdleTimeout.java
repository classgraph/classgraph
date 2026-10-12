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
 * Times a wait for other threads that gives up only once none of them has made progress for the whole timeout.
 * Progress is anything reported to {@link InterruptionChecker#progress()}. The wait is made in slices of one tenth
 * of the timeout, or one second if that is shorter. A slice that took longer than it should have, because the whole
 * JVM was paused (at a breakpoint that suspends all threads, or by SIGSTOP), counts only as one slice, so a pause
 * can use up at most one slice of the timeout. A breakpoint that suspends only a worker thread is not a pause of
 * the whole JVM, so set a longer timeout, or 0, while debugging a scan.
 *
 * <pre>
 * var idleTimeout = new IdleTimeout(interruptionChecker, timeoutNanos);
 * while (!latch.await(idleTimeout.nextWaitNanos(), TimeUnit.NANOSECONDS)) {
 *     if (idleTimeout.hasRunOut()) {
 *         throw new TimeoutException();
 *     }
 * }
 * </pre>
 *
 * <p>
 * An instance is used by one waiting thread only.
 */
public final class IdleTimeout {
    /** The longest slice. A pause counts as at most this long. */
    private static final long MAX_SLICE_NANOS = TimeUnit.SECONDS.toNanos(1);

    /** The InterruptionChecker that the threads being waited for report their progress to. */
    private final InterruptionChecker interruptionChecker;

    /** The timeout in nanoseconds. */
    private final long timeoutNanos;

    /** The length of one slice of the wait in nanoseconds. */
    private final long sliceNanos;

    /** The progress count when progress was last seen. */
    private long progressCount;

    /** The time spent waiting since progress was last seen, in nanoseconds. */
    private long idleNanos;

    /** The {@link System#nanoTime()} at which the current slice started. */
    private long sliceStartNanos;

    /**
     * Constructor.
     *
     * @param interruptionChecker
     *            the InterruptionChecker that the threads being waited for report their progress to.
     * @param timeoutNanos
     *            the timeout in nanoseconds: how long the threads being waited for may go without progress.
     */
    public IdleTimeout(final InterruptionChecker interruptionChecker, final long timeoutNanos) {
        this.interruptionChecker = interruptionChecker;
        this.timeoutNanos = timeoutNanos;
        this.sliceNanos = Math.max(1L, Math.min(timeoutNanos / 10, MAX_SLICE_NANOS));
        this.progressCount = interruptionChecker.getProgressCount();
    }

    /**
     * Get the timeout.
     *
     * @return the timeout in nanoseconds.
     */
    public long getTimeoutNanos() {
        return timeoutNanos;
    }

    /**
     * Start the next slice of the wait.
     *
     * @return how long to wait for, in nanoseconds.
     */
    public long nextWaitNanos() {
        sliceStartNanos = System.nanoTime();
        return Math.min(sliceNanos, timeoutNanos - idleNanos);
    }

    /**
     * Call when a slice of the wait ended without the thing waited for happening.
     *
     * @return true if no progress has been made for the whole timeout.
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
