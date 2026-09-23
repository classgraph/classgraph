package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * How a type parameter is rendered as a string.
 */
class TypeParameterToStringTest {
    /** A class whose type parameter name contains a '$', which is legal in Java and common in Scala (#495). */
    public static class Generic<T$X extends Comparable<T$X>> {
        /** A field whose type is the type variable. */
        public T$X field;
    }

    /** With simple names, a type parameter keeps its whole name, as the type variables that refer to it do. */
    @Test
    void typeParameterNameIsNotShortenedAtDollarSign() {
        try (var scanResult = new ClassGraph().enableClasspath().enableClassInfo().enableFieldInfo()
                .acceptClasses(Generic.class.getName()).scan()) {
            final var classInfo = scanResult.getClassInfo(Generic.class.getName());
            final var typeParameter = classInfo.getTypeSignature().getTypeParameters().get(0);
            assertThat(typeParameter.toString()).isEqualTo("T$X extends java.lang.Comparable<T$X>");
            assertThat(typeParameter.toStringWithSimpleNames()).isEqualTo("T$X extends Comparable<T$X>");
            assertThat(classInfo.getFieldInfo("field").getTypeSignature().toStringWithSimpleNames())
                    .isEqualTo("T$X");
        }
    }
}
