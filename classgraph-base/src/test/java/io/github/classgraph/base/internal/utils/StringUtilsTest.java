package io.github.classgraph.base.internal.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Tests for {@link StringUtils}. */
public class StringUtilsTest {
    /**
     * Build a byte array from int literals, so that bytes above 0x7f can be written without casts.
     *
     * @param values
     *            The byte values.
     * @return The byte array.
     */
    private static byte[] bytes(final int... values) {
        final var arr = new byte[values.length];
        for (var i = 0; i < values.length; i++) {
            arr[i] = (byte) values[i];
        }
        return arr;
    }

    /**
     * Read a whole byte array as a modified UTF8 string.
     *
     * @param arr
     *            The bytes to read.
     * @return The string.
     */
    private static String readString(final byte[] arr) {
        return StringUtils.readStringModifiedUtf8(arr, 0, arr.length);
    }

    /** A string with nothing to escape in it is returned as it is, rather than being copied. */
    @Test
    public void stringsWithNothingToEscapeAreReturnedUnchanged() {
        final var plain = "abc XYZ 019 !$%&/()[]{}";
        assertThat(StringUtils.escapeString(plain)).isSameAs(plain);
    }

    /** Quotes, backslashes and the control characters with their own escape sequences. */
    @Test
    public void charactersWithTheirOwnEscapeSequencesAreEscaped() {
        assertThat(StringUtils.escapeString("a\"b")).isEqualTo("a\\\"b");
        assertThat(StringUtils.escapeString("a\\b")).isEqualTo("a\\\\b");
        assertThat(StringUtils.escapeString("a\nb\rc\td\be\ff")).isEqualTo("a\\nb\\rc\\td\\be\\ff");
    }

    /** Control characters and characters outside the printable ASCII range become unicode escapes. */
    @Test
    public void charactersWithoutAnEscapeSequenceBecomeUnicodeEscapes() {
        assertThat(StringUtils.escapeString("\0\1\37")).isEqualTo("\\u0000\\u0001\\u001f");
        assertThat(StringUtils.escapeString("\177")).isEqualTo("\\u007f");
        // Characters in the Latin-1 range above the printable ASCII range are escaped as well, since a log or a
        // toString() cannot be relied on to be read back in the same character encoding it was written in
        assertThat(StringUtils.escapeString("é")).isEqualTo("\\u00e9");
        assertThat(StringUtils.escapeString("中")).isEqualTo("\\u4e2d");
    }

    /** A single character is escaped for display between single quotes, rather than double quotes. */
    @Test
    public void charactersAreEscapedForDisplayBetweenSingleQuotes() {
        assertThat(StringUtils.escapeChar('a')).isEqualTo("a");
        assertThat(StringUtils.escapeChar('\'')).isEqualTo("\\'");
        // A double quote does not need escaping between single quotes, unlike in escapeString()
        assertThat(StringUtils.escapeChar('"')).isEqualTo("\"");
        assertThat(StringUtils.escapeChar('\\')).isEqualTo("\\\\");
        assertThat(StringUtils.escapeChar('\n')).isEqualTo("\\n");
        assertThat(StringUtils.escapeChar('\1')).isEqualTo("\\u0001");
        assertThat(StringUtils.escapeChar('é')).isEqualTo("\\u00e9");
        assertThat(StringUtils.escapeChar('中')).isEqualTo("\\u4e2d");
    }

    /** An ASCII string is read as it is. */
    @Test
    public void asciiStringsAreRead() {
        final var arr = "java/lang/String".getBytes(StandardCharsets.UTF_8);
        assertThat(readString(arr)).isEqualTo("java/lang/String");
        assertThat(readString(new byte[0])).isEmpty();
    }

    /** Only the requested range of the array is read, since strings are read out of a whole classfile. */
    @Test
    public void onlyTheRequestedRangeIsRead() {
        final var arr = "xxjava/langyy".getBytes(StandardCharsets.UTF_8);
        assertThat(StringUtils.readStringModifiedUtf8(arr, 2, 9)).isEqualTo("java/lang");
    }

