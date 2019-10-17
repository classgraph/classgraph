package io.github.classgraph.issues.issue38;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Issue38Test.
 */
class Issue38Test {
    /**
     * The Class AnnotationLiteral.
     *
     * @param <T>
     *            the generic type
     */
    public static abstract class AnnotationLiteral<T extends Annotation> implements Annotation {
    }

    /**
     * Test implements suppress warnings.
     */
    @Test
    void testImplementsSuppressWarnings() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(Issue38Test.class.getPackage().getName())
                .scan()) {
            assertThat(scanResult.getClassesImplementing(SuppressWarnings.class.getName()).getNames())
                    .containsOnly(ImplementsSuppressWarnings.class.getName());
        }
    }
}
