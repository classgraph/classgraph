package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

/**
 * Array-valued annotation parameters: {@link AnnotationInfo#equals(Object)} and {@link AnnotationInfo#hashCode()}
 * compare them by their elements, and {@link AnnotationParameterValue#getValue()} returns a copy of them.
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

    /**
     * Equal annotations have equal hash codes, whether or not their parameter values have been read yet.
     */
    @Test
    public void equalAnnotationsHaveEqualHashCodes() {
        try (var scanResult = new ClassGraph().enableClasspath().enableClassInfo().enableAnnotationInfo()
                .acceptClasses(A.class.getName(), B.class.getName(), Ints.class.getName()).scan()) {
            final var a = scanResult.getClassInfo(A.class.getName()).getAllAnnotationInfo()
                    .get(Ints.class.getName());
            final var b = scanResult.getClassInfo(B.class.getName()).getAllAnnotationInfo()
                    .get(Ints.class.getName());
            assertThat(a).isNotNull();
            assertThat(b).isNotNull();

            // Read the parameter values of only one of the two annotations
            assertThat(a.getParameterValues().getValue("value")).isEqualTo(new int[] { 1, 2 });
            assertThat(a).isEqualTo(b);
            assertThat(b).isEqualTo(a);
            assertThat(a).hasSameHashCodeAs(b);

            assertThat(b.getParameterValues().getValue("value")).isEqualTo(new int[] { 1, 2 });
            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }
    }

    /** Changing the array returned for an array-valued parameter does not change the parameter value. */
    @Test
    public void returnedArrayIsACopy() {
        try (var scanResult = new ClassGraph().enableClasspath().enableClassInfo().enableAnnotationInfo()
                .acceptClasses(A.class.getName(), Ints.class.getName()).scan()) {
            final var a = scanResult.getClassInfo(A.class.getName()).getAllAnnotationInfo()
                    .get(Ints.class.getName());
            assertThat(a).isNotNull();
            final var ints = (int[]) a.getParameterValues().getValue("value");
            assertThat(ints).isNotNull();
            ints[0] = 99;
            assertThat(a.getParameterValues().getValue("value")).isEqualTo(new int[] { 1, 2 });
        }
    }
}
