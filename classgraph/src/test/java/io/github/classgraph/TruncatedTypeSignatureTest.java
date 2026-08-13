package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.github.classgraph.base.internal.parser.ParseException;

/**
 * A malformed or truncated type signature must be reported as a {@link ParseException}, since that is what the
 * callers of the signature parser catch (e.g. {@code Classfile#getReferencedClassNames}, which logs and skips the
 * offending constant pool entry). A signature ending in '$' or '.' used to make the parser advance to exactly the
 * end of the string, which was wrongly rejected, throwing {@link IllegalArgumentException} instead.
 */
public class TruncatedTypeSignatureTest {
    /**
     * A class reference signature truncated immediately after a '$' suffix separator.
     */
    @Test
    public void truncatedAfterDollarSignThrowsParseException() {
        assertThatThrownBy(() -> TypeSignature.parse("LFoo$", /* definingClass = */ null))
                .isInstanceOf(ParseException.class);
    }

    /**
     * A class reference signature truncated immediately after a '.' suffix separator.
     */
    @Test
    public void truncatedAfterDotThrowsParseException() {
        assertThatThrownBy(() -> TypeSignature.parse("LFoo.", /* definingClass = */ null))
                .isInstanceOf(ParseException.class);
    }

    /** A well-formed nested class signature still parses. */
    @Test
    public void wellFormedNestedClassSignatureStillParses() throws ParseException {
        final var sig = TypeSignature.parse("Lcom/xyz/Foo$Bar;", /* definingClass = */ null);
        assertThat(sig).isInstanceOf(ClassRefTypeSignature.class);
        assertThat(((ClassRefTypeSignature) sig).getBaseClassName()).isEqualTo("com.xyz.Foo");
    }
}
