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

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A ThreadPoolExecutor that can be used in a try-with-resources block. */
public class AutoCloseableExecutorService extends ThreadPoolExecutor implements AutoCloseable {
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

    /** Shut down thread pool on close(). */
    @Override
    public void close() {
        try {
            // Prevent new tasks being submitted
            shutdown();
        } catch (final SecurityException e) {
            // Ignore
        }
        boolean terminated = false;
        try {
            // Await termination of any running tasks
            terminated = awaitTermination(2500, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            // Ignore
        }
        if (!terminated) {
            try {
                // Interrupt all the threads to terminate them, if awaitTermination() timed out
                shutdownNow();
            } catch (final SecurityException e) {
                throw new RuntimeException(
                        "Could not shut down ExecutorService -- need java.lang.RuntimePermission(\"modifyThread\"), "
                                + "or the security manager's checkAccess method denies access",
                        e);
            }
        }
    }
}
