package nonapi.io.github.classgraph.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link AutoCloseableExecutorService}. */
public class AutoCloseableExecutorServiceTest {
    /**
     * Clear the interrupt status of the test thread, since checking the interruption checker after a task was
     * interrupted interrupts the thread that checks it, and the test threads are reused.
     */
    @AfterEach
    public void clearInterruptStatus() {
        Thread.interrupted();
    }

    /**
     * An exception thrown by a submitted task is recorded, so that the thread that started the work sees it even
     * though nothing calls get() on the task's future.
     */
    @Test
    public void anExceptionThrownByASubmittedTaskIsRecorded() {
        final IllegalStateException cause = new IllegalStateException("the reason");
        final AutoCloseableExecutorService executorService;
        try (AutoCloseableExecutorService closeableExecutorService = new AutoCloseableExecutorService(1)) {
            executorService = closeableExecutorService;
            executorService.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    throw cause;
                }
            });
        }
        // The executor service is closed, so every task has finished
        final ExecutionException executionException = executorService.interruptionChecker
                .getExecutionException();
        assertThat(executionException).isNotNull();
        assertThat(InterruptionChecker.getCause(executionException)).isSameAs(cause);
        // A failure is not an interruption: the recorded exception is enough to stop the other threads, since
        // check() throws it
        assertThat(executorService.interruptionChecker.checkAndReturn()).isFalse();
    }

    /**
     * An exception thrown by an executed task is recorded too, even though execute() reports it in a different way
     * from submit().
     */
    @Test
    public void anExceptionThrownByAnExecutedTaskIsRecorded() {
        final IllegalStateException cause = new IllegalStateException("the reason");
        final AutoCloseableExecutorService executorService;
        try (AutoCloseableExecutorService closeableExecutorService = new AutoCloseableExecutorService(1)) {
            executorService = closeableExecutorService;
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    throw cause;
                }
            });
        }
        final ExecutionException executionException = executorService.interruptionChecker
                .getExecutionException();
        assertThat(executionException).isNotNull().hasMessage("Uncaught exception");
        assertThat(InterruptionChecker.getCause(executionException)).isSameAs(cause);
        assertThat(executorService.interruptionChecker.checkAndReturn()).isFalse();
    }
}
