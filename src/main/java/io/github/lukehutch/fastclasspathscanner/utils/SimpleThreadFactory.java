package io.github.lukehutch.fastclasspathscanner.utils;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * "Simple" implementation of thread factory.
 *
 * @author Johno Crawford (johno@sulake.com)
 */
public class SimpleThreadFactory implements java.util.concurrent.ThreadFactory {

    private final String threadNamePrefix;

    private final static AtomicInteger threadIdx = new AtomicInteger();

    private boolean daemon;

    /**
     * Constructor.
     *
     * @param threadNamePrefix prefix for created threads.
     * @param daemon           create daemon threads?
     */
    public SimpleThreadFactory(String threadNamePrefix, boolean daemon) {
        this.threadNamePrefix = threadNamePrefix;
        this.daemon = daemon;
    }

    @Override
    public Thread newThread(Runnable r) {
        final Thread t = new Thread(r, threadNamePrefix + threadIdx.getAndIncrement());
        t.setDaemon(daemon);
        return t;
    }
}
