package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link AutoCloseableExecutorService}. */
public class AutoCloseableExecutorServiceTest {
    /**
     * Clear the interrupt status of the test thread, which a test interrupts in order to close the executor service
     * on an interrupted thread, since the test threads are reused.
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
     * Closing the executor service on an interrupted thread keeps that thread interrupted and interrupts the tasks
     * that are still running, but does not mark the scan that ran on the executor service as interrupted.
     * {@link ClassGraph#scan(int)} closes the executor service after the scan has returned its {@link ScanResult},
     * so a caller that is interrupted just as the scan finishes closes the executor service on an interrupted
     * thread. The scan result keeps the interruption checker of the scan, and closing the scan result interrupts
     * the thread that closes it if that checker is marked as interrupted.
     *
     * @throws Exception
     *             if the test thread was interrupted while waiting.
     */
    @Test
    public void closingOnAnInterruptedThreadDoesNotMarkTheScanAsInterrupted() throws Exception {
        final var taskStarted = new CountDownLatch(1);
        final var taskWasInterrupted = new CountDownLatch(1);
        final ScanResult scanResult;
        try (var executorService = new AutoCloseableExecutorService(2)) {
            scanResult = new ClassGraph().enableClasspath().acceptPackages("io.github.classgraph.issues.issue933")
                    .scan(executorService, 2);
            // A task that is still running when the executor service is closed, so that close() has to wait for it
            executorService.submit(() -> {
                taskStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (final InterruptedException e) {
                    taskWasInterrupted.countDown();
                }
            });
            assertThat(taskStarted.await(10, TimeUnit.SECONDS)).isTrue();
            Thread.currentThread().interrupt();
        }
        // Thread.interrupted() both reports and clears the status
        assertThat(Thread.interrupted()).isTrue();
        assertThat(taskWasInterrupted.await(10, TimeUnit.SECONDS)).isTrue();

        final var closingThreadWasInterrupted = new AtomicBoolean();
        final var closingThread = new Thread(() -> {
            scanResult.close();
            closingThreadWasInterrupted.set(Thread.currentThread().isInterrupted());
        });
        closingThread.start();
        closingThread.join();
        assertThat(closingThreadWasInterrupted).isFalse();
    }
}
