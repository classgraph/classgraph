package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

/**
 * {@link ClassInfo#getClassesWithFieldAnnotation()} passed the classes with a <i>method</i> annotation as the set
 * of directly-annotated classes, so calling {@link ClassInfoList#directOnly()} on the result returned the classes
 * with an annotated method rather than the classes with an annotated field.
 */
public class ClassesWithFieldAnnotationDirectOnlyTest {
    /** An annotation that is placed on both a field and a method. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface FieldOrMethodAnnotation {
    }

    /** A class with a field that has the annotation. */
    public static class ClassWithAnnotatedField {
        /** The annotated field. */
        @FieldOrMethodAnnotation
        public int annotatedField;
    }

    /** A class with a method that has the annotation. */
    public static class ClassWithAnnotatedMethod {
        /** The annotated method. */
        @FieldOrMethodAnnotation
        public void annotatedMethod() {
        }
    }

    /**
     * {@code directOnly()} returns the classes with an annotated field, not with an annotated method.
     */
    @Test
    public void directOnlyReturnsClassesWithAnnotatedField() {
        try (var scanResult = new ClassGraph().enableClassInfo().enableClasspath()
                .acceptClasses(FieldOrMethodAnnotation.class.getName(), ClassWithAnnotatedField.class.getName(),
                        ClassWithAnnotatedMethod.class.getName())
                .enableFieldInfo().enableMethodInfo().enableAnnotationInfo().scan()) {
            final var annotation = scanResult.getClassInfo(FieldOrMethodAnnotation.class.getName());
            assertThat(annotation.getClassesWithFieldAnnotation().directOnly().getNames())
                    .containsExactly(ClassWithAnnotatedField.class.getName());
        }
    }
}
