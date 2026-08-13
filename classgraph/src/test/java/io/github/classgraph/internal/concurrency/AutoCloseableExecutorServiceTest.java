package io.github.classgraph.internal.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;

/** Tests for {@link AutoCloseableExecutorService}. */
public class AutoCloseableExecutorServiceTest {
    /**
     * Clear the interrupt status of the test thread, since the executor service interrupts the thread that a
     * failure is noticed on, and the test threads are reused.
     */
    @AfterEach
    public void clearInterruptStatus() {
        Thread.interrupted();
    }

    /** The executor service runs the tasks that are submitted to it, and shuts down when it is closed. */
    @Test
    public void tasksAreRunAndTheServiceIsShutDownWhenItIsClosed() throws InterruptedException {
        final var taskRan = new CountDownLatch(1);
        final AutoCloseableExecutorService executorService;
        try (var closeableExecutorService = new AutoCloseableExecutorService(2)) {
            executorService = closeableExecutorService;
            executorService.submit(taskRan::countDown);
            assertThat(taskRan.await(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(executorService.isShutdown()).isTrue();
        assertThat(executorService.isTerminated()).isTrue();
    }

    /** The worker threads are daemon threads, so that they cannot keep the JVM alive after the scan is over. */
    @Test
    public void theWorkerThreadsAreDaemonThreads() throws Exception {
        final var workerThread = new AtomicReference<Thread>();
        try (var executorService = new AutoCloseableExecutorService(1)) {
            executorService.submit(() -> workerThread.set(Thread.currentThread())).get();
        }
        assertThat(workerThread.get()).isNotNull();
        assertThat(workerThread.get().isDaemon()).isTrue();
        assertThat(workerThread.get().getName()).startsWith("ClassGraph-worker-");
    }

    /**
     * An exception thrown by a submitted task is recorded, so that the thread that started the work sees it even
     * though nothing calls get() on the task's future.
     */
    @Test
    public void anExceptionThrownByASubmittedTaskIsRecorded() throws InterruptedException {
        final var cause = new IllegalStateException("the reason");
        final AutoCloseableExecutorService executorService;
        try (var closeableExecutorService = new AutoCloseableExecutorService(1)) {
            executorService = closeableExecutorService;
            executorService.submit(() -> {
                throw cause;
            });
        }
        // The executor service is closed, so every task has finished
        assertThat(executorService.interruptionChecker.getExecutionException()).isNotNull();
        assertThat(InterruptionChecker.getCause(executorService.interruptionChecker.getExecutionException()))
                .isSameAs(cause);
    }

    /**
     * An exception thrown by an executed task is recorded too, even though execute() reports it in a different way
     * from submit().
     */
    @Test
    public void anExceptionThrownByAnExecutedTaskIsRecorded() {
        final var cause = new IllegalStateException("the reason");
        final AutoCloseableExecutorService executorService;
        try (var closeableExecutorService = new AutoCloseableExecutorService(1)) {
            executorService = closeableExecutorService;
            executorService.execute(() -> {
                throw cause;
            });
        }
        final var executionException = executorService.interruptionChecker.getExecutionException();
        assertThat(executionException).isNotNull().hasMessage("Uncaught exception");
        assertThat(InterruptionChecker.getCause(executionException)).isSameAs(cause);
    }

    /** A cancelled task interrupts the other threads, so that the whole scan stops rather than hanging. */
    @Test
    public void aCancelledTaskInterruptsTheOtherThreads() throws InterruptedException {
        final var taskStarted = new CountDownLatch(1);
        final var releaseTask = new CountDownLatch(1);
        final AutoCloseableExecutorService executorService;
        try (var closeableExecutorService = new AutoCloseableExecutorService(1)) {
            executorService = closeableExecutorService;
            final var future = executorService.submit(() -> {
                taskStarted.countDown();
                try {
                    releaseTask.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(taskStarted.await(10, TimeUnit.SECONDS)).isTrue();
            future.cancel(/* mayInterruptIfRunning = */ true);
        }
        // The task was cancelled rather than throwing, so there is nothing to report but the interruption
        assertThat(executorService.interruptionChecker.checkAndReturn()).isTrue();
        assertThat(executorService.interruptionChecker.getExecutionException()).isNull();
    }

    /** Every executor service has its own interruption checker, so that one failed scan cannot stop another. */
    @Test
    public void eachExecutorServiceHasItsOwnInterruptionChecker() {
        try (var first = new AutoCloseableExecutorService(1); var second = new AutoCloseableExecutorService(1)) {
            first.interruptionChecker.setExecutionException(new ExecutionException(new IllegalStateException()));
            assertThat(second.interruptionChecker.getExecutionException()).isNull();
        }
    }
}
