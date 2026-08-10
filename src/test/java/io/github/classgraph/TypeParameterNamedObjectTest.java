package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@code TypeParameter#toStringInternal} suppresses a redundant {@code "extends java.lang.Object"} class bound. It
 * used to detect the simple-name form of that bound by string comparison alone, then cast the class bound to
 * {@link ClassRefTypeSignature} to confirm it. A type parameter may legally be named {@code Object} (shadowing
 * {@code java.lang.Object}), in which case a bound referring to it renders as {@code "Object"} but is a
 * {@link TypeVariableSignature}, so the cast threw {@link ClassCastException}.
 */
public class TypeParameterNamedObjectTest {
    /**
     * A class whose first type parameter is named {@code Object}, and whose second type parameter is bounded by the
     * first.
     *
     * @param <Object>
     *            a type parameter that shadows {@code java.lang.Object}.
     * @param <T>
     *            a type parameter bounded by the type parameter named {@code Object}.
     */
    public static class TypeParameterNamedObject<Object, T extends Object> {
    }

    /**
     * A class with an ordinary type parameter, whose bound in the classfile is the real {@code java.lang.Object}.
     *
     * @param <T>
     *            a type parameter with no bound of its own.
     */
    public static class TypeParameterWithNoBound<T> {
    }

    /**
     * Get the type parameters of one of the fixture classes.
     *
     * @param scanResult
     *            the scan result.
     * @param cls
     *            the fixture class.
     * @return the class' type parameters.
     */
    private static List<TypeParameter> typeParameters(final ScanResult scanResult, final Class<?> cls) {
        return scanResult.getClassInfo(cls.getName()).getTypeSignature().getTypeParameters();
    }

    /** {@code class Foo<Object, T extends Object>}, where the bound of {@code T} is the type parameter. */
    @Test
    public void typeParameterNamedObjectUsedAsClassBound() {
        try (var scanResult = new ClassGraph().acceptClasses(TypeParameterNamedObject.class.getName()).scan()) {
            final var params = typeParameters(scanResult, TypeParameterNamedObject.class);
            assertThat(params).hasSize(2);
            final var t = params.get(1);
            assertThat(t.toString()).isEqualTo("T extends Object");
            assertThat(t.toStringWithSimpleNames()).isEqualTo("T extends Object");
        }
    }

    /** A real {@code java.lang.Object} class bound is still suppressed. */
    @Test
    public void javaLangObjectClassBoundStillSuppressed() {
        try (var scanResult = new ClassGraph().acceptClasses(TypeParameterWithNoBound.class.getName()).scan()) {
            final var params = typeParameters(scanResult, TypeParameterWithNoBound.class);
            assertThat(params).hasSize(1);
            final var t = params.get(0);
            assertThat(t.toString()).isEqualTo("T");
            assertThat(t.toStringWithSimpleNames()).isEqualTo("T");
        }
    }
}
