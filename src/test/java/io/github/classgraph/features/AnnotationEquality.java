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
 * AnnotationEquality.
 */
class AnnotationEquality {
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
    @X(b = 5, c = { Long.class, Integer.class, AnnotationEquality.class, W.class, X.class }
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
                .whitelistPackages(AnnotationEquality.class.getPackage().getName()).enableAllInfo().scan()) {
            final ClassInfo classInfo = scanResult.getClassInfo(Y.class.getName());
            assertThat(classInfo).isNotNull();
            final Class<?> cls = classInfo.loadClass();
            final Annotation annotation = cls.getAnnotations()[0];
            assertThat(X.class.isInstance(annotation));
            final AnnotationInfo annotationInfo = classInfo.getAnnotationInfo().get(0);
            final Annotation proxyAnnotation = annotationInfo.loadClassAndInstantiate();
            assertThat(X.class.isInstance(proxyAnnotation));
            assertThat(annotation.hashCode()).isEqualTo(proxyAnnotation.hashCode());
            assertThat(annotation).isEqualTo(proxyAnnotation);
            assertThat(annotation.toString()).isEqualTo(annotationInfo.toString());
            assertThat(annotation.toString()).isEqualTo(proxyAnnotation.toString());
        }
    }
}
