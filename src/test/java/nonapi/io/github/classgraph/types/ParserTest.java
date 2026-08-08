package nonapi.io.github.classgraph.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Tests for the position bounds of {@link Parser}.
 *
 * <p>
 * The end-of-string position is a valid parser position -- it is what
 * {@link Parser#getPosition()} returns once the whole input has been consumed,
 * and {@link Parser#peek()} and {@link Parser#hasMore()} both handle it.
 * Rejecting it in {@link Parser#advance(int)} made a truncated type signature
 * throw {@link IllegalArgumentException} out of the signature parser, rather
 * than the {@link ParseException} that its callers catch.
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
     * A negative skip distance, and one large enough to overflow int when added to
     * the position, are rejected.
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
}
