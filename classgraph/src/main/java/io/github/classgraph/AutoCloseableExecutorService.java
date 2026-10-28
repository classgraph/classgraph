/*
 * This file is part of ClassGraph.
 *
 * Author: Johno Crawford (johno@sulake.com)
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Johno Crawford
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
package io.github.classgraph;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A {@link ThreadPoolExecutor} that shuts down when closed in a try-with-resources block.
 */
class AutoCloseableExecutorService extends ThreadPoolExecutor {
    /**
     * Grace period in milliseconds for running tasks to terminate after {@link #shutdown()} before interrupting.
     */
    private static final long TERMINATION_TIMEOUT_MILLIS = 2500;

    /** Factory for daemon worker threads named {@code ClassGraph-worker-<n>}. */
    private static final ThreadFactory WORKER_THREAD_FACTORY = Thread.ofPlatform().name("ClassGraph-worker-", 0)
            .daemon(true).factory();

    /**
     * Constructor.
     *
     * @param numThreads
     *            The number of worker threads to allocate.
     */
    public AutoCloseableExecutorService(final int numThreads) {
        super(numThreads, numThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                WORKER_THREAD_FACTORY);
    }

    /** Shut down thread pool on close(). */
    @Override
    public void close() {
        // Prevent new tasks being submitted
        shutdown();
        var terminated = false;
        try {
            // Await termination of any running tasks
            terminated = awaitTermination(TERMINATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException _) {
            // Restore the interrupt status that throwing InterruptedException cleared, so that the caller still
            // sees the interruption. terminated is still false, so the tasks that are still running are
            // interrupted below. Nothing is recorded in the scan's interruption checker: the scan has already
            // ended, so nothing checks it any more
            Thread.currentThread().interrupt();
        }
        if (!terminated) {
            // Interrupt all the threads to terminate them, if awaitTermination() timed out or was interrupted
            shutdownNow();
        }
    }
}
