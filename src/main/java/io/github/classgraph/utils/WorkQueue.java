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
package io.github.classgraph.utils;

import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final ConcurrentLinkedQueue<T> workQueue = new ConcurrentLinkedQueue<>();

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
         * @param workUnit
         *            The work unit.
         * @param workQueue
         *            The work queue.
         * @throws Exception
         *             If something goes wrong while processing the work unit.
         */
        public void processWorkUnit(T workUnit, WorkQueue<T> workQueue) throws Exception;
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
     * @param numParallelTasks
     *            The number of parallel tasks.
     * @param workUnitProcessor
     *            The {@link WorkUnitProcessor}.
     * @param interruptionChecker
     *            The {@link InterruptionChecker}.
     * @param log
     *            The log.
     * @throws ExecutionException
     *             If an exception is thrown while processing a work unit.
     * @throws InterruptedException
     *             If the work was interrupted.
     */
    public static <U> void runWorkQueue(final Collection<U> elements, final ExecutorService executorService,
            final int numParallelTasks, final WorkUnitProcessor<U> workUnitProcessor,
            final InterruptionChecker interruptionChecker, final LogNode log)
            throws ExecutionException, InterruptedException {
        // Wrap in a try-with-resources block, so that the WorkQueue is closed on exception
        try (WorkQueue<U> workQueue = new WorkQueue<>(elements, workUnitProcessor, interruptionChecker, log)) {
            // Start (numParallelTasks - 1) worker threads (may start zero threads if numParallelTasks == 1)
            workQueue.startWorkers(executorService, numParallelTasks - 1, log);
            // Use the current thread to do work too, in case there is only one thread available in the
            // ExecutorService, or in case numParallelTasks is greater than the number of available threads in the
            // ExecutorService.
            workQueue.runWorkLoop();
        }
        // WorkQueue#close() is called when the above try-with-resources block terminates, initiating a barrier wait
        // while all worker threads complete.
    }

    /** A parallel work queue. */
    private WorkQueue(final WorkUnitProcessor<T> workUnitProcessor, final InterruptionChecker interruptionChecker,
            final LogNode log) {
        this.workUnitProcessor = workUnitProcessor;
        this.interruptionChecker = interruptionChecker;
        this.log = log;
    }

    /** A parallel work queue. */
    private WorkQueue(final Collection<T> initialWorkUnits, final WorkUnitProcessor<T> workUnitProcessor,
            final InterruptionChecker interruptionChecker, final LogNode log) {
        this(workUnitProcessor, interruptionChecker, log);
        addWorkUnits(initialWorkUnits);
    }

    /** Start worker threads with a shared log. */
    private void startWorkers(final ExecutorService executorService, final int numWorkers, final LogNode log) {
        for (int i = 0; i < numWorkers; i++) {
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
     */
    private void runWorkLoop() throws InterruptedException, ExecutionException {
        // Get next work unit from queue
        while (numWorkUnitsRemaining.get() > 0) {
            T workUnit = null;
            int counter = 0;
            while (numWorkUnitsRemaining.get() > 0) {
                if (++counter > 100) {
                    interruptionChecker.check();
                }
                // Busy-wait for work units added after the queue is empty, while work units are still being
                // processed, since the in-process work units may generate other work units.
                workUnit = workQueue.poll();
                if (workUnit != null) {
                    // Got a work unit
                    break;
                }
                Thread.sleep(5);
            }
            if (workUnit == null) {
                // No work units remaining
                return;
            }
            // Got a work unit -- hold numWorkUnitsRemaining high until work is complete
            interruptionChecker.check();
            try {
                // Process the work unit
                numRunningThreads.incrementAndGet();
                workUnitProcessor.processWorkUnit(workUnit, this);
            } catch (final InterruptedException e) {
                // Interrupt all threads
                interruptionChecker.interrupt();
                throw e;
            } catch (final Exception e) {
                if (log != null) {
                    log.log("Exception in worker thread", e);
                }
                if (e.getCause() instanceof InterruptedException) {
                    interruptionChecker.interrupt();
                }
                throw interruptionChecker.executionException(e);
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
     */
    private void addWorkUnit(final T workUnit) {
        numWorkUnitsRemaining.incrementAndGet();
        workQueue.add(workUnit);
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
     */
    @Override
    public void close() throws ExecutionException {
        boolean uncompletedWork = false;
        if (numWorkUnitsRemaining.get() > 0) {
            uncompletedWork = true;
            if (log != null) {
                log.log("Some work units not completed");
            }
        }
        for (Future<?> future; (future = workerFutures.poll()) != null;) {
            try {
                if (uncompletedWork) {
                    future.cancel(true);
                }
                // Call future.get(), so that ExecutionExceptions get logged if the worker threw an exception
                future.get();
            } catch (CancellationException | InterruptedException e) {
                // Ignore
            } catch (final ExecutionException e) {
                if (log != null) {
                    log.log("Closed work queue because worker threw exception", e);
                }
                interruptionChecker.executionException(e);
            }
        }
        while (numRunningThreads.get() > 0) {
            // Barrier (busy wait) for worker thread completion. (If an exception is thrown, future.cancel(true)
            // returns immediately, so we need to wait for thread shutdown here. Otherwise a finally-block of a
            // caller may be called before the worker threads have completed and cleaned up theri resources.)
        }
    }
}
