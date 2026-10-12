package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import io.github.classgraph.WorkQueue.WorkUnitProcessor;
import io.github.classgraph.base.internal.concurrency.InterruptionChecker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link WorkQueue}. */
public class WorkQueueTest {
    /**
     * Check that the work queue left the interrupt status of the test thread clear, since a failure is not an
     * interruption, and clear it either way, since the test threads are reused.
     */
    @AfterEach
    public void checkInterruptStatusIsClear() {
        assertThat(Thread.interrupted()).as("the interrupt status of the test thread").isFalse();
    }

    /** The worker timeout to use in tests that are not testing the timeout itself. */
    private static final long WORKER_TIMEOUT_NANOS = ClassGraph.DEFAULT_WORKER_TIMEOUT.toNanos();

    /**
     * Run a work queue over the given work units.
     *
     * @param workUnits
     *            the work units to process.
     * @param numParallelTasks
     *            the number of tasks to process them with.
     * @param workUnitProcessor
     *            the work unit processor.
     * @throws InterruptedException
     *             if the work was interrupted.
     * @throws ExecutionException
     *             if a worker threw an uncaught exception.
     */
    private static void runWorkQueue(final List<Integer> workUnits, final int numParallelTasks,
            final WorkUnitProcessor<Integer> workUnitProcessor) throws InterruptedException, ExecutionException {
        try (var executorService = new AutoCloseableExecutorService(numParallelTasks)) {
            WorkQueue.runWorkQueue(workUnits, executorService, new InterruptionChecker(), numParallelTasks,
                    WORKER_TIMEOUT_NANOS, /* log = */ null, workUnitProcessor);
        }
    }

