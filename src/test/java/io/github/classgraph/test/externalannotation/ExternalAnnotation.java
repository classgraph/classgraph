package io.github.classgraph.test.externalannotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An annotation that is deliberately declared outside the package accepted by the scan that reads it.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface ExternalAnnotation {
    /**
     * A {@code String} array parameter.
     *
     * @return the value
     */
    String[] value();
}
