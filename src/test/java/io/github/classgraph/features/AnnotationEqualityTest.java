package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * AnnotationEqualityTest.
 */
class AnnotationEqualityTest {
    /**
     * The Interface W.
     */
    private static interface W {
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

        // Right now Annotation::toString does not quote strings or chars, which seems like an oversight,
        // so maybe it's better to break compatibility for these parameter value types.

        //        /**
        //         * D.
        //         *
        //         * @return the string
        //         */
        //        String d();
        //
        //        /**
        //         * E.
        //         *
        //         * @return the char
        //         */
        //        char e();
    }

    /**
     * The Class Y.
     */
    @X(b = 5, c = { Long.class, Integer.class, AnnotationEqualityTest.class, W.class, X.class }
    // , d = "xyz", e = 'w'
    )
    private static class Y {
    }

    /**
     * Test equality of JRE-instantiated Annotation with proxy instance instantiated by ClassGraph.
     */
    @Test
    void annotationEquality() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(AnnotationEqualityTest.class.getPackage().getName()).enableAllInfo().scan()) {
            final ClassInfo classInfo = scanResult.getClassInfo(Y.class.getName());
            assertThat(classInfo).isNotNull();
            final Class<?> cls = classInfo.loadClass();
            final X annotation = (X) cls.getAnnotations()[0];
            assertThat(X.class.isInstance(annotation));
            final AnnotationInfo annotationInfo = classInfo.getAnnotationInfo().get(0);
            final Annotation proxyAnnotationGeneric = annotationInfo.loadClassAndInstantiate();
            assertThat(X.class.isInstance(proxyAnnotationGeneric));
            final X proxyAnnotation = (X) proxyAnnotationGeneric;
            assertThat(proxyAnnotation.b()).isEqualTo(annotation.b());
            assertThat(proxyAnnotation.c()).isEqualTo(annotation.c());
            assertThat(annotation.hashCode()).isEqualTo(proxyAnnotation.hashCode());
            assertThat(annotation).isEqualTo(proxyAnnotation);
            // Annotation::toString is implementation-dependent (#361)
            // assertThat(annotation.toString()).isEqualTo(annotationInfo.toString());
            // assertThat(annotation.toString()).isEqualTo(proxyAnnotation.toString());
        }
    }
}
