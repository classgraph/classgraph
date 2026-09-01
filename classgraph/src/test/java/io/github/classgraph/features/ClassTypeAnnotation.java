package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * Test that a type annotation on a superclass or interface reference is rendered on the type it annotates, not on
 * the enclosing class.
 */
class ClassTypeAnnotation {
    /** A type-use annotation, applied to the superclass reference of the fixture classes below. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE_USE, ElementType.TYPE })
    private static @interface P {
    }

    /** A type-use annotation, applied to the first interface reference of the fixture classes below. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE_USE, ElementType.TYPE })
    private static @interface Q {
    }

    /** A type-use annotation, applied to the second interface reference of the fixture classes below. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE_USE, ElementType.TYPE })
    private static @interface R {
    }

    /** The superclass that the fixture classes extend. */
    private static class Z {
    }

    /** An interface that the fixture classes implement. */
    private interface A {
    }

    /** A second interface that the fixture classes implement. */
    private interface B {
    }

    /** A third interface, reached only through {@link BSubC} rather than implemented directly. */
    private interface C {
    }

    /** An interface extending {@link C}, so that {@link J} reaches an interface it does not implement directly. */
    private interface BSubC extends C {
    }

    /**
     * A generic class with an annotated superclass and two annotated interfaces, so that {@code getTypeSignature()}
     * has a generic signature to render.
     */
    // The type parameter is the fixture, and is deliberately not used by the body
    @SuppressWarnings("unused")
    private static class E<T> extends @P Z implements @Q A, @R B {
    }

    /** As {@link E}, but not generic, so the type descriptor rather than the type signature is rendered. */
    private static class F extends @P Z implements @Q A, @R B {
    }

    /** As {@link F}, but with the two interfaces in the opposite order. */
    private static class G extends @P Z implements @Q B, @R A {
    }

    /** A class with an annotated superclass and no interfaces. */
    private static class H extends @P Z {
    }

    /** A class with annotated interfaces and no explicit superclass. */
    private static class I implements @Q B, @R A {
    }

    /**
     * A class whose first interface extends another interface, so that only the directly implemented interfaces,
     * and not {@link C}, may appear in the synthesized type descriptor.
     */
    private static class J implements @Q BSubC, @R A {
    }

    @Test
    void classTypeAnnotation() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(ClassTypeAnnotation.class.getPackage().getName()).enableClassInfo()
                .enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {

            // Type with annotations should be rendered by toString() as Y<T> extends ClassTypeAnnotation$@X Z and
            // not Y<T> extends @X ClassTypeAnnotation$Z because the annotation is on Z, not ClassTypeAnnotation

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

            // The synthesized type descriptor must list the directly implemented interfaces, in classfile order,
            // since the class type annotation targets index into the classfile's own interfaces[] array
            assertThat(scanResult.getClassInfo(J.class.getName()).getTypeSignatureOrTypeDescriptor().toString())
                    .isEqualTo("private static class " + J.class.getName() + " implements "
                            + ClassTypeAnnotation.class.getName() + "$@" + Q.class.getName() + " "
                            + BSubC.class.getSimpleName() + ", " + ClassTypeAnnotation.class.getName() + "$@"
                            + R.class.getName() + " " + A.class.getSimpleName());
        }
    }
}
