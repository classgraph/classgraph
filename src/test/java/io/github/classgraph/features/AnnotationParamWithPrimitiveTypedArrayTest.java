package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.ClassGraph;

/**
 * AnnotationParamWithPrimitiveTypedArray.
 */
public class AnnotationParamWithPrimitiveTypedArrayTest {
    /**
     * The Interface NestedAnnotation.
     */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface NestedAnnotation {

        /**
         * Str.
         *
         * @return the string
         */
        String str();

        /**
         * Int array.
         *
         * @return the int[]
         */
        int[] intArray();
    }

    /**
     * The Interface AnnotationWithPrimitiveArrayParams.
     */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface AnnotationWithPrimitiveArrayParams {

        /**
         * V 0.
         *
         * @return the int[]
         */
        int[] v0() default { 1, 2 };

        /**
         * V 1.
         *
         * @return the char[]
         */
        char[] v1();

        /**
         * V 2.
         *
         * @return the string[]
         */
        String[] v2();

        /**
         * V 3.
         *
         * @return the int[]
         */
        int[] v3();

        /**
         * V 4.
         *
         * @return the nested annotation[]
         */
        NestedAnnotation[] v4();
    }

    /**
     * The Class AnnotatedClass.
     */
    @AnnotationWithPrimitiveArrayParams(v1 = { 'a' }, v2 = { "x" }, v3 = {}, v4 = {
            @NestedAnnotation(str = "Test", intArray = { 9 }) })
    public abstract static class AnnotatedClass {
    }

    /**
     * Primitive array params.
     */
    @Test
    public void primitiveArrayParams() {
        try (var scanResult = new ClassGraph().enableAllInfo()
                .acceptPackages(AnnotationParamWithPrimitiveTypedArrayTest.class.getPackage().getName()).scan()) {
            final var annotationInfo = scanResult.getClassInfo(AnnotatedClass.class.getName())
                    .getAllAnnotationInfo().get(0);
            final var annotationParams = annotationInfo.getParameterValues();
            final var v0 = annotationParams.getValue("v0");
            final var v1 = annotationParams.getValue("v1");
            final var v2 = annotationParams.getValue("v2");
            final var v3 = annotationParams.getValue("v3");
            final var v4 = annotationParams.getValue("v4");

            assertThat(v0.getClass()).isEqualTo(int[].class);
            assertThat(v1.getClass()).isEqualTo(char[].class);
            assertThat(v2.getClass()).isEqualTo(String[].class);
            assertThat(v3.getClass()).isEqualTo(int[].class);
            // Arrays of nested annotations are returned as Object[] of AnnotationInfo
            assertThat(v4.getClass()).isEqualTo(Object[].class);

            assertThat(v0).isEqualTo(new int[] { 1, 2 });
            assertThat(v1).isEqualTo(new char[] { 'a' });
            assertThat(v2).isEqualTo(new String[] { "x" });
            assertThat(v3).isEqualTo(new int[] {});
            assertThat(Arrays.toString((Object[]) v4))
                    .isEqualTo("[@" + NestedAnnotation.class.getName() + "(str=\"Test\", intArray={9})]");

            final var nestedAnnotationInfo = (AnnotationInfo) ((Object[]) v4)[0];
            assertThat(nestedAnnotationInfo.getName()).isEqualTo(NestedAnnotation.class.getName());
            final var nestedParams = nestedAnnotationInfo.getParameterValues();
            assertThat(nestedParams.getValue("str")).isEqualTo("Test");
            assertThat(nestedParams.getValue("intArray")).isEqualTo(new int[] { 9 });
        }
    }
}
