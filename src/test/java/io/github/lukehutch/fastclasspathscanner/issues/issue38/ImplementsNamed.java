package io.github.lukehutch.fastclasspathscanner.issues.issue38;

import java.lang.annotation.Annotation;

import javax.inject.Named;

@SuppressWarnings("all")
public class ImplementsNamed implements Named {
    @Override
    public Class<? extends Annotation> annotationType() {
        return ImplementsNamed.class;
    }

    @Override
    public String value() {
        return null;
    }

    @Named
    public static class AnnotatedByNamed {
    }
}
