package io.github.classgraph.features.innerclasses;

/**
 * A class whose field, method and method parameter are annotated with nested annotations of another class.
 */
public class AnnotatedMembers {
    /** The field. */
    @NonPublicNestedClasses.OnField
    public int field;

    /**
     * The method.
     *
     * @param parameter
     *            the parameter
     */
    @NonPublicNestedClasses.OnMethod
    public void method(@NonPublicNestedClasses.OnParameter final int parameter) {
        // Empty
    }
}
