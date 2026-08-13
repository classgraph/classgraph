package io.github.classgraph.base.internal.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Parser}.
 *
 * <p>
 * The end-of-string position is a valid parser position -- it is what {@link Parser#getPosition()} returns once the
 * whole input has been consumed, and {@link Parser#peek()} and {@link Parser#hasMore()} both handle it. Rejecting
 * it in {@link Parser#advance(int)} made a truncated type signature throw {@link IllegalArgumentException} out of
 * the signature parser, rather than the {@link ParseException} that its callers catch.
 */
public class ParserTest {
    /** Advancing to exactly the end of the input is valid. */
    @Test
    public void canAdvanceToEndOfString() throws ParseException {
        final var parser = new Parser("abc");
        parser.advance(3);
        assertThat(parser.getPosition()).isEqualTo(3);
        assertThat(parser.hasMore()).isFalse();
        assertThat(parser.peek()).isEqualTo('\0');
    }

    /** Advancing past the end of the input is still rejected. */
    @Test
    public void cannotAdvancePastEndOfString() throws ParseException {
        final var parser = new Parser("abc");
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> parser.advance(4));
    }

    /**
     * A negative skip distance, and one large enough to overflow int when added to the position, are rejected.
     */
    @Test
    public void invalidSkipDistancesAreRejected() throws ParseException {
        final var parser = new Parser("abc");
        parser.advance(2);
        assertThatThrownBy(() -> parser.advance(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.advance(Integer.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class);
        // The failed calls must not have moved the position
        assertThat(parser.getPosition()).isEqualTo(2);
    }

    /** A saved end-of-string position can be restored. */
    @Test
    public void canRestoreEndOfStringPosition() throws ParseException {
        final var parser = new Parser("abc");
        parser.advance(3);
        final var endPosition = parser.getPosition();
        parser.setPosition(0);
        parser.setPosition(endPosition);
        assertThat(parser.getPosition()).isEqualTo(3);
    }

    /**
     * A position past the end of the input, or a negative one, is still rejected.
     */
    @Test
    public void invalidPositionsAreRejected() throws ParseException {
        final var parser = new Parser("abc");
        assertThatThrownBy(() -> parser.setPosition(4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.setPosition(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------------------------------------------------

    /** There is nothing to parse in a null string, and that is a parse error rather than a null dereference. */
    @Test
    public void aNullStringCannotBeParsed() {
        assertThatExceptionOfType(ParseException.class).isThrownBy(() -> new Parser(null));
    }

    /** Reading consumes the input one character at a time, until it runs out. */
    @Test
    public void readingConsumesTheInputOneCharacterAtATime() throws ParseException {
        final var parser = new Parser("ab");
        assertThat(parser.hasMore()).isTrue();
        assertThat(parser.getc()).isEqualTo('a');
        assertThat(parser.getc()).isEqualTo('b');
        assertThat(parser.hasMore()).isFalse();
        assertThatExceptionOfType(ParseException.class).isThrownBy(parser::getc);
    }

    /** Peeking looks at the next character without consuming it, and reports the end of the input as a nul. */
    @Test
    public void peekingDoesNotConsumeTheInput() throws ParseException {
        final var parser = new Parser("ab");
        assertThat(parser.peek()).isEqualTo('a');
        assertThat(parser.peek()).isEqualTo('a');
        assertThat(parser.getPosition()).isZero();
        parser.advance(2);
        assertThat(parser.peek()).isEqualTo('\0');
    }

    /** The next character can be checked against several characters at once, without consuming any of them. */
    @Test
    public void severalCharactersCanBeCheckedAtOnceWithoutConsumingThem() throws ParseException {
        final var parser = new Parser("abcd");
        assertThat(parser.peekMatches("abc")).isTrue();
        assertThat(parser.peekMatches("abd")).isFalse();
        assertThat(parser.getPosition()).isZero();
        parser.advance(2);
        assertThat(parser.peekMatches("cd")).isTrue();
        // A string that runs off the end of the input does not match, rather than failing
        assertThat(parser.peekMatches("cde")).isFalse();
    }

    /** A character that was expected is consumed, and one that was not is a parse error. */
    @Test
    public void expectingACharacterConsumesItOnlyIfItIsThere() throws ParseException {
        final var parser = new Parser("ab");
        parser.expect('a');
        assertThat(parser.getPosition()).isEqualTo(1);
        assertThatExceptionOfType(ParseException.class).isThrownBy(() -> parser.expect('c'));
        // Expecting a character when the input has run out is a parse error, not an out-of-bounds read
        final var emptyParser = new Parser("");
        assertThatExceptionOfType(ParseException.class).isThrownBy(() -> emptyParser.expect('a'));
    }

    /** An expected character can be checked for without consuming it, whether or not it is there. */
    @Test
    public void expectingACharacterWithoutConsumingItLeavesThePositionAlone() throws ParseException {
        final var parser = new Parser("ab");
        parser.peekExpect('a');
        assertThat(parser.getPosition()).isZero();
        assertThatExceptionOfType(ParseException.class).isThrownBy(() -> parser.peekExpect('b'));
        assertThat(parser.getPosition()).isZero();
        // Expecting a character when the input has run out is a parse error, not an out-of-bounds read
        final var emptyParser = new Parser("");
        assertThatExceptionOfType(ParseException.class).isThrownBy(() -> emptyParser.peekExpect('a'));
    }

    /** A character can be skipped without reading it. */
    @Test
    public void aCharacterCanBeSkippedWithoutReadingIt() throws ParseException {
        final var parser = new Parser("ab");
        parser.next();
        assertThat(parser.peek()).isEqualTo('b');
    }

    /** Whitespace of every kind is skipped, up to the next character that is not whitespace. */
    @Test
    public void whitespaceIsSkipped() throws ParseException {
        final var parser = new Parser(" \t\r\n x y");
        parser.skipWhitespace();
        assertThat(parser.getc()).isEqualTo('x');
        parser.skipWhitespace();
        assertThat(parser.getc()).isEqualTo('y');
        // Skipping whitespace when there is none left to skip, or no input left at all, does nothing
        parser.skipWhitespace();
        assertThat(parser.hasMore()).isFalse();
    }

    /** Characters and strings are accumulated into the token buffer, which is emptied when the token is read. */
    @Test
    public void theTokenBufferAccumulatesUntilItIsRead() throws ParseException {
        final var parser = new Parser("");
        assertThat(parser.currToken()).isEmpty();
        parser.appendToToken('a');
        parser.appendToToken("bc");
        assertThat(parser.currToken()).isEqualTo("abc");
        // Reading the token empties the buffer, so the next token starts from scratch
        parser.appendToToken('d');
        assertThat(parser.currToken()).isEqualTo("d");
    }

    /** Part of the input can be read back without moving the parser position. */
    @Test
    public void partOfTheInputCanBeReadBackWithoutMovingThePosition() throws ParseException {
        final var parser = new Parser("abcdef");
        parser.advance(4);
        assertThat(parser.getSubstring(1, 3)).isEqualTo("bc");
        assertThat(parser.getSubsequence(1, 3).toString()).isEqualTo("bc");
        assertThat(parser.getPosition()).isEqualTo(4);
    }

    /** The state object handed to the parser is handed back when it is replaced, so that it can be restored. */
    @Test
    public void theStateObjectIsHandedBackWhenItIsReplaced() throws ParseException {
        final var parser = new Parser("abc");
        assertThat(parser.getState()).isNull();
        assertThat(parser.setState("first")).isNull();
        assertThat(parser.getState()).isEqualTo("first");
        assertThat(parser.setState("second")).isEqualTo("first");
        assertThat(parser.setState(null)).isEqualTo("second");
        assertThat(parser.getState()).isNull();
    }

    /**
     * The parsing context shows the input on either side of the current position, with the special characters in it
     * escaped so that the context is readable on one line, and shows the current token and position.
     */
    @Test
    public void theParsingContextShowsTheInputOnEitherSideOfThePosition() throws ParseException {
        final var parser = new Parser("ab\ncd");
        parser.advance(3);
        parser.appendToToken("tok");
        assertThat(parser.getPositionInfo()).contains("before: \"ab\\n\"").contains("after: \"cd\"")
                .contains("position: 3").contains("token: \"tok\"");
        // The context is what a parse error reports, so the two are the same
        assertThat(parser).hasToString(parser.getPositionInfo());
    }

    /** Only a window of the input around the current position is shown, so that the context stays readable. */
    @Test
    public void onlyAWindowOfTheInputAroundThePositionIsShown() throws ParseException {
        final var parser = new Parser("x".repeat(500));
        parser.advance(250);
        final var positionInfo = parser.getPositionInfo();
        assertThat(positionInfo).contains("position: 250");
        assertThat(positionInfo.length()).isLessThan(300);
    }
}
