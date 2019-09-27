package io.github.classgraph.issues.issue370.annotations;

public @interface ApiOperation {
    String value();

    String notes();

    /**
     * @return an optional array of extensions
     */

    Extension[] extensions() default @Extension(properties = @ExtensionProperty(name = "", value = ""));
}