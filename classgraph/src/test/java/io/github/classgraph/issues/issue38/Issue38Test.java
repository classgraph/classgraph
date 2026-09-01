package io.github.classgraph.issues.issue38;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

class Issue38Test {
    /**
     * The Class AnnotationLiteral.
     *
     * @param <T>
     *            the generic type
     */
    public abstract static class AnnotationLiteral<T extends Annotation> implements Annotation {
    }

    /**
     * Test implements suppress warnings.
     */
    @Test
    void testImplementsSuppressWarnings() {
        try (var scanResult = new ClassGraph().enableClassInfo().enableClasspath()
                .acceptPackages(Issue38Test.class.getPackage().getName()).scan()) {
            assertThat(scanResult.getAllClassesImplementing(SuppressWarnings.class).getNames())
                    .containsOnly(ImplementsSuppressWarnings.class.getName());
        }
    }
}
