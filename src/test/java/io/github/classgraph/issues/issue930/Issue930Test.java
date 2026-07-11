package io.github.classgraph.issues.issue930;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * Issue 930: calling {@code equals} on annotations loaded via
 * {@code AnnotationInfo#loadClassAndInstantiate()} must work after the
 * {@link ScanResult} has been closed.
 *
 * <p>
 * {@link ScanResult#close()} nulls out {@code reflectionUtils} but leaves
 * {@code AnnotationInfo.scanResult} non-null. The equals invocation handler
 * previously only checked {@code scanResult == null}, so after close it used a
 * null {@code ReflectionUtils} and threw {@link NullPointerException}.
 */
public class Issue930Test {
    @Retention(RetentionPolicy.RUNTIME)
    private static @interface Example {
        String value();
    }

    @Example("example")
    private static class FirstAnnotatedClass {
    }

    @Example("example")
    private static class SecondAnnotatedClass {
    }

    @Example("other")
    private static class ThirdAnnotatedClass {
    }

    @Test
    public void annotationEqualsWorksAfterScanResultClosed() {
        final List<Example> annotations = new ArrayList<>();
        try (ScanResult scanResult = new ClassGraph() //
                .acceptPackages(Issue930Test.class.getPackage().getName()) //
                .ignoreClassVisibility() //
                .enableAnnotationInfo() //
                .scan()) {
            for (final String className : new String[] { //
                    FirstAnnotatedClass.class.getName(), //
                    SecondAnnotatedClass.class.getName(), //
                    ThirdAnnotatedClass.class.getName() }) {
                final ClassInfo classInfo = scanResult.getClassInfo(className);
                assertThat(classInfo).as(className).isNotNull();
                final Annotation annotation = classInfo.getAnnotationInfo() //
                        .get(Example.class.getName()).loadClassAndInstantiate();
                annotations.add((Example) annotation);
            }
        }

        assertThat(annotations).hasSize(3);

        // After close, equals must not throw (was NPE before the fix).
        assertThatCode(() -> annotations.get(0).equals(annotations.get(1))).doesNotThrowAnyException();
        assertThatCode(() -> annotations.get(0).equals(annotations.get(2))).doesNotThrowAnyException();

        // Same parameter values → equal; different values → not equal.
        assertThat(annotations.get(0)).isEqualTo(annotations.get(1));
        assertThat(annotations.get(0)).isNotEqualTo(annotations.get(2));
        // Self-equality and non-annotation argument still work.
        assertThat(annotations.get(0)).isEqualTo(annotations.get(0));
        assertThat(annotations.get(0).equals("not-an-annotation")).isFalse();
    }
}
