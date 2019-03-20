package io.github.classgraph.issues.issue38;

import java.lang.annotation.Annotation;

/**
 * ImplementsSuppressWarnings.
 */
@SuppressWarnings("all")
public class ImplementsSuppressWarnings implements SuppressWarnings {
    /* (non-Javadoc)
     * @see java.lang.annotation.Annotation#annotationType()
     */
    @Override
    public Class<? extends Annotation> annotationType() {
        return ImplementsSuppressWarnings.class;
    }

    /* (non-Javadoc)
     * @see java.lang.SuppressWarnings#value()
     */
    @Override
    public String[] value() {
        return null;
    }

    /**
     * The Class AnnotatedBySuppressWarnings.
     */
    @SuppressWarnings("")
    public static class AnnotatedBySuppressWarnings {
    }
}
