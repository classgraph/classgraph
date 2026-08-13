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
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.vfs.Vfs;

/**
 * A zipfile with a Zip64 End Of Central Directory record has the number of entries recorded twice: once in the End
 * Of Central Directory record, in a 16-bit field, and once in the Zip64 record, in a 64-bit field. The 16-bit field
 * holds 0xffff when the real count does not fit in it, but some zipfile writers leave the two fields disagreeing
 * for other reasons, and then neither count can be trusted, so the entries have to be counted by walking the
 * central directory.
 *
 * <p>
 * The zipfiles here are written byte by byte rather than with {@link java.util.zip.ZipOutputStream}, because
 * {@code ZipOutputStream} writes its own End Of Central Directory records, and only writes a Zip64 record when the
 * zipfile is too large for the 32-bit fields.
 */
public class Zip64EntryCountTest {
    /** The names of the two entries of every zipfile written here. */
    private static final String[] ENTRY_NAMES = { "testpkg/first.txt", "testpkg/second.txt" };

    /** The contents of the entries, which are stored uncompressed. */
    private static final byte[][] CONTENTS = { "first contents".getBytes(StandardCharsets.UTF_8),
            "second contents".getBytes(StandardCharsets.UTF_8) };

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
     * Write a zipfile holding two stored entries, with a Zip64 End Of Central Directory record that records the
     * right number of entries, and an End Of Central Directory record that records the given number.
     *
     * @param tempDir
     *            the directory to write the zipfile into
     * @param jarName
     *            the name of the zipfile to write
     * @param numEntInEndOfCentralDirectory
     *            the number of entries to record in the End Of Central Directory record
     * @return the zipfile
     * @throws Exception
     *             if the zipfile could not be written
     */
    private static File writeZip(final File tempDir, final String jarName, final int numEntInEndOfCentralDirectory)
            throws Exception {
        return writeZip(tempDir, jarName, numEntInEndOfCentralDirectory, ENTRY_NAMES.length);
    }

