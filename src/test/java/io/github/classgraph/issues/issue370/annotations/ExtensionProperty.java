package io.github.classgraph.issues.issue370.annotations;

/**
 * ExtensionProperty.
 */
public @interface ExtensionProperty {

    /**
     * The name of the property.
     *
     * @return the name of the property
     */
    String name();

    /**
     * The value of the property.
     *
     * @return the value of the property
     */
    String value();
}