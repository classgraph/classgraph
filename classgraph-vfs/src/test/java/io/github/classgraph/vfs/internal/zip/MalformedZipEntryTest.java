package io.github.classgraph.vfs.internal.zip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.vfs.Vfs;

/**
 * A zipfile can hold entries that cannot be read: entries that are encrypted, or compressed with a method that is
 * not supported, or whose central directory record describes them impossibly. An unreadable entry is skipped, and
 * the rest of the zipfile is still read, so that one bad entry does not make a whole jarfile unusable. Only damage
 * to the structure of the central directory itself, which makes the position of the following entries unknowable,
 * stops the zipfile from being read at all.
 *
 * <p>
 * The zipfiles here are written byte by byte rather than with {@link java.util.zip.ZipOutputStream}, because
 * {@code ZipOutputStream} will not write a malformed entry.
 */
public class MalformedZipEntryTest {
    /** The name of the readable entry that every zipfile written here holds. */
    private static final String GOOD_NAME = "testpkg/good.txt";

    /** The contents of every entry, which is stored uncompressed. */
    private static final byte[] CONTENTS = "contents".getBytes(StandardCharsets.UTF_8);

    /** The signature of a central directory record. */
    private static final long CEN_SIGNATURE = 0x02014b50L;

    /** The value a central directory field holds when its real value did not fit in the field's 32 bits. */
    private static final long OVERFLOWED = 0xffffffffL;

    /** The logger that the verbose log is written to. */
    private static final Logger LOGGER = Logger.getLogger("io.github.classgraph.ClassGraph");

    // -----------------------------------------------------------------------------------------------------------

    /**
     * An entry to write into a test zipfile. The entry itself is always written correctly; it is its central
     * directory record that the fields here can make impossible to read.
     */
    private static final class EntrySpec {
        /** The name of the entry. */
        private final String name;

        /** The general purpose bit flags of the entry. */
        private int flags;

        /** The compression method of the entry. */
        private int compressionMethod;

        /** The extra field area of the entry's central directory record. */
        private byte[] extraField = new byte[0];

        /** The uncompressed size to write in the central directory record, or null to write the real size. */
        private Long cenUncompressedSize;

        /** The compressed size to write in the central directory record, or null to write the real size. */
        private Long cenCompressedSize;

        /** The local header offset to write in the central directory record, or null to write the real offset. */
        private Long cenLocalHeaderOffset;

        /** The filename length to write in the central directory record, or null to write the real length. */
        private Integer cenFilenameLen;

        /** The extra field length to write in the central directory record, or null to write the real length. */
        private Integer cenExtraFieldLen;

        /** The comment length to write in the central directory record, or null to write no comment. */
        private Integer cenCommentLen;

        /** The signature to write at the head of the central directory record. */
        private long cenSignature = CEN_SIGNATURE;

        /**
         * Constructor.
         *
         * @param name
         *            the name of the entry
         */
        private EntrySpec(final String name) {
            this.name = name;
        }

        /**
         * Set the general purpose bit flags.
         *
         * @param flags
         *            the flags
         * @return this (for method chaining)
         */
        private EntrySpec flags(final int flags) {
            this.flags = flags;
            return this;
        }

        /**
         * Set the compression method.
         *
         * @param compressionMethod
         *            the compression method
         * @return this (for method chaining)
         */
        private EntrySpec compressionMethod(final int compressionMethod) {
            this.compressionMethod = compressionMethod;
            return this;
        }

        /**
         * Set the extra field area of the central directory record.
         *
         * @param extraField
         *            the extra field area
         * @return this (for method chaining)
         */
        private EntrySpec extraField(final byte[] extraField) {
            this.extraField = extraField;
            return this;
        }

        /**
         * Set the uncompressed size written in the central directory record.
         *
         * @param cenUncompressedSize
         *            the uncompressed size
         * @return this (for method chaining)
         */
        private EntrySpec cenUncompressedSize(final long cenUncompressedSize) {
            this.cenUncompressedSize = cenUncompressedSize;
            return this;
        }

