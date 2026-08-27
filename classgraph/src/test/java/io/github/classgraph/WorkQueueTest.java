package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
     * Clear the interrupt status of the test thread, since a work queue interrupts the thread that started it when
     * a worker fails, and the test threads are reused.
     */
    @AfterEach
    public void clearInterruptStatus() {
        Thread.interrupted();
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
            WorkQueue.runWorkQueue(workUnits, executorService, executorService.interruptionChecker,
                    numParallelTasks, WORKER_TIMEOUT_NANOS, /* log = */ null, workUnitProcessor);
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
                WorkQueue.runWorkQueue(workUnits, executorService, executorService.interruptionChecker,
                        /* numParallelTasks = */ 4, WORKER_TIMEOUT_NANOS, /* log = */ null,
                        (workUnit, workQueue, log) -> processed.add(workUnit));
                return null;
            });
            assertThat(workQueueTask.get(30, TimeUnit.SECONDS)).isNull();
        }

        assertThat(processed).containsExactlyInAnyOrderElementsOf(workUnits);
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
    }

    /**
     * A worker that never finishes stops the work queue after the worker timeout, rather than blocking the calling
     * thread forever. (Without the timeout, this test hangs.)
     */
    @Test
    public void aWorkerThatNeverFinishesTimesOut() {
        final var interruptionChecker = new InterruptionChecker();
        final var callingThread = Thread.currentThread();
        // Released at the end of the test, so that the stuck worker thread can finish
        final var releaseWorker = new CountDownLatch(1);
        // Counted down once the worker thread is stuck, so that the calling thread cannot stop the queue first
        final var workerIsStuck = new CountDownLatch(1);

        final Throwable thrown;
        try (var executorService = new AutoCloseableExecutorService(2)) {
            thrown = catchThrowable(() -> WorkQueue.runWorkQueue(List.of(1, 2), executorService,
                    interruptionChecker, /* numParallelTasks = */ 2, Duration.ofMillis(250).toNanos(),
                    /* log = */ null, (workUnit, workQueue, log) -> {
                        if (Thread.currentThread() == callingThread) {
                            // Stop the work queue, so that the calling thread reaches the completion barrier in
                            // WorkQueue#close() while the worker thread is still stuck
                            workerIsStuck.await();
                            throw new IllegalStateException("stopping the work queue");
                        }
                        workerIsStuck.countDown();
                        releaseWorker.await();
                    }));
            releaseWorker.countDown();
        }

        // The reason the calling thread stopped is reported, rather than being masked by the timeout
        assertThat(thrown).isInstanceOf(ExecutionException.class);
        assertThat(InterruptionChecker.getCause(thrown)).isInstanceOf(IllegalStateException.class)
                .hasMessage("stopping the work queue");
        // The timeout is recorded, so that a scan that only hits the timeout still reports why it could not finish
        final var timeout = interruptionChecker.getExecutionException();
        assertThat(timeout).isNotNull();
        assertThat(InterruptionChecker.getCause(timeout)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Timed out after 250ms waiting for a worker thread to finish");
    }
}
