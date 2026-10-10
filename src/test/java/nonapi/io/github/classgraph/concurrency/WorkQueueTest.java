package nonapi.io.github.classgraph.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.concurrency.WorkQueue.WorkUnitProcessor;
import nonapi.io.github.classgraph.utils.LogNode;

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
        final List<Integer> workUnits = java.util.Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7);
        final Set<Integer> processed = ConcurrentHashMap.newKeySet();

        try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(1)) {
            // Run the work queue on the executor's only thread, asking for more tasks than it has threads
            final Future<?> workQueueTask = executorService.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    WorkQueue.runWorkQueue(workUnits, executorService, executorService.interruptionChecker,
                            /* numParallelTasks = */ 4, Long.MAX_VALUE, /* log = */ (LogNode) null,
                            new WorkUnitProcessor<Integer>() {
                                @Override
                                public void processWorkUnit(final Integer workUnit,
                                        final WorkQueue<Integer> workQueue, final LogNode log) {
                                    processed.add(workUnit);
                                }
                            });
                    return null;
                }
            });
            assertThat(workQueueTask.get(30, TimeUnit.SECONDS)).isNull();
        }

        assertThat(processed).containsExactlyInAnyOrderElementsOf(workUnits);
    }

    /**
     * A worker that never finishes stops the work queue after the worker timeout when the calling thread has failed
     * and is waiting in {@link WorkQueue#close()} for the workers to finish, rather than blocking the calling
     * thread forever. (Without the timeout, this test hangs.)
     */
    @Test
    public void aWorkerThatNeverFinishesTimesOut() {
        final InterruptionChecker interruptionChecker = new InterruptionChecker();
        final Thread callingThread = Thread.currentThread();
        // Released at the end of the test, so that the stuck worker thread can finish
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        // Counted down once the worker thread is stuck, so that the calling thread cannot stop the queue first
        final CountDownLatch workerIsStuck = new CountDownLatch(1);

        final Throwable thrown;
        try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(2)) {
            thrown = catchThrowable(new ThrowingCallable() {
                @Override
                public void call() throws Throwable {
                    WorkQueue.runWorkQueue(Arrays.asList(1, 2), executorService, interruptionChecker,
                            /* numParallelTasks = */ 2, TimeUnit.MILLISECONDS.toNanos(250),
                            /* log = */ (LogNode) null, new WorkUnitProcessor<Integer>() {
                                @Override
                                public void processWorkUnit(final Integer workUnit,
                                        final WorkQueue<Integer> workQueue, final LogNode log)
                                        throws InterruptedException {
                                    if (Thread.currentThread() == callingThread) {
                                        // Stop the work queue, so that the calling thread reaches the completion
                                        // barrier in WorkQueue#close() while the worker thread is still stuck
                                        workerIsStuck.await();
                                        throw new IllegalStateException("stopping the work queue");
                                    }
                                    workerIsStuck.countDown();
                                    releaseWorker.await();
                                }
                            });
                }
            });
            releaseWorker.countDown();
        }

        // The reason the calling thread stopped is reported, rather than being masked by the timeout
        assertThat(thrown).isInstanceOf(ExecutionException.class);
        assertThat(InterruptionChecker.getCause(thrown)).isInstanceOf(IllegalStateException.class)
                .hasMessage("stopping the work queue");
        // The timeout is recorded, so that a scan that only hits the timeout still reports why it could not finish
        final ExecutionException timeout = interruptionChecker.getExecutionException();
        assertThat(timeout).isNotNull();
        assertThat(InterruptionChecker.getCause(timeout)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Timed out after 250ms waiting for a worker thread to finish");
    }

    /**
     * A worker that never finishes stops the work queue after the worker timeout when the calling thread has run
     * out of work and is waiting for more, which is how a scan gets stuck when nothing has failed. The calling
     * thread must not wait for more work forever, since the only other place the timeout is applied is
     * {@link WorkQueue#close()}, which the calling thread does not reach until the work loop ends.
     */
    @Test
    public void aStuckWorkerTimesOutWhileTheCallingThreadIsIdle() {
        final InterruptionChecker interruptionChecker = new InterruptionChecker();
        // Released at the end of the test, so that the stuck worker thread can finish
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        // Counted down once the worker thread is stuck, so that the calling thread cannot take both work units
        final CountDownLatch workerIsStuck = new CountDownLatch(1);

        try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(2)) {
            try {
                // Run the work queue on another thread, so that the test fails rather than hangs if the calling
                // thread waits for more work forever
                final Throwable thrown = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                    final Thread callingThread = Thread.currentThread();
                    final Throwable thrownByWorkQueue = catchThrowable(() -> WorkQueue.runWorkQueue(
                            Arrays.asList(1, 2), executorService, interruptionChecker, /* numParallelTasks = */ 2,
                            TimeUnit.MILLISECONDS.toNanos(250), /* log = */ (LogNode) null,
                            (workUnit, workQueue, log) -> {
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
                        .hasMessageContaining("Timed out after 250ms waiting for a worker thread to finish");
                // The timeout is recorded too, so that the other workers stop
                assertThat(interruptionChecker.getExecutionException()).isSameAs(thrown);
            } finally {
                releaseWorker.countDown();
            }
        }
    }

    /**
     * The worker timeout counts from when a work unit last finished, not from when the calling thread ran out of
     * work, so a work queue whose workers are still finishing work units is not stopped, however long the workers
     * take altogether.
     *
     * @throws Exception
     *             if the work queue failed.
     */
    @Test
    public void workersThatKeepFinishingWorkUnitsDoNotTimeOut() throws Exception {
        final int numWorkers = 4;
        final InterruptionChecker interruptionChecker = new InterruptionChecker();
        final Thread callingThread = Thread.currentThread();
        final AtomicInteger nextWorkerIndex = new AtomicInteger();
        // Counted down by each worker once it has a work unit, so that the calling thread then has no work left
        final CountDownLatch everyWorkerHasAWorkUnit = new CountDownLatch(numWorkers);
        // Each worker finishes its work unit only after the previous worker has finished its own
        final List<CountDownLatch> workerFinished = new ArrayList<>();
        final List<Integer> workUnits = new ArrayList<>();
        for (int i = 0; i < numWorkers; i++) {
            workerFinished.add(new CountDownLatch(1));
            workUnits.add(i);
        }
        workUnits.add(numWorkers);
        final Set<Integer> processed = ConcurrentHashMap.newKeySet();

        try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(numWorkers)) {
            // After the calling thread runs out of work, the workers take 1200ms altogether, which is longer than
            // the 1000ms timeout, but one of them finishes a work unit every 300ms
            WorkQueue.runWorkQueue(workUnits, executorService, interruptionChecker,
                    /* numParallelTasks = */ numWorkers + 1, TimeUnit.MILLISECONDS.toNanos(1000),
                    /* log = */ (LogNode) null, (workUnit, workQueue, log) -> {
                        if (Thread.currentThread() == callingThread) {
                            assertThat(everyWorkerHasAWorkUnit.await(10, TimeUnit.SECONDS)).isTrue();
                        } else {
                            final int workerIndex = nextWorkerIndex.getAndIncrement();
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
}
