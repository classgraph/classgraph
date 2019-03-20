package io.github.classgraph.test.whitelisted;

/**
 * StaticField.
 */
public class StaticField {
    /** The Constant stringField. */
    // Non-public -- need ignoreFieldVisibility() to match these
    static final String stringField = "Static field contents";

    /** The Constant intField. */
    static final int intField = 3;

    /** The Constant boolField. */
    @SuppressWarnings("unused")
    private static final boolean boolField = true;

    /** The Constant charField. */
    protected static final char charField = 'y';

    /** The Constant integerField. */
    // Non-constant initializers, due to autoboxing
    public static final Integer integerField = 5;

    /** The Constant booleanField. */
    public static final Boolean booleanField = true;
}
