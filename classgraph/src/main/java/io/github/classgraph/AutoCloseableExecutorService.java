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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A ThreadPoolExecutor that can be used in a try-with-resources block.
 *
 * <p>
 * A task that fails is not reported here: the only tasks submitted to this executor are the workers of a
 * {@link WorkQueue}, and {@link WorkQueue#close()} waits for each worker that started and records its failure or
 * timeout in the interruption checker of the scan. That is also how a failure is reported when the caller supplies
 * its own {@link java.util.concurrent.ExecutorService}.
 */
class AutoCloseableExecutorService extends ThreadPoolExecutor implements AutoCloseable {
    /**
     * How long {@link #close()} waits, after {@link #shutdown()}, for the tasks that are still running to finish,
     * before {@link #shutdownNow()} interrupts them.
     *
     * <p>
     * This is a grace period for the worker threads to unwind, not a worker timeout: by the time the executor is
     * closed, {@link WorkQueue#close()} has already waited up to the scan's worker timeout for each of its workers.
     */
    private static final long TERMINATION_TIMEOUT_MILLIS = 2500;

    /**
     * A ThreadPoolExecutor that can be used in a try-with-resources block.
     *
     * @param numThreads
     *            The number of threads to allocate.
     */
    public AutoCloseableExecutorService(final int numThreads) {
        super(numThreads, numThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                new SimpleThreadFactory("ClassGraph-worker-", true));
    }

    /** Shut down thread pool on close(). */
    @Override
    public void close() {
        try {
            // Prevent new tasks being submitted
            shutdown();
        } catch (final SecurityException e) {
            // A security manager denied shutdown(). Nothing needs reporting here: the executor cannot terminate
            // without it, so awaitTermination() below times out, and shutdownNow() is then denied for the same
            // reason -- that is where the failure is thrown.
        }
        var terminated = false;
        try {
            // Await termination of any running tasks
            terminated = awaitTermination(TERMINATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            // Restore the interrupt status that throwing InterruptedException cleared, so that the caller still
            // sees the interruption. terminated is still false, so the tasks that are still running are
            // interrupted below. Nothing is recorded in the scan's interruption checker: the scan has already
            // ended, and the ScanResult it returned keeps that checker, so marking it would make closing the
            // ScanResult interrupt whichever thread closes it
            Thread.currentThread().interrupt();
        }
        if (!terminated) {
            try {
                // Interrupt all the threads to terminate them, if awaitTermination() timed out or was interrupted
                shutdownNow();
            } catch (final SecurityException e) {
                throw new RuntimeException("Could not shut down ExecutorService -- need "
                        + "java.lang.RuntimePermission(\"modifyThread\"), "
                        + "or the security manager's checkAccess method denies access", e);
            }
        }
    }
}