        /**
         * Set the compressed size written in the central directory record.
         *
         * @param cenCompressedSize
         *            the compressed size
         * @return this (for method chaining)
         */
        private EntrySpec cenCompressedSize(final long cenCompressedSize) {
            this.cenCompressedSize = cenCompressedSize;
            return this;
        }

        /**
         * Set the local file header offset written in the central directory record.
         *
         * @param cenLocalHeaderOffset
         *            the local file header offset
         * @return this (for method chaining)
         */
        private EntrySpec cenLocalHeaderOffset(final long cenLocalHeaderOffset) {
            this.cenLocalHeaderOffset = cenLocalHeaderOffset;
            return this;
        }

        /**
         * Set the filename length written in the central directory record, which need not be the real length of the
         * name that follows it.
         *
         * @param cenFilenameLen
         *            the filename length
         * @return this (for method chaining)
         */
        private EntrySpec cenFilenameLen(final int cenFilenameLen) {
            this.cenFilenameLen = cenFilenameLen;
            return this;
        }

        /**
         * Set the extra field length written in the central directory record, which need not be the real length of
         * the extra field area that follows it.
         *
         * @param cenExtraFieldLen
         *            the extra field length
         * @return this (for method chaining)
         */
        private EntrySpec cenExtraFieldLen(final int cenExtraFieldLen) {
            this.cenExtraFieldLen = cenExtraFieldLen;
            return this;
        }

        /**
         * Set the comment length written in the central directory record. No comment is written after it, so any
         * nonzero length is a length the record does not have room for.
         *
         * @param cenCommentLen
         *            the comment length
         * @return this (for method chaining)
         */
        private EntrySpec cenCommentLen(final int cenCommentLen) {
            this.cenCommentLen = cenCommentLen;
            return this;
        }

        /**
         * Set the signature written at the head of the central directory record.
         *
         * @param cenSignature
         *            the signature
         * @return this (for method chaining)
         */
        private EntrySpec cenSignature(final long cenSignature) {
            this.cenSignature = cenSignature;
            return this;
        }
    }

    /**
     * An entry that is written correctly, and read back unless one of the fields of {@link EntrySpec} is set.
     *
     * @param name
     *            the name of the entry
     * @return the entry
     */
    private static EntrySpec entry(final String name) {
        return new EntrySpec(name);
    }

    // -----------------------------------------------------------------------------------------------------------

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
     * Write a 64-bit little-endian value.
     *
     * @param buf
     *            the buffer to write to
     * @param val
     *            the value to write
     */
    private static void write64(final ByteArrayOutputStream buf, final long val) {
        for (var i = 0; i < 8; i++) {
            buf.write((int) ((val >> (i * 8)) & 0xff));
        }
    }

    /**
     * Build an extra field with the given tag, holding the given data.
     *
     * @param tag
     *            the tag of the extra field
     * @param data
     *            the data area of the extra field
     * @return the extra field bytes
     */
    private static byte[] extraField(final int tag, final byte[] data) {
        final var buf = new ByteArrayOutputStream();
        write16(buf, tag);
        write16(buf, data.length);
        buf.writeBytes(data);
        return buf.toByteArray();
    }

    /**
     * Build a Zip64 extended information extra field holding the given 64-bit values, in the order the zipfile
     * specification puts them in. A null value is one whose central directory field did not overflow, so it is left
     * out of the extra field entirely.
     *
     * @param uncompressedSize
     *            the uncompressed size to write, or null not to write one
     * @param compressedSize
     *            the compressed size to write, or null not to write one
     * @param localHeaderOffset
     *            the local file header offset to write, or null not to write one
     * @return the extra field bytes
     */
    private static byte[] zip64ExtraField(final Long uncompressedSize, final Long compressedSize,
            final Long localHeaderOffset) {
        final var data = new ByteArrayOutputStream();
        if (uncompressedSize != null) {
            write64(data, uncompressedSize);
        }
        if (compressedSize != null) {
            write64(data, compressedSize);
        }
        if (localHeaderOffset != null) {
            write64(data, localHeaderOffset);
        }
        return extraField(0x0001, data.toByteArray());
    }

