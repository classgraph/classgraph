package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.types.ParseException;

/**
 * Tests for {@link ArrayTypeSignature}. An array type has at most 255 dimensions (JVMS 4.3.2 and 4.4.1), and an
 * array type with more dimensions must be rejected, since the nested dimensions of an array type signature are
 * built, compared and hashed by recursion, one call per dimension.
 */
public class ArrayTypeSignatureTest {
    /**
     * Repeat a string.
     *
     * @param str
     *            the string
     * @param count
     *            the number of times to repeat it
     * @return the repeated string
     */
    private static String repeat(final String str, final int count) {
        final StringBuilder buf = new StringBuilder();
        for (int i = 0; i < count; i++) {
            buf.append(str);
        }
        return buf.toString();
    }

    /**
     * An array type signature with 255 dimensions parses.
     *
     * @throws ParseException
     *             if the signature does not parse
     */
    @Test
    public void arrayTypeWith255DimensionsParses() throws ParseException {
        final TypeSignature typeSignature = TypeSignature.parse(repeat("[", 255) + "I",
                /* definingClassName = */ null);
        assertThat(typeSignature).isInstanceOf(ArrayTypeSignature.class);
        assertThat(((ArrayTypeSignature) typeSignature).getNumDimensions()).isEqualTo(255);
    }

    /** An array type signature with 256 dimensions does not parse. */
    @Test
    public void arrayTypeWith256DimensionsDoesNotParse() {
        assertThatThrownBy(() -> TypeSignature.parse(repeat("[", 256) + "I", /* definingClassName = */ null))
                .isInstanceOf(ParseException.class).hasMessageContaining("255");
    }

    /** An array type signature with 20000 dimensions does not parse, rather than overflowing the stack. */
    @Test
    public void arrayTypeWith20000DimensionsDoesNotParse() {
        assertThatThrownBy(() -> TypeSignature.parse(repeat("[", 20000) + "I", /* definingClassName = */ null))
                .isInstanceOf(ParseException.class);
    }

    /** The class info of an array class can have at most 255 dimensions. */
    @Test
    public void arrayClassInfoHasAtMost255Dimensions() {
        final ClassInfo classInfo = ClassInfo.getOrCreateClassInfo("int" + repeat("[]", 255),
                new HashMap<String, ClassInfo>());
        assertThat(((ArrayClassInfo) classInfo).getNumDimensions()).isEqualTo(255);
        assertThatThrownBy(
                () -> ClassInfo.getOrCreateClassInfo("int" + repeat("[]", 256), new HashMap<String, ClassInfo>()))
                        .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClassInfo.getOrCreateClassInfo(repeat("[", 20000) + "Ljava/lang/String;",
                new HashMap<String, ClassInfo>())).isInstanceOf(IllegalArgumentException.class);
    }
}
