package io.github.classgraph.features.innerclasses;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Nested classes that are not public, so their classfiles are not read as external classes unless class visibility
 * is ignored. Each one is brought into the class graph by a different kind of reference.
 */
class NonPublicNestedClasses {
    /** A superclass. */
    static class Superclass {
    }

    /** An interface. */
    interface Interface {
    }

    /** An annotation for a class. */
    @Retention(RetentionPolicy.RUNTIME)
    @interface OnClass {
    }

    /** An annotation for a field. */
    @Retention(RetentionPolicy.RUNTIME)
    @interface OnField {
    }

    /** An annotation for a method. */
    @Retention(RetentionPolicy.RUNTIME)
    @interface OnMethod {
    }

    /** An annotation for a method parameter. */
    @Retention(RetentionPolicy.RUNTIME)
    @interface OnParameter {
    }
}
