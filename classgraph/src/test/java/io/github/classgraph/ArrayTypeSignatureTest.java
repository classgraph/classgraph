package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ArrayTypeSignature}. An array type has at most 255 dimensions (JVMS 4.3.2 and 4.4.1), and an
 * array type with more dimensions must be rejected, since the nested dimensions of an array type signature are
 * built, compared and hashed by recursion, one call per dimension.
 */
public class ArrayTypeSignatureTest {
    /**
     * An array type signature with 255 dimensions parses.
     *
     * @throws TypeSignatureParseException
     *             if the signature does not parse
     */
    @Test
    public void arrayTypeWith255DimensionsParses() throws TypeSignatureParseException {
        final var typeSignature = TypeSignature.parse("[".repeat(255) + "I", /* definingClassName = */ null);
        assertThat(typeSignature).isInstanceOf(ArrayTypeSignature.class);
        assertThat(((ArrayTypeSignature) typeSignature).getNumDimensions()).isEqualTo(255);
    }

    /** An array type signature with 256 dimensions does not parse. */
    @Test
    public void arrayTypeWith256DimensionsDoesNotParse() {
        assertThatThrownBy(() -> TypeSignature.parse("[".repeat(256) + "I", /* definingClassName = */ null))
                .isInstanceOf(TypeSignatureParseException.class).hasMessageContaining("255");
    }

    /** An array type signature with 20000 dimensions does not parse, rather than overflowing the stack. */
    @Test
    public void arrayTypeWith20000DimensionsDoesNotParse() {
        assertThatThrownBy(() -> TypeSignature.parse("[".repeat(20000) + "I", /* definingClassName = */ null))
                .isInstanceOf(TypeSignatureParseException.class);
    }

    /**
     * An array type substituted for the element type of an array type adds its dimensions, but a type variable is
     * left unsubstituted if the result would have more than 255 dimensions.
     *
     * @throws TypeSignatureParseException
     *             if a type signature does not parse
     */
    @Test
    public void substitutionGivesAnArrayTypeWithAtMost255Dimensions() throws TypeSignatureParseException {
        final var arrayOfT = TypeSignature.parse("[".repeat(200) + "TT;", "p.C");
        final var key = TypeSignature.substitutionKey("p.C", "T");
        final var substituted = arrayOfT.substituteTypeVariables(Map.of(key, typeArgument("[".repeat(55) + "I")));
        assertThat(((ArrayTypeSignature) substituted).getNumDimensions()).isEqualTo(255);
        assertThat(((ArrayTypeSignature) substituted).getElementTypeSignature().toString()).isEqualTo("int");
        assertThat(arrayOfT.substituteTypeVariables(Map.of(key, typeArgument("[".repeat(56) + "I"))))
                .isSameAs(arrayOfT);
    }

    /**
     * Parse a type argument.
     *
     * @param typeArgumentStr
     *            the type argument, in type signature format.
     * @return the type argument.
     * @throws TypeSignatureParseException
     *             if the type argument does not parse
     */
    private static TypeArgument typeArgument(final String typeArgumentStr) throws TypeSignatureParseException {
        return ((ClassRefTypeSignature) TypeSignature.parse("Ljava/util/List<" + typeArgumentStr + ">;",
                /* definingClassName = */ null)).getTypeArguments().get(0);
    }

    /** The type signature of an erased array class can have at most 255 dimensions. */
    @Test
    public void erasedArrayTypeHasAtMost255Dimensions() {
        assertThat(ArrayTypeSignature.erased("int", 255).getNumDimensions()).isEqualTo(255);
        assertThatThrownBy(() -> ArrayTypeSignature.erased("int", 256))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArrayTypeSignature.erased("java.lang.String", 20000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
