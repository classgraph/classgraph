package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Test
 */
class ClassTypeAnnotation {
    /***/
    @Retention(RetentionPolicy.RUNTIME)
    @Target(value = { ElementType.TYPE_USE, ElementType.TYPE })
    private static @interface P {
    }

    /***/
    @Retention(RetentionPolicy.RUNTIME)
    @Target(value = { ElementType.TYPE_USE, ElementType.TYPE })
    private static @interface Q {
    }

    /***/
    @Retention(RetentionPolicy.RUNTIME)
    @Target(value = { ElementType.TYPE_USE, ElementType.TYPE })
    private static @interface R {
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
    private static class E<T> extends @P Z implements @Q A, @R B {
    }

    /***/
    private static class F extends @P Z implements @Q A, @R B {
    }

    /***/
    private static class G extends @P Z implements @Q B, @R A {
    }

    /***/
    private static class H extends @P Z {
    }

    /***/
    private static class I implements @Q B, @R A {
    }

    @Test
    void classTypeAnnotation() {
        try (ScanResult scanResult = new ClassGraph()
                .acceptPackages(ClassTypeAnnotation.class.getPackage().getName()).enableAllInfo().scan()) {

            // Type with annotations should be rendered by toString() as
            //   Y<T> extends ClassTypeAnnotation$@X Z
            // and not
            //   Y<T> extends @X ClassTypeAnnotation$Z
            // because the annotation is on Z, not ClassTypeAnnotation

            assertThat(scanResult.getClassInfo(E.class.getName()).getTypeSignature().toString())
                    .isEqualTo("private static class " + E.class.getName() + "<T> extends "
                            + ClassTypeAnnotation.class.getName() + "$@" + P.class.getName() + " "
                            + Z.class.getSimpleName() + " implements " + ClassTypeAnnotation.class.getName() + "$@"
                            + Q.class.getName() + " " + A.class.getSimpleName() + ", "
                            + ClassTypeAnnotation.class.getName() + "$@" + R.class.getName() + " "
                            + B.class.getSimpleName());

            assertThat(scanResult.getClassInfo(F.class.getName()).getTypeSignatureOrTypeDescriptor().toString())
                    .isEqualTo("private static class " + F.class.getName() + " extends "
                            + ClassTypeAnnotation.class.getName() + "$@" + P.class.getName() + " "
                            + Z.class.getSimpleName() + " implements " + ClassTypeAnnotation.class.getName() + "$@"
                            + Q.class.getName() + " " + A.class.getSimpleName() + ", "
                            + ClassTypeAnnotation.class.getName() + "$@" + R.class.getName() + " "
                            + B.class.getSimpleName());

            assertThat(scanResult.getClassInfo(G.class.getName()).getTypeSignatureOrTypeDescriptor().toString())
                    .isEqualTo("private static class " + G.class.getName() + " extends "
                            + ClassTypeAnnotation.class.getName() + "$@" + P.class.getName() + " "
                            + Z.class.getSimpleName() + " implements " + ClassTypeAnnotation.class.getName() + "$@"
                            + Q.class.getName() + " " + B.class.getSimpleName() + ", "
                            + ClassTypeAnnotation.class.getName() + "$@" + R.class.getName() + " "
                            + A.class.getSimpleName());

            assertThat(scanResult.getClassInfo(H.class.getName()).getTypeSignatureOrTypeDescriptor().toString())
                    .isEqualTo("private static class " + H.class.getName() + " extends "
                            + ClassTypeAnnotation.class.getName() + "$@" + P.class.getName() + " "
                            + Z.class.getSimpleName());

            assertThat(scanResult.getClassInfo(I.class.getName()).getTypeSignatureOrTypeDescriptor().toString())
                    .isEqualTo("private static class " + I.class.getName() + " implements "
                            + ClassTypeAnnotation.class.getName() + "$@" + Q.class.getName() + " "
                            + B.class.getSimpleName() + ", " + ClassTypeAnnotation.class.getName() + "$@"
                            + R.class.getName() + " " + A.class.getSimpleName());
        }
    }
}
