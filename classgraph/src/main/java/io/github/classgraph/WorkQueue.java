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
package io.github.classgraph;

import java.util.Collection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.concurrency.IdleTimeout;
import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import org.jspecify.annotations.Nullable;

// Work is scheduled here, and in AutoCloseableExecutorService, on a fixed pool of platform threads. Virtual threads
// are the obvious thing to reach for instead, but measurement says they would not make a scan of local files any
// faster, and the reasons are worth recording so that this is not tried twice:
//
// A virtual thread is only an improvement over a pooled platform thread if it unmounts while it is blocked, and
// almost nothing a scan blocks on unmounts. Reading a file through FileChannel or RandomAccessFile pins the carrier
// thread, and the scheduler does not start another carrier to compensate: with the scheduler's parallelism set to
// 1, three virtual threads blocked on a file read let only the first one run, while three blocked in Thread.sleep()
// or on a socket read all ran (measured on JDK 25 and 26). Reads through a MappedByteBuffer, which is how file
// content is read wherever a file can be mapped, are page faults, which the JVM cannot see at all.
//
// Nor is there idle parallelism to reclaim: the work is CPU- and memory-bound once the page cache is warm. Scanning
// 144 jars with all the info options enabled (61.5k classes) took 3.38s with 1 worker thread, 0.90s with 8, 0.84s
// with 16 and 0.87s with 32, on a machine with 32 cores -- so the scan already stops scaling below the core count,
// and adding more concurrent tasks cannot help. A thread per work unit would also mean tens of thousands of
// threads, since a work unit is a single classfile.
//
// The one place virtual threads would help is JarURLDownloader, which fetches a remote jar over HTTP: socket reads
// do unmount, so many jars could be downloaded at once without holding a worker thread each.
//
// TODO: once StructuredTaskScope is no longer a preview API (it still is in JDK 25 and 26), make the simplification
// that is worth making regardless. The poison pills, the count of incomplete work units, and the manual claiming
// of unstarted workers in close() all exist to manage a fixed pool of workers pulling from a shared queue, and
// structured concurrency would remove the need to track submitted workers by hand -- but the
// pool also bounds parallelism deliberately (see ClassGraph#DEFAULT_NUM_WORKER_THREADS), so whatever replaces it
// still has to bound the number of work units in flight.

/**
 * A parallel work queue.
 *
 * @param <T>
 *            The work unit type.
 */
final class WorkQueue<T> implements AutoCloseable {
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

