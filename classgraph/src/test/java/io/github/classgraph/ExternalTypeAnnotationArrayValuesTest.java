package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.test.typeannotation.internal.UsesExternalTypeAnnotation;

/**
 * The array-typed parameter values of a type annotation whose class was not scanned come back as an array of the
 * element type, as they do for a declaration annotation.
 */
public class ExternalTypeAnnotationArrayValuesTest {
    /** Arrays of a primitive type or of strings are not returned as an {@code Object[]} array. */
    @Test
    public void typeAnnotationArraysAreConverted() {
        try (var scanResult = new ClassGraph().enableClasspath().enableClassInfo().enableFieldInfo()
                .enableAnnotationInfo().acceptPackages(UsesExternalTypeAnnotation.class.getPackage().getName())
                .scan()) {
            final var fieldInfo = scanResult.getClassInfo(UsesExternalTypeAnnotation.class.getName())
                    .getFieldInfo("arrays");
            assertThat(fieldInfo).isNotNull();
            final var typeAnnotations = fieldInfo.getTypeSignatureOrTypeDescriptor().getTypeAnnotationInfo();
            assertThat(typeAnnotations).isNotNull().hasSize(1);
            final var annotationInfo = typeAnnotations.get(0);
            // The annotation class is outside the accepted package, so it was not scanned
            assertThat(annotationInfo.getClassInfo()).isNull();
            assertThat(annotationInfo.getParameterValues().getValue("ints")).isEqualTo(new int[] { 1, 2 });
            assertThat(annotationInfo.getParameterValues().getValue("strings")).isEqualTo(new String[] { "a" });
        }
    }
}
