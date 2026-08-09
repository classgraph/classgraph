package io.github.classgraph.test.annotationdefaults;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * The default values of annotation parameters that are not given an explicit
 * value are read from the annotation class.
 */
public class AnnotationDefaultValuesTest {
    /** An annotation with a parameter that has a default value. */
    @Retention(RetentionPolicy.RUNTIME)
    @interface MyAnnotation {
        /**
         * A parameter with a default value.
         *
         * @return the message
         */
        String msg() default "hello";
    }

    /** A class annotated without an explicit parameter value. */
    @MyAnnotation
    static class MyClass {
    }

    /**
     * The default parameter values of an annotation are listed for an annotation
     * instance that does not give the parameter an explicit value.
     */
    @Test
    public void defaultParameterValues() {
        try (var scanResult = new ClassGraph()
                .acceptPackages(AnnotationDefaultValuesTest.class.getPackage().getName()).enableAllInfo().scan()) {
            final var classInfo = scanResult.getClassInfo(MyClass.class.getName());
            assertThat(classInfo).isNotNull();
            final var annotationInfo = classInfo.getAllAnnotationInfo(MyAnnotation.class.getName());
            assertThat(annotationInfo).isNotNull();
            // The parameter value is not stored in the classfile of the annotated class,
            // only in the classfile of the annotation
            assertThat(annotationInfo.getParameterValues(/* includeDefaultValues = */ false)).isEmpty();
            assertThat(annotationInfo.getDefaultParameterValues().asMap()).containsOnlyKeys("msg");
            assertThat(annotationInfo.getDefaultParameterValues().getValue("msg")).isEqualTo("hello");
            assertThat(annotationInfo.getParameterValues().getValue("msg")).isEqualTo("hello");
        }
    }
}
