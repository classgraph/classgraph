package io.github.classgraph.issues.issue930;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Issue 930: calling {@code equals()} on an annotation instance returned by
 * {@code AnnotationInfo#loadClassAndInstantiate()} threw a
 * {@link NullPointerException} once the {@link ScanResult} it came from had
 * been closed.
 *
 * <p>
 * {@code ScanResult#close()} sets {@code ScanResult#reflectionUtils} to null
 * but leaves {@code AnnotationInfo#scanResult} pointing at the closed
 * {@code ScanResult}, so the guard
 * {@code scanResult == null ? new ReflectionUtils() : scanResult.reflectionUtils}
 * yielded null rather than falling back to a fresh {@code ReflectionUtils}.
 */
public class Issue930Test {
    /**
     * An annotation with a parameter, so that {@code equals()} has to reflectively
     * read a parameter value.
     */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Issue930Annotation {
        /**
         * The annotation value.
         *
         * @return the value
         */
        String value();
    }

    /** A class annotated with {@link Issue930Annotation}. */
    @Issue930Annotation("abc")
    public static class Annotated {
    }

    /**
     * An annotation instance obtained from a {@link ScanResult} must remain usable
     * after that {@link ScanResult} has been closed.
     */
    @Test
    public void annotationEqualsWorksAfterScanResultIsClosed() {
        final Annotation proxyAnnotation;
        try (var scanResult = new ClassGraph().acceptPackages(Issue930Test.class.getPackage().getName()).enableAllInfo()
                .scan()) {
            final var classInfo = scanResult.getClassInfo(Annotated.class.getName());
            assertThat(classInfo).isNotNull();
            proxyAnnotation = classInfo.getAnnotationInfo(Issue930Annotation.class).loadClassAndInstantiate();
            assertThat(proxyAnnotation).isNotNull();
        }

        // The ScanResult is now closed, so ScanResult#reflectionUtils is null.
        final Annotation jdkAnnotation = Annotated.class.getAnnotation(Issue930Annotation.class);
        assertThatCode(() -> proxyAnnotation.equals(jdkAnnotation)).doesNotThrowAnyException();
        assertThat(proxyAnnotation.equals(jdkAnnotation)).isTrue();
    }
}
