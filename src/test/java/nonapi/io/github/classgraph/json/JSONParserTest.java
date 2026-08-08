package nonapi.io.github.classgraph.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.types.ParseException;

/**
 * Tests for {@link JSONParser}'s handling of whitespace and of malformed input.
 *
 * <p>
 * The fast path of {@code JSONParser#parseString()} (taken for strings that
 * contain no escape sequences) used to return without skipping the whitespace
 * after the closing quote, unlike the slow path. That left whitespace between
 * an object key and the ':' that follows it unconsumed, so an object like
 * <code>{"a" : 1}</code> parsed as null. The parser also silently truncated
 * unterminated strings, silently returned null for an object with a missing
 * ':', and reported the backslash rather than the offending character in the
 * "invalid escape sequence" message.
 */
public class JSONParserTest {
    /**
     * Whitespace between an object key and its ':' must not stop the object from
     * parsing.
     */
    @Test
    public void whitespaceBetweenKeyAndColonIsAllowed() throws ParseException {
        final var parsed = JSONParser.parseJSON("{\"a\" : 1}");
        assertThat(parsed).isInstanceOf(JSONObject.class);
        final var obj = (JSONObject) parsed;
        assertThat(obj.items).hasSize(1);
        assertThat(obj.items.get(0).getKey()).isEqualTo("a");
        assertThat(obj.items.get(0).getValue()).isEqualTo(1);
    }

    /**
     * The same, for a tab, and for a key that does contain an escape sequence (i.e.
     * the slow path).
     */
    @Test
    public void whitespaceIsSkippedOnBothParseStringPaths() throws ParseException {
        assertThat(((JSONObject) JSONParser.parseJSON("{\"a\"\t:1}")).items.get(0).getKey()).isEqualTo("a");
        assertThat(((JSONObject) JSONParser.parseJSON("{\"a\\n\" : 1}")).items.get(0).getKey()).isEqualTo("a\n");
    }

    /** A string with no closing quote must be reported, not silently truncated. */
    @Test
    public void unterminatedStringIsRejected() {
        assertThatThrownBy(() -> JSONParser.parseJSON("\"unterminated")).isInstanceOf(ParseException.class)
                .hasMessageContaining("Unterminated string");
    }

    /**
     * An object entry with a missing ':' must be reported, not silently parsed as a
     * null object.
     */
    @Test
    public void missingColonIsRejected() {
        assertThatThrownBy(() -> JSONParser.parseJSON("{\"a\" 1}")).isInstanceOf(ParseException.class)
                .hasMessageContaining("Expected ':'");
    }

    /**
     * The "invalid escape sequence" message must name the offending character, not
     * the backslash.
     */
    @Test
    public void invalidEscapeSequenceNamesTheOffendingCharacter() {
        assertThatThrownBy(() -> JSONParser.parseJSON("\"bad\\q\"")).isInstanceOf(ParseException.class)
                .hasMessageContaining("Invalid escape sequence: \\q");
    }

    /** Well-formed input still parses. */
    @Test
    public void wellFormedInputStillParses() throws ParseException {
        assertThat(((JSONObject) JSONParser.parseJSON("{\"a\":1}")).items.get(0).getValue()).isEqualTo(1);
        assertThat(((JSONArray) JSONParser.parseJSON("[\"x\" , \"y\"]")).items).hasSize(2);
        assertThat(JSONParser.parseJSON("  \"hello\"  ").toString()).isEqualTo("hello");
        assertThat(JSONParser.parseJSON("null")).isNull();
    }
}
