package io.github.classgraph.vfs.internal.zip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.vfs.Vfs;

/**
 * The uncompressed size, the compressed size and the local file header offset of a zip entry each have only 32 bits
 * in the central directory record, so a value too large for 32 bits is written as the overflow marker 0xffffffff,
 * and its real 64-bit value is written in the Zip64 extended information extra field (tag 0x0001) instead.
 *
 * <p>
 * A value appears in that extra field only if the central directory field it belongs to holds the overflow marker,
 * and the values that do appear are written in a fixed order, so which value each 8 bytes of the extra field holds
 * depends on which of the central directory fields are marked. This is what the zipfile specification requires, and
 * what {@link java.util.zip.ZipOutputStream} writes and {@link java.util.zip.ZipFile} reads.
 *
 * <p>
 * The zipfiles here are written byte by byte rather than with {@link java.util.zip.ZipOutputStream}, because
 * {@code ZipOutputStream} only writes a Zip64 extra field for a value that really is over 4GB, and writing an over
 * 4GB zipfile per test case is not reasonable. The entries here are tiny, and only their central directory records
 * claim that they overflowed.
 */
public class Zip64ExtraFieldTest {
    /** The name of the entry of the zipfiles written here. */
    private static final String ENTRY_NAME = "testpkg/entry.txt";

    /** The contents of the entry, which is stored uncompressed. */
    private static final byte[] CONTENTS = "contents".getBytes(StandardCharsets.UTF_8);

    /** The value a central directory field holds when its real value did not fit in the field's 32 bits. */
    private static final long OVERFLOWED = 0xffffffffL;

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
     * @param diskNumber
     *            the disk number to write, or null not to write one
     * @return the extra field bytes
     */
    private static byte[] zip64ExtraField(final Long uncompressedSize, final Long compressedSize,
            final Long localHeaderOffset, final Integer diskNumber) {
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
        if (diskNumber != null) {
            write32(data, diskNumber);
        }
        final var buf = new ByteArrayOutputStream();
        write16(buf, 0x0001);
        write16(buf, data.size());
        buf.writeBytes(data.toByteArray());
        return buf.toByteArray();
    }

    /**
     * Write a zipfile holding a single stored entry, whose central directory record holds the given values for its
     * uncompressed size, compressed size and local file header offset, and the given extra field.
     *
     * @param tempDir
     *            the directory to write the zipfile into
     * @param jarName
     *            the name of the zipfile to write
     * @param cenUncompressedSize
     *            the uncompressed size to write in the central directory record
     * @param cenCompressedSize
     *            the compressed size to write in the central directory record
     * @param cenLocalHeaderOffset
     *            the local file header offset to write in the central directory record
     * @param extraField
     *            the extra field area of the central directory record
     * @return the zipfile
     * @throws Exception
     *             if the zipfile could not be written
     */
    private static File writeZip(final File tempDir, final String jarName, final long cenUncompressedSize,
            final long cenCompressedSize, final long cenLocalHeaderOffset, final byte[] extraField)
            throws Exception {
        final var nameBytes = ENTRY_NAME.getBytes(StandardCharsets.UTF_8);
        final var crc = new CRC32();
        crc.update(CONTENTS);

        final var zip = new ByteArrayOutputStream();

        // Local file header, immediately followed by the contents of the entry
        write32(zip, 0x04034b50L);
        write16(zip, 45); // Version needed to extract
        write16(zip, 0); // Flags
        write16(zip, 0); // Method (stored)
        write16(zip, 0); // Last modified time
        write16(zip, 0x21); // Last modified date (1 Jan 1980)
        write32(zip, crc.getValue());
        write32(zip, CONTENTS.length); // Compressed size
        write32(zip, CONTENTS.length); // Uncompressed size
        write16(zip, nameBytes.length);
        write16(zip, 0); // Extra field length
        zip.writeBytes(nameBytes);
        zip.writeBytes(CONTENTS);

        // Central directory
        final var centralDirectoryOffset = zip.size();
        write32(zip, 0x02014b50L);
        write16(zip, 45); // Version made by
        write16(zip, 45); // Version needed to extract
        write16(zip, 0); // Flags
        write16(zip, 0); // Method (stored)
        write16(zip, 0); // Last modified time
        write16(zip, 0x21); // Last modified date (1 Jan 1980)
        write32(zip, crc.getValue());
        write32(zip, cenCompressedSize);
        write32(zip, cenUncompressedSize);
        write16(zip, nameBytes.length);
        write16(zip, extraField.length);
        write16(zip, 0); // Comment length
        write16(zip, 0); // Disk number start
        write16(zip, 0); // Internal file attributes
        write32(zip, 0); // External file attributes
        write32(zip, cenLocalHeaderOffset);
        zip.writeBytes(nameBytes);
        zip.writeBytes(extraField);
        final var centralDirectorySize = zip.size() - centralDirectoryOffset;

        // End of central directory record
        write32(zip, 0x06054b50L);
        write16(zip, 0); // Disk number
        write16(zip, 0); // Disk on which the central directory starts
        write16(zip, 1); // Number of entries on this disk
        write16(zip, 1); // Total number of entries
        write32(zip, centralDirectorySize);
        write32(zip, centralDirectoryOffset);
        write16(zip, 0); // Comment length

        final var jarFile = new File(tempDir, jarName);
        Files.write(jarFile.toPath(), zip.toByteArray());
        return jarFile;
    }

