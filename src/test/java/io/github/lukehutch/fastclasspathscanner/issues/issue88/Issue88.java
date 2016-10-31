package io.github.lukehutch.fastclasspathscanner.issues.issue88;

import static org.assertj.core.api.StrictAssertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.MatchProcessorException;

public class Issue88 {
    /**
     * Test that multiple exceptions are packaged up in a MatchProcessingException, and that they don't prevent the
     * scan from continuing.
     */
    @Test
    public void exceptionsArePropagated() {
        final AtomicInteger callCounter = new AtomicInteger(0);
        try {
            new FastClasspathScanner().matchClassesWithMethodAnnotation(Test.class, (cls, method) -> {
                if (cls.getName().equals(Issue88.class.getName())) {
                    callCounter.incrementAndGet();
                    throw new RuntimeException("Wham!");
                }
            }).scan();
            throw new RuntimeException("Would have expected to get an exception here");
        } catch (final Exception e) {
            assertThat(e.getMessage())
                    .isEqualTo("Multiple exceptions thrown of type: java.lang.RuntimeException: Wham!. "
                            + "To see individual exceptions, call MatchProcessorException#getExceptions(), "
                            + "or call FastClasspathScanner#verbose() before FastClasspathScanner#scan().");
            assertThat(callCounter.get()).isEqualTo(2);
        }
    }

    private static void rec() {
        rec();
    }

    /** Test that Errors are caught (not just Exceptions). */
    @Test
    public void infiniteRecursion() {
        boolean exceptionThrown = false;
        try {
            new FastClasspathScanner().matchClassesWithMethodAnnotation(Test.class, (cls, method) -> {
                if (cls == Issue88.class && method.getName().equals("infiniteRecursion")) {
                    rec();
                }
            }).scan();
        } catch (final MatchProcessorException e) {
            exceptionThrown = e.getExceptions().iterator().next().toString().equals("java.lang.StackOverflowError");
        }
        assertThat(exceptionThrown).isTrue();
    }
}