    /**
     * Build an Info-ZIP Unicode path extra field, which gives an entry's name in UTF-8.
     *
     * @param version
     *            the version of the extra field, of which only version 1 is defined
     * @param legacyName
     *            the entry's own name, whose CRC-32 is stored in the extra field so that the reader can tell that
     *            the field still describes the entry it is attached to
     * @param unicodeName
     *            the name of the entry in UTF-8
     * @return the extra field bytes
     */
    private static byte[] unicodePathExtraField(final int version, final String legacyName,
            final String unicodeName) {
        final var nameBytes = unicodeName.getBytes(StandardCharsets.UTF_8);
        final var data = new ByteArrayOutputStream();
        data.write(version);
        // CRC-32 of the name this one replaces
        final var nameCRC32 = new CRC32();
        nameCRC32.update(legacyName.getBytes(StandardCharsets.UTF_8));
        write32(data, nameCRC32.getValue());
        data.writeBytes(nameBytes);
        return extraField(0x7075, data.toByteArray());
    }

    /**
     * Write a zipfile holding the given entries, each written correctly, but each with the central directory record
     * its {@link EntrySpec} describes.
     *
     * @param tempDir
     *            the directory to write the zipfile into
     * @param jarName
     *            the name of the zipfile to write
     * @param entrySpecs
     *            the entries to write
     * @return the zipfile
     * @throws Exception
     *             if the zipfile could not be written
     */
    private static File writeZip(final File tempDir, final String jarName, final EntrySpec... entrySpecs)
            throws Exception {
        final var crc = new CRC32();
        crc.update(CONTENTS);
        final var zip = new ByteArrayOutputStream();

        // Local file header of each entry, each immediately followed by the contents of the entry
        final var localHeaderOffsets = new ArrayList<Integer>();
        for (final var entrySpec : entrySpecs) {
            localHeaderOffsets.add(zip.size());
            final var nameBytes = entrySpec.name.getBytes(StandardCharsets.UTF_8);
            write32(zip, 0x04034b50L);
            write16(zip, 45); // Version needed to extract
            write16(zip, entrySpec.flags);
            write16(zip, entrySpec.compressionMethod);
            write16(zip, 0); // Last modified time
            write16(zip, 0x21); // Last modified date (1 Jan 1980)
            write32(zip, crc.getValue());
            write32(zip, CONTENTS.length); // Compressed size
            write32(zip, CONTENTS.length); // Uncompressed size
            write16(zip, nameBytes.length);
            write16(zip, 0); // Extra field length
            zip.writeBytes(nameBytes);
            zip.writeBytes(CONTENTS);
        }

        // Central directory record of each entry
        final var centralDirectoryOffset = zip.size();
        for (var i = 0; i < entrySpecs.length; i++) {
            final var entrySpec = entrySpecs[i];
            final var nameBytes = entrySpec.name.getBytes(StandardCharsets.UTF_8);
            write32(zip, entrySpec.cenSignature);
            write16(zip, 45); // Version made by
            write16(zip, 45); // Version needed to extract
            write16(zip, entrySpec.flags);
            write16(zip, entrySpec.compressionMethod);
            write16(zip, 0); // Last modified time
            write16(zip, 0x21); // Last modified date (1 Jan 1980)
            write32(zip, crc.getValue());
            write32(zip, entrySpec.cenCompressedSize == null ? CONTENTS.length : entrySpec.cenCompressedSize);
            write32(zip, entrySpec.cenUncompressedSize == null ? CONTENTS.length : entrySpec.cenUncompressedSize);
            write16(zip, entrySpec.cenFilenameLen == null ? nameBytes.length : entrySpec.cenFilenameLen);
            write16(zip,
                    entrySpec.cenExtraFieldLen == null ? entrySpec.extraField.length : entrySpec.cenExtraFieldLen);
            write16(zip, entrySpec.cenCommentLen == null ? 0 : entrySpec.cenCommentLen);
            write16(zip, 0); // Disk number start
            write16(zip, 0); // Internal file attributes
            write32(zip, 0); // External file attributes
            write32(zip, entrySpec.cenLocalHeaderOffset == null ? localHeaderOffsets.get(i)
                    : entrySpec.cenLocalHeaderOffset);
            zip.writeBytes(nameBytes);
            zip.writeBytes(entrySpec.extraField);
        }
        final var centralDirectorySize = zip.size() - centralDirectoryOffset;

        // End of central directory record
        write32(zip, 0x06054b50L);
        write16(zip, 0); // Disk number
        write16(zip, 0); // Disk on which the central directory starts
        write16(zip, entrySpecs.length); // Number of entries on this disk
        write16(zip, entrySpecs.length); // Total number of entries
        write32(zip, centralDirectorySize);
        write32(zip, centralDirectoryOffset);
        write16(zip, 0); // Comment length

        final var jarFile = new File(tempDir, jarName);
        Files.write(jarFile.toPath(), zip.toByteArray());
        return jarFile;
    }

