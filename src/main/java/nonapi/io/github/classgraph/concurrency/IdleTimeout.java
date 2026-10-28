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
package nonapi.io.github.classgraph.concurrency;

import java.util.concurrent.TimeUnit;

/**
 * Times a sliced wait across worker threads that expires only after no progress has been reported to
 * {@link InterruptionChecker#progress()} for the full timeout duration. Each slice is capped so that a whole-JVM
 * pause (e.g. a debugger breakpoint or SIGSTOP) consumes at most one slice of the timeout.
 */
public final class IdleTimeout {
    /** The maximum wait slice duration in nanoseconds. */
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
     *            the timeout in nanoseconds.
     */
    public IdleTimeout(final InterruptionChecker interruptionChecker, final long timeoutNanos) {
        this.interruptionChecker = interruptionChecker;
        this.timeoutNanos = timeoutNanos;
        this.sliceNanos = Math.max(1L, Math.min(timeoutNanos / 10, MAX_SLICE_NANOS));
        this.progressCount = interruptionChecker.getProgressCount();
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
     * Check whether the idle timeout has expired after a wait slice completes.
     *
     * @return true if no progress has been reported for the full timeout duration.
     */
    public boolean hasRunOut() {
        final long newProgressCount = interruptionChecker.getProgressCount();
        if (newProgressCount != progressCount) {
            progressCount = newProgressCount;
            idleNanos = 0L;
            return false;
        }
        idleNanos += Math.max(0L, Math.min(System.nanoTime() - sliceStartNanos, sliceNanos));
        return idleNanos >= timeoutNanos;
    }
}
