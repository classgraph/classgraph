package io.github.classgraph.issues.issue38;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

public class Issue38Test {
    public static abstract class AnnotationLiteral<T extends Annotation> implements Annotation {
    }

    @Test
    public void testImplementsSuppressWarnings() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(Issue38Test.class.getPackage().getName())
                .scan()) {
            assertThat(scanResult.getClassesImplementing(SuppressWarnings.class.getName()).getNames())
                    .containsExactlyInAnyOrder(ImplementsSuppressWarnings.class.getName());
        }
    }
}
