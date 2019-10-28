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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple implementation of a thread factory.
 *
 * @author Johno Crawford (johno@sulake.com)
 */
public class SimpleThreadFactory implements java.util.concurrent.ThreadFactory {

    /** The thread name prefix. */
    private final String threadNamePrefix;

    /** The thread index counter, used for assigning unique thread ids. */
    private static final AtomicInteger threadIdx = new AtomicInteger();

    /** Whether to set daemon mode. */
    private final boolean daemon;

    /**
     * Constructor.
     *
     * @param threadNamePrefix
     *            prefix for created threads.
     * @param daemon
     *            create daemon threads?
     */
    SimpleThreadFactory(final String threadNamePrefix, final boolean daemon) {
        this.threadNamePrefix = threadNamePrefix;
        this.daemon = daemon;
    }

    /**
     * New thread.
     *
     * @param runnable
     *            the runnable
     * @return the thread
     */
    @Override
    public Thread newThread(final Runnable runnable) {
        final SecurityManager s = System.getSecurityManager();
        final Thread thread = new Thread(
                s != null ? s.getThreadGroup() : new ThreadGroup("ClassGraph-thread-group"), runnable,
                threadNamePrefix + threadIdx.getAndIncrement());
        thread.setDaemon(daemon);
        return thread;
    }
}
