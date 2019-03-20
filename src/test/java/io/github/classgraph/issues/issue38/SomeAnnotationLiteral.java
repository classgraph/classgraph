package io.github.classgraph.issues.issue38;

import java.lang.annotation.Annotation;

import io.github.classgraph.issues.issue38.Issue38Test.AnnotationLiteral;

/**
 * SomeAnnotationLiteral.
 */
@SuppressWarnings("all")
public class SomeAnnotationLiteral extends AnnotationLiteral<SomeAnnotationLiteral> implements SomeAnnotation {
    /* (non-Javadoc)
     * @see io.github.classgraph.issues.issue38.SomeAnnotation#value()
     */
    @Override
    public String value() {
        return "test";
    }

    /* (non-Javadoc)
     * @see java.lang.annotation.Annotation#annotationType()
     */
    @Override
    public Class<? extends Annotation> annotationType() {
        return SuppressWarnings.class;
    }
}
