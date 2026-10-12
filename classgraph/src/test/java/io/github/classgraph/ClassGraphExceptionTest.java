package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests that an interrupted scan throws {@link ClassGraphException}, as {@link ClassGraph#scan()} documents, rather
 * than letting the {@link InterruptedException} escape, and that the interruption is not swallowed in the process,
 * nor invented when the scan fails for some other reason.
 */
public class ClassGraphExceptionTest {
    /**
     * An interrupted scan is reported as an interruption, with the {@link InterruptedException} as the cause, and
     * the thread is left interrupted: throwing {@link InterruptedException} cleared the interrupt status, and
     * reporting the interruption as an unchecked exception would otherwise lose it, so a caller that catches
     * {@link ClassGraphException} would see a thread that no longer looks interrupted.
     *
     * <p>
     * The interruption is triggered from the {@link ExecutorService}, at the point where the scan starts a worker
     * thread, which the calling thread does from inside the scan, and the submitted worker is never run, so that
     * the calling thread is certain to be interrupted partway through the scan rather than after it has already
     * finished. Interrupting the thread before the scan starts would leave the outcome up to whether every step
     * before the first interruption check happens to preserve the interrupt status, which is not something the JDK
     * guarantees.
     */
    @Test
    public void interruptedScanIsReportedWithoutSwallowingTheInterruption() {
        final ExecutorService executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()) {
            @Override
            public <T> Future<T> submit(final Callable<T> task) {
                Thread.currentThread().interrupt();
                // Return a task that is never run, so that the calling thread does all the work itself, and finds
                // the interrupt status set the next time it checks for interruption
                return new FutureTask<>(task);
            }
        };
        try {
            // Two parallel tasks, so that the scan starts a worker thread, which is what triggers the interruption
            assertThatThrownBy(() -> new ClassGraph().enableClasspath().scan(executorService, 2))
                    .isInstanceOf(ClassGraphException.class).hasMessage("Scan interrupted")
                    .hasCauseInstanceOf(InterruptedException.class);
            // Thread.interrupted() both reports and clears the status, so this also stops the interruption leaking
            // into the next test that runs on this thread
            assertThat(Thread.interrupted()).isTrue();
        } finally {
            Thread.interrupted();
            executorService.shutdown();
        }
    }

    /** How the {@link ExecutorService} that a scan is given makes the scan fail. */
    enum ExecutorFailure {
        /**
         * Submitting the worker throws an unchecked exception. (Not a {@link RejectedExecutionException}, which
         * does not fail the scan, since the calling thread then does the work of the rejected worker.)
         */
        SUBMIT_THREW,
        /** The worker runs, but never reports that it finished, so the scan times out waiting for it. */
        TIMED_OUT,
        /** The worker runs, but reports that it threw an exception. */
        FAILED
    }

    /**
     * A scan that fails without being interrupted leaves the interrupt status of the calling thread clear, so that
     * the calling thread can carry on, and in particular can retry the scan, as the timeout message suggests.
     *
     * @param failure
     *            how the scan is made to fail.
     */
    @ParameterizedTest
    @EnumSource(ExecutorFailure.class)
    @Timeout(value = 30, threadMode = ThreadMode.SEPARATE_THREAD)
    public void aFailedScanDoesNotInterruptTheCallingThread(final ExecutorFailure failure) {
        final ExecutorService executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()) {
            @Override
            public <T> Future<T> submit(final Callable<T> task) {
                if (failure == ExecutorFailure.SUBMIT_THREW) {
                    throw new IllegalStateException("the reason");
                }
                // Run the worker here, so that it has finished before the scan waits for it, and only the result
                // that it reports is wrong
                try {
                    task.call();
                } catch (final Exception e) {
                    throw new IllegalStateException(e);
                }
                return failure == ExecutorFailure.TIMED_OUT ? new CompletableFuture<>()
                        : CompletableFuture.failedFuture(new IllegalStateException("the reason"));
            }
        };
        try {
            assertThatThrownBy(() -> new ClassGraph().enableClasspath().setWorkerTimeout(Duration.ofMillis(10))
                    .scan(executorService, 2)).isInstanceOf(ClassGraphException.class)
                    .hasMessage("Uncaught exception during scan");
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            try (var scanResult = new ClassGraph().enableClasspath().scan(1)) {
                assertThat(scanResult.getClasspathURIs()).isNotEmpty();
            }
        } finally {
            Thread.interrupted();
            executorService.shutdown();
        }
    }
}
