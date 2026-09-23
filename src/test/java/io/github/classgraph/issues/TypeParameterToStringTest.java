package io.github.classgraph.issues;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import io.github.classgraph.TypeParameter;

/**
 * How a type parameter is rendered as a string.
 */
public class TypeParameterToStringTest {
    /** A class whose type parameter name contains a '$', which is legal in Java and common in Scala (#495). */
    public static class Generic<T$X extends Comparable<T$X>> {
        /** A field whose type is the type variable. */
        public T$X field;
    }

    /** With simple names, a type parameter keeps its whole name, as the type variables that refer to it do. */
    @Test
    public void typeParameterNameIsNotShortenedAtDollarSign() {
        try (ScanResult scanResult = new ClassGraph().enableFieldInfo().acceptClasses(Generic.class.getName())
                .scan()) {
            final ClassInfo classInfo = scanResult.getClassInfo(Generic.class.getName());
            final TypeParameter typeParameter = classInfo.getTypeSignature().getTypeParameters().get(0);
            assertThat(typeParameter.toString()).isEqualTo("T$X extends java.lang.Comparable<T$X>");
            assertThat(typeParameter.toStringWithSimpleNames()).isEqualTo("T$X extends Comparable<T$X>");
            assertThat(classInfo.getFieldInfo("field").getTypeSignature().toStringWithSimpleNames())
                    .isEqualTo("T$X");
        }
    }
}