    /**
     * Read back the name and content of every entry of a zipfile.
     *
     * @param jarFile
     *            the zipfile to read
     * @return one {@code "name: content"} string per entry
     * @throws Exception
     *             if the zipfile could not be read
     */
    private static List<String> entriesReadBack(final File jarFile) throws Exception {
        final List<String> entries = new ArrayList<>();
        try (var vfs = new Vfs()) {
            for (final var entry : vfs.open(jarFile.getPath()).getEntries()) {
                entries.add(entry.getPathFromRoot() + ": " + new String(entry.load(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    /**
     * Read back the name and declared uncompressed size of every entry of a zipfile, without reading any content.
     *
     * @param jarFile
     *            the zipfile to read
     * @return one {@code "name: length"} string per entry
     * @throws Exception
     *             if the zipfile could not be read
     */
    private static List<String> entryLengthsReadBack(final File jarFile) throws Exception {
        final List<String> entries = new ArrayList<>();
        try (var vfs = new Vfs()) {
            for (final var entry : vfs.open(jarFile.getPath()).getEntries()) {
                entries.add(entry.getPathFromRoot() + ": " + entry.getLength());
            }
        }
        return entries;
    }

    /** An entry that did not overflow any of its central directory fields has no Zip64 extra field at all. */
    @Test
    public void noZip64ExtraField(@TempDir final File tempDir) throws Exception {
        assertThat(entriesReadBack(
                writeZip(tempDir, "no-zip64.jar", CONTENTS.length, CONTENTS.length, 0, new byte[0])))
                .containsExactly(ENTRY_NAME + ": contents");
    }

    /**
     * A zipfile over 4GB has entries whose local file header offset overflowed even though the entries themselves
     * are small, so their Zip64 extra field holds the offset alone, and is only 8 bytes long.
     */
    @Test
    public void onlyTheLocalHeaderOffsetOverflowed(@TempDir final File tempDir) throws Exception {
        assertThat(entriesReadBack(writeZip(tempDir, "offset-overflowed.jar", CONTENTS.length, CONTENTS.length,
                OVERFLOWED, zip64ExtraField(null, null, 0L, null)))).containsExactly(ENTRY_NAME + ": contents");
    }

    /**
     * An entry over 4GB near the start of a zipfile has overflowed sizes but an offset that still fits, so its
     * Zip64 extra field holds the two sizes and nothing else.
     */
    @Test
    public void onlyTheSizesOverflowed(@TempDir final File tempDir) throws Exception {
        assertThat(entriesReadBack(writeZip(tempDir, "sizes-overflowed.jar", OVERFLOWED, OVERFLOWED, 0,
                zip64ExtraField((long) CONTENTS.length, (long) CONTENTS.length, null, null))))
                .containsExactly(ENTRY_NAME + ": contents");
    }

    /** An entry over 4GB in a zipfile over 4GB overflows all three of its central directory fields. */
    @Test
    public void everyFieldOverflowed(@TempDir final File tempDir) throws Exception {
        assertThat(entriesReadBack(writeZip(tempDir, "all-overflowed.jar", OVERFLOWED, OVERFLOWED, OVERFLOWED,
                zip64ExtraField((long) CONTENTS.length, (long) CONTENTS.length, 0L, null))))
                .containsExactly(ENTRY_NAME + ": contents");
    }

    /** The three values can be followed by a disk number, which a single-disk zipfile has no use for. */
    @Test
    public void everyFieldOverflowedAndADiskNumberFollows(@TempDir final File tempDir) throws Exception {
        assertThat(entriesReadBack(writeZip(tempDir, "all-overflowed-with-disk.jar", OVERFLOWED, OVERFLOWED,
                OVERFLOWED, zip64ExtraField((long) CONTENTS.length, (long) CONTENTS.length, 0L, 0))))
                .containsExactly(ENTRY_NAME + ": contents");
    }

    /**
     * Only the compressed size overflowing is unusual, but it is what a stored entry of just under 4GB does when
     * its uncompressed size is exactly at the boundary, and the extra field then holds the compressed size alone.
     */
    @Test
    public void onlyTheCompressedSizeOverflowed(@TempDir final File tempDir) throws Exception {
        assertThat(entriesReadBack(writeZip(tempDir, "csize-overflowed.jar", CONTENTS.length, OVERFLOWED, 0,
                zip64ExtraField(null, (long) CONTENTS.length, null, null))))
                .containsExactly(ENTRY_NAME + ": contents");
    }

    /**
     * A central directory field that overflowed but has no value in the Zip64 extra field to replace it leaves the
     * entry with no real value for that field at all, so the zipfile cannot be read.
     */
    @Test
    public void aValueMissingFromTheZip64ExtraField(@TempDir final File tempDir) throws Exception {
        final var jarFile = writeZip(tempDir, "missing-value.jar", OVERFLOWED, OVERFLOWED, 0,
                zip64ExtraField((long) CONTENTS.length, null, null, null));
        assertThatThrownBy(() -> entriesReadBack(jarFile)).rootCause()
                .hasMessageContaining("Zip64 extra field is missing the compressed size");
    }

    /**
     * A central directory field that overflowed in an entry that has no Zip64 extra field at all has nothing to
     * replace it either, so the entry is rejected the same way as one whose extra field is too short. Without this,
     * the overflow marker is left in place and read as a size of just under 4GB, which is not a size the entry
     * could have had and still have been written this way.
     */
    @Test
    public void noZip64ExtraFieldToReplaceAnOverflowedValue(@TempDir final File tempDir) throws Exception {
        final var noSize = writeZip(tempDir, "no-extra-field-size.jar", OVERFLOWED, CONTENTS.length, 0,
                new byte[0]);
        assertThatThrownBy(() -> entriesReadBack(noSize)).rootCause()
                .hasMessageContaining("Zip64 extra field is missing the uncompressed size");

        // The same when the entry has extra fields, but none of them is the Zip64 one
        final var otherField = new ByteArrayOutputStream();
        write16(otherField, 0x7855); // Info-ZIP Unix UID and GID
        write16(otherField, 0);
        final var noOffset = writeZip(tempDir, "no-extra-field-offset.jar", CONTENTS.length, CONTENTS.length,
                OVERFLOWED, otherField.toByteArray());
        assertThatThrownBy(() -> entriesReadBack(noOffset)).rootCause()
                .hasMessageContaining("Zip64 extra field is missing the local file header offset");
    }

    /**
     * A value of exactly 0xffffffff is one of the values that has to be moved into the Zip64 extra field, because
     * the central directory field it came from cannot hold it without being read as the overflow marker. Such a
     * value is therefore a real value once it has been read back out of the extra field, and the entry that
     * declares it must not be mistaken for one that left the marker behind.
     */
    @Test
    public void aZip64ValueThatIsItselfTheOverflowMarker(@TempDir final File tempDir) throws Exception {
        assertThat(entryLengthsReadBack(writeZip(tempDir, "size-is-the-marker.jar", OVERFLOWED, CONTENTS.length, 0,
                zip64ExtraField(OVERFLOWED, null, null, null)))).containsExactly(ENTRY_NAME + ": " + OVERFLOWED);
    }
}
