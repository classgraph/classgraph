package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * Test
 */
class ClassTypeAnnotation {
    /***/
    @Retention(RetentionPolicy.RUNTIME)
    private static @interface X {
    }

    /***/
    private static class Z {
    }

    /***/
    private static interface A {
    }

    /***/
    private static interface B {
    }

    /***/
    private static class Y<T> extends @X Z implements A, B {
    }

    @Test
    void classTypeAnnotation() {
        try (ScanResult scanResult = new ClassGraph()
                .acceptPackages(ClassTypeAnnotation.class.getPackage().getName()).enableAllInfo().scan()) {
            final ClassInfo classInfo = scanResult.getClassInfo(Y.class.getName());
            assertThat(classInfo).isNotNull();

            // This is
            //   Y<T> extends ClassTypeAnnotation.@X Z
            // and not
            //   Y<T> extends @X ClassTypeAnnotation.Z
            // Because the annotation is on Z, not ClassTypeAnnotation
            assertThat(classInfo.getTypeSignature().toString()).isEqualTo("private static class "
                    + Y.class.getName() + "<T> extends " + ClassTypeAnnotation.class.getName() + "$@"
                    + X.class.getName() + " " + Z.class.getSimpleName() + " implements " + A.class.getName() + ", "
                    + B.class.getName());
        }
    }
}
