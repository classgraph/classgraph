package io.github.classgraph.vfs.internal.zip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.vfs.Vfs;

/**
 * Zip entry names are not stored in the "modified UTF-8" encoding of the Java classfile format. Bit 11 of an
 * entry's general purpose bit flag declares that its name is standard UTF-8, and when the bit is clear, the zip
 * specification calls for IBM Code Page 437.
 *
 * <p>
 * The zipfiles here are written byte by byte rather than with {@link java.util.zip.ZipOutputStream}, so that the
 * name bytes and the flag that describes them can be set independently, including in combinations that
 * {@code ZipOutputStream} will not write.
 */
public class ZipEntryNameEncodingTest {
    /** Bit 11 of the general purpose bit flag, which declares the entry name to be UTF-8. */
    private static final int UTF8_NAME_FLAG_BIT = 1 << 11;

    /** The contents of every entry written here, stored uncompressed. */
    private static final byte[] CONTENTS = "contents".getBytes(StandardCharsets.UTF_8);

    /**
     * Write a 16-bit little-endian value.
     *
     * @param buf
     *            the buffer to write to
     * @param val
     *            the value to write
     */
    private static void write16(final ByteArrayOutputStream buf, final int val) {
        buf.write(val & 0xff);
        buf.write((val >> 8) & 0xff);
    }

    /**
     * Write a 32-bit little-endian value.
     *
     * @param buf
     *            the buffer to write to
     * @param val
     *            the value to write
     */
    private static void write32(final ByteArrayOutputStream buf, final long val) {
        for (var i = 0; i < 4; i++) {
            buf.write((int) ((val >> (i * 8)) & 0xff));
        }
    }

    /**
     * Write a zipfile holding one stored entry per given name, with the given general purpose bit flags.
     *
     * @param tempDir
     *            the directory to write the zipfile into
     * @param jarName
     *            the name of the zipfile to write
     * @param flags
     *            the general purpose bit flags to record for every entry
     * @param nameBytes
     *            the name of each entry, already encoded
     * @return the zipfile
     * @throws Exception
     *             if the zipfile could not be written
     */
    private static File writeZip(final File tempDir, final String jarName, final int flags,
            final byte[]... nameBytes) throws Exception {
        final var zip = new ByteArrayOutputStream();
        final var crc = new CRC32();
        crc.update(CONTENTS);

        // Local file headers, each immediately followed by the contents of its entry
        final var localHeaderOffsets = new long[nameBytes.length];
        for (var i = 0; i < nameBytes.length; i++) {
            localHeaderOffsets[i] = zip.size();
            write32(zip, 0x04034b50L);
            write16(zip, 20); // Version needed to extract
            write16(zip, flags);
            write16(zip, 0); // Method (stored)
            write16(zip, 0); // Last modified time
            write16(zip, 0x21); // Last modified date (1 Jan 1980)
            write32(zip, crc.getValue());
            write32(zip, CONTENTS.length); // Compressed size
            write32(zip, CONTENTS.length); // Uncompressed size
            write16(zip, nameBytes[i].length);
            write16(zip, 0); // Extra field length
            zip.writeBytes(nameBytes[i]);
            zip.writeBytes(CONTENTS);
        }

        // Central directory
        final var centralDirectoryOffset = zip.size();
        for (var i = 0; i < nameBytes.length; i++) {
            write32(zip, 0x02014b50L);
            write16(zip, 20); // Version made by
            write16(zip, 20); // Version needed to extract
            write16(zip, flags);
            write16(zip, 0); // Method (stored)
            write16(zip, 0); // Last modified time
            write16(zip, 0x21); // Last modified date (1 Jan 1980)
            write32(zip, crc.getValue());
            write32(zip, CONTENTS.length); // Compressed size
            write32(zip, CONTENTS.length); // Uncompressed size
            write16(zip, nameBytes[i].length);
            write16(zip, 0); // Extra field length
            write16(zip, 0); // Comment length
            write16(zip, 0); // Disk number start
            write16(zip, 0); // Internal file attributes
            write32(zip, 0); // External file attributes
            write32(zip, localHeaderOffsets[i]);
            zip.writeBytes(nameBytes[i]);
        }
        final var centralDirectorySize = zip.size() - centralDirectoryOffset;

        // End of central directory record
        write32(zip, 0x06054b50L);
        write16(zip, 0); // Disk number
        write16(zip, 0); // Disk on which the central directory starts
        write16(zip, nameBytes.length); // Number of entries on this disk
        write16(zip, nameBytes.length); // Total number of entries
        write32(zip, centralDirectorySize);
        write32(zip, centralDirectoryOffset);
        write16(zip, 0); // Comment length

        final var jarFile = new File(tempDir, jarName);
        Files.write(jarFile.toPath(), zip.toByteArray());
        return jarFile;
    }

