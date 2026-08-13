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

import java.util.Collection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.classgraph.base.internal.utils.LogNode;
import org.jspecify.annotations.Nullable;

/**
 * A parallel work queue.
 *
 * @param <T>
 *            The work unit type.
 */
public final class WorkQueue<T> implements AutoCloseable {
    /** The work unit processor. */
    private final WorkUnitProcessor<T> workUnitProcessor;

    /** The queue of work units. */
    private final BlockingQueue<WorkUnitWrapper<T>> workUnits = new LinkedBlockingQueue<>();

    /** The number of workers. */
    private final int numWorkers;

    /**
     * The number of work units remaining to be processed, plus the number of currently running threads working on a
     * work unit.
     */
    private final AtomicInteger numIncompleteWorkUnits = new AtomicInteger();

    /**
     * The worker tasks that were submitted to the {@link ExecutorService}, used to detect worker completion.
     */
    private final ConcurrentLinkedQueue<Worker> workerTasks = new ConcurrentLinkedQueue<>();

    /**
     * The shared InterruptionChecker, used to detect thread interruption and execution exceptions, and to shut down
     * all threads if either of these occurs.
     */
    private final InterruptionChecker interruptionChecker;

    /** The log node. */
    private final @Nullable LogNode log;

    /**
     * A wrapper for work units (needed to send a poison pill as a null value, since BlockingQueue does not accept
     * null values).
     *
     * @param <T>
     *            the generic type
     * @param workUnit
     *            the work unit, or null to represent a poison pill.
     */
    private record WorkUnitWrapper<T>(@Nullable T workUnit) {
    }

    /**
     * A worker submitted to the {@link ExecutorService}, and the flag that decides whether the worker runs the work
     * loop or is abandoned. The worker sets the flag when it starts, and {@link #close()} sets it if the worker has
     * not started by then; whichever sets it first wins, so once {@link #close()} has claimed a worker, that worker
     * is guaranteed never to run the work loop, and does not have to be waited for.
     *
     * @param claimed
     *            whether the worker or {@link #close()} claimed this worker first.
     * @param future
     *            the {@link Future} of the submitted worker.
     */
    private record Worker(AtomicBoolean claimed, Future<?> future) {
    }

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
        void processWorkUnit(T workUnit, WorkQueue<T> workQueue, @Nullable LogNode log) throws InterruptedException;
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
            final InterruptionChecker interruptionChecker, final int numParallelTasks, final @Nullable LogNode log,
            final WorkUnitProcessor<U> workUnitProcessor) throws InterruptedException, ExecutionException {
        if (elements.isEmpty()) {
            // Nothing to do
            return;
        }
        // WorkQueue#close() is called when this try-with-resources block terminates, initiating a barrier wait
        // while all worker threads complete.
        try (WorkQueue<U> workQueue = new WorkQueue<>(elements, workUnitProcessor, numParallelTasks,
                interruptionChecker, log)) {
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
     * @param initialWorkUnits
     *            the initial work units
     * @param workUnitProcessor
     *            the work unit processor
     * @param numWorkers
     *            the number of workers
     * @param interruptionChecker
     *            the interruption checker
     * @param log
     *            the log node, or null to skip logging
     */
    private WorkQueue(final Collection<T> initialWorkUnits, final WorkUnitProcessor<T> workUnitProcessor,
            final int numWorkers, final InterruptionChecker interruptionChecker, final @Nullable LogNode log) {
        this.workUnitProcessor = workUnitProcessor;
        this.numWorkers = numWorkers;
        this.interruptionChecker = interruptionChecker;
        this.log = log;
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
        for (var i = 0; i < numTasks; i++) {
            final var claimed = new AtomicBoolean();
            workerTasks.add(new Worker(claimed, executorService.submit(() -> {
                if (claimed.compareAndSet(false, true)) {
                    runWorkLoop();
                }
                return null;
            })));
        }
    }

    /**
     * Send poison pills to workers.
     */
    private void sendPoisonPills() {
        for (var i = 0; i < numWorkers; i++) {
            workUnits.add(new WorkUnitWrapper<>(null));
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
    private void runWorkLoop() throws InterruptedException, ExecutionException {
        // Get next work unit from queue
        for (;;) {
            // Process the work unit
            try {
                // Check for interruption
                interruptionChecker.check();

                // Get next work unit
                final var workUnit = workUnits.take().workUnit();

                if (workUnit == null) {
                    // Received poison pill
                    break;
                }

                // Process the work unit (may throw InterruptedException)
                workUnitProcessor.processWorkUnit(workUnit, this, log);

            } catch (InterruptedException | Error e) {
                // On InterruptedException or OutOfMemoryError, drain work queue, send poison pills, and re-throw
                workUnits.clear();
                numIncompleteWorkUnits.set(0);
                sendPoisonPills();
                throw e;

            } catch (final RuntimeException e) {
                // On unchecked exception, drain work queue, send poison pills, and throw ExecutionException
                workUnits.clear();
                numIncompleteWorkUnits.set(0);
                sendPoisonPills();
                throw new ExecutionException("Worker thread threw unchecked exception", e);

            }
            if (numIncompleteWorkUnits.decrementAndGet() == 0) {
                // No more work units -- send poison pills
                sendPoisonPills();
            }
        }
    }

    /**
     * Add a unit of work. May be called by workers to add more work units to the tail of the queue.
     *
     * @param workUnit
     *            the work unit
     * @throws NullPointerException
     *             if the work unit is null.
     */
    public void addWorkUnit(final T workUnit) {
        if (workUnit == null) {
            throw new NullPointerException("workUnit cannot be null");
        }
        numIncompleteWorkUnits.incrementAndGet();
        workUnits.add(new WorkUnitWrapper<>(workUnit));
    }

    /**
     * Add multiple units of work. May be called by workers to add more work units to the tail of the queue.
     *
     * @param workUnits
     *            The work units to add to the tail of the queue.
     * @throws NullPointerException
     *             if any of the work units are null.
     */
    public void addWorkUnits(final Collection<T> workUnits) {
        for (final T workUnit : workUnits) {
            addWorkUnit(workUnit);
        }
    }

    /**
     * Completion barrier for work queue. This should be called after runWorkLoop() exits on the main thread (e.g.
     * using try-with-resources).
     *
     * @throws ExecutionException
     *             If a worker threw an uncaught exception.
     */
    @Override
    public void close() throws ExecutionException {
        for (Worker worker; (worker = workerTasks.poll()) != null;) {
            // If the ExecutorService did not have a free thread to start a worker on, claiming the worker here
            // stops it running the work loop if it is started later, so there is nothing to wait for. Waiting for
            // it instead would block forever if the ExecutorService cannot free up a thread until this thread
            // returns, which is the case when the work queue was itself started by a task submitted to the same
            // ExecutorService. (All the work has been done by the time this method is called, whether or not
            // every worker ran.)
            if (worker.claimed.compareAndSet(false, true)) {
                continue;
            }
            final var future = worker.future;
            try {
                // Block on completion using future.get(), which may throw one of the exceptions below
                future.get();
            } catch (final CancellationException e) {
                if (log != null) {
                    log.log("~", "Worker thread was cancelled");
                }
            } catch (final InterruptedException e) {
                if (log != null) {
                    log.log("~", "Worker thread was interrupted");
                }
                // Interrupt other threads
                interruptionChecker.interrupt();
            } catch (final ExecutionException e) {
                interruptionChecker.setExecutionException(e);
                interruptionChecker.interrupt();
            }
        }
    }
}
