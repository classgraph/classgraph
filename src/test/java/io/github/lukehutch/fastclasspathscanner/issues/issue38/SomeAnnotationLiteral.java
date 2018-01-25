package io.github.lukehutch.fastclasspathscanner.issues.issue38;

import javax.enterprise.util.AnnotationLiteral;

@SuppressWarnings("all")
public class SomeAnnotationLiteral extends AnnotationLiteral<SomeAnnotationLiteral> implements SomeAnnotation {
    @Override
    public String value() {
        return "test";
    }
}
