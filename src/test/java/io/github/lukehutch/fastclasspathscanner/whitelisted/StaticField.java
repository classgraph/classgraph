package io.github.lukehutch.fastclasspathscanner.whitelisted;

public class StaticField {
    public static final String stringField = "Static field contents";
    public static final int intField = 3;
    public static final boolean boolField = true;
    public static final char charField = 'y';
    
    // Not a initializers, due to autoboxing
    public static final Integer integerField = 5;
    public static final Boolean booleanField = true;
}
