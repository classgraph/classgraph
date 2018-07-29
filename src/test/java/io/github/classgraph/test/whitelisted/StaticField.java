package io.github.classgraph.test.whitelisted;

public class StaticField {
    // Non-public -- need ignoreFieldVisibility() to match these
    static final String stringField = "Static field contents";
    static final int intField = 3;

    @SuppressWarnings("unused")
    private static final boolean boolField = true;

    protected static final char charField = 'y';

    // Non-constant initializers, due to autoboxing
    public static final Integer integerField = 5;
    public static final Boolean booleanField = true;
}