    /**
     * An out of range offset or length is rejected, rather than being allowed to throw
     * {@link ArrayIndexOutOfBoundsException} out of the middle of the classfile parser. The two large values are
     * the case that a range check written as {@code startOffset + numBytes > arr.length} would let through, since
     * the sum overflows to a negative number.
     */
    @Test
    public void outOfRangeOffsetsAndLengthsAreRejected() {
        final var arr = "abc".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> StringUtils.readStringModifiedUtf8(arr, -1, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("offset or numBytes out of range");
        assertThatThrownBy(() -> StringUtils.readStringModifiedUtf8(arr, 0, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("offset or numBytes out of range");
        assertThatThrownBy(() -> StringUtils.readStringModifiedUtf8(arr, 1, 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("offset or numBytes out of range");
        assertThatThrownBy(() -> StringUtils.readStringModifiedUtf8(arr, Integer.MAX_VALUE, Integer.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("offset or numBytes out of range");
    }

    /** The two-byte and three-byte forms of the classfile "modified UTF8" encoding are decoded. */
    @Test
    public void multiByteSequencesAreDecoded() {
        // Modified UTF8 encodes a null character as two bytes, so that a string can never contain a zero byte
        assertThat(readString(bytes(0xc0, 0x80))).isEqualTo("\0");
        assertThat(readString(bytes(0xc3, 0xa9))).isEqualTo("é");
        assertThat(readString(bytes(0xe4, 0xb8, 0xad))).isEqualTo("中");
        // An ASCII character after a multi-byte character is read by the same loop as the multi-byte characters
        assertThat(readString(bytes(0xc3, 0xa9, 'A', 0xe4, 0xb8, 0xad, 'B'))).isEqualTo("éA中B");
    }

    /** A '/' is decoded as a '/' however it was encoded, including in the overlong forms of the encoding. */
    @Test
    public void slashIsDecodedFromEveryFormOfTheEncoding() {
        // The '/' written in the one-, two- and three-byte forms of the encoding in turn
        assertThat(readString(bytes(0xc3, 0xa9, '/'))).isEqualTo("é/");
        assertThat(readString(bytes(0xc0, 0xaf))).isEqualTo("/");
        assertThat(readString(bytes(0xe0, 0x80, 0xaf))).isEqualTo("/");
    }

    /** A byte sequence that is not valid modified UTF8 is rejected. */
    @Test
    public void badModifiedUtf8IsRejected() {
        // A leading byte that starts no valid sequence: a stray continuation byte, and the four-byte form, which
        // modified UTF8 does not have (characters outside the basic multilingual plane are written as two
        // three-byte surrogates instead)
        assertThatThrownBy(() -> readString(bytes(0x80))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bad modified UTF8");
        assertThatThrownBy(() -> readString(bytes(0xf0, 0x9f, 0x98, 0x80)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Bad modified UTF8");
        // A two- or three-byte sequence cut short by the end of the string
        assertThatThrownBy(() -> readString(bytes(0xc3))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bad modified UTF8");
        assertThatThrownBy(() -> readString(bytes(0xe4, 0xb8))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bad modified UTF8");
        // A sequence whose continuation bytes are not continuation bytes
        assertThatThrownBy(() -> readString(bytes(0xc3, 'A'))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bad modified UTF8");
        assertThatThrownBy(() -> readString(bytes(0xe4, 'A', 0xad))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bad modified UTF8");
        assertThatThrownBy(() -> readString(bytes(0xe4, 0xb8, 'A'))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bad modified UTF8");
    }

    /** A type descriptor has '/' replaced with '.', and its 'L' prefix and ';' suffix stripped, when asked for. */
    @Test
    public void aTypeDescriptorIsNormalized() {
        assertThat(StringUtils.normalizeTypeDescriptor("Ljava/lang/String;", /* replaceSlashWithDot = */ true,
                /* stripLSemicolon = */ true)).isEqualTo("java.lang.String");
        assertThat(StringUtils.normalizeTypeDescriptor("Ljava/lang/String;", true, false))
                .isEqualTo("Ljava.lang.String;");
        assertThat(StringUtils.normalizeTypeDescriptor("Ljava/lang/String;", false, true))
                .isEqualTo("java/lang/String");
        assertThat(StringUtils.normalizeTypeDescriptor("L;", false, true)).isEmpty();
    }

    /** A string that is not asked to be normalized is returned as it is, rather than being copied. */
    @Test
    public void aTypeDescriptorThatIsNotNormalizedIsReturnedUnchanged() {
        final var plain = "Ljava/lang/String;";
        assertThat(StringUtils.normalizeTypeDescriptor(plain, false, false)).isSameAs(plain);
    }

    /** A string that was expected to be a type descriptor but is not is rejected, and is quoted in the message. */
    @Test
    public void aStringThatIsNotATypeDescriptorIsRejected() {
        assertThatThrownBy(() -> StringUtils.normalizeTypeDescriptor("java/lang/String", false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expected string to start with 'L' and end with ';', got \"java/lang/String\"");
        // The message quotes the string in the form it was rejected in, after the '/' characters were replaced
        assertThatThrownBy(() -> StringUtils.normalizeTypeDescriptor("java/lang/String", true, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expected string to start with 'L' and end with ';', got \"java.lang.String\"");
        // A string too short to have both an 'L' and a ';' in it
        assertThatThrownBy(() -> StringUtils.normalizeTypeDescriptor("L", false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expected string to start with 'L' and end with ';', got \"L\"");
        assertThatThrownBy(() -> StringUtils.normalizeTypeDescriptor("", false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expected string to start with 'L' and end with ';', got \"\"");
    }

    /** Elements are joined by the separator, and null elements are written as "null" rather than throwing. */
    @Test
    public void elementsAreJoinedBySeparator() {
        assertThat(StringUtils.join(", ", List.of("a", "b", "c"))).isEqualTo("a, b, c");
        assertThat(StringUtils.join(", ", List.of("a"))).isEqualTo("a");
        assertThat(StringUtils.join(", ", List.of())).isEmpty();
        assertThat(StringUtils.join(", ", Arrays.asList("a", null, "c"))).isEqualTo("a, null, c");
        assertThat(StringUtils.join(", ", List.of(1, 2, 3))).isEqualTo("1, 2, 3");
    }

    /** The joined elements can be surrounded by an opening and a closing token. */
    @Test
    public void joinedElementsCanBeSurroundedByTokens() {
        final var buf = new StringBuilder("x = ");
        StringUtils.join(buf, "[", ", ", "]", List.of("a", "b"));
        assertThat(buf.toString()).isEqualTo("x = [a, b]");

        final var empty = new StringBuilder();
        StringUtils.join(empty, "[", ", ", "]", List.of());
        assertThat(empty.toString()).isEqualTo("[]");
    }
}
