package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.base.internal.parser.ParseException;

/**
 * {@code MethodTypeSignature#toStringInternal} passes {@code useSimpleNames} down to the type parameters, the
 * parameter types and the throws signatures, but it used to render the result type with a bare
 * {@code resultType.toString()} call, so the return type kept its fully-qualified name in the output of
 * {@link HierarchicalTypeSignature#toStringWithSimpleNames()}.
 */
public class MethodTypeSignatureSimpleNamesTest {
    /** The result type uses a simple name, like the parameter and throws types. */
    @Test
    public void resultTypeUsesSimpleName() throws ParseException {
        final var sig = MethodTypeSignature.parse("(Ljava/lang/String;)Ljava/util/Map;^Ljava/io/IOException;",
                /* definingClassName = */ null);
        assertThat(sig.toStringWithSimpleNames()).isEqualTo("Map (String) throws IOException");
        assertThat(sig.toString()).isEqualTo("java.util.Map (java.lang.String) throws java.io.IOException");
    }
}
