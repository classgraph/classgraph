package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link ScanResult#getClassesWithAllAnnotations(String...)} and
 * {@link ScanResult#getClassesWithAnyAnnotation(String...)} sorted the result before testing it for null, so calling
 * either of them with no annotation names threw {@link NullPointerException} rather than returning the empty list.
 */
public class ClassesWithAnnotationsNoArgsTest {
    /** Calling the annotation queries with no annotation names returns the empty list. */
    @Test
    public void noAnnotationNamesReturnsEmptyList() {
        try (ScanResult scanResult = new ClassGraph().acceptPackages("io.github.classgraph.test.accepted")
                .enableAnnotationInfo().scan()) {
            assertThat(scanResult.getClassesWithAllAnnotations(new String[0])).isEmpty();
            assertThat(scanResult.getClassesWithAnyAnnotation(new String[0])).isEmpty();
        }
    }
}
