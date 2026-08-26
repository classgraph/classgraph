package io.github.classgraph.test.paramannotation.internal;

import io.github.classgraph.test.paramannotation.external.ExternalParamAnnotation;

/**
 * A class in an accepted package with a method that has an annotated parameter, but no annotation on the method
 * itself, and whose parameter annotation is declared in a package that is not accepted.
 */
public class UsesExternalParamAnnotation {
    /**
     * A method with no annotation of its own, and one annotated parameter.
     *
     * @param param
     *            the annotated parameter.
     */
    public void method(@ExternalParamAnnotation final String param) {
    }
}
