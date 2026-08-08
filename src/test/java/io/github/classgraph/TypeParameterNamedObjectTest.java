package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.types.ParseException;

/**
 * {@code TypeParameter#toStringInternal} suppresses a redundant {@code "extends java.lang.Object"} class bound. It
 * used to detect the simple-name form of that bound by string comparison alone, then cast the class bound to
 * {@link ClassRefTypeSignature} to confirm it. A type parameter may legally be named {@code Object} (shadowing
 * {@code java.lang.Object}), in which case a bound referring to it renders as {@code "Object"} but is a
 * {@link TypeVariableSignature}, so the cast threw {@link ClassCastException}.
 */
public class TypeParameterNamedObjectTest {
    /** {@code class Foo<Object, T extends Object>}, where the bound of {@code T} is the type parameter. */
    @Test
    public void typeParameterNamedObjectUsedAsClassBound() throws ParseException {
        final List<TypeParameter> typeParameters = ClassTypeSignature
                .parse("<Object:Ljava/lang/Object;T:TObject;>Ljava/lang/Object;", /* classInfo = */ null)
                .getTypeParameters();
        assertThat(typeParameters).hasSize(2);
        final TypeParameter t = typeParameters.get(1);
        assertThat(t.toString()).isEqualTo("T extends Object");
        assertThat(t.toStringWithSimpleNames()).isEqualTo("T extends Object");
    }

    /** A real {@code java.lang.Object} class bound is still suppressed. */
    @Test
    public void javaLangObjectClassBoundStillSuppressed() throws ParseException {
        final List<TypeParameter> typeParameters = ClassTypeSignature
                .parse("<T:Ljava/lang/Object;>Ljava/lang/Object;", /* classInfo = */ null).getTypeParameters();
        assertThat(typeParameters).hasSize(1);
        final TypeParameter t = typeParameters.get(0);
        assertThat(t.toString()).isEqualTo("T");
        assertThat(t.toStringWithSimpleNames()).isEqualTo("T");
    }
}
