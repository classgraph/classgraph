package io.github.classgraph.issues.issue370.annotations;

/**
 * Extension.
 */
public @interface Extension {
    /**
     * An option name for these extensions.
     *
     * @return an option name for these extensions - will be prefixed with "x-"
     */
    String name() default "";

    /**
     * The extension properties.
     *
     * @return the actual extension properties
     * @see ExtensionProperty
     */
    ExtensionProperty[] properties();
}