package io.github.classgraph.test.typeannotation.internal;

import io.github.classgraph.test.typeannotation.external.ExternalTypeAnnotation;

/**
 * A class in an accepted package that uses a type annotation declared in a package that is not accepted.
 */
public class UsesExternalTypeAnnotation {
    /** A field whose type carries the external type annotation. */
    public @ExternalTypeAnnotation String field;
}
