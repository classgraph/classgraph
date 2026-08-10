package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Tests that an interrupted scan throws {@link ClassGraphException}, as {@link ClassGraph#scan()} documents, rather
 * than letting the {@link InterruptedException} escape, and that the interruption is not swallowed in the process.
 */
public class ClassGraphExceptionTest {
    /**
     * A scan on an interrupted thread is reported as an interruption, with the {@link InterruptedException} as the
     * cause, and the thread is left interrupted: throwing {@link InterruptedException} cleared the interrupt
     * status, and reporting the interruption as an unchecked exception would otherwise lose it, so a caller that
     * catches {@link ClassGraphException} would see a thread that no longer looks interrupted.
     */
    @Test
    public void interruptedScanIsReportedWithoutSwallowingTheInterruption() {
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> new ClassGraph().scan()).isInstanceOf(ClassGraphException.class)
                    .hasMessage("Scan interrupted").hasCauseInstanceOf(InterruptedException.class);
            // Thread.interrupted() both reports and clears the status, so this also stops the interruption leaking
            // into the next test that runs on this thread
            assertThat(Thread.interrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
