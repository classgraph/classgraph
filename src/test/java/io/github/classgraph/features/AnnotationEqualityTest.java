package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationClassRef;
import io.github.classgraph.ClassGraph;

/**
 * AnnotationEqualityTest.
 */
class AnnotationEqualityTest {
    /**
     * The Interface W.
     */
    private interface W {
    }

    /**
     * The Interface X.
     */
    @Retention(RetentionPolicy.RUNTIME)
    private static @interface X {
        /**
         * A.
         *
         * @return the int
         */
        int a()

        default 3;

        /**
         * B.
         *
         * @return the int
         */
        int b();

        /**
         * C.
         *
         * @return the class[]
         */
        Class<?>[] c();
    }

    /**
     * The Class Y.
     */
    @X(b = 5, c = { Long.class, Integer.class, AnnotationEqualityTest.class, W.class, X.class })
    private static class Y {
    }

    /**
     * Test that the annotation parameter values read from the classfile match the
     * values that the JRE reports for the same annotation, including the default
     * value that the annotation instance does not give explicitly.
     */
    @Test
    void annotationEquality() {
        try (var scanResult = new ClassGraph().acceptPackages(AnnotationEqualityTest.class.getPackage().getName())
                .enableAllInfo().scan()) {
            final var classInfo = scanResult.getClassInfo(Y.class.getName());
            assertThat(classInfo).isNotNull();
            final var annotation = (X) Y.class.getAnnotations()[0];
            final var annotationInfo = classInfo.getAllAnnotationInfo().get(0);
            assertThat(annotationInfo.getName()).isEqualTo(X.class.getName());

            final var paramValues = annotationInfo.getParameterValues();
            // a() is not given in the annotation instance, so its default value is used
            assertThat(paramValues.getValue("a")).isEqualTo(annotation.a());
            assertThat(paramValues.getValue("b")).isEqualTo(annotation.b());

            // Class references in annotation parameters are returned as
            // AnnotationClassRef, which holds the name of the referenced class
            final var classRefs = Arrays.stream((Object[]) paramValues.getValue("c"))
                    .map(ref -> ((AnnotationClassRef) ref).getName()).toList();
            final var expectedClassNames = Arrays.stream(annotation.c()).map(Class::getName).toList();
            assertThat(classRefs).isEqualTo(expectedClassNames);
            assertThat(classRefs).isEqualTo(List.of(Long.class.getName(), Integer.class.getName(),
                    AnnotationEqualityTest.class.getName(), W.class.getName(), X.class.getName()));
        }
    }
}
