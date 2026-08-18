package nonapi.io.github.classgraph.fastzipfilereader;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/** Tests for the close of a {@link NestedJarHandler}. */
public class NestedJarHandlerCloseTest {
    /**
     * An interruption that a step of the close restored on this thread rather than recorded reaches the shared
     * interruption checker, so that any other thread still reading through this handler stops too. Asking the
     * garbage collector to unmap files is one such step: it is a static utility with no checker to reach, so
     * restoring the status that the throw cleared is all it can do.
     */
    @Test
    public void theCloseRoutesAnInterruptionOnThisThreadThroughTheSharedChecker() {
        final InterruptionChecker interruptionChecker = new InterruptionChecker();
        final NestedJarHandler nestedJarHandler = new NestedJarHandler(new ScanSpec(), interruptionChecker,
                new ReflectionUtils());
        Thread.currentThread().interrupt();
        nestedJarHandler.close(/* log = */ null);

        // Clear this thread's status, so that only the shared flag can make the check below report an interruption
        assertThat(Thread.interrupted()).isTrue();
        assertThat(interruptionChecker.checkAndReturn()).isTrue();

        // checkAndReturn() interrupts this thread again when the shared flag is set, so clear it once more
        Thread.interrupted();
    }
}
