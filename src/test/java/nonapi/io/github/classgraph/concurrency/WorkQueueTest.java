package nonapi.io.github.classgraph.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.concurrency.WorkQueue.WorkUnitProcessor;
import nonapi.io.github.classgraph.utils.LogNode;

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
     * A worker that never finishes stops the work queue after the worker timeout, rather than blocking the calling
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
}
