package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.types.ParseException;
import nonapi.io.github.classgraph.types.Parser;

/**
 * The type signature parser is recursive, so it limits how deeply type argument lists may be nested, so that a
 * corrupt or crafted signature cannot make it overflow the stack.
 */
public class TypeArgumentNestingDepthTest {
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
     * A class type signature whose type arguments are nested to the given depth, {@code La<La<...La;...>;>;}.
     *
     * @param depth
     *            the number of nested type argument lists
     * @return the type signature.
     */
    private static String nestedTypeArguments(final int depth) {
        return repeat("La<", depth) + "La;" + repeat(">;", depth);
    }

    /**
     * Type arguments nested up to the limit parse, and nesting them one level deeper is a parse error.
     *
     * @throws ParseException
     *             if a type signature that should parse could not be parsed.
     */
    @Test
    public void typeArgumentsNestedPastTheLimitCannotBeParsed() throws ParseException {
        final int limit = Parser.MAX_TYPE_ARGUMENT_NESTING_DEPTH;
        assertThat(TypeSignature.parse(nestedTypeArguments(limit), null))
                .hasToString(nestedTypeArguments(limit).replace("La", "a").replace(";", ""));
        assertThatExceptionOfType(ParseException.class)
                .isThrownBy(() -> TypeSignature.parse(nestedTypeArguments(limit + 1), null))
                .withMessageContaining("nested more than " + limit + " deep");
        // The limit applies to method signatures too, which are parsed by the same rules
        assertThatExceptionOfType(ParseException.class)
                .isThrownBy(() -> MethodTypeSignature.parse("(" + nestedTypeArguments(limit + 1) + ")V", null));
        // A type signature nested far past the limit is a parse error, not a StackOverflowError
        assertThatExceptionOfType(ParseException.class)
                .isThrownBy(() -> TypeSignature.parse(nestedTypeArguments(100000), null));
    }

    /**
     * The limit is on how deeply type argument lists are nested, not on how many lists a signature has.
     *
     * @throws ParseException
     *             if a type signature that should parse could not be parsed.
     */
    @Test
    public void theLimitIsOnNestingNotOnTheNumberOfTypeArgumentLists() throws ParseException {
        final int limit = Parser.MAX_TYPE_ARGUMENT_NESTING_DEPTH;
        // Many sibling type arguments, each with its own type argument list
        final String siblings = "La<" + repeat("La<La;>;", limit * 2) + ">;";
        assertThat(TypeSignature.parse(siblings, null)).isNotNull();
        // Many type argument lists on the inner classes of one class reference
        final String suffixes = "La<La;>" + repeat(".b<La;>", limit * 2) + ";";
        assertThat(TypeSignature.parse(suffixes, null)).isNotNull();
        // Two parameters, each nested to the limit
        assertThat(MethodTypeSignature.parse(
                "(" + nestedTypeArguments(limit) + nestedTypeArguments(limit) + ")" + nestedTypeArguments(limit),
                null)).isNotNull();
    }
}
