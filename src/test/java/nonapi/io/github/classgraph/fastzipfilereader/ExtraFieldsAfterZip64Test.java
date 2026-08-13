package nonapi.io.github.classgraph.fastzipfilereader;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * The extra field area of a central directory entry holds a sequence of extra fields, and every one of them has to
 * be read. Reading used to stop at the Zip64 extended information extra field (tag 0x0001), so anything after it --
 * such as an Info-ZIP Unicode path extra field (tag 0x7075), which replaces the entry's name -- was ignored.
 *
 * <p>
 * The zipfiles here are written byte by byte rather than with {@link java.util.zip.ZipOutputStream}, because
 * {@code ZipOutputStream} drops any Zip64 or extended timestamp extra field it is handed and writes its own.
 */
public class ExtraFieldsAfterZip64Test {
    /** The entry name stored in the central directory's own entry name field. */
    private static final String LEGACY_NAME = "testpkg/legacy.txt";

    /** The entry name stored in the Info-ZIP Unicode path extra field. */
    private static final String UNICODE_NAME = "testpkg/unicode.txt";

    /** The contents of the entry, which is stored uncompressed. */
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
        for (int i = 0; i < 4; i++) {
            buf.write((int) ((val >> (i * 8)) & 0xff));
        }
    }

    /**
     * Write a 64-bit little-endian value.
     *
     * @param buf
     *            the buffer to write to
     * @param val
     *            the value to write
     */
    private static void write64(final ByteArrayOutputStream buf, final long val) {
        for (int i = 0; i < 8; i++) {
            buf.write((int) ((val >> (i * 8)) & 0xff));
        }
    }

    /**
     * Write a byte array.
     *
     * @param buf
     *            the buffer to write to
     * @param bytes
     *            the bytes to write
     */
    private static void writeBytes(final ByteArrayOutputStream buf, final byte[] bytes) {
        buf.write(bytes, 0, bytes.length);
    }

    /**
     * Build a Zip64 extended information extra field (tag 0x0001) that repeats the sizes the entry already has in
     * the central directory record, so that it is consistent with the entry and changes nothing about it.
     *
     * @return the extra field bytes
     */
    private static byte[] makeZip64ExtraField() {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        write16(buf, 0x0001);
        // Data size = uncompressedSize(8) + compressedSize(8) + localHeaderOffset(8)
        write16(buf, 24);
        write64(buf, CONTENTS.length);
        write64(buf, CONTENTS.length);
        write64(buf, 0);
        return buf.toByteArray();
    }

    /**
     * Build an Info-ZIP Unicode path extra field (tag 0x7075) holding the given entry name.
     *
     * @param unicodeName
     *            the entry name to store in the extra field
     * @return the extra field bytes
     */
    private static byte[] makeUnicodePathExtraField(final String unicodeName) {
        final byte[] nameBytes = unicodeName.getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        write16(buf, 0x7075);
        // Data size = version(1) + nameCRC32(4) + name
        write16(buf, 5 + nameBytes.length);
        buf.write(1);
        // CRC32 of the legacy name (not checked by the reader)
        write32(buf, 0);
        writeBytes(buf, nameBytes);
        return buf.toByteArray();
    }

    /**
     * Write a jarfile holding a single stored entry whose central directory extra field area is the given sequence
     * of extra fields, and return the entry names ClassGraph reads back from its central directory.
     *
     * @param tempDir
     *            the directory to write the jar into
     * @param jarName
     *            the name of the jar to write
     * @param extraFields
     *            the extra fields of the entry, in the order they are to be written
     * @return the entry names read back from the jar
     * @throws Exception
     *             if the jar could not be written or read
     */
    private static List<String> entryNamesReadBack(final File tempDir, final String jarName,
            final byte[]... extraFields) throws Exception {
        final ByteArrayOutputStream extraFieldArea = new ByteArrayOutputStream();
        for (final byte[] extraField : extraFields) {
            writeBytes(extraFieldArea, extraField);
        }
        final byte[] extraFieldAreaBytes = extraFieldArea.toByteArray();
        final byte[] nameBytes = LEGACY_NAME.getBytes(StandardCharsets.UTF_8);
        final CRC32 crc = new CRC32();
        crc.update(CONTENTS);

        final ByteArrayOutputStream zip = new ByteArrayOutputStream();

        // Local file header, with no extra field area of its own
        write32(zip, 0x04034b50L);
        write16(zip, 20); // Version needed to extract
        write16(zip, 0); // Flags
        write16(zip, 0); // Method (stored)
        write16(zip, 0); // Last modified time
        write16(zip, 0x21); // Last modified date (1 Jan 1980)
        write32(zip, crc.getValue());
        write32(zip, CONTENTS.length); // Compressed size
        write32(zip, CONTENTS.length); // Uncompressed size
        write16(zip, nameBytes.length);
        write16(zip, 0); // Extra field length
        writeBytes(zip, nameBytes);
        writeBytes(zip, CONTENTS);

        // Central directory
        final int centralDirectoryOffset = zip.size();
        write32(zip, 0x02014b50L);
        write16(zip, 20); // Version made by
        write16(zip, 20); // Version needed to extract
        write16(zip, 0); // Flags
        write16(zip, 0); // Method (stored)
        write16(zip, 0); // Last modified time
        write16(zip, 0x21); // Last modified date (1 Jan 1980)
        write32(zip, crc.getValue());
        write32(zip, CONTENTS.length); // Compressed size
        write32(zip, CONTENTS.length); // Uncompressed size
        write16(zip, nameBytes.length);
        write16(zip, extraFieldAreaBytes.length);
        write16(zip, 0); // Comment length
        write16(zip, 0); // Disk number start
        write16(zip, 0); // Internal file attributes
        write32(zip, 0); // External file attributes
        write32(zip, 0); // Local file header offset
        writeBytes(zip, nameBytes);
        writeBytes(zip, extraFieldAreaBytes);
        final int centralDirectorySize = zip.size() - centralDirectoryOffset;

        // End of central directory record
        write32(zip, 0x06054b50L);
        write16(zip, 0); // Disk number
        write16(zip, 0); // Disk on which the central directory starts
        write16(zip, 1); // Number of entries on this disk
        write16(zip, 1); // Total number of entries
        write32(zip, centralDirectorySize);
        write32(zip, centralDirectoryOffset);
        write16(zip, 0); // Comment length

        final File jarFile = new File(tempDir, jarName);
        try (OutputStream fileOut = new FileOutputStream(jarFile)) {
            fileOut.write(zip.toByteArray());
        }

        final NestedJarHandler nestedJarHandler = new NestedJarHandler(new ScanSpec(), new InterruptionChecker(),
                new ReflectionUtils());
        final List<String> entryNames = new ArrayList<>();
        try {
            final Entry<LogicalZipFile, String> logicalZipFileAndPackageRoot = //
                    nestedJarHandler.nestedPathToLogicalZipFileAndPackageRootMap.get(jarFile.getPath(),
                            /* log = */ null);
            for (final FastZipEntry zipEntry : logicalZipFileAndPackageRoot.getKey().entries) {
                entryNames.add(zipEntry.entryName);
            }
        } finally {
            // The jarfile must not be left open, otherwise the temporary directory cannot be deleted on Windows
            nestedJarHandler.close(/* log = */ null);
        }
        return entryNames;
    }

    /** With the Unicode path extra field first, it is read, and it renames the entry. */
    @Test
    public void unicodePathExtraFieldBeforeZip64ExtraField(@TempDir final File tempDir) throws Exception {
        assertThat(entryNamesReadBack(tempDir, "unicode-then-zip64.jar", makeUnicodePathExtraField(UNICODE_NAME),
                makeZip64ExtraField())).containsExactly(UNICODE_NAME);
    }

    /** With the Zip64 extra field first, the Unicode path extra field after it still has to be read. */
    @Test
    public void unicodePathExtraFieldAfterZip64ExtraField(@TempDir final File tempDir) throws Exception {
        assertThat(entryNamesReadBack(tempDir, "zip64-then-unicode.jar", makeZip64ExtraField(),
                makeUnicodePathExtraField(UNICODE_NAME))).containsExactly(UNICODE_NAME);
    }

    /**
     * An extra field with a zero-length data area occupies just the four bytes of its own header, so when it is the
     * last extra field it ends exactly at the end of the extra field area. Reading it changes nothing (it has no
     * data to read), but the loop that walks the extra field area has to stop cleanly at the end of it either way.
     */
    @Test
    public void zeroLengthExtraFieldAtTheEndOfTheExtraFieldArea(@TempDir final File tempDir) throws Exception {
        // Info-ZIP Unix UID and GID field (tag 0x7855), with no data area at all
        final byte[] emptyExtraField = new byte[] { 0x55, 0x78, 0, 0 };
        assertThat(entryNamesReadBack(tempDir, "empty-trailing-extra-field.jar",
                makeUnicodePathExtraField(UNICODE_NAME), emptyExtraField)).containsExactly(UNICODE_NAME);
    }

    /**
     * A Unicode path extra field with a zero-length data area holds no name to rename its entry with, and no version
     * byte either, so it has nothing to read. The bytes that follow it are not part of it, so they must not be read
     * as if they were its version byte -- here they are the end of the central directory, so reading them fails.
     */
    @Test
    public void emptyUnicodePathExtraFieldAtTheEndOfTheCentralDirectory(@TempDir final File tempDir)
            throws Exception {
        final byte[] emptyUnicodePathExtraField = new byte[] { 0x75, 0x70, 0, 0 };
        assertThat(entryNamesReadBack(tempDir, "empty-unicode-path.jar", emptyUnicodePathExtraField))
                .containsExactly(LEGACY_NAME);
    }

    /**
     * The same empty Unicode path extra field, but with another extra field after it: the bytes that would be read
     * as its version byte belong to that next field, and reading the rest of the extra field area has to carry on.
     */
    @Test
    public void emptyUnicodePathExtraFieldFollowedByAnotherExtraField(@TempDir final File tempDir)
            throws Exception {
        final byte[] emptyUnicodePathExtraField = new byte[] { 0x75, 0x70, 0, 0 };
        assertThat(entryNamesReadBack(tempDir, "empty-then-unicode-path.jar", emptyUnicodePathExtraField,
                makeUnicodePathExtraField(UNICODE_NAME))).containsExactly(UNICODE_NAME);
    }
}
