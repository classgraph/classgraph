package io.github.lukehutch.fastclasspathscanner.issues.issue88;

import static org.assertj.core.api.StrictAssertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class Issue88 {
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
            assertThat(e.getMessage()).isEqualTo("Multiple exceptions thrown: Wham!");
            assertThat(callCounter.get()).isEqualTo(2);
        }
    }

    @Test
    public void otherTestMethod() {
    }
}
