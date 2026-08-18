package nonapi.io.github.classgraph.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

/** Tests for {@link InterruptionChecker}. */
public class InterruptionCheckerTest {
    /**
     * A worker that was interrupted was cancelled, not broken, so the interruption is recorded as an interruption
     * rather than as an exception -- otherwise the next check reports the scan as having failed, since a recorded
     * exception is thrown ahead of the interruption check, and a genuine failure on another thread is masked, since
     * only the first exception is recorded.
     */
    @Test
    public void anInterruptedWorkerIsRecordedAsAnInterruption() {
        final InterruptionChecker interruptionChecker = new InterruptionChecker();
        interruptionChecker
                .setExecutionException(new ExecutionException("Uncaught exception", new InterruptedException()));

        assertThat(interruptionChecker.getExecutionException()).isNull();
        assertThat(interruptionChecker.checkAndReturn()).isTrue();
        assertThatThrownBy(interruptionChecker::check).isInstanceOf(InterruptedException.class);

        // A genuine failure on another thread is still recorded afterwards
        final ExecutionException failure = new ExecutionException("failed",
                new IllegalStateException("the reason"));
        interruptionChecker.setExecutionException(failure);
        assertThat(interruptionChecker.getExecutionException()).isSameAs(failure);

        // check() interrupts the calling thread when the shared flag is set, so clear it again
        Thread.interrupted();
    }

    /**
     * A task that was interrupted is reported as an interruption however it ended. A scan worker throws
     * {@link InterruptedException} when the scan is cancelled, and {@link java.util.concurrent.Future#get()} wraps
     * that in an {@link ExecutionException} like any other, so the recording path is where it has to be recognized.
     */
    @Test
    public void anInterruptedTaskIsReportedAsAnInterruption() {
        final AutoCloseableExecutorService executorService;
        try (AutoCloseableExecutorService closeableExecutorService = new AutoCloseableExecutorService(1)) {
            executorService = closeableExecutorService;
            executorService.submit((Callable<Void>) () -> {
                throw new InterruptedException();
            });
        }
        // The executor service is closed, so every task has finished
        assertThat(executorService.interruptionChecker.getExecutionException()).isNull();
        assertThat(executorService.interruptionChecker.checkAndReturn()).isTrue();

        // checkAndReturn() interrupts the calling thread when the shared flag is set, so clear it again
        Thread.interrupted();
    }
}
