package io.github.classgraph.issues.issue897.ecjenum;

import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Enum with a TYPE_USE-annotated constructor parameter, compiled with ecj (which, unlike javac, emits no
 * Signature attribute for enum constructors). Used as a checked-in test fixture. Recompile with:
 * {@code java -jar ecj-3.42.0.jar -8 EnumWithAnnoCtor.java}
 */
// #897
public enum EnumWithAnnoCtor {
    /** A constant, so the enum is non-trivial. */
    VALUE(new Object());

    EnumWithAnnoCtor(@Anno Object o) {
    }

    /** TYPE_USE annotation placed on the constructor parameter type. */
    @Target(TYPE_USE)
    @Retention(RUNTIME)
    @interface Anno {
    }
}
