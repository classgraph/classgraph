package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassRefTypeSignature;
import io.github.classgraph.ScanResult;

/**
 * The type signatures that javac writes for a generic class whose name ends in '$' can be read.
 */
public class DollarClassNameSignatureTest {
    /**
     * A generic class whose name ends in '$'.
     *
     * @param <T>
     *            an unused type parameter.
     */
    public static class Box$<T> {
    }

    /** A subclass of a parameterized {@code Box$}. */
    public static class SubBox extends Box$<Integer> {
    }

    /** A class with a field and a method parameter of a parameterized {@code Box$} type. */
    public static class UsesBox {
        /** A field of a parameterized {@code Box$} type. */
        public Box$<String> f;

        /**
         * A method with a parameter of a parameterized {@code Box$} type.
         *
         * @param p
         *            the parameter.
         */
        public void m(final Box$<Integer> p) {
            // Only the signature is needed
        }
    }

    /**
     * Scan the fixture classes.
     *
     * @return the scan result.
     */
    private static ScanResult scanFixture() {
        return new ClassGraph().enableClasspath().enableClassInfo().enableFieldInfo().enableMethodInfo()
                .acceptClasses(Box$.class.getName(), SubBox.class.getName(), UsesBox.class.getName()).scan();
    }

    /** The type of a field of type {@code Box$<String>} keeps its type argument. */
    @Test
    public void aFieldTypeKeepsItsTypeArgument() {
        try (var scanResult = scanFixture()) {
            final var fieldInfo = scanResult.getClassInfo(UsesBox.class.getName()).getFieldInfo("f");
            final var typeSignature = (ClassRefTypeSignature) fieldInfo.getTypeSignature();
            assertThat(typeSignature).isNotNull();
            assertThat(typeSignature.getFullyQualifiedClassName()).isEqualTo(Box$.class.getName());
            assertThat(typeSignature.toString()).endsWith("Box$<java.lang.String>");
            assertThat(fieldInfo.toString()).endsWith("Box$<java.lang.String> f");
        }
    }

    /** The type of a method parameter of type {@code Box$<Integer>} keeps its type argument. */
    @Test
    public void aMethodParameterTypeKeepsItsTypeArgument() {
        try (var scanResult = scanFixture()) {
            final var methodInfo = scanResult.getClassInfo(UsesBox.class.getName()).getMethodInfo("m").get(0);
            assertThat(methodInfo.getTypeSignature()).isNotNull();
            final var parameterInfo = methodInfo.getParameterInfo();
            assertThat(parameterInfo).hasSize(1);
            assertThat(parameterInfo.get(0).getTypeSignature()).isNotNull();
            assertThat(parameterInfo.get(0).getTypeSignature().toString()).endsWith("Box$<java.lang.Integer>");
        }
    }

    /** The superclass of a class that extends {@code Box$<Integer>} keeps its type argument. */
    @Test
    public void aSuperclassKeepsItsTypeArgument() {
        try (var scanResult = scanFixture()) {
            final var classSignature = scanResult.getClassInfo(SubBox.class.getName()).getTypeSignature();
            assertThat(classSignature).isNotNull();
            final var superclassSignature = classSignature.getSuperclassSignature();
            assertThat(superclassSignature).isNotNull();
            assertThat(superclassSignature.getFullyQualifiedClassName()).isEqualTo(Box$.class.getName());
            final var suffixTypeArguments = superclassSignature.getSuffixTypeArguments();
            assertThat(suffixTypeArguments.get(suffixTypeArguments.size() - 1)).extracting(Object::toString)
                    .containsExactly("java.lang.Integer");
        }
    }
}