    /**
     * Write a zipfile holding two stored entries, with a Zip64 End Of Central Directory record and an End Of
     * Central Directory record that record the given numbers of entries.
     *
     * @param tempDir
     *            the directory to write the zipfile into
     * @param jarName
     *            the name of the zipfile to write
     * @param numEntInEndOfCentralDirectory
     *            the number of entries to record in the End Of Central Directory record
     * @param numEntInZip64Record
     *            the number of entries to record in the Zip64 End Of Central Directory record
     * @return the zipfile
     * @throws Exception
     *             if the zipfile could not be written
     */
    private static File writeZip(final File tempDir, final String jarName, final int numEntInEndOfCentralDirectory,
            final long numEntInZip64Record) throws Exception {
        final var zip = new ByteArrayOutputStream();

        // Local file headers, each immediately followed by the contents of its entry
        final var localHeaderOffsets = new long[ENTRY_NAMES.length];
        for (var i = 0; i < ENTRY_NAMES.length; i++) {
            final var nameBytes = ENTRY_NAMES[i].getBytes(StandardCharsets.UTF_8);
            final var crc = new CRC32();
            crc.update(CONTENTS[i]);
            localHeaderOffsets[i] = zip.size();
            write32(zip, 0x04034b50L);
            write16(zip, 20); // Version needed to extract
            write16(zip, 0); // Flags
            write16(zip, 0); // Method (stored)
            write16(zip, 0); // Last modified time
            write16(zip, 0x21); // Last modified date (1 Jan 1980)
            write32(zip, crc.getValue());
            write32(zip, CONTENTS[i].length); // Compressed size
            write32(zip, CONTENTS[i].length); // Uncompressed size
            write16(zip, nameBytes.length);
            write16(zip, 0); // Extra field length
            zip.writeBytes(nameBytes);
            zip.writeBytes(CONTENTS[i]);
        }

        // Central directory
        final var centralDirectoryOffset = zip.size();
        for (var i = 0; i < ENTRY_NAMES.length; i++) {
            final var nameBytes = ENTRY_NAMES[i].getBytes(StandardCharsets.UTF_8);
            final var crc = new CRC32();
            crc.update(CONTENTS[i]);
            write32(zip, 0x02014b50L);
            write16(zip, 20); // Version made by
            write16(zip, 20); // Version needed to extract
            write16(zip, 0); // Flags
            write16(zip, 0); // Method (stored)
            write16(zip, 0); // Last modified time
            write16(zip, 0x21); // Last modified date (1 Jan 1980)
            write32(zip, crc.getValue());
            write32(zip, CONTENTS[i].length); // Compressed size
            write32(zip, CONTENTS[i].length); // Uncompressed size
            write16(zip, nameBytes.length);
            write16(zip, 0); // Extra field length
            write16(zip, 0); // Comment length
            write16(zip, 0); // Disk number start
            write16(zip, 0); // Internal file attributes
            write32(zip, 0); // External file attributes
            write32(zip, localHeaderOffsets[i]);
            zip.writeBytes(nameBytes);
        }
        final var centralDirectorySize = zip.size() - centralDirectoryOffset;

        // Zip64 end of central directory record, which has to start where the central directory ends
        final var zip64EndOfCentralDirectoryOffset = zip.size();
        write32(zip, 0x06064b50L);
        write64(zip, 44); // Size of the rest of this record
        write16(zip, 45); // Version made by
        write16(zip, 45); // Version needed to extract
        write32(zip, 0); // Disk number
        write32(zip, 0); // Disk on which the central directory starts
        write64(zip, numEntInZip64Record); // Number of entries on this disk
        write64(zip, numEntInZip64Record); // Total number of entries
        write64(zip, centralDirectorySize);
        write64(zip, centralDirectoryOffset);

        // Zip64 end of central directory locator
        write32(zip, 0x07064b50L);
        write32(zip, 0); // Disk on which the Zip64 end of central directory record is found
        write64(zip, zip64EndOfCentralDirectoryOffset);
        write32(zip, 1); // Total number of disks

        // End of central directory record
        write32(zip, 0x06054b50L);
        write16(zip, 0); // Disk number
        write16(zip, 0); // Disk on which the central directory starts
        write16(zip, numEntInEndOfCentralDirectory); // Number of entries on this disk
        write16(zip, numEntInEndOfCentralDirectory); // Total number of entries
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
                entries.add(entry.getName() + ": " + new String(entry.load(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    /** The two records agreeing on the number of entries is the ordinary case. */
    @Test
    public void entryCountsThatAgree(@TempDir final File tempDir) throws Exception {
        assertThat(entriesReadBack(writeZip(tempDir, "counts-agree.jar", ENTRY_NAMES.length)))
                .containsExactly("testpkg/first.txt: first contents", "testpkg/second.txt: second contents");
    }

    /**
     * The 16-bit entry count field of the End Of Central Directory record holds 0xffff when the real count is too
     * large to fit in it, in which case the Zip64 count is the real one.
     */
    @Test
    public void entryCountOverflowedIntoTheZip64Record(@TempDir final File tempDir) throws Exception {
        assertThat(entriesReadBack(writeZip(tempDir, "count-overflowed.jar", 0xffff)))
                .containsExactly("testpkg/first.txt: first contents", "testpkg/second.txt: second contents");
    }

    /**
     * When the two records disagree on the number of entries for any other reason, neither count can be trusted, so
     * the entries are counted by walking the central directory, and all of them are still found.
     */
    @Test
    public void entryCountsThatDisagree(@TempDir final File tempDir) throws Exception {
        assertThat(entriesReadBack(writeZip(tempDir, "counts-disagree.jar", ENTRY_NAMES.length - 1)))
                .containsExactly("testpkg/first.txt: first contents", "testpkg/second.txt: second contents");
    }

    /**
     * The Zip64 entry count is an unsigned 64-bit field, so a value of 2^63 or greater is read back as a negative
     * number, which is too small rather than too large to be caught by a range check, and which then truncates to a
     * large positive {@code int} when it is used to size the list of entries. The count here truncates to
     * 0x7ffffff0, which would ask for a two-billion-element list.
     */
    @Test
    public void entryCountTooLargeForASignedLong(@TempDir final File tempDir) throws Exception {
        assertThatThrownBy(
                () -> entriesReadBack(writeZip(tempDir, "count-negative.jar", 0xffff, 0xffffffff7ffffff0L)))
                .isInstanceOf(IOException.class).hasStackTraceContaining("Zip64 number of entries is out of range");
    }
}
