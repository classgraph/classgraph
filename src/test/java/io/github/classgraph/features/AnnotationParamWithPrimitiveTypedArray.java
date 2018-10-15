package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

import org.junit.Test;

import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.AnnotationParameterValueList;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

public class AnnotationParamWithPrimitiveTypedArray {

    @Retention(RetentionPolicy.RUNTIME)
    public abstract static @interface NestedAnnotation {
        public String str();

        public int[] intArray();
    }

    @Retention(RetentionPolicy.RUNTIME)
    public abstract static @interface AnnotationWithPrimitiveArrayParams {
        public int[] v0() default { 1, 2 };

        public char[] v1();

        public String[] v2();

        public int[] v3();

        public NestedAnnotation[] v4();
    }

    @AnnotationWithPrimitiveArrayParams(v1 = { 'a' }, v2 = { "x" }, v3 = {}, v4 = {
            @NestedAnnotation(str = "Test", intArray = { 9 }) })
    public abstract static class AnnotatedClass {
    }

    @Test
    public void primitiveArrayParams() {
        try (ScanResult scanResult = new ClassGraph().enableAllInfo()
                .whitelistPackages(AnnotationParamWithPrimitiveTypedArray.class.getPackage().getName()).scan()) {
            final AnnotationInfo annotationInfo = scanResult.getClassInfo(AnnotatedClass.class.getName())
                    .getAnnotationInfo().get(0);
            final AnnotationParameterValueList annotationParams = annotationInfo.getParameterValues();
            final Object v0 = annotationParams.get("v0");
            final Object v1 = annotationParams.get("v1");
            final Object v2 = annotationParams.get("v2");
            final Object v3 = annotationParams.get("v3");
            final Object v4 = annotationParams.get("v4");

            assertThat(v0.getClass()).isEqualTo(int[].class);
            assertThat(v1.getClass()).isEqualTo(char[].class);
            assertThat(v2.getClass()).isEqualTo(String[].class);
            assertThat(v3.getClass()).isEqualTo(int[].class);
            // v4 has type Object[] until instantiated, then it becomes NestedAnnotation[]
            assertThat(v4.getClass()).isEqualTo(Object[].class);

            assertThat(v0).isEqualTo(new int[] { 1, 2 });
            assertThat(v1).isEqualTo(new char[] { 'a' });
            assertThat(v2).isEqualTo(new String[] { "x" });
            assertThat(v3).isEqualTo(new int[] {});
            assertThat(Arrays.toString((Object[]) v4))
                    .isEqualTo("[@" + NestedAnnotation.class.getName() + "(str = \"Test\", intArray = {9})]");

            final AnnotationWithPrimitiveArrayParams annotation = (AnnotationWithPrimitiveArrayParams) annotationInfo
                    .loadClassAndInstantiate();
            assertThat(annotation.v0()).isEqualTo(new int[] { 1, 2 });
            assertThat(annotation.v1()).isEqualTo(new char[] { 'a' });
            assertThat(annotation.v2()).isEqualTo(new String[] { "x" });
            assertThat(annotation.v3()).isEqualTo(new int[] {});
            assertThat(annotation.v4().getClass()).isEqualTo(NestedAnnotation[].class);
            assertThat(annotation.v4()[0].str()).isEqualTo("Test");
            assertThat(annotation.v4()[0].intArray()).isEqualTo(new int[] { 9 });
        }
    }
}
