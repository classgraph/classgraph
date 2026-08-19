package io.github.classgraph.vfs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link ManifestParser}, which reads the main section of a jarfile manifest.
 */
public class ManifestParserTest {
    /**
     * Parse a manifest written as text.
     *
     * @param manifest
     *            the text of the manifest.
     * @return the attributes of its main section.
     */
    private static Map<String, String> parse(final String manifest) {
        return ManifestParser.parse(manifest.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parse a manifest assembled from a mixture of text and raw bytes, so that a byte sequence that is not valid
     * text can be written into it.
     *
     * @param parts
     *            the parts of the manifest. A {@link String} is written as UTF-8, an {@link Integer} as a single
     *            raw byte.
     * @return the attributes of its main section.
     * @throws IOException
     *             if the parts could not be assembled.
     */
    private static Map<String, String> parseBytes(final Object... parts) throws IOException {
        final var manifest = new ByteArrayOutputStream();
        for (final Object part : parts) {
            if (part instanceof final String text) {
                manifest.write(text.getBytes(StandardCharsets.UTF_8));
            } else {
                manifest.write((Integer) part);
            }
        }
        return ManifestParser.parse(manifest.toByteArray());
    }

    /**
     * Every attribute of the main section is read, and each keeps its own value. {@code Add-Opens} used to be
     * stored under the key of {@code Add-Exports}, so it overwrote any {@code Add-Exports} value.
     */
    @Test
    public void eachAttributeKeepsItsOwnValue() {
        assertThat(parse("Manifest-Version: 1.0\r\n" //
                + "Add-Exports: java.base/jdk.internal.misc\r\n" //
                + "Add-Opens: java.base/java.lang\r\n" //
                + "\r\n")).containsOnly(Map.entry("Add-Exports", "java.base/jdk.internal.misc"),
                        Map.entry("Add-Opens", "java.base/java.lang"), Map.entry("Manifest-Version", "1.0"));
    }

    /**
     * An attribute name holding a byte outside the ASCII range must not derail the parse of the attributes that
     * follow it. Attribute names used to be matched through a 256-entry lookup table indexed with the raw signed
     * byte, so a byte greater than 0x7f was negative and threw {@link ArrayIndexOutOfBoundsException}, making the
     * whole jarfile unreadable.
     *
     * @throws IOException
     *             if the manifest could not be assembled.
     */
    @Test
    public void aNonAsciiAttributeNameDoesNotDerailTheParse() throws IOException {
        // The first attribute name is the same length as "Class-Path", so that its bytes are compared against that
        // name one by one, but its last byte lies outside the ASCII range
        assertThat(parseBytes("Manifest-Version: 1.0\r\n", "Class-Pat", 0xc3, ": value\r\n",
                "Class-Path: lib/dep.jar\r\n\r\n")).containsEntry("Class-Path", "lib/dep.jar");
    }

    /** Manifest attribute names are case insensitive, so an attribute is found however it is capitalized. */
    @Test
    public void attributeNamesAreCaseInsensitive() {
        final var attributes = parse("Class-Path: lib/dep.jar\r\n\r\n");
        assertThat(attributes).containsEntry("CLASS-PATH", "lib/dep.jar").containsEntry("class-path",
                "lib/dep.jar");
    }

    /**
     * Only the main section is parsed. The sections that follow it describe individual entries of the jarfile
     * rather than the jarfile as a whole, so their attributes are not attributes of the manifest.
     */
    @Test
    public void onlyTheMainSectionIsParsed() {
        assertThat(parse("Manifest-Version: 1.0\r\n" //
                + "Main-Class: com.xyz.Main\r\n" //
                + "\r\n" //
                + "Name: com/xyz/Widget.class\r\n" //
                + "Sealed: true\r\n" //
                + "\r\n")).containsOnlyKeys("Manifest-Version", "Main-Class");
    }

    /** A value continued across following lines, each starting with a space, is joined back into one value. */
    @Test
    public void aContinuedValueIsJoined() {
        assertThat(parse("Class-Path: lib/first.jar\r\n" //
                + " lib/second.jar\r\n" //
                + " lib/third.jar\r\n" //
                + "Main-Class: com.xyz.Main\r\n" //
                + "\r\n")).containsEntry("Class-Path", "lib/first.jarlib/second.jarlib/third.jar")
                .containsEntry("Main-Class", "com.xyz.Main");
    }

    /**
     * A manifest is wrapped to 72 bytes, not 72 characters, so the bytes of a multi-byte character can be split
     * across a line break. The segments therefore have to be joined before they are decoded, not after.
     *
     * @throws IOException
     *             if the manifest could not be assembled.
     */
    @Test
    public void aMultiByteCharacterSplitAcrossALineBreakSurvives() throws IOException {
        // The two bytes of "é" are 0xc3 0xa9
        assertThat(parseBytes("Implementation-Vendor: caf", 0xc3, "\r\n ", 0xa9, "\r\n\r\n"))
                .containsEntry("Implementation-Vendor", "café");
    }

    /** A manifest may terminate its lines with CR, LF or CRLF, and may mix them. */
    @Test
    public void crLfAndCrLfAreAllLineTerminators() {
        assertThat(parse("Manifest-Version: 1.0\r" //
                + "Main-Class: com.xyz.Main\n" //
                + "Class-Path: lib/dep.jar\r\n" //
                + "\n")).containsOnlyKeys("Manifest-Version", "Main-Class", "Class-Path");
    }

    /** A line with no colon on it is not an attribute, so it is skipped rather than ending the main section. */
    @Test
    public void aLineWithNoColonIsSkipped() {
        assertThat(parse("Manifest-Version: 1.0\r\n" //
                + "this line has no colon\r\n" //
                + "Main-Class: com.xyz.Main\r\n" //
                + "\r\n")).containsOnlyKeys("Manifest-Version", "Main-Class");
    }

    /**
     * The space that separates an attribute from its value is not part of the value. The jarfile specification
     * requires exactly one space and no trailing space, but manifests in the wild are not always so careful.
     */
    @Test
    public void theSpacesAroundAValueAreNotPartOfIt() {
        assertThat(parse("Tight:1.0\r\nLoose:   1.0\r\nTrailing: 1.0   \r\n\r\n")).containsEntry("Tight", "1.0")
                .containsEntry("Loose", "1.0").containsEntry("Trailing", "1.0");
    }

    /** An attribute with no value is read as an empty value, rather than being skipped or running on. */
    @Test
    public void anAttributeWithNoValueIsEmpty() {
        assertThat(parse("Empty:\r\nMain-Class: com.xyz.Main\r\n\r\n")).containsEntry("Empty", "")
                .containsEntry("Main-Class", "com.xyz.Main");
    }

    /** The last line of a manifest is read even if it is not terminated, and a blank line need not follow it. */
    @Test
    public void anUnterminatedLastLineIsRead() {
        assertThat(parse("Manifest-Version: 1.0\r\nMain-Class: com.xyz.Main")).containsEntry("Main-Class",
                "com.xyz.Main");
    }

    /** A manifest with no attributes at all, or with an empty main section, yields no attributes. */
    @Test
    public void anEmptyManifestHasNoAttributes() {
        assertThat(parse("")).isEmpty();
        assertThat(parse("\r\nName: com/xyz/Widget.class\r\n")).isEmpty();
    }

    /** The attributes are handed out as an unmodifiable map, since the manifest of a root is cached and shared. */
    @Test
    public void theAttributesAreUnmodifiable() {
        final var attributes = parse("Manifest-Version: 1.0\r\n\r\n");
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> attributes.put("Main-Class", "com.xyz.Main"));
    }

    // -----------------------------------------------------------------------------------------------------------
    // Reading a manifest from an InputStream

    /**
     * An {@link java.io.InputStream} over a byte array that hands out at most a fixed number of bytes per read, and
     * counts how many bytes were read from it in total.
     */
    private static final class ChunkedInputStream extends ByteArrayInputStream {
        /** The maximum number of bytes to return from a single call to {@link #read(byte[], int, int)}. */
        private final int chunkSize;

        /** The number of bytes read from this stream so far. */
        int numBytesRead;

        ChunkedInputStream(final byte[] bytes, final int chunkSize) {
            super(bytes);
            this.chunkSize = chunkSize;
        }

        @Override
        public synchronized int read(final byte[] dstBuf, final int off, final int len) {
            final var numBytes = super.read(dstBuf, off, Math.min(len, chunkSize));
            if (numBytes > 0) {
                numBytesRead += numBytes;
            }
            return numBytes;
        }
    }

    /**
     * Parse a manifest written as text, reading it from an {@link java.io.InputStream} that hands out at most
     * {@code chunkSize} bytes per read.
     *
     * @param manifest
     *            the text of the manifest.
     * @param chunkSize
     *            the maximum number of bytes to return from a single read.
     * @return the attributes of its main section.
     * @throws IOException
     *             if the manifest could not be read.
     */
    private static Map<String, String> parseStream(final String manifest, final int chunkSize) throws IOException {
        return ManifestParser.parse(new ChunkedInputStream(manifest.getBytes(StandardCharsets.UTF_8), chunkSize));
    }

    /**
     * Reading a manifest from a stream gives the same attributes as reading it from a byte array, whatever line
     * terminator it uses, and however few bytes each read of the stream returns. Reading a byte at a time splits
     * every CRLF across two reads, so a CR that arrives at the end of one read must not be taken for a line
     * terminator in its own right.
     */
    @Test
    public void aStreamIsParsedTheSameWayAsAByteArray() throws IOException {
        for (final String eol : new String[] { "\r\n", "\n", "\r" }) {
            final var manifest = "Manifest-Version: 1.0" + eol //
                    + "Multi-Release: true" + eol //
                    + "Main-Class: com.xyz.Main" + eol //
                    + eol //
                    + "Name: com/xyz/Widget.class" + eol //
                    + "SHA-256-Digest: 0000" + eol;
            final var expected = Map.of("Manifest-Version", "1.0", "Multi-Release", "true", "Main-Class",
                    "com.xyz.Main");
            assertThat(parse(manifest)).containsExactlyInAnyOrderEntriesOf(expected);
            for (final int chunkSize : new int[] { 1, 2, 3, 7, 64, 8192 }) {
                assertThat(parseStream(manifest, chunkSize)).as("eol %s, chunk size %d", eol.length(), chunkSize)
                        .containsExactlyInAnyOrderEntriesOf(expected);
            }
        }
    }

    /**
     * Only the main section is read from the stream. The per-entry sections of a signed jarfile hold a digest of
     * every entry, so they are orders of magnitude larger than the main section, and reading them would mean
     * inflating the whole manifest just to throw it away.
     */
    @Test
    public void thePerEntrySectionsAreNotRead() throws IOException {
        final var mainSection = "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n";
        final var perEntrySections = "Name: com/xyz/Widget.class\r\nSHA-256-Digest: 0000\r\n\r\n".repeat(10_000);
        final var manifestBytes = (mainSection + perEntrySections).getBytes(StandardCharsets.UTF_8);
        final var inputStream = new ChunkedInputStream(manifestBytes, 8192);
        assertThat(ManifestParser.parse(inputStream)).containsEntry("Multi-Release", "true");
        // The blank line that ends the main section lands in the first read, so nothing beyond that read is needed
        assertThat(inputStream.numBytesRead).isLessThanOrEqualTo(8192);
        assertThat(manifestBytes.length).isGreaterThan(500_000);
    }

    /**
     * A manifest whose main section is larger than the limit is rejected, rather than inflated into the heap. A
     * manifest is a deflated zipfile entry, so without a limit a small jarfile could allocate an arbitrarily large
     * amount of memory simply by being opened.
     */
    @Test
    public void anOversizedMainSectionIsRejected() {
        final var hugeMainSection = "Manifest-Version: 1.0\r\n"
                + "Bloat: xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\r\n".repeat(50_000);
        assertThat(hugeMainSection.length()).isGreaterThan(2 * 1024 * 1024);
        assertThatExceptionOfType(IOException.class).isThrownBy(() -> parseStream(hugeMainSection, 8192))
                .withMessageContaining("main section is larger than");
    }

    /** A main section that reaches the end of the stream without a blank line after it is still read in full. */
    @Test
    public void aStreamThatEndsWithoutABlankLineIsRead() throws IOException {
        for (final int chunkSize : new int[] { 1, 2, 8192 }) {
            assertThat(parseStream("Manifest-Version: 1.0\r\nMain-Class: com.xyz.Main", chunkSize))
                    .containsEntry("Main-Class", "com.xyz.Main");
            assertThat(parseStream("", chunkSize)).isEmpty();
            assertThat(parseStream("\r\nName: com/xyz/Widget.class\r\n", chunkSize)).isEmpty();
        }
    }
}
