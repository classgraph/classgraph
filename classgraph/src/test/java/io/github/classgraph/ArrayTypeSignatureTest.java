package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
