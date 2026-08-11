package io.github.classgraph.base.internal.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link InterruptionChecker}. */
public class InterruptionCheckerTest {
    /**
     * Clear the interrupt status of the test thread, since interrupting the threads that share an
     * {@link InterruptionChecker} interrupts the calling thread too, and the test threads are reused.
     */
    @AfterEach
    public void clearInterruptStatus() {
        Thread.interrupted();
    }

    /** A checker that has not been interrupted, and has recorded no exception, reports neither. */
    @Test
    public void aCheckerThatHasSeenNothingReportsNothing() {
        final var interruptionChecker = new InterruptionChecker();
        assertThat(interruptionChecker.checkAndReturn()).isFalse();
        assertThat(interruptionChecker.getExecutionException()).isNull();
        assertThatCode(interruptionChecker::check).doesNotThrowAnyException();
    }

    /** Interrupting the checker interrupts the calling thread, so that a blocking call it makes returns. */
    @Test
    public void interruptingTheCheckerInterruptsTheCallingThread() {
        final var interruptionChecker = new InterruptionChecker();
        interruptionChecker.interrupt();

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(interruptionChecker.checkAndReturn()).isTrue();
        assertThatThrownBy(interruptionChecker::check).isInstanceOf(InterruptedException.class);
    }

    /**
     * A thread that is interrupted on its own, rather than through the checker, still interrupts the other threads
     * that share the checker, so that one interrupted worker stops the whole scan.
     */
    @Test
    public void anInterruptedThreadInterruptsTheThreadsThatShareTheChecker() throws InterruptedException {
        final var interruptionChecker = new InterruptionChecker();
        final var interruptedThread = new Thread(() -> {
            Thread.currentThread().interrupt();
            interruptionChecker.checkAndReturn();
        });
        interruptedThread.start();
        interruptedThread.join();

        // This thread has not been interrupted itself, but the checker it shares reports the interruption, and
        // interrupts it in turn
        assertThat(interruptionChecker.checkAndReturn()).isTrue();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    /** Only the first exception is recorded, since it is the one that caused all the others. */
    @Test
    public void onlyTheFirstExceptionIsRecorded() {
        final var interruptionChecker = new InterruptionChecker();
        final var first = new ExecutionException("first", new IllegalStateException());
        interruptionChecker.setExecutionException(first);
        interruptionChecker.setExecutionException(new ExecutionException("second", new IllegalStateException()));

        assertThat(interruptionChecker.getExecutionException()).isSameAs(first);
    }

    /** Recording a null exception records nothing, so that a caller does not have to null-check first. */
    @Test
    public void recordingANullExceptionRecordsNothing() {
        final var interruptionChecker = new InterruptionChecker();
        interruptionChecker.setExecutionException(null);
        assertThat(interruptionChecker.getExecutionException()).isNull();
    }

    /** A recorded exception is re-thrown by the next check, so that it reaches the thread that started the work. */
    @Test
    public void aRecordedExceptionIsRethrownByTheNextCheck() {
        final var interruptionChecker = new InterruptionChecker();
        final var executionException = new ExecutionException("failed", new IllegalStateException("the reason"));
        interruptionChecker.setExecutionException(executionException);

        assertThatThrownBy(interruptionChecker::check).isSameAs(executionException);
        // An exception is reported even though no thread was interrupted
        assertThat(interruptionChecker.checkAndReturn()).isFalse();
    }

    /** The cause of an exception is the first cause that is not itself an execution exception. */
    @Test
    public void theCauseIsTheFirstCauseThatIsNotAnExecutionException() {
        final var cause = new IllegalStateException("the reason");
        assertThat(InterruptionChecker.getCause(cause)).isSameAs(cause);
        assertThat(InterruptionChecker.getCause(new ExecutionException(cause))).isSameAs(cause);
        // Execution exceptions are unwrapped however deeply they are nested
        assertThat(InterruptionChecker.getCause(new ExecutionException(new ExecutionException(cause))))
                .isSameAs(cause);
    }

    /** An exception with no cause at all is reported as an exception with an unknown cause, rather than as null. */
    @Test
    public void anExceptionWithNoCauseIsReportedAsHavingAnUnknownCause() {
        assertThat(InterruptionChecker.getCause(new ExecutionException("failed", null)))
                .isInstanceOf(ExecutionException.class).hasMessage("ExecutionException with unknown cause");
    }
}
