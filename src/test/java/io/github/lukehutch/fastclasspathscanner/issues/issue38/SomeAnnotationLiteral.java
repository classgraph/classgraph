package io.github.lukehutch.fastclasspathscanner.issues.issue38;

import java.lang.annotation.Annotation;

import io.github.lukehutch.fastclasspathscanner.issues.issue38.Issue38Test.AnnotationLiteral;

@SuppressWarnings("all")
public class SomeAnnotationLiteral extends AnnotationLiteral<SomeAnnotationLiteral> implements SomeAnnotation {
    @Override
    public String value() {
        return "test";
    }

    @Override
    public Class<? extends Annotation> annotationType() {
        return SuppressWarnings.class;
    }
}
