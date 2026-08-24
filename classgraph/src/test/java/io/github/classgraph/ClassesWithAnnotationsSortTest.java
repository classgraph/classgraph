package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

/**
 * Tests that {@link ScanResult#getClassesWithAllAnnotations(String...)} and
 * {@link ScanResult#getClassesWithAnyAnnotation(String...)} can sort a result of more than one class, given that
 * the lists they sort are the unmodifiable lists returned by the rest of the public API.
 */
public class ClassesWithAnnotationsSortTest {
    /** A test annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Ann1 {
    }

    /** A second test annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Ann2 {
    }

    /** A class annotated with both test annotations. */
    @Ann1
    @Ann2
    public static class A {
    }

    /** A second class annotated with both test annotations. */
    @Ann1
    @Ann2
    public static class B {
    }

    /**
     * Scan this test's classes.
     *
     * @return the scan result
     */
    private static ScanResult scan() {
        return new ClassGraph().enableClasspath().acceptClasses(A.class.getName(), B.class.getName())
                .enableAnnotationInfo().scan();
    }

    /** More than one class with all of the named annotations can be returned. */
    @Test
    public void allAnnotationsWithMultipleResults() {
        try (var scanResult = scan()) {
            assertThat(
                    scanResult.getClassesWithAllAnnotations(Ann1.class.getName(), Ann2.class.getName()).getNames())
                    .containsExactly(A.class.getName(), B.class.getName());
        }
    }

    /** More than one class with any of the named annotations can be returned. */
    @Test
    public void anyAnnotationWithMultipleResults() {
        try (var scanResult = scan()) {
            assertThat(
                    scanResult.getClassesWithAnyAnnotation(Ann1.class.getName(), Ann2.class.getName()).getNames())
                    .containsExactly(A.class.getName(), B.class.getName());
        }
    }

    /** More than one class with a single named annotation can be returned. */
    @Test
    public void singleAnnotationWithMultipleResults() {
        try (var scanResult = scan()) {
            assertThat(scanResult.getClassesWithAllAnnotations(Ann1.class.getName()).getNames())
                    .containsExactly(A.class.getName(), B.class.getName());
        }
    }
}
