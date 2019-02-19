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
package nonapi.io.github.classgraph.concurrency;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.github.classgraph.ClassGraphException;

/** A ThreadPoolExecutor that can be used in a try-with-resources block. */
public class AutoCloseableExecutorService extends ThreadPoolExecutor implements AutoCloseable {
    /** The {@link InterruptionChecker}. */
    public final InterruptionChecker interruptionChecker = new InterruptionChecker();

    /**
     * A ThreadPoolExecutor that can be used in a try-with-resources block.
     * 
     * @param numThreads
     *            The number of threads to allocate.
     */
    public AutoCloseableExecutorService(final int numThreads) {
        super(numThreads, numThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(),
                new SimpleThreadFactory("ClassGraph-worker-", true));
    }

    /**
     * Catch exceptions from both submit() and execute(), and call {@link InterruptionChecker#interrupt()} to
     * interrupt all threads.
     *
     * @param runnable
     *            the Runnable
     * @param throwable
     *            the Throwable
     */
    @Override
    public void afterExecute(final Runnable runnable, final Throwable throwable) {
        super.afterExecute(runnable, throwable);
        if (throwable != null) {
            // Wrap the throwable in an ExecutionException (execute() does not do this)
            interruptionChecker.setExecutionException(new ExecutionException("Uncaught exception", throwable));
            // execute() was called and an uncaught exception or error was thrown
            interruptionChecker.interrupt();
        } else if (/* throwable == null && */ runnable instanceof Future<?>) {
            // submit() was called, so throwable is not set 
            try {
                // This call will not block, since execution has finished
                ((Future<?>) runnable).get();
            } catch (CancellationException | InterruptedException e) {
                // If this thread was cancelled or interrupted, interrupt other threads
                interruptionChecker.interrupt();
            } catch (final ExecutionException e) {
                // Record the exception that was thrown by the thread
                interruptionChecker.setExecutionException(e);
                // Interrupt other threads
                interruptionChecker.interrupt();
            }
        }
    }

    /** Shut down thread pool on close(). */
    @Override
    public void close() {
        try {
            // Prevent new tasks being submitted
            shutdown();
        } catch (final SecurityException e) {
            // Ignore for now (caught again if shutdownNow() fails)
        }
        boolean terminated = false;
        try {
            // Await termination of any running tasks
            terminated = awaitTermination(2500, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            interruptionChecker.interrupt();
        }
        if (!terminated) {
            try {
                // Interrupt all the threads to terminate them, if awaitTermination() timed out
                shutdownNow();
            } catch (final SecurityException e) {
                throw ClassGraphException.newClassGraphException("Could not shut down ExecutorService -- need "
                        + "java.lang.RuntimePermission(\"modifyThread\"), "
                        + "or the security manager's checkAccess method denies access", e);
            }
        }
    }
}