    /**
     * Every work unit is processed exactly once, however many tasks are processing them. One task means the work is
     * all done on the calling thread; more tasks than work units means some workers only ever see a poison pill.
     *
     * @param numParallelTasks
     *            the number of tasks to process the work units with.
     * @throws Exception
     *             if the work queue failed.
     */
    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 4, 32 })
    public void everyWorkUnitIsProcessedExactlyOnce(final int numParallelTasks) throws Exception {
        final var workUnits = IntStream.range(0, 16).boxed().toList();
        final Set<Integer> processed = ConcurrentHashMap.newKeySet();
        final var numTimesProcessed = new AtomicInteger();

        runWorkQueue(workUnits, numParallelTasks, (workUnit, workQueue, log) -> {
            processed.add(workUnit);
            numTimesProcessed.incrementAndGet();
        });

        assertThat(processed).containsExactlyInAnyOrderElementsOf(workUnits);
        assertThat(numTimesProcessed).hasValue(workUnits.size());
    }

    /**
     * A worker can add more work units while it is processing one, and those are processed too. This is how a
     * directory scan reaches the files below the directories it starts from.
     *
     * @throws Exception
     *             if the work queue failed.
     */
    @Test
    public void workUnitsAddedByAWorkerAreProcessedToo() throws Exception {
        final Set<Integer> processed = ConcurrentHashMap.newKeySet();

        // Each work unit under 8 adds its two children, so this walks a binary tree from its root
        runWorkQueue(List.of(1), 4, (workUnit, workQueue, log) -> {
            processed.add(workUnit);
            if (workUnit < 8) {
                workQueue.addWorkUnits(List.of(workUnit * 2, workUnit * 2 + 1));
            }
        });

        assertThat(processed).containsExactlyInAnyOrderElementsOf(IntStream.range(1, 16).boxed().toList());
    }

    /**
     * The work is all done even when the {@link java.util.concurrent.ExecutorService} has no free thread to run any
     * worker on, which is the case when the work queue is started by a task that is itself running on a
     * single-threaded {@link java.util.concurrent.ExecutorService}. The thread that starts the work queue processes
     * the work units itself in that case, and must not then wait for workers that can never start.
     *
     * @throws Exception
     *             if the work queue failed.
     */
    @Test
    public void workIsCompletedWhenTheExecutorServiceHasNoFreeThread() throws Exception {
        final var workUnits = IntStream.range(0, 16).boxed().toList();
        final Set<Integer> processed = ConcurrentHashMap.newKeySet();

        try (var executorService = new AutoCloseableExecutorService(1)) {
            // Run the work queue on the executor's only thread, asking for more tasks than it has threads
            final Future<?> workQueueTask = executorService.submit(() -> {
                WorkQueue.runWorkQueue(workUnits, executorService, new InterruptionChecker(),
                        /* numParallelTasks = */ 4, WORKER_TIMEOUT_NANOS, /* log = */ null,
                        (workUnit, workQueue, log) -> processed.add(workUnit));
                return null;
            });
            assertThat(workQueueTask.get(30, TimeUnit.SECONDS)).isNull();
        }

        assertThat(processed).containsExactlyInAnyOrderElementsOf(workUnits);
    }

    /**
     * A worker that the {@link ExecutorService} rejects is not an error, since the calling thread processes every
     * work unit that no worker takes. A pool with a bounded queue rejects the workers that it has neither a free
     * thread nor room in its queue for, and a pool that has been shut down rejects them all.
     *
     * @param pool
     *            the kind of pool to run the workers on.
     * @throws Exception
     *             if the work queue failed.
     */
    @ParameterizedTest
    @ValueSource(strings = { "no queue", "a full queue", "shut down" })
    public void workIsCompletedWhenTheExecutorServiceRejectsWorkers(final String pool) throws Exception {
        final ExecutorService executorService = switch (pool) {
        // The first worker is started on the only thread, and the second is rejected
        case "no queue" -> new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>());
        // The first worker is started on the only thread, the second is queued, and the third is rejected
        case "a full queue" -> new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
        // The first worker is rejected
        default -> {
            final var shutDownPool = Executors.newFixedThreadPool(1);
            shutDownPool.shutdown();
            yield shutDownPool;
        }
        };
        final var workUnits = IntStream.range(0, 1000).boxed().toList();
        final Set<Integer> processed = ConcurrentHashMap.newKeySet();
        final var numTimesProcessed = new AtomicInteger();
        final var interruptionChecker = new InterruptionChecker();

        try {
            WorkQueue.runWorkQueue(workUnits, executorService, interruptionChecker, /* numParallelTasks = */ 4,
                    WORKER_TIMEOUT_NANOS, /* log = */ null, (workUnit, workQueue, log) -> {
                        processed.add(workUnit);
                        numTimesProcessed.incrementAndGet();
                    });
        } finally {
            executorService.shutdown();
        }

        assertThat(processed).containsExactlyInAnyOrderElementsOf(workUnits);
        assertThat(numTimesProcessed).hasValue(workUnits.size());
        assertThat(interruptionChecker.getExecutionException()).isNull();
    }

    /** A work queue with nothing in it completes without starting any workers. */
    @Test
    public void anEmptyWorkQueueDoesNothing() {
        assertThatCode(() -> runWorkQueue(List.of(), 4, (workUnit, workQueue, log) -> {
            throw new AssertionError("Should not be called");
        })).doesNotThrowAnyException();
    }

    /** The queue defensively rejects non-positive parallelism even when there is no work. */
    @Test
    public void nonPositiveParallelismIsRejected() {
        final var executor = Executors.newSingleThreadExecutor();
        try {
            for (final int parallelism : new int[] { 0, -1 }) {
                assertThatThrownBy(() -> WorkQueue.runWorkQueue(List.of(), executor, new InterruptionChecker(),
                        parallelism, WORKER_TIMEOUT_NANOS, /* log = */ null, (workUnit, workQueue, log) -> {
                        })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 1");
            }
        } finally {
            executor.shutdown();
        }
    }

    /** A null work unit is rejected, since the queue uses null internally to tell a worker to stop. */
    @Test
    public void aNullWorkUnitIsRejected() {
        // A null among the work units the queue is started with is rejected before any of them are processed
        assertThatThrownBy(() -> runWorkQueue(Arrays.asList(1, null), 1, (workUnit, workQueue, log) -> {
            throw new AssertionError("Should not be called");
        })).isInstanceOf(NullPointerException.class).hasMessage("workUnit cannot be null");

        // A null added by a worker is rejected too, and reaches the caller the same way as any other unchecked
        // exception thrown while processing a work unit
        assertThatThrownBy(() -> runWorkQueue(List.of(1), 1, (workUnit, workQueue, log) -> //
        workQueue.addWorkUnit(null))).isInstanceOf(ExecutionException.class).cause()
                .isInstanceOf(NullPointerException.class).hasMessage("workUnit cannot be null");
    }

    /**
     * An unchecked exception thrown while processing a work unit is wrapped in an execution exception, so that the
     * thread that started the work queue sees it.
     */
    @Test
    public void anUncheckedExceptionIsWrappedInAnExecutionException() {
        final var cause = new IllegalStateException("the reason");

        // One task, so the work is all done on this thread, and the exception is thrown from here
        assertThatThrownBy(() -> runWorkQueue(List.of(1), 1, (workUnit, workQueue, log) -> {
            throw cause;
        })).isInstanceOf(ExecutionException.class).hasMessage("Worker thread threw unchecked exception")
                .hasCause(cause);
    }

    /**
     * An unchecked exception thrown by a worker thread stops the whole work queue, whichever thread it was thrown
     * on.
     */
    @Test
    public void anUncheckedExceptionOnAWorkerThreadStopsTheWorkQueue() {
        final var cause = new IllegalStateException("the reason");
        final var interruptionChecker = new InterruptionChecker();
        final var numProcessed = new AtomicInteger();

        final Throwable thrown;
        try (var executorService = new AutoCloseableExecutorService(4)) {
            thrown = catchThrowable(() -> WorkQueue.runWorkQueue(IntStream.range(0, 1000).boxed().toList(),
                    executorService, interruptionChecker, 4, WORKER_TIMEOUT_NANOS, /* log = */ null,
                    (workUnit, workQueue, log) -> {
                        numProcessed.incrementAndGet();
                        throw cause;
                    }));
        }

        // The failure reaches the caller as an execution exception if it happened to be thrown on the calling
        // thread, and through the shared interruption checker if it was thrown on a worker thread
        final var reported = thrown != null ? thrown : interruptionChecker.getExecutionException();
        assertThat(reported).isInstanceOf(ExecutionException.class);
        assertThat(InterruptionChecker.getCause(reported)).isSameAs(cause);
        // The remaining work units are dropped rather than processed, so the queue stops promptly
        assertThat(numProcessed).hasValueLessThan(1000);
    }

    /**
     * An exception thrown on a worker thread is recorded by the work queue itself, in the interruption checker it
     * was given, so that the failure is reported whatever {@link java.util.concurrent.ExecutorService} ran the
     * worker. The calling thread does not fail here, so the recorded exception is the only report of the failure.
     *
     * @throws Exception
     *             if the work queue threw, which it should not, since the calling thread did not fail.
     */
    @Test
    public void anExceptionOnAWorkerThreadIsRecordedByTheWorkQueue() throws Exception {
        final var cause = new IllegalStateException("the reason");
        final var interruptionChecker = new InterruptionChecker();
        final var callingThread = Thread.currentThread();
        final var workerFailed = new CountDownLatch(1);

        final var executorService = Executors.newFixedThreadPool(1);
        try {
            WorkQueue.runWorkQueue(List.of(1, 2), executorService, interruptionChecker, /* numParallelTasks = */ 2,
                    WORKER_TIMEOUT_NANOS, /* log = */ null, (workUnit, workQueue, log) -> {
                        if (Thread.currentThread() == callingThread) {
                            // Wait for the worker thread to fail, so that the calling thread cannot fail first
                            assertThat(workerFailed.await(10, TimeUnit.SECONDS)).isTrue();
                        } else {
                            workerFailed.countDown();
                            throw cause;
                        }
                    });
        } finally {
            executorService.shutdown();
        }

        final var recorded = interruptionChecker.getExecutionException();
        assertThat(recorded).isNotNull();
        assertThat(InterruptionChecker.getCause(recorded)).isSameAs(cause);
    }

    /** Interruption stops the whole work queue, leaving the work units that were still queued unprocessed. */
    @Test
    public void interruptionStopsTheWorkQueue() {
        final var interruptionChecker = new InterruptionChecker();
        final var numProcessed = new AtomicInteger();

        // One task, so the work is all done on this thread, and the interruption is thrown from here
        try (var executorService = new AutoCloseableExecutorService(1)) {
            assertThatThrownBy(() -> WorkQueue.runWorkQueue(IntStream.range(0, 1000).boxed().toList(),
                    executorService, interruptionChecker, 1, WORKER_TIMEOUT_NANOS, /* log = */ null,
                    (workUnit, workQueue, log) -> {
                        numProcessed.incrementAndGet();
                        interruptionChecker.interrupt();
                    })).isInstanceOf(InterruptedException.class);
        }

        assertThat(numProcessed).hasValue(1);
        // The interruption is not swallowed
        assertThat(Thread.interrupted()).isTrue();
    }

    /**
     * A worker that never finishes stops the work queue after the worker timeout when the calling thread has failed
     * and is waiting in {@link WorkQueue#close()} for the workers to finish, rather than blocking the calling
     * thread forever. (Without the timeout, this test hangs.)
     */
    @Test
    public void aWorkerThatNeverFinishesTimesOut() {
        final var interruptionChecker = new InterruptionChecker();
        // Released at the end of the test, so that the stuck worker thread can finish
        final var releaseWorker = new CountDownLatch(1);
        // Counted down once the worker thread is stuck, so that the calling thread cannot stop the queue first
        final var workerIsStuck = new CountDownLatch(1);

        final Throwable thrown;
        try (var executorService = new AutoCloseableExecutorService(2)) {
            try {
                // Run the work queue on another thread, so that the test fails rather than hangs if close() waits
                // for the stuck worker forever
                thrown = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                    final var callingThread = Thread.currentThread();
                    return catchThrowable(() -> WorkQueue.runWorkQueue(List.of(1, 2), executorService,
                            interruptionChecker, /* numParallelTasks = */ 2, Duration.ofMillis(250).toNanos(),
                            /* log = */ null, (workUnit, workQueue, log) -> {
                                if (Thread.currentThread() == callingThread) {
                                    // Stop the work queue, so that the calling thread reaches the completion
                                    // barrier in WorkQueue#close() while the worker thread is still stuck
                                    workerIsStuck.await();
                                    throw new IllegalStateException("stopping the work queue");
                                }
                                workerIsStuck.countDown();
                                releaseWorker.await();
                            }));
                });
            } finally {
                releaseWorker.countDown();
            }
        }

        // The reason the calling thread stopped is reported, rather than being masked by the timeout
        assertThat(thrown).isInstanceOf(ExecutionException.class);
        assertThat(InterruptionChecker.getCause(thrown)).isInstanceOf(IllegalStateException.class)
                .hasMessage("stopping the work queue");
        // The timeout is recorded, so that a scan that only hits the timeout still reports why it could not finish
        final var timeout = interruptionChecker.getExecutionException();
        assertThat(timeout).isNotNull();
        assertThat(InterruptionChecker.getCause(timeout)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Timed out after 250ms with no progress, waiting for a worker thread to finish");
    }

    /**
     * A worker that never finishes stops the work queue after the worker timeout when the calling thread has run
     * out of work and is waiting for more, which is how a scan gets stuck when nothing has failed. The calling
     * thread must not wait for more work forever, since the only other place the timeout is applied is
     * {@link WorkQueue#close()}, which the calling thread does not reach until the work loop ends.
     */
    @Test
    public void aStuckWorkerTimesOutWhileTheCallingThreadIsIdle() {
        final var interruptionChecker = new InterruptionChecker();
        // Released at the end of the test, so that the stuck worker thread can finish
        final var releaseWorker = new CountDownLatch(1);
        // Counted down once the worker thread is stuck, so that the calling thread cannot take both work units
        final var workerIsStuck = new CountDownLatch(1);

        try (var executorService = new AutoCloseableExecutorService(2)) {
            try {
                // Run the work queue on another thread, so that the test fails rather than hangs if the calling
                // thread waits for more work forever
                final var thrown = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                    final var callingThread = Thread.currentThread();
                    final var thrownByWorkQueue = catchThrowable(() -> WorkQueue.runWorkQueue(List.of(1, 2),
                            executorService, interruptionChecker, /* numParallelTasks = */ 2,
                            Duration.ofMillis(250).toNanos(), /* log = */ null, (workUnit, workQueue, log) -> {
                                if (Thread.currentThread() == callingThread) {
                                    // Finish this work unit once the worker thread is stuck on the other one,
                                    // leaving the calling thread with nothing to do but wait for more work
                                    assertThat(workerIsStuck.await(10, TimeUnit.SECONDS)).isTrue();
                                } else {
                                    workerIsStuck.countDown();
                                    releaseWorker.await();
                                }
                            }));
                    // A timeout is a failure, not an interruption
                    assertThat(callingThread.isInterrupted()).as("the interrupt status of the calling thread")
                            .isFalse();
                    return thrownByWorkQueue;
                });

                assertThat(thrown).isInstanceOf(ExecutionException.class);
                assertThat(InterruptionChecker.getCause(thrown)).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(
                                "Timed out after 250ms with no progress, waiting for a worker thread to finish");
                // The timeout is recorded too, so that the other workers stop
                assertThat(interruptionChecker.getExecutionException()).isSameAs(thrown);
            } finally {
                releaseWorker.countDown();
            }
        }
    }

    /**
     * A thread that stops because a failure was recorded in the interruption checker stops the threads that are
     * waiting for more work too, even while another thread is stuck on a work unit. Otherwise a waiting worker
     * holds a thread of the caller's {@link ExecutorService} for as long as the stuck work unit takes, and a
     * waiting calling thread, which waits indefinitely when there is no worker timeout, does not return at all.
     * This is the case when a thread times out waiting for another thread that is stuck opening a classpath
     * element: it records the timeout, finishes its work unit normally, and stops at its next check.
     *
     * @throws Exception
     *             if the test was interrupted.
     */
    @Test
    public void aRecordedFailureStopsTheThreadsThatAreWaitingForWork() throws Exception {
        final var interruptionChecker = new InterruptionChecker();
        final Set<Thread> poolThreads = ConcurrentHashMap.newKeySet();
        final var executorService = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                runnable -> {
                    final var thread = new Thread(runnable);
                    poolThreads.add(thread);
                    return thread;
                });
        // Released at the end of the test, so that the stuck work unit can finish
        final var releaseStuckWorkUnit = new CountDownLatch(1);
        final var stuckThread = new AtomicReference<Thread>();
        final var stuckThreadStarted = new CountDownLatch(1);
        final var failureRecorded = new CountDownLatch(1);
        final var workQueueStopped = new CountDownLatch(1);
        final var runWorkQueueThread = new AtomicReference<Thread>();
        final var callingThread = new Thread(() -> {
            runWorkQueueThread.set(Thread.currentThread());
            try {
                WorkQueue.runWorkQueue(List.of(1, 2), executorService, interruptionChecker,
                        /* numParallelTasks = */ 3, /* workerTimeoutNanos = */ Long.MAX_VALUE, /* log = */ null,
                        (workUnit, workQueue, log) -> {
                            final var currentThread = Thread.currentThread();
                            // The first worker thread to take a work unit gets stuck on it. The calling thread
                            // never does, since then runWorkQueue could not return until the work unit finished
                            if (poolThreads.contains(currentThread)
                                    && stuckThread.compareAndSet(null, currentThread)) {
                                stuckThreadStarted.countDown();
                                releaseStuckWorkUnit.await();
                                return;
                            }
                            // Wait until the third thread is waiting for more work, then record a failure and
                            // finish this work unit normally
                            assertThat(stuckThreadStarted.await(10, TimeUnit.SECONDS)).isTrue();
                            final var otherThreads = new HashSet<>(poolThreads);
                            otherThreads.add(runWorkQueueThread.get());
                            otherThreads.remove(currentThread);
                            otherThreads.remove(stuckThread.get());
                            assertThat(otherThreads).hasSize(1);
                            final var idleThread = otherThreads.iterator().next();
                            final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                            while (idleThread.getState() != Thread.State.WAITING) {
                                assertThat(System.nanoTime()).as("time waiting for the idle thread")
                                        .isLessThan(deadline);
                                Thread.onSpinWait();
                            }
                            interruptionChecker.setExecutionException(
                                    new ExecutionException(new IllegalStateException("test")));
                            failureRecorded.countDown();
                        });
            } catch (final InterruptedException | ExecutionException e) {
                // The failure that the work queue stopped for
            } finally {
                workQueueStopped.countDown();
            }
        });
        try {
            callingThread.start();
            assertThat(failureRecorded.await(10, TimeUnit.SECONDS)).as("failure recorded").isTrue();

            // runWorkQueue returns, and the only pool thread still busy is the one that is stuck
            assertThat(workQueueStopped.await(10, TimeUnit.SECONDS)).as("runWorkQueue returned").isTrue();
            final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (executorService.getActiveCount() > 1 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(executorService.getActiveCount()).as("busy pool threads").isEqualTo(1);
        } finally {
            releaseStuckWorkUnit.countDown();
            executorService.shutdown();
            assertThat(executorService.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            callingThread.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(callingThread.isAlive()).isFalse();
        }
    }

    /**
     * The worker timeout counts only time in which no work unit finished, not the time since the calling thread ran
     * out of work, so a work queue whose workers are still finishing work units is not stopped, however long the
     * workers take altogether.
     *
     * @throws Exception
     *             if the work queue failed.
     */
    @Test
    public void workersThatKeepFinishingWorkUnitsDoNotTimeOut() throws Exception {
        final var numWorkers = 4;
        final var interruptionChecker = new InterruptionChecker();
        final var callingThread = Thread.currentThread();
        final var nextWorkerIndex = new AtomicInteger();
        // Counted down by each worker once it has a work unit, so that the calling thread then has no work left
        final var everyWorkerHasAWorkUnit = new CountDownLatch(numWorkers);
        // Each worker finishes its work unit only after the previous worker has finished its own
        final var workerFinished = IntStream.range(0, numWorkers).mapToObj(i -> new CountDownLatch(1)).toList();
        final Set<Integer> processed = ConcurrentHashMap.newKeySet();

        try (var executorService = new AutoCloseableExecutorService(numWorkers)) {
            // After the calling thread runs out of work, the workers take 1200ms altogether, which is longer than
            // the 1000ms timeout, but one of them finishes a work unit every 300ms
            WorkQueue.runWorkQueue(IntStream.range(0, numWorkers + 1).boxed().toList(), executorService,
                    interruptionChecker, /* numParallelTasks = */ numWorkers + 1, Duration.ofMillis(1000).toNanos(),
                    /* log = */ null, (workUnit, workQueue, log) -> {
                        if (Thread.currentThread() == callingThread) {
                            assertThat(everyWorkerHasAWorkUnit.await(10, TimeUnit.SECONDS)).isTrue();
                        } else {
                            final var workerIndex = nextWorkerIndex.getAndIncrement();
                            everyWorkerHasAWorkUnit.countDown();
                            assertThat(everyWorkerHasAWorkUnit.await(10, TimeUnit.SECONDS)).isTrue();
                            if (workerIndex > 0) {
                                assertThat(workerFinished.get(workerIndex - 1).await(10, TimeUnit.SECONDS))
                                        .isTrue();
                            }
                            Thread.sleep(300);
                            workerFinished.get(workerIndex).countDown();
                        }
                        processed.add(workUnit);
                    });
        }

        assertThat(processed).hasSize(numWorkers + 1);
        assertThat(interruptionChecker.getExecutionException()).isNull();
    }

    /**
     * A worker that reports progress from within a work unit is not timed out while the calling thread waits for
     * more work, however long the work unit takes, which is the case for a slow download of a jarfile. Here the
     * work unit takes four times the worker timeout, and reports progress ten times per timeout.
     */
    @Test
    public void aWorkerThatKeepsReportingProgressDoesNotTimeOut() {
        final var interruptionChecker = new InterruptionChecker();
        // Counted down once the worker thread has a work unit, so that the calling thread cannot take both
        final var workerStarted = new CountDownLatch(1);
        final Set<Integer> processed = ConcurrentHashMap.newKeySet();

        try (var executorService = new AutoCloseableExecutorService(2)) {
            final var thrown = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                final var callingThread = Thread.currentThread();
                return catchThrowable(() -> WorkQueue.runWorkQueue(List.of(1, 2), executorService,
                        interruptionChecker, /* numParallelTasks = */ 2, Duration.ofMillis(250).toNanos(),
                        /* log = */ null, (workUnit, workQueue, log) -> {
                            if (Thread.currentThread() == callingThread) {
                                // Finish this work unit once the worker has the other one, leaving the calling
                                // thread with nothing to do but wait for more work
                                assertThat(workerStarted.await(10, TimeUnit.SECONDS)).isTrue();
                            } else {
                                workerStarted.countDown();
                                for (var i = 0; i < 40; i++) {
                                    Thread.sleep(25);
                                    interruptionChecker.progress();
                                }
                            }
                            processed.add(workUnit);
                        }));
            });
            assertThat(thrown).isNull();
        }

        assertThat(processed).containsExactlyInAnyOrder(1, 2);
        assertThat(interruptionChecker.getExecutionException()).isNull();
    }

    /**
     * {@link WorkQueue#close()} waits for a worker that reports progress from within a work unit, however long the
     * work unit takes, rather than giving up after the worker timeout. Here the calling thread fails, so it reaches
     * {@link WorkQueue#close()} while the worker is still running a work unit that takes four times the worker
     * timeout, and reports progress ten times per timeout. A timeout here would be recorded in the
     * {@link InterruptionChecker}.
     */
    @Test
    public void closeWaitsForAWorkerThatKeepsReportingProgress() {
        final var interruptionChecker = new InterruptionChecker();
        final var failure = new IllegalStateException("stopping the work queue");
        // Counted down once the worker thread has a work unit, so that the calling thread cannot take both
        final var workerStarted = new CountDownLatch(1);
        // Counted down once the worker thread has finished its work unit
        final var workerFinished = new CountDownLatch(1);

        final Throwable thrown;
        try (var executorService = new AutoCloseableExecutorService(2)) {
            thrown = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                final var callingThread = Thread.currentThread();
                return catchThrowable(() -> WorkQueue.runWorkQueue(List.of(1, 2), executorService,
                        interruptionChecker, /* numParallelTasks = */ 2, Duration.ofMillis(250).toNanos(),
                        /* log = */ null, (workUnit, workQueue, log) -> {
                            if (Thread.currentThread() == callingThread) {
                                assertThat(workerStarted.await(10, TimeUnit.SECONDS)).isTrue();
                                throw failure;
                            }
                            workerStarted.countDown();
                            for (var i = 0; i < 40; i++) {
                                Thread.sleep(25);
                                interruptionChecker.progress();
                            }
                            workerFinished.countDown();
                        }));
            });
        }

        assertThat(thrown).isInstanceOf(ExecutionException.class);
        assertThat(InterruptionChecker.getCause(thrown)).isSameAs(failure);
        // close() returned only once the worker had finished, and did not record a timeout
        assertThat(workerFinished.getCount()).isZero();
        assertThat(interruptionChecker.getExecutionException()).isNull();
    }
}
