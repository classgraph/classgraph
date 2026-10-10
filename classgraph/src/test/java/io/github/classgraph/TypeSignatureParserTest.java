package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TypeSignatureParser}.
 */
public class TypeSignatureParserTest {
    /** There is nothing to parse in a null string, and that is a parse error rather than a null dereference. */
    @Test
    public void aNullStringCannotBeParsed() {
        assertThatExceptionOfType(TypeSignatureParseException.class)
                .isThrownBy(() -> new TypeSignatureParser(null));
    }

    /** Reading consumes the input one character at a time, until it runs out. */
    @Test
    public void readingConsumesTheInputOneCharacterAtATime() throws TypeSignatureParseException {
        final var parser = new TypeSignatureParser("ab");
        assertThat(parser.hasMore()).isTrue();
        assertThat(parser.getc()).isEqualTo('a');
        assertThat(parser.getc()).isEqualTo('b');
        assertThat(parser.hasMore()).isFalse();
        assertThatExceptionOfType(TypeSignatureParseException.class).isThrownBy(parser::getc);
    }

    /** Peeking looks at the next character without consuming it, and reports the end of the input as a nul. */
    @Test
    public void peekingDoesNotConsumeTheInput() throws TypeSignatureParseException {
        final var parser = new TypeSignatureParser("ab");
        assertThat(parser.peek()).isEqualTo('a');
        assertThat(parser.peek()).isEqualTo('a');
        assertThat(parser.getPosition()).isZero();
        parser.next();
        parser.next();
        assertThat(parser.peek()).isEqualTo('\0');
    }

    /** A character that was expected is consumed, and one that was not is a parse error. */
    @Test
    public void expectingACharacterConsumesItOnlyIfItIsThere() throws TypeSignatureParseException {
        final var parser = new TypeSignatureParser("ab");
        parser.expect('a');
        assertThat(parser.getPosition()).isEqualTo(1);
        assertThatExceptionOfType(TypeSignatureParseException.class).isThrownBy(() -> parser.expect('c'));
        // Expecting a character when the input has run out is a parse error, not an out-of-bounds read
        final var emptyParser = new TypeSignatureParser("");
        assertThatExceptionOfType(TypeSignatureParseException.class).isThrownBy(() -> emptyParser.expect('a'));
    }

    /** A character can be skipped without reading it. */
    @Test
    public void aCharacterCanBeSkippedWithoutReadingIt() throws TypeSignatureParseException {
        final var parser = new TypeSignatureParser("ab");
        parser.next();
        assertThat(parser.peek()).isEqualTo('b');
    }

    /** Characters are accumulated into the token buffer, which is emptied when the token is read. */
    @Test
    public void theTokenBufferAccumulatesUntilItIsRead() throws TypeSignatureParseException {
        final var parser = new TypeSignatureParser("");
        assertThat(parser.currToken()).isEmpty();
        parser.appendToToken('a');
        parser.appendToToken('b');
        assertThat(parser.currToken()).isEqualTo("ab");
        // Reading the token empties the buffer, so the next token starts from scratch
        parser.appendToToken('d');
        assertThat(parser.currToken()).isEqualTo("d");
    }

    /** Part of the input can be read back without moving the parser position. */
    @Test
    public void partOfTheInputCanBeReadBackWithoutMovingThePosition() throws TypeSignatureParseException {
        final var parser = new TypeSignatureParser("abcdef");
        parser.expect('a');
        parser.expect('b');
        assertThat(parser.getSubstring(0, 3)).isEqualTo("abc");
        assertThat(parser.getPosition()).isEqualTo(2);
    }

    /**
     * The parsing context shows the input on either side of the current position, with the special characters in it
     * escaped so that the context is readable on one line, and shows the current token and position.
     */
    @Test
    public void theParsingContextShowsTheInputOnEitherSideOfThePosition() throws TypeSignatureParseException {
        final var parser = new TypeSignatureParser("ab\ncd");
        parser.next();
        parser.next();
        parser.next();
        parser.appendToToken('t');
        assertThat(parser.getPositionInfo()).contains("before: \"ab\\n\"").contains("after: \"cd\"")
                .contains("position: 3").contains("token: \"t\"");
        // The context is what a parse error reports, so the two are the same
        assertThat(parser).hasToString(parser.getPositionInfo());
    }

    /** Only a window of the input around the current position is shown, so that the context stays readable. */
    @Test
    public void onlyAWindowOfTheInputAroundThePositionIsShown() throws TypeSignatureParseException {
        final var parser = new TypeSignatureParser("x".repeat(500));
        for (var i = 0; i < 250; i++) {
            parser.next();
        }
        final var positionInfo = parser.getPositionInfo();
        assertThat(positionInfo).contains("position: 250");
        assertThat(positionInfo.length()).isLessThan(300);
    }

    /**
     * A class type signature whose type arguments are nested to the given depth, {@code La<La<...La;...>;>;}.
     *
     * @param depth
     *            the number of nested type argument lists
     * @return the type signature.
     */
    private static String nestedTypeArguments(final int depth) {
        return "La<".repeat(depth) + "La;" + ">;".repeat(depth);
    }

    /**
     * Type arguments nested up to the limit parse, and nesting them one level deeper is a parse error, so that a
     * corrupt or crafted signature cannot make the recursive parser overflow the stack.
     */
    @Test
    public void typeArgumentsNestedPastTheLimitCannotBeParsed() throws TypeSignatureParseException {
        final var limit = TypeSignatureParser.MAX_TYPE_ARGUMENT_NESTING_DEPTH;
        assertThat(TypeSignature.parse(nestedTypeArguments(limit), null))
                .hasToString(nestedTypeArguments(limit).replace("La", "a").replace(";", ""));
        assertThatExceptionOfType(TypeSignatureParseException.class)
                .isThrownBy(() -> TypeSignature.parse(nestedTypeArguments(limit + 1), null))
                .withMessageContaining("nested more than " + limit + " deep");
        // The limit applies to method signatures too, which are parsed by the same rules
        assertThatExceptionOfType(TypeSignatureParseException.class)
                .isThrownBy(() -> MethodTypeSignature.parse("(" + nestedTypeArguments(limit + 1) + ")V", null));
    }

    /** The limit is on how deeply type argument lists are nested, not on how many lists a signature has. */
    @Test
    public void theLimitIsOnNestingNotOnTheNumberOfTypeArgumentLists() throws TypeSignatureParseException {
        final var limit = TypeSignatureParser.MAX_TYPE_ARGUMENT_NESTING_DEPTH;
        // Many sibling type arguments, each with its own type argument list
        final var siblings = "La<" + "La<La;>;".repeat(limit * 2) + ">;";
        assertThat(TypeSignature.parse(siblings, null)).isNotNull();
        // Many type argument lists on the inner classes of one class reference
        final var suffixes = "La<La;>" + ".b<La;>".repeat(limit * 2) + ";";
        assertThat(TypeSignature.parse(suffixes, null)).isNotNull();
        // Two parameters, each nested to the limit
        assertThat(MethodTypeSignature.parse(
                "(" + nestedTypeArguments(limit) + nestedTypeArguments(limit) + ")" + nestedTypeArguments(limit),
                null)).isNotNull();
    }
}
