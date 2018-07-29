package io.github.classgraph.issues.issue38;

import java.lang.annotation.Annotation;

@SuppressWarnings("all")
public class ImplementsSuppressWarnings implements SuppressWarnings {
    @Override
    public Class<? extends Annotation> annotationType() {
        return ImplementsSuppressWarnings.class;
    }

    @Override
    public String[] value() {
        return null;
    }

    @SuppressWarnings("")
    public static class AnnotatedBySuppressWarnings {
    }
}
