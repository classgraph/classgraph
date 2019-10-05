package io.github.classgraph.issues.issue370.annotations;

/**
 * ApiOperation.
 */
public @interface ApiOperation {
    /**
     * Value.
     *
     * @return the string
     */
    String value();

    /**
     * Notes.
     *
     * @return the string
     */
    String notes();

    /**
     * @return an optional array of extensions
     */
    Extension[] extensions() default @Extension(properties = @ExtensionProperty(name = "", value = ""));
}