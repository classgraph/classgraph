package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.MethodTypeSignature;

/**
 * A type annotation is attached to its type signature by a decorator that runs after the signature has been given
 * its {@link io.github.classgraph.ScanResult}, so the annotation has to be given the {@code ScanResult} as it is
 * added. Without that, the annotation cannot reach the annotation class it names, so its default parameter values
 * are silently dropped.
 */
public class TypeAnnotationScanResultTest {
    /** A type annotation with a default parameter value. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE_USE })
    public @interface Level {
        /** @return the level. */
        int level() default 7;
    }

    /** A class carrying type annotations in each of the positions that are decorated lazily. */
    public class Annotated {
        /** A field whose type carries a type annotation. */
        public @Level String field;

        /** A field with a primitive type that carries a type annotation. */
        public @Level int primitiveField;

        /**
         * A method with an annotated explicit receiver parameter.
         *
         * @return the empty string.
         */
        public String method(@Level Annotated this) {
            return "";
        }
    }

    /** Every type annotation must be able to reach its own annotation class, and so its default values. */
    @Test
    public void typeAnnotationsCanReachTheirAnnotationClass() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(TypeAnnotationScanResultTest.class.getPackage().getName()).enableAllInfo().scan()) {
            final var classInfo = scanResult.getClassInfo(Annotated.class.getName());
            assertThat(classInfo).isNotNull();

            final var fieldType = classInfo.getFieldInfo("field").getTypeSignatureOrTypeDescriptor();
            final var fieldTypeAnnotations = fieldType.getTypeAnnotationInfo();
            assertThat(fieldTypeAnnotations).isNotNull();
            assertDefaultValueIsVisible(fieldTypeAnnotations.get(0));

            final var primitiveType = classInfo.getFieldInfo("primitiveField").getTypeSignatureOrTypeDescriptor();
            final var primitiveTypeAnnotations = primitiveType.getTypeAnnotationInfo();
            assertThat(primitiveTypeAnnotations).isNotNull();
            assertDefaultValueIsVisible(primitiveTypeAnnotations.get(0));

            final MethodTypeSignature methodType = classInfo.getMethodInfo("method").get(0)
                    .getTypeSignatureOrTypeDescriptor();
            final var receiverAnnotations = methodType.getReceiverTypeAnnotationInfo();
            assertThat(receiverAnnotations).isNotNull();
            assertDefaultValueIsVisible(receiverAnnotations.get(0));
        }
    }

    /** Assert that an annotation can reach its annotation class, and so can report its default parameter value. */
    private static void assertDefaultValueIsVisible(final AnnotationInfo annotationInfo) {
        assertThat(annotationInfo.getName()).isEqualTo(Level.class.getName());
        assertThat(annotationInfo.getClassInfo()).isNotNull();
        assertThat(annotationInfo.getParameterValues().getValue("level")).isEqualTo(7);
    }
}
