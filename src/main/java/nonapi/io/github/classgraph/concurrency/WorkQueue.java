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
 * Copyright (c) 2018 Luke Hutchison
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

import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import nonapi.io.github.classgraph.utils.LogNode;

/**
 * A parallel work queue.
 *
 * @param <T>
 *            The work unit type.
 */
public class WorkQueue<T> implements AutoCloseable {
    /** The work unit processor. */
    private final WorkUnitProcessor<T> workUnitProcessor;

    /** The queue of work units. */
    private final ConcurrentLinkedQueue<T> workUnits = new ConcurrentLinkedQueue<>();

    /**
     * The number of work units remaining. This will always be at least workQueue.size(), but will be higher if work
     * units have been removed from the queue and are currently being processed. Holding this high while work is
     * being done allows us to use this count to safely detect when all work has been completed. This is needed
     * because work units can add new work units to the work queue.
     */
    private final AtomicInteger numWorkUnitsRemaining = new AtomicInteger();

    /** The number of threads currently running (used for clean shutdown). */
    private final AtomicInteger numRunningThreads = new AtomicInteger();

    /** The Future object added for each worker, used to detect worker completion. */
    private final ConcurrentLinkedQueue<Future<?>> workerFutures = new ConcurrentLinkedQueue<>();

    /**
     * The shared InterruptionChecker, used to detect thread interruption and execution exceptions, and to shut down
     * all threads if either of these occurs.
     */
    private final InterruptionChecker interruptionChecker;

    /** The log node. */
    private final LogNode log;

    /**
     * A work unit processor.
     * 
     * @param <T>
     *            The type of work unit to process.
     */
    public interface WorkUnitProcessor<T> {
        /**
         * Process a work unit.
         *
         * @param workUnit
         *            The work unit.
         * @param workQueue
         *            The work queue.
         * @param log
         *            The log.
         * @throws InterruptedException
         *             If the worker thread is interrupted.
         */
        void processWorkUnit(T workUnit, WorkQueue<T> workQueue, LogNode log) throws InterruptedException;
    }

    /**
     * Start a work queue on the elements in the provided collection, blocking until all work units have been
     * completed.
     *
     * @param <U>
     *            The type of the work queue units.
     * @param elements
     *            The work queue units to process.
     * @param executorService
     *            The {@link ExecutorService}.
     * @param interruptionChecker
     *            the interruption checker
     * @param numParallelTasks
     *            The number of parallel tasks.
     * @param log
     *            The log.
     * @param workUnitProcessor
     *            The {@link WorkUnitProcessor}.
     * @throws InterruptedException
     *             If the work was interrupted.
     * @throws ExecutionException
     *             If a worker throws an uncaught exception.
     */
    public static <U> void runWorkQueue(final Collection<U> elements, final ExecutorService executorService,
            final InterruptionChecker interruptionChecker, final int numParallelTasks, final LogNode log,
            final WorkUnitProcessor<U> workUnitProcessor) throws InterruptedException, ExecutionException {
        // WorkQueue#close() is called when this try-with-resources block terminates, initiating a barrier wait
        // while all worker threads complete.
        try (WorkQueue<U> workQueue = new WorkQueue<>(elements, workUnitProcessor, interruptionChecker, log)) {
            // Start (numParallelTasks - 1) worker threads (may start zero threads if numParallelTasks == 1)
            workQueue.startWorkers(executorService, numParallelTasks - 1);
            // Use the current thread to do work too, in case there is only one thread available in the
            // ExecutorService, or in case numParallelTasks is greater than the number of available threads in the
            // ExecutorService.
            workQueue.runWorkLoop();
        }
    }

    /**
     * A parallel work queue.
     *
     * @param workUnitProcessor
     *            the work unit processor
     * @param interruptionChecker
     *            the interruption checker
     * @param log
     *            the log
     */
    private WorkQueue(final WorkUnitProcessor<T> workUnitProcessor, final InterruptionChecker interruptionChecker,
            final LogNode log) {
        this.workUnitProcessor = workUnitProcessor;
        this.interruptionChecker = interruptionChecker;
        this.log = log;
    }

    /**
     * A parallel work queue.
     *
     * @param initialWorkUnits
     *            the initial work units
     * @param workUnitProcessor
     *            the work unit processor
     * @param interruptionChecker
     *            the interruption checker
     * @param log
     *            the log
     */
    private WorkQueue(final Collection<T> initialWorkUnits, final WorkUnitProcessor<T> workUnitProcessor,
            final InterruptionChecker interruptionChecker, final LogNode log) {
        this(workUnitProcessor, interruptionChecker, log);
        addWorkUnits(initialWorkUnits);
    }