    /**
     * The number of nanoseconds with no progress after which the calling thread stops waiting for more work while
     * it has no work of its own to do, and {@link #close()} stops waiting for the workers to finish, or
     * {@link Long#MAX_VALUE} to wait indefinitely. Progress is anything reported to
     * {@link InterruptionChecker#progress()}: a work unit finishing, or a worker reporting progress from within a
     * long work unit.
     */
    private final long workerTimeoutNanos;

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
     * completed, or the work queue has failed or timed out.
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
     * @param workerTimeoutNanos
     *            The number of nanoseconds with no progress after which to stop waiting for the workers, or
     *            {@link Long#MAX_VALUE} to wait indefinitely. Progress is anything reported to
     *            {@link InterruptionChecker#progress()}: a work unit finishing on any thread, or a worker reporting
     *            progress from within a long work unit. The timeout applies once the calling thread has no work of
     *            its own left to do, and while waiting for the workers to finish. The waits are timed by an
     *            {@link IdleTimeout}, so a pause of the whole JVM, for example in a debugger, counts only as one
     *            slice, and can use up at most one slice of the timeout.
     * @param log
     *            The log.
     * @param workUnitProcessor
     *            The {@link WorkUnitProcessor}.
     * @throws InterruptedException
     *             If the work was interrupted.
     * @throws ExecutionException
     *             If the calling thread throws an uncaught exception while processing a work unit, or if no
     *             progress is made for the timeout while the calling thread is waiting for more work. A failure or
     *             timeout of a worker thread is not thrown here, but recorded in the {@link InterruptionChecker},
     *             to be thrown by its next {@link InterruptionChecker#check()}.
     */
    public static <U> void runWorkQueue(final Collection<U> elements, final ExecutorService executorService,
            final InterruptionChecker interruptionChecker, final int numParallelTasks,
            final long workerTimeoutNanos, final @Nullable LogNode log,
            final WorkUnitProcessor<U> workUnitProcessor) throws InterruptedException, ExecutionException {
        if (numParallelTasks < 1) {
            throw new IllegalArgumentException("numParallelTasks must be at least 1");
        }
        if (elements.isEmpty()) {
            // Nothing to do
            return;
        }
        // WorkQueue#close() is called when this try-with-resources block terminates, initiating a barrier wait
        // while all worker threads complete.
        try (WorkQueue<U> workQueue = new WorkQueue<>(elements, workUnitProcessor, numParallelTasks,
                interruptionChecker, workerTimeoutNanos, log)) {
            // Start (numParallelTasks - 1) worker threads (may start zero threads if numParallelTasks == 1)
            workQueue.startWorkers(executorService, numParallelTasks - 1);
            // Use the current thread to do work too, in case there is only one thread available in the
            // ExecutorService, or in case numParallelTasks is greater than the number of available threads in the
            // ExecutorService.
            workQueue.runWorkLoop(/* isCallingThread = */ true);
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
     * @param workerTimeoutNanos
     *            the number of nanoseconds with no progress after which to stop waiting for the workers, or
     *            {@link Long#MAX_VALUE} to wait indefinitely
     * @param log
     *            the log node, or null to skip logging
     */
    private WorkQueue(final Collection<T> initialWorkUnits, final WorkUnitProcessor<T> workUnitProcessor,
            final int numWorkers, final InterruptionChecker interruptionChecker, final long workerTimeoutNanos,
            final @Nullable LogNode log) {
        this.workUnitProcessor = workUnitProcessor;
        this.numWorkers = numWorkers;
        this.interruptionChecker = interruptionChecker;
        this.workerTimeoutNanos = workerTimeoutNanos;
        this.log = log;
        addWorkUnits(initialWorkUnits);
    }

    /**
     * Submit workers to the {@link ExecutorService}. Once the {@link ExecutorService} rejects a worker, as a pool
     * with a bounded queue does when it is busy and any pool does once it has been shut down, no more workers are
     * submitted. That is not an error, since the calling thread runs the work loop too, so the work units are then
     * processed by the calling thread and the workers that were started. (The poison pills that
     * {@link #sendPoisonPills()} sends for the workers that were not started stay in the queue, which is discarded
     * with this work queue.)
     *
     * @param executorService
     *            the executor service
     * @param numTasks
     *            the number of worker tasks to start
     */
    private void startWorkers(final ExecutorService executorService, final int numTasks) {
        for (var i = 0; i < numTasks; i++) {
            final var claimed = new AtomicBoolean();
            final Future<?> future;
            try {
                future = executorService.submit(() -> {
                    if (claimed.compareAndSet(false, true)) {
                        runWorkLoop(/* isCallingThread = */ false);
                    }
                    return null;
                });
            } catch (final RejectedExecutionException e) {
                if (log != null) {
                    log.log("The ExecutorService rejected a worker task, so " + i + " of " + numTasks
                            + " worker tasks were started, and the calling thread does the rest of the work", e);
                }
                break;
            }
            workerTasks.add(new Worker(claimed, future));
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
     * Take work units from the queue and process them until a poison pill is received. Each worker started by
     * {@link #startWorkers(ExecutorService, int)} runs this, and so does the thread that called
     * {@link #runWorkQueue}, so that the work still gets done if the ExecutorService has fewer free threads than
     * numParallelTasks. When this method returns normally, either all the work has been completed, or another
     * thread failed, was interrupted or timed out, and emptied the queue so that every thread stops. When it
     * throws, it empties the queue in the same way.
     *
     * @param isCallingThread
     *            true on the thread that called {@link #runWorkQueue}, which stops waiting for more work once no
     *            progress has been made for the worker timeout. False on a worker thread, which waits for more work
     *            for as long as it takes, since the calling thread sends it a poison pill if the work queue times
     *            out.
     * @throws InterruptedException
     *             if this thread or another thread was interrupted
     * @throws ExecutionException
     *             if this thread throws an uncaught exception, if a failure of another thread has been recorded in
     *             the {@link InterruptionChecker}, or if this is the calling thread and the work queue timed out
     */
    private void runWorkLoop(final boolean isCallingThread) throws InterruptedException, ExecutionException {
        for (;;) {
            try {
                // Check for interruption
                interruptionChecker.check();

                // Get next work unit
                final var workUnit = isCallingThread ? takeWorkUnitOnCallingThread() : workUnits.take().workUnit();

                if (workUnit == null) {
                    // Received poison pill
                    break;
                }

                // Process the work unit (may throw InterruptedException)
                workUnitProcessor.processWorkUnit(workUnit, this, log);

            } catch (InterruptedException | ExecutionException | Error e) {
                // On InterruptedException, on ExecutionException (a failure that another thread recorded in the
                // InterruptionChecker, or the timeout of the calling thread), or on an Error such as
                // OutOfMemoryError, drain the work queue, send poison pills, and re-throw. The poison pills stop
                // the threads that are waiting for more work, which would otherwise wait until every work unit in
                // progress had finished, however long a stuck one takes
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
            interruptionChecker.progress();
            if (numIncompleteWorkUnits.decrementAndGet() == 0) {
                // No more work units -- send poison pills
                sendPoisonPills();
            }
        }
    }

    /**
     * Take the next work unit from the queue on the thread that called {@link #runWorkQueue}, giving up once no
     * progress has been made for the worker timeout. The calling thread cannot wait for more work indefinitely,
     * since a worker thread that never finishes its work unit would then stop it from ever reaching
     * {@link #close()}, where the worker timeout is applied to the workers.
     *
     * <p>
     * The timeout counts only time with no progress on any thread, so that the work queue is not stopped while the
     * other threads are still finishing work units, or are still making progress within a long work unit, such as a
     * slow download. A pause of the whole JVM counts only as one slice of the wait, so a pause can use up at most
     * one slice of the timeout.
     *
     * @return the work unit, or null for a poison pill.
     * @throws InterruptedException
     *             if this thread was interrupted while waiting.
     * @throws ExecutionException
     *             if no progress was made for the worker timeout. The queue has then been emptied and the other
     *             threads sent poison pills, and the timeout has been recorded in the {@link InterruptionChecker}.
     */
    private @Nullable T takeWorkUnitOnCallingThread() throws InterruptedException, ExecutionException {
        if (workerTimeoutNanos == Long.MAX_VALUE) {
            return workUnits.take().workUnit();
        }
        final var idleTimeout = new IdleTimeout(interruptionChecker, workerTimeoutNanos);
        for (;;) {
            final var workUnitWrapper = workUnits.poll(idleTimeout.nextWaitNanos(), TimeUnit.NANOSECONDS);
            if (workUnitWrapper != null) {
                return workUnitWrapper.workUnit();
            }
            if (idleTimeout.hasRunOut()) {
                // No progress was made for the whole timeout, so a worker thread is stuck. Record the timeout
                // before throwing it, so that close() does not wait for the full timeout again for the stuck
                // worker, and so that the other workers stop at their next check()
                final var timeout = newWorkerTimeoutException();
                interruptionChecker.setExecutionException(timeout);
                workUnits.clear();
                numIncompleteWorkUnits.set(0);
                sendPoisonPills();
                throw timeout;
            }
        }
    }

    /**
     * Create the exception that reports that a worker thread made no progress for the worker timeout. The message
     * has to go in the innermost exception, since {@link InterruptionChecker#getCause(Throwable)} unwraps nested
     * {@link ExecutionException}s.
     *
     * @return the exception.
     */
    private ExecutionException newWorkerTimeoutException() {
        return new ExecutionException(new IllegalStateException("Timed out after "
                + TimeUnit.NANOSECONDS.toMillis(workerTimeoutNanos)
                + "ms waiting for a worker thread to finish. Either the calling thread holds a lock that the "
                + "classloader needs in order to load one of ClassGraph's own classes on a worker thread, which "
                + "deadlocks the scan (call scan(1) to run the whole scan on the calling thread, so that no class "
                + "is loaded on a worker thread), or a worker thread is blocked reading a classpath element from a "
                + "filesystem or network (call ClassGraph#setWorkerTimeout(Duration) to allow more time)"));
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
     * <p>
     * Nothing is thrown from here, even if a worker failed: a worker's exception is handed to the
     * {@link InterruptionChecker}, so that it is thrown by the next {@link InterruptionChecker#check()} on the
     * calling thread, rather than replacing whatever exception is already propagating out of the try-with-resources
     * block that called this.
     */
    @Override
    public void close() {
        // Once a worker has failed or timed out, here or while the calling thread was waiting for more work, the
        // remaining workers are only polled rather than each waited on for the full timeout, since the failure is
        // reported however long they take to finish
        var timeoutNanos = interruptionChecker.getExecutionException() != null ? 0L : workerTimeoutNanos;
        // One IdleTimeout for all the workers, so that the wait gives up once no thread has made progress for the
        // worker timeout, however many workers are still running
        final var idleTimeout = new IdleTimeout(interruptionChecker, workerTimeoutNanos);
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
                // Block on completion using future.get(), which may throw one of the exceptions below. A timed wait
                // is made in slices, and gives up once no thread has made progress for the worker timeout
                if (timeoutNanos == 0L || timeoutNanos == Long.MAX_VALUE) {
                    future.get(timeoutNanos, TimeUnit.NANOSECONDS);
                } else {
                    for (;;) {
                        try {
                            future.get(idleTimeout.nextWaitNanos(), TimeUnit.NANOSECONDS);
                            break;
                        } catch (final TimeoutException e) {
                            if (idleTimeout.hasRunOut()) {
                                throw e;
                            }
                        }
                    }
                }
            } catch (final TimeoutException _) {
                // The worker is still running, and there is no way to stop it, since a thread blocked on class
                // loading or on a filesystem read cannot be interrupted. All that can be done is to report why the
                // scan cannot finish.
                interruptionChecker.setExecutionException(newWorkerTimeoutException());
                timeoutNanos = 0L;
            } catch (final CancellationException _) {
                if (log != null) {
                    log.log("~", "Worker thread was cancelled");
                }
            } catch (final InterruptedException _) {
                if (log != null) {
                    log.log("~", "Worker thread was interrupted");
                }
                // Interrupt other threads
                interruptionChecker.interrupt();
            } catch (final ExecutionException e) {
                // A failure is recorded rather than turned into an interruption, which would leave the interrupt
                // status of this thread set. Recording it is enough to stop the other workers, since check()
                // throws the recorded exception
                interruptionChecker.setExecutionException(e);
                timeoutNanos = 0L;
            }
        }
    }
}
