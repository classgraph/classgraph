package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Tests that an interrupted scan throws {@link ClassGraphException}, as {@link ClassGraph#scan()} documents, rather
 * than letting the {@link InterruptedException} escape, and that the interruption is not swallowed in the process.
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
     * finished.
     */
    @Test
    public void interruptedScanIsReportedWithoutSwallowingTheInterruption() {
        final ExecutorService executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>()) {
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
            assertThatThrownBy(() -> new ClassGraph().scan(executorService, 2))
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
}
