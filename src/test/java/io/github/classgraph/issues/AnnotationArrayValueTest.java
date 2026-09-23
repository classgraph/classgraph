package io.github.classgraph.issues;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.AnnotationInfoList;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.ScanResult;
import io.github.classgraph.test.typeannotation.internal.UsesExternalTypeAnnotation;

/**
 * Array-valued annotation parameters: equal annotations have equal hash codes, and the arrays of a type annotation
 * whose class was not scanned are converted to an array of the element type.
 */
public class AnnotationArrayValueTest {
    /** An annotation with an array-typed parameter. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Ints {
        /**
         * @return the ints.
         */
        int[] value();
    }

    /** The first annotated class. */
    @Ints({ 1, 2 })
    public static class A {
    }

    /** The second annotated class, with the same annotation. */
    @Ints({ 1, 2 })
    public static class B {
    }

    /** Equal annotations have equal hash codes, whether or not their parameter values have been read yet. */
    @Test
    public void equalAnnotationsHaveEqualHashCodes() {
        try (ScanResult scanResult = new ClassGraph().enableAnnotationInfo()
                .acceptClasses(A.class.getName(), B.class.getName(), Ints.class.getName()).scan()) {
            final AnnotationInfo a = scanResult.getClassInfo(A.class.getName())
                    .getAnnotationInfo(Ints.class.getName());
            final AnnotationInfo b = scanResult.getClassInfo(B.class.getName())
                    .getAnnotationInfo(Ints.class.getName());
            assertThat(a).isNotNull();
            assertThat(b).isNotNull();

            // Read the parameter values of only one of the two annotations
            assertThat(a.getParameterValues().getValue("value")).isEqualTo(new int[] { 1, 2 });
            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);

            assertThat(b.getParameterValues().getValue("value")).isEqualTo(new int[] { 1, 2 });
            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }
    }

    /** The arrays of a type annotation whose class was not scanned are not returned as an Object[] array. */
    @Test
    public void typeAnnotationArraysAreConverted() {
        try (ScanResult scanResult = new ClassGraph().enableFieldInfo().enableAnnotationInfo()
                .acceptPackages(UsesExternalTypeAnnotation.class.getPackage().getName()).scan()) {
            final FieldInfo fieldInfo = scanResult.getClassInfo(UsesExternalTypeAnnotation.class.getName())
                    .getFieldInfo("arrays");
            assertThat(fieldInfo).isNotNull();
            final AnnotationInfoList typeAnnotations = fieldInfo.getTypeSignatureOrTypeDescriptor()
                    .getTypeAnnotationInfo();
            assertThat(typeAnnotations).isNotNull().hasSize(1);
            final AnnotationInfo annotationInfo = typeAnnotations.get(0);
            // The annotation class is outside the accepted package, so it was not scanned
            assertThat(annotationInfo.getClassInfo()).isNull();
            assertThat(annotationInfo.getParameterValues().getValue("ints")).isEqualTo(new int[] { 1, 2 });
            assertThat(annotationInfo.getParameterValues().getValue("strings")).isEqualTo(new String[] { "a" });
        }
    }
}