    /**
     * Start worker threads with a shared log.
     *
     * @param executorService
     *            the executor service
     * @param numTasks
     *            the number of worker tasks to start
     */
    private void startWorkers(final ExecutorService executorService, final int numTasks) {
        for (int i = 0; i < numTasks; i++) {
            workerFutures.add(executorService.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    runWorkLoop();
                    return null;
                }
            }));
        }
    }

    /**
     * Start a worker. Called by startWorkers(), but should also be called by the main thread to do some of the work
     * on that thread, to prevent deadlock in the case that the ExecutorService doesn't have as many threads
     * available as numParallelTasks. When this method returns, either all the work has been completed, or this or
     * some other thread was interrupted. If InterruptedException is thrown, this thread or another was interrupted.
     *
     * @throws InterruptedException
     *             if a worker thread was interrupted
     * @throws ExecutionException
     *             if a worker thread throws an uncaught exception
     */
    private void runWorkLoop() throws InterruptedException {
        // Get next work unit from queue
        while (numWorkUnitsRemaining.get() > 0) {
            T workUnit = null;
            while (numWorkUnitsRemaining.get() > 0) {
                // Check for interruption
                interruptionChecker.check();
                // Busy-wait for work units added after the queue is empty, while work units are still being
                // processed, since the in-process work units may generate other work units.
                workUnit = workUnits.poll();
                if (workUnit != null) {
                    // Got a work unit
                    break;
                }
                Thread.sleep(5);
            }
            if (workUnit == null) {
                // numWorkUnitsRemaining == 0
                return;
            }
            // Got a work unit -- hold numWorkUnitsRemaining high until work is complete
            try {
                // Process the work unit
                numRunningThreads.incrementAndGet();
                workUnitProcessor.processWorkUnit(workUnit, this, log);
            } finally {
                // Only after completing the work unit, decrement the count of work units remaining. This way, if
                // process() generates mork work units, but the queue is emptied some time after this work unit was
                // removed from the queue, other worker threads haven't terminated yet, so the newly-added work
                // units can get taken by workers.
                numWorkUnitsRemaining.decrementAndGet();
                numRunningThreads.decrementAndGet();
            }
        }
    }

    /**
     * Add a unit of work. May be called by workers to add more work units to the tail of the queue.
     *
     * @param workUnit
     *            the work unit
     */
    public void addWorkUnit(final T workUnit) {
        numWorkUnitsRemaining.incrementAndGet();
        workUnits.add(workUnit);
    }

    /**
     * Add multiple units of work. May be called by workers to add more work units to the tail of the queue.
     * 
     * @param workUnits
     *            The work units to add to the tail of the queue.
     */
    public void addWorkUnits(final Collection<T> workUnits) {
        for (final T workUnit : workUnits) {
            addWorkUnit(workUnit);
        }
    }

    /**
     * Ensure that there are no work units still uncompleted. This should be called after runWorkLoop() exits on the
     * main thread (e.g. using try-with-resources, since this class is AutoCloseable). If any work units are still
     * uncompleted (e.g. in the case of an exception), will shut down remaining workers.
     *
     * @throws ExecutionException
     *             If a worker threw an uncaught exception.
     */
    @Override
    public void close() throws ExecutionException {
        for (Future<?> future; (future = workerFutures.poll()) != null;) {
            try {
                if (numWorkUnitsRemaining.get() > 0) {
                    interruptionChecker.interrupt();
                    future.cancel(true);
                    if (log != null) {
                        log.log("Some work units were not completed");
                    }
                }
                // Block on completion using future.get(), which may throw one of the exceptions below
                future.get();
            } catch (final CancellationException e) {
                if (log != null) {
                    log.log("Worker thread was cancelled");
                }
            } catch (final InterruptedException e) {
                interruptionChecker.interrupt();
                if (log != null) {
                    log.log("Worker thread was interrupted");
                }
            } catch (final ExecutionException e) {
                interruptionChecker.setExecutionException(e);
                interruptionChecker.interrupt();
            }
        }
        while (numRunningThreads.get() > 0) {
            // Barrier (busy wait) for worker thread completion. (If an exception is thrown, future.cancel(true)
            // returns immediately, so we need to wait for thread shutdown here. Otherwise a finally-block of a
            // caller may be called before the worker threads have completed and cleaned up their resources.)
        }
        // If a worker threw an uncaught exception, re-throw it.
        final ExecutionException executionException = interruptionChecker.getExecutionException();
        if (executionException != null) {
            throw executionException;
        }
    }
}
