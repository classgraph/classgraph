package io.github.classgraph.issues.issue350;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Unit test.
 */
public class Issue350 {
    /** */
    @Retention(RetentionPolicy.RUNTIME)
    public static @interface SuperclassAnnotation {
    }

    /** */
    public static class Pub {
        /** */
        @SuperclassAnnotation
        public int annotatedPublicField;

        /** */
        @SuperclassAnnotation
        public void annotatedPublicMethod() {
        }
    }

    /** */
    public static class Priv {
        /** */
        @SuperclassAnnotation
        private int annotatedPrivateField;

        /** */
        @SuperclassAnnotation
        private void annotatedPrivateMethod() {
        }
    }

    /** */
    public static class PubSub extends Pub {
    }

    /** */
    public static class PrivSub extends Priv {
    }

    /** Test finding subclasses of classes with annotated methods or fields. */
    @Test
    public void test() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(Issue350.class.getPackage().getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo().scan()) {
            assertThat(scanResult.getClassesWithFieldAnnotation(SuperclassAnnotation.class.getName()).getNames())
                    .containsOnly(Pub.class.getName(), PubSub.class.getName());
            assertThat(scanResult.getClassesWithMethodAnnotation(SuperclassAnnotation.class.getName()).getNames())
                    .containsOnly(Pub.class.getName(), PubSub.class.getName());
        }
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(Issue350.class.getPackage().getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .ignoreFieldVisibility().ignoreMethodVisibility().scan()) {
            assertThat(scanResult.getClassesWithFieldAnnotation(SuperclassAnnotation.class.getName()).getNames())
                    .containsOnly(Pub.class.getName(), PubSub.class.getName(), Priv.class.getName());
            assertThat(scanResult.getClassesWithMethodAnnotation(SuperclassAnnotation.class.getName()).getNames())
                    .containsOnly(Pub.class.getName(), PubSub.class.getName(), Priv.class.getName());
        }
    }
}