    /**
     * Read back the names of the entries of a zipfile.
     *
     * @param jarFile
     *            the zipfile to read
     * @return the entry names
     * @throws Exception
     *             if the zipfile could not be read
     */
    private static List<String> entryNames(final File jarFile) throws Exception {
        final List<String> names = new ArrayList<>();
        try (var vfs = new Vfs()) {
            for (final var entry : vfs.open(jarFile.getPath()).getEntries()) {
                names.add(entry.getPathFromRoot());
            }
        }
        return names;
    }

    /**
     * Encode a name as UTF-8.
     *
     * @param name
     *            the name
     * @return the UTF-8 encoding of the name
     */
    private static byte[] utf8(final String name) {
        return name.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * An entry name containing a character outside the Basic Multiplane Plane is encoded by standard UTF-8 as a
     * single four-byte sequence, which the modified UTF-8 encoding of the classfile format cannot represent.
     * Reading such a name must not fail, and must not stop the rest of the archive from being read.
     */
    @Test
    public void supplementaryPlaneEntryName(@TempDir final File tempDir) throws Exception {
        assertThat(entryNames(writeZip(tempDir, "emoji.jar", UTF8_NAME_FLAG_BIT, utf8("pkg/first.txt"),
                utf8("pkg/😀.txt"), utf8("pkg/second.txt"))) //
        ).containsExactly("pkg/first.txt", "pkg/😀.txt", "pkg/second.txt");
    }

    /** An entry name within the Basic Multilingual Plane is decoded as UTF-8 when the UTF-8 flag is set. */
    @Test
    public void utf8EntryName(@TempDir final File tempDir) throws Exception {
        assertThat(entryNames(
                writeZip(tempDir, "utf8.jar", UTF8_NAME_FLAG_BIT, utf8("pkg/café.txt"), utf8("pkg/中.txt"))) //
        ).containsExactly("pkg/café.txt", "pkg/中.txt");
    }

    /**
     * Writers that do not set the UTF-8 flag nevertheless commonly write UTF-8, so a name that is valid UTF-8 is
     * decoded as UTF-8 whether or not the flag is set.
     */
    @Test
    public void utf8EntryNameWithoutTheUtf8Flag(@TempDir final File tempDir) throws Exception {
        assertThat(entryNames(writeZip(tempDir, "utf8-unflagged.jar", 0, utf8("pkg/café.txt"), utf8("pkg/😀.txt"))) //
        ).containsExactly("pkg/café.txt", "pkg/😀.txt");
    }

    /**
     * A name that is not valid UTF-8 and whose UTF-8 flag is clear is decoded as CP437, as the zip specification
     * requires. (0x81 and 0x94 are not a valid UTF-8 sequence, and are "ü" and "ö" in CP437.)
     */
    @Test
    public void cp437EntryName(@TempDir final File tempDir) throws Exception {
        final var name = new byte[] { 'p', 'k', 'g', '/', (byte) 0x81, (byte) 0x94, '.', 't', 'x', 't' };
        assertThat(entryNames(writeZip(tempDir, "cp437.jar", 0, name))) //
                .containsExactly("pkg/üö.txt");
    }

    /**
     * A name whose UTF-8 flag is set but which is not valid UTF-8 is malformed either way. The unreadable bytes
     * become replacement characters rather than stopping the archive from being read.
     */
    @Test
    public void malformedUtf8EntryName(@TempDir final File tempDir) throws Exception {
        final var name = new byte[] { 'p', 'k', 'g', '/', (byte) 0xc3, '.', 't', 'x', 't' };
        assertThat(entryNames(writeZip(tempDir, "malformed.jar", UTF8_NAME_FLAG_BIT, name, utf8("pkg/ok.txt")))) //
                .containsExactly("pkg/�.txt", "pkg/ok.txt");
    }

    /** The inlined CP437 table maps every byte to the same character that the JDK's IBM437 charset does. */
    @Test
    public void cp437TableMatchesTheJdkCharset() {
        assumeTrue(Charset.isSupported("IBM437"), "IBM437 charset is not available in this runtime image");
        final var allBytes = new byte[256];
        for (var i = 0; i < 256; i++) {
            allBytes[i] = (byte) i;
        }
        // The UTF-8 flag is clear and these bytes are not valid UTF-8, so they are decoded as CP437
        assertThat(ZipEntryNameCodec.decodeEntryName(allBytes, /* isUtf8 = */ false))
                .isEqualTo(new String(allBytes, Charset.forName("IBM437")));
    }
}