    // -----------------------------------------------------------------------------------------------------------

    /**
     * Read back the name of every entry of a zipfile that could be read.
     *
     * @param jarFile
     *            the zipfile to read
     * @return the names of the entries
     * @throws Exception
     *             if the zipfile could not be read
     */
    private static List<String> entryNamesReadBack(final File jarFile) throws Exception {
        final List<String> entryNames = new ArrayList<>();
        try (var vfs = new Vfs()) {
            for (final var entry : vfs.open(jarFile.getPath()).getEntries()) {
                entryNames.add(entry.getName());
            }
        }
        return entryNames;
    }

    /**
     * Read back the content of every entry of a zipfile that could be read.
     *
     * @param jarFile
     *            the zipfile to read
     * @return one {@code "name: content"} string per entry
     * @throws Exception
     *             if the zipfile could not be read
     */
    private static List<String> entryContentsReadBack(final File jarFile) throws Exception {
        final List<String> entries = new ArrayList<>();
        try (var vfs = new Vfs()) {
            for (final var entry : vfs.open(jarFile.getPath()).getEntries()) {
                entries.add(entry.getName() + ": " + new String(entry.load(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    /**
     * Read a zipfile with verbose logging on, recording the log rather than printing it.
     *
     * @param jarFile
     *            the zipfile to read
     * @return the verbose log
     * @throws Exception
     *             if the zipfile could not be read
     */
    private static String verboseLogOfReading(final File jarFile) throws Exception {
        final var logged = new StringBuilder();
        final var handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                logged.append(record.getMessage()).append('\n');
            }

            @Override
            public void flush() {
                // Nothing to flush
            }

            @Override
            public void close() {
                // Nothing to close
            }
        };
        final var useParentHandlers = LOGGER.getUseParentHandlers();
        LOGGER.setUseParentHandlers(false);
        LOGGER.addHandler(handler);
        try (var vfs = new Vfs()) {
            vfs.verbose();
            vfs.open(jarFile.getPath()).getEntries();
        } finally {
            LOGGER.removeHandler(handler);
            LOGGER.setUseParentHandlers(useParentHandlers);
        }
        return logged.toString();
    }

    // -----------------------------------------------------------------------------------------------------------

    /** An entry whose name ends with a slash names a directory, and directories are not entries of their own. */
    @Test
    public void directoryEntriesAreNotReported(@TempDir final File tempDir) throws Exception {
        assertThat(
                entryNamesReadBack(writeZip(tempDir, "directory-entry.jar", entry("testpkg/"), entry(GOOD_NAME))))
                .containsExactly(GOOD_NAME);
    }

    /** An entry named by the root of the zipfile alone has no name once the leading slash is stripped. */
    @Test
    public void anEntryWithNoNameIsNotReported(@TempDir final File tempDir) throws Exception {
        assertThat(entryNamesReadBack(writeZip(tempDir, "empty-name.jar", entry("/"), entry(GOOD_NAME))))
                .containsExactly(GOOD_NAME);
    }

    /** The contents of an encrypted entry cannot be read without the password, so the entry is skipped. */
    @Test
    public void encryptedEntriesAreSkipped(@TempDir final File tempDir) throws Exception {
        final var jarFile = writeZip(tempDir, "encrypted.jar", entry("testpkg/secret.txt").flags(1),
                entry(GOOD_NAME));
        assertThat(entryNamesReadBack(jarFile)).containsExactly(GOOD_NAME);
        assertThat(verboseLogOfReading(jarFile)).contains("Skipping encrypted zip entry: testpkg/secret.txt");
    }

    /** Only the stored and deflated compression methods are supported, so an entry using any other is skipped. */
    @Test
    public void entriesCompressedWithAnUnsupportedMethodAreSkipped(@TempDir final File tempDir) throws Exception {
        // Method 12 is bzip2, which the zipfile specification allows but which is very rarely used
        final var jarFile = writeZip(tempDir, "bzip2.jar", entry("testpkg/bzip2.txt").compressionMethod(12),
                entry(GOOD_NAME));
        assertThat(entryNamesReadBack(jarFile)).containsExactly(GOOD_NAME);
        assertThat(verboseLogOfReading(jarFile))
                .contains("Skipping zip entry with invalid compression method 12: testpkg/bzip2.txt");
    }

    /**
     * A compressed size is written as an unsigned 32-bit value, so it can only be negative if a Zip64 extra field
     * overrode it with a negative 64-bit value.
     */
    @Test
    public void anEntryWithANegativeCompressedSizeIsSkipped(@TempDir final File tempDir) throws Exception {
        final var jarFile = writeZip(tempDir, "negative-csize.jar", entry("testpkg/negative.txt")
                .cenCompressedSize(OVERFLOWED).extraField(zip64ExtraField(null, -1L, null)), entry(GOOD_NAME));
        assertThat(entryNamesReadBack(jarFile)).containsExactly(GOOD_NAME);
        assertThat(verboseLogOfReading(jarFile))
                .contains("Skipping zip entry with invalid compressed size (-1): testpkg/negative.txt");
    }

    /** As for the compressed size, an uncompressed size can only be negative if a Zip64 extra field made it so. */
    @Test
    public void anEntryWithANegativeUncompressedSizeIsSkipped(@TempDir final File tempDir) throws Exception {
        final var jarFile = writeZip(tempDir, "negative-size.jar", entry("testpkg/negative.txt")
                .cenUncompressedSize(OVERFLOWED).extraField(zip64ExtraField(-1L, null, null)), entry(GOOD_NAME));
        assertThat(entryNamesReadBack(jarFile)).containsExactly(GOOD_NAME);
        assertThat(verboseLogOfReading(jarFile))
                .contains("Skipping zip entry with invalid uncompressed size (-1): testpkg/negative.txt");
    }

    /** As for the sizes, a local file header offset can only be negative if a Zip64 extra field made it so. */
    @Test
    public void anEntryWithANegativeLocalHeaderOffsetIsSkipped(@TempDir final File tempDir) throws Exception {
        final var jarFile = writeZip(tempDir, "negative-offset.jar", entry("testpkg/negative.txt")
                .cenLocalHeaderOffset(OVERFLOWED).extraField(zip64ExtraField(null, null, -1L)), entry(GOOD_NAME));
        assertThat(entryNamesReadBack(jarFile)).containsExactly(GOOD_NAME);
        assertThat(verboseLogOfReading(jarFile))
                .contains("Skipping zip entry with invalid pos (-1): testpkg/negative.txt");
    }

    /** An entry whose local file header is past the end of the zipfile has no contents to read. */
    @Test
    public void anEntryWhoseLocalHeaderIsPastTheEndOfTheZipfileIsSkipped(@TempDir final File tempDir)
            throws Exception {
        final var jarFile = writeZip(tempDir, "offset-past-eof.jar",
                entry("testpkg/past-eof.txt").cenLocalHeaderOffset(1_000_000L), entry(GOOD_NAME));
        assertThat(entryNamesReadBack(jarFile)).containsExactly(GOOD_NAME);
        assertThat(verboseLogOfReading(jarFile))
                .contains("Unexpected EOF when trying to read LOC header: testpkg/past-eof.txt");
    }

    /**
     * An entry whose compressed size runs past the end of the zipfile has no complete content to read, so reading
     * it fails rather than returning whatever bytes happen to follow it.
     */
    @Test
    public void anEntryWhoseContentExtendsPastTheEndOfTheZipfileCannotBeRead(@TempDir final File tempDir)
            throws Exception {
        final var jarFile = writeZip(tempDir, "csize-past-eof.jar",
                entry("testpkg/past-eof.txt").cenCompressedSize(0xfffffff0L));
        assertThatThrownBy(() -> entryContentsReadBack(jarFile)).isInstanceOf(IOException.class)
                .hasMessageContaining("Unexpected EOF when trying to read zip entry data: testpkg/past-eof.txt");
    }

    /**
     * A compressed size can be made large enough that adding it to the entry's start position overflows a 64-bit
     * value, which must not let the entry past the check that its content lies within the zipfile.
     */
    @Test
    public void anEntryWhoseCompressedSizeOverflowsTheEndOfTheZipfileCannotBeRead(@TempDir final File tempDir)
            throws Exception {
        final var jarFile = writeZip(tempDir, "csize-overflow.jar", entry("testpkg/overflow.txt")
                .cenCompressedSize(OVERFLOWED).extraField(zip64ExtraField(null, Long.MAX_VALUE, null)));
        assertThatThrownBy(() -> entryContentsReadBack(jarFile)).isInstanceOf(IOException.class)
                .hasMessageContaining("Unexpected EOF when trying to read zip entry data: testpkg/overflow.txt");
    }

    /**
     * An extra field that claims to be longer than the area it is in cannot be read, and neither can any extra
     * field after it, but the entry itself is still readable using the fields of its central directory record.
     */
    @Test
    public void anExtraFieldLongerThanItsAreaEndsTheExtraFields(@TempDir final File tempDir) throws Exception {
        // Claim a 32-byte Zip64 extra field, but write only the 4-byte header of it
        final var truncatedExtraField = new byte[] { 0x01, 0x00, 0x20, 0x00 };
        final var jarFile = writeZip(tempDir, "long-extra-field.jar",
                entry("testpkg/long-extra-field.txt").extraField(truncatedExtraField), entry(GOOD_NAME));
        assertThat(entryNamesReadBack(jarFile)).containsExactly("testpkg/long-extra-field.txt", GOOD_NAME);
        assertThat(verboseLogOfReading(jarFile)).contains(
                "Ignoring the rest of the extra fields of zip entry, which has an extra field that extends past "
                        + "the end of its extra field area: testpkg/long-extra-field.txt");
    }

    /**
     * An entry whose name is not in UTF-8 carries its name a second time, in UTF-8, in an Info-ZIP Unicode path
     * extra field, and that is the name it is reported under.
     */
    @Test
    public void aUnicodePathExtraFieldRenamesItsEntry(@TempDir final File tempDir) throws Exception {
        assertThat(entryNamesReadBack(writeZip(tempDir, "unicode-path.jar",
                entry("testpkg/name.txt")
                        .extraField(unicodePathExtraField(1, "testpkg/name.txt", "testpkg/über.txt")))))
                .containsExactly("testpkg/über.txt");
    }

    /** An entry that a Unicode path extra field renames into a directory is a directory, so it is not reported. */
    @Test
    public void aUnicodePathExtraFieldCanRenameAnEntryIntoADirectory(@TempDir final File tempDir) throws Exception {
        assertThat(entryNamesReadBack(writeZip(tempDir, "unicode-path-directory.jar",
                entry("testpkg/name.txt").extraField(
                        unicodePathExtraField(1, "testpkg/name.txt", "testpkg/renamed/")),
                entry(GOOD_NAME)))).containsExactly(GOOD_NAME);
    }

    /**
     * The name in a Unicode path extra field is sanitized exactly as the name it replaces is, so it cannot be used
     * to smuggle in a path that reaches outside the zipfile.
     */
    @Test
    public void aUnicodePathExtraFieldCannotNameAPathOutsideTheZipfile(@TempDir final File tempDir)
            throws Exception {
        assertThat(entryNamesReadBack(writeZip(tempDir, "unicode-path-escape.jar",
                entry("testpkg/name.txt")
                        .extraField(unicodePathExtraField(1, "testpkg/name.txt", "/../../etc/passwd")))))
                .containsExactly("etc/passwd");
    }

    /**
     * A Unicode path extra field with an empty data area holds no name to rename its entry with, and no version
     * byte either -- the bytes that follow the field belong to the next record, so they must not be read as if they
     * were the version byte of this one.
     */
    @Test
    public void anEmptyUnicodePathExtraFieldIsIgnored(@TempDir final File tempDir) throws Exception {
        assertThat(entryNamesReadBack(writeZip(tempDir, "unicode-path-empty.jar",
                entry("testpkg/name.txt").extraField(extraField(0x7075, new byte[0])), entry(GOOD_NAME))))
                .containsExactly("testpkg/name.txt", GOOD_NAME);
    }

    /** Only version 1 of the Unicode path extra field is defined, so any other version cannot be read. */
    @Test
    public void aUnicodePathExtraFieldOfAnUnknownVersionIsRejected(@TempDir final File tempDir) throws Exception {
        final var jarFile = writeZip(tempDir, "unicode-path-version.jar", entry("testpkg/name.txt")
                .extraField(unicodePathExtraField(2, "testpkg/name.txt", "testpkg/other.txt")));
        assertThatThrownBy(() -> entryNamesReadBack(jarFile)).rootCause()
                .hasMessageContaining("Unknown Unicode entry name format 2 in extra field: testpkg/name.txt");
    }

    /**
     * A central directory record without a central directory signature means the position of every record after it
     * is unknown, so the zipfile cannot be read at all.
     */
    @Test
    public void aRecordWithoutACentralDirectorySignatureIsRejected(@TempDir final File tempDir) throws Exception {
        final var jarFile = writeZip(tempDir, "bad-signature.jar", entry(GOOD_NAME),
                entry("testpkg/bad.txt").cenSignature(0x02014b51L));
        assertThatThrownBy(() -> entryNamesReadBack(jarFile)).rootCause()
                .hasMessageContaining("Invalid central directory signature");
    }

    /**
     * A record whose name extends past the end of the central directory means the length of the record, and so the
     * position of the record after it, is unknown, so the entries read so far are kept and the rest are dropped.
     */
    @Test
    public void aRecordWhoseNameExtendsPastTheCentralDirectoryEndsTheEntries(@TempDir final File tempDir)
            throws Exception {
        final var jarFile = writeZip(tempDir, "long-filename.jar", entry(GOOD_NAME),
                entry("testpkg/long-name.txt").cenFilenameLen(60000));
        assertThat(entryNamesReadBack(jarFile)).containsExactly(GOOD_NAME);
        assertThat(verboseLogOfReading(jarFile)).contains("Filename extends past end of entry");
    }

    /**
     * A record whose extra field area extends past the end of the central directory has the same problem as one
     * whose name does: the position of the record after it is unknown, so the entries read so far are kept and the
     * rest are dropped.
     */
    @Test
    public void aRecordWhoseExtraFieldAreaExtendsPastTheCentralDirectoryEndsTheEntries(@TempDir final File tempDir)
            throws Exception {
        final var jarFile = writeZip(tempDir, "long-extra-field-area.jar", entry(GOOD_NAME),
                entry("testpkg/long-extra-field-area.txt").cenExtraFieldLen(60000));
        assertThat(entryNamesReadBack(jarFile)).containsExactly(GOOD_NAME);
        assertThat(verboseLogOfReading(jarFile)).contains("Extra field area extends past end of entry");
    }

    /**
     * A record whose comment extends past the end of the central directory ends the entries too, but that record
     * itself is still read: the comment is the one part of a record that is never read, so an entry whose comment
     * is the only part of it that does not fit is not damaged by that.
     */
    @Test
    public void aRecordWhoseCommentExtendsPastTheCentralDirectoryIsStillRead(@TempDir final File tempDir)
            throws Exception {
        assertThat(entryNamesReadBack(writeZip(tempDir, "long-comment.jar", entry(GOOD_NAME),
                entry("testpkg/long-comment.txt").cenCommentLen(60000))))
                .containsExactly(GOOD_NAME, "testpkg/long-comment.txt");
    }
}
