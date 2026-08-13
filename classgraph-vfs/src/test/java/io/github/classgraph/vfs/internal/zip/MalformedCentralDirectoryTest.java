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
 * The entries of a zipfile are found through its End Of Central Directory record, and through the Zip64 End Of
 * Central Directory record that supersedes it in a zipfile too large for the 32-bit fields of the original record.
 * Everything those records say about where the central directory is, and how much of it there is, has to be checked
 * before it is used, because a record that says something impossible would otherwise send a read to an arbitrary
 * place in the file, or ask for an arbitrary amount of memory.
 *
 * <p>
 * A zipfile split across several disks is also rejected here rather than read: only the last disk holds the End Of
 * Central Directory record, so the other disks, and most of the entries, are simply not present in the file.
 *
 * <p>
 * The zipfiles here are written byte by byte rather than with {@link java.util.zip.ZipOutputStream}, because
 * {@code ZipOutputStream} writes its own End Of Central Directory records, and writes them correctly.
 */
public class MalformedCentralDirectoryTest {
    /** The names of the two entries of every zipfile written here. */
    private static final String[] ENTRY_NAMES = { "testpkg/first.txt", "testpkg/second.txt" };

    /** The contents of the entries, which are stored uncompressed. */
    private static final byte[][] CONTENTS = { "first contents".getBytes(StandardCharsets.UTF_8),
            "second contents".getBytes(StandardCharsets.UTF_8) };

    /** The names the entries are read back under when the zipfile is read successfully. */
    private static final String[] EXPECTED_ENTRIES = { "testpkg/first.txt: first contents",
            "testpkg/second.txt: second contents" };

    /** The signature of a Zip64 End Of Central Directory record. */
    private static final long ZIP64_EOCD_SIGNATURE = 0x06064b50L;

    // -----------------------------------------------------------------------------------------------------------

    /**
     * A description of the End Of Central Directory records to write. Every field defaults to the value that
     * describes the zipfile correctly, so a test sets only the one field it is about.
     */
    private static final class ZipSpec {
        /** Whether to write a Zip64 End Of Central Directory record and its locator. */
        private boolean zip64;

        /** The number of the disk this zipfile is on, per the End Of Central Directory record. */
        private int eocdDiskNumber;

        /** The number of the disk the central directory starts on, per the End Of Central Directory record. */
        private int eocdCentralDirectoryStartDisk;

        /** The number of entries on this disk, per the End Of Central Directory record. */
        private int eocdNumEntThisDisk = ENTRY_NAMES.length;

        /** The total number of entries, per the End Of Central Directory record. */
        private int eocdNumEntTotal = ENTRY_NAMES.length;

        /** The size of the central directory per the End Of Central Directory record, or null for the real size. */
        private Long eocdCentralDirectorySize;

        /**
         * The offset of the central directory per the End Of Central Directory record, or null for the real offset.
         */
        private Long eocdCentralDirectoryOffset;

        /** The signature to write at the head of the Zip64 End Of Central Directory record. */
        private long zip64Signature = ZIP64_EOCD_SIGNATURE;

        /** The number of the disk this zipfile is on, per the Zip64 End Of Central Directory record. */
        private int zip64DiskNumber;

        /** The number of the disk the central directory starts on, per the Zip64 record. */
        private int zip64CentralDirectoryStartDisk;

        /** The number of entries on this disk, per the Zip64 End Of Central Directory record. */
        private long zip64NumEntThisDisk = ENTRY_NAMES.length;

        /** The total number of entries, per the Zip64 End Of Central Directory record. */
        private long zip64NumEntTotal = ENTRY_NAMES.length;

        /** The size of the central directory per the Zip64 record, or null for the real size. */
        private Long zip64CentralDirectorySize;

        /** The offset of the central directory per the Zip64 record, or null for the real offset. */
        private Long zip64CentralDirectoryOffset;

        /** The number of the disk the Zip64 End Of Central Directory record is on, per its locator. */
        private int locatorZip64RecordDisk;

        /** The total number of disks, per the Zip64 End Of Central Directory locator. */
        private long locatorTotalDisks = 1;

        /** The index of the entry whose central directory signature to corrupt, or -1 to corrupt none. */
        private int entryWithACorruptSignature = -1;

        /**
         * Write a Zip64 End Of Central Directory record and its locator.
         *
         * @return this (for method chaining)
         */
        private ZipSpec zip64() {
            zip64 = true;
            return this;
        }
    }

    /**
     * A zipfile whose End Of Central Directory record describes it correctly, and which has no Zip64 record.
     *
     * @return the zipfile description
     */
    private static ZipSpec zip() {
        return new ZipSpec();
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
     * Write a zipfile holding two stored entries, with the End Of Central Directory records the given description
     * asks for.
     *
     * @param tempDir
     *            the directory to write the zipfile into
     * @param jarName
     *            the name of the zipfile to write
     * @param zipSpec
     *            the description of the End Of Central Directory records to write
     * @return the zipfile
     * @throws Exception
     *             if the zipfile could not be written
     */
    private static File writeZip(final File tempDir, final String jarName, final ZipSpec zipSpec) throws Exception {
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
            write32(zip, i == zipSpec.entryWithACorruptSignature ? 0x02014b51L : 0x02014b50L);
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

        if (zipSpec.zip64) {
            // Zip64 end of central directory record, which has to start where the central directory ends
            final var zip64EndOfCentralDirectoryOffset = zip.size();
            write32(zip, zipSpec.zip64Signature);
            write64(zip, 44); // Size of the rest of this record
            write16(zip, 45); // Version made by
            write16(zip, 45); // Version needed to extract
            write32(zip, zipSpec.zip64DiskNumber);
            write32(zip, zipSpec.zip64CentralDirectoryStartDisk);
            write64(zip, zipSpec.zip64NumEntThisDisk);
            write64(zip, zipSpec.zip64NumEntTotal);
            write64(zip, zipSpec.zip64CentralDirectorySize == null ? centralDirectorySize
                    : zipSpec.zip64CentralDirectorySize);
            write64(zip, zipSpec.zip64CentralDirectoryOffset == null ? centralDirectoryOffset
                    : zipSpec.zip64CentralDirectoryOffset);

            // Zip64 end of central directory locator
            write32(zip, 0x07064b50L);
            write32(zip, zipSpec.locatorZip64RecordDisk);
            write64(zip, zip64EndOfCentralDirectoryOffset);
            write32(zip, zipSpec.locatorTotalDisks);
        }

        // End of central directory record
        write32(zip, 0x06054b50L);
        write16(zip, zipSpec.eocdDiskNumber);
        write16(zip, zipSpec.eocdCentralDirectoryStartDisk);
        write16(zip, zipSpec.eocdNumEntThisDisk);
        write16(zip, zipSpec.eocdNumEntTotal);
        write32(zip,
                zipSpec.eocdCentralDirectorySize == null ? centralDirectorySize : zipSpec.eocdCentralDirectorySize);
        write32(zip, zipSpec.eocdCentralDirectoryOffset == null ? centralDirectoryOffset
                : zipSpec.eocdCentralDirectoryOffset);
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

    // -----------------------------------------------------------------------------------------------------------

    /** A correctly described zipfile is read, whether or not it has a Zip64 End Of Central Directory record. */
    @Test
    public void aWellFormedZipfileIsRead(@TempDir final File tempDir) throws Exception {
        assertThat(entriesReadBack(writeZip(tempDir, "well-formed.jar", zip()))).containsExactly(EXPECTED_ENTRIES);
        assertThat(entriesReadBack(writeZip(tempDir, "well-formed-zip64.jar", zip().zip64())))
                .containsExactly(EXPECTED_ENTRIES);
    }

    /** A zipfile that is not the first disk of a multi-disk zipfile is missing everything before this disk. */
    @Test
    public void aZipfileThatIsNotTheFirstDiskIsRejected(@TempDir final File tempDir) throws Exception {
        final var spec = zip();
        spec.eocdDiskNumber = 1;
        final var jarFile = writeZip(tempDir, "not-first-disk.jar", spec);
        assertThatThrownBy(() -> entriesReadBack(jarFile)).rootCause()
                .hasMessageContaining("Multi-disk jarfiles not supported");
    }

    /** A zipfile whose central directory starts on an earlier disk is missing part of its central directory. */
    @Test
    public void aZipfileWhoseCentralDirectoryStartsOnAnotherDiskIsRejected(@TempDir final File tempDir)
            throws Exception {
        final var spec = zip();
        spec.eocdCentralDirectoryStartDisk = 1;
        final var jarFile = writeZip(tempDir, "central-directory-elsewhere.jar", spec);
        assertThatThrownBy(() -> entriesReadBack(jarFile)).rootCause()
                .hasMessageContaining("Multi-disk jarfiles not supported");
    }

    /**
     * The End Of Central Directory record holds both the number of entries on this disk and the total number of
     * entries, and the two differing means the rest of the entries are on a disk that is not here.
     */
    @Test
    public void aZipfileWithMoreEntriesThanAreOnThisDiskIsRejected(@TempDir final File tempDir) throws Exception {
        final var spec = zip();
        spec.eocdNumEntThisDisk = ENTRY_NAMES.length;
        spec.eocdNumEntTotal = ENTRY_NAMES.length + 1;
        final var jarFile = writeZip(tempDir, "entries-elsewhere.jar", spec);
        assertThatThrownBy(() -> entriesReadBack(jarFile)).rootCause()
                .hasMessageContaining("Multi-disk jarfiles not supported");
    }

    /** The Zip64 End Of Central Directory locator naming another disk means the Zip64 record is not here. */
    @Test
    public void aZip64RecordOnAnotherDiskIsRejected(@TempDir final File tempDir) throws Exception {
        final var spec = zip().zip64();
        spec.locatorZip64RecordDisk = 1;
        final var jarFile = writeZip(tempDir, "zip64-record-elsewhere.jar", spec);
        assertThatThrownBy(() -> entriesReadBack(jarFile)).rootCause()
                .hasMessageContaining("Multi-disk jarfiles not supported");
    }

    /** The Zip64 End Of Central Directory locator giving a disk count above one describes a multi-disk zipfile. */
    @Test
    public void aZip64LocatorThatCountsMoreThanOneDiskIsRejected(@TempDir final File tempDir) throws Exception {
        final var spec = zip().zip64();
        spec.locatorTotalDisks = 2;
        final var jarFile = writeZip(tempDir, "zip64-many-disks.jar", spec);
        assertThatThrownBy(() -> entriesReadBack(jarFile)).rootCause()
                .hasMessageContaining("Multi-disk jarfiles not supported");
    }

    /** The two disk fields and the two entry counts of the Zip64 record are checked as the plain record's are. */
    @Test
    public void aZip64RecordThatDescribesAMultiDiskZipfileIsRejected(@TempDir final File tempDir) throws Exception {
        final var onALaterDisk = zip().zip64();
        onALaterDisk.zip64DiskNumber = 1;
        final var centralDirectoryElsewhere = zip().zip64();
        centralDirectoryElsewhere.zip64CentralDirectoryStartDisk = 1;
        final var entriesElsewhere = zip().zip64();
        entriesElsewhere.zip64NumEntTotal = ENTRY_NAMES.length + 1;

        assertThatThrownBy(() -> entriesReadBack(writeZip(tempDir, "zip64-not-first-disk.jar", onALaterDisk)))
                .rootCause().hasMessageContaining("Multi-disk jarfiles not supported");
        assertThatThrownBy(
                () -> entriesReadBack(writeZip(tempDir, "zip64-cen-elsewhere.jar", centralDirectoryElsewhere)))
                .rootCause().hasMessageContaining("Multi-disk jarfiles not supported");
        assertThatThrownBy(
                () -> entriesReadBack(writeZip(tempDir, "zip64-entries-elsewhere.jar", entriesElsewhere)))
                .rootCause().hasMessageContaining("Multi-disk jarfiles not supported");
    }

    /** The locator says where the Zip64 End Of Central Directory record is, so that record has to be there. */
    @Test
    public void aZip64LocatorPointingAtSomethingElseIsRejected(@TempDir final File tempDir) throws Exception {
        final var spec = zip().zip64();
        spec.zip64Signature = ZIP64_EOCD_SIGNATURE + 1;
        final var jarFile = writeZip(tempDir, "zip64-record-missing.jar", spec);
        assertThatThrownBy(() -> entriesReadBack(jarFile)).rootCause()
                .hasMessageContaining("does not have Zip64 central directory header");
    }

    /**
     * The size of the central directory is recorded in both End Of Central Directory records, and the plain record
     * holds the overflow marker when the size does not fit in its 32-bit field, so any other disagreement between
     * the two means one of them is wrong, and there is no way to tell which.
     */
    @Test
    public void recordsThatDisagreeOnTheSizeOfTheCentralDirectoryAreRejected(@TempDir final File tempDir)
            throws Exception {
        final var spec = zip().zip64();
        spec.zip64CentralDirectorySize = 46L;
        final var jarFile = writeZip(tempDir, "size-mismatch.jar", spec);
        assertThatThrownBy(() -> entriesReadBack(jarFile)).rootCause()
                .hasMessageContaining("Mismatch in central directory size");
    }

    /** As for its size, the two records disagreeing on where the central directory is cannot be resolved. */
    @Test
    public void recordsThatDisagreeOnTheOffsetOfTheCentralDirectoryAreRejected(@TempDir final File tempDir)
            throws Exception {
        final var spec = zip().zip64();
        spec.zip64CentralDirectoryOffset = 0L;
        final var jarFile = writeZip(tempDir, "offset-mismatch.jar", spec);
        assertThatThrownBy(() -> entriesReadBack(jarFile)).rootCause()
                .hasMessageContaining("Mismatch in central directory offset");
    }

    /**
     * The central directory ends where the End Of Central Directory record starts, so a central directory longer
     * than everything before that record would have to start before the beginning of the file.
     */
    @Test
    public void aCentralDirectoryLongerThanTheZipfileIsRejected(@TempDir final File tempDir) throws Exception {
        final var spec = zip();
        spec.eocdCentralDirectorySize = 1_000_000L;
        final var jarFile = writeZip(tempDir, "central-directory-too-long.jar", spec);
        assertThatThrownBy(() -> entriesReadBack(jarFile)).rootCause()
                .hasMessageContaining("Central directory size out of range");
    }

    /**
     * The offset of the central directory recorded in the End Of Central Directory record is relative to the start
     * of the entries, which is not the start of the file for a zipfile with something prepended to it, such as a
     * self-extracting archive. An offset larger than where the central directory really is would put the start of
     * the entries before the beginning of the file.
     */
    @Test
    public void aCentralDirectoryOffsetLargerThanTheZipfileIsRejected(@TempDir final File tempDir)
            throws Exception {
        final var spec = zip();
        spec.eocdCentralDirectoryOffset = 1_000_000L;
        final var jarFile = writeZip(tempDir, "central-directory-offset-too-large.jar", spec);
        assertThatThrownBy(() -> entriesReadBack(jarFile)).rootCause()
                .hasMessageContaining("Local file header offset out of range");
    }

    /**
     * A central directory record is at least 46 bytes long, so a zipfile that claims more entries than its central
     * directory has room for is claiming entries that are not there, and would otherwise have that much memory
     * reserved for them.
     */
    @Test
    public void moreEntriesThanTheCentralDirectoryHasRoomForAreRejected(@TempDir final File tempDir)
            throws Exception {
        final var spec = zip();
        spec.eocdNumEntThisDisk = 1000;
        spec.eocdNumEntTotal = 1000;
        final var jarFile = writeZip(tempDir, "too-many-entries.jar", spec);
        assertThatThrownBy(() -> entriesReadBack(jarFile)).rootCause()
                .hasMessageContaining("Too many zipfile entries: 1000");
    }

    /**
     * When the two End Of Central Directory records disagree on the number of entries, the entries are counted by
     * walking the central directory, which cannot be done if a record is not where the previous one said it ends.
     */
    @Test
    public void aCorruptRecordFoundWhileCountingTheEntriesIsRejected(@TempDir final File tempDir) throws Exception {
        final var spec = zip().zip64();
        spec.eocdNumEntThisDisk = ENTRY_NAMES.length - 1;
        spec.eocdNumEntTotal = ENTRY_NAMES.length - 1;
        spec.entryWithACorruptSignature = 1;
        final var jarFile = writeZip(tempDir, "corrupt-while-counting.jar", spec);
        assertThatThrownBy(() -> entriesReadBack(jarFile)).rootCause()
                .hasMessageContaining("Invalid central directory signature");
    }

    /** A file too short to hold even an empty End Of Central Directory record is not a zipfile. */
    @Test
    public void aFileTooShortToHoldACentralDirectoryIsRejected(@TempDir final File tempDir) throws Exception {
        final var jarFile = new File(tempDir, "too-short.jar");
        Files.write(jarFile.toPath(), new byte[] { 'P', 'K', 3, 4 });
        assertThatThrownBy(() -> entriesReadBack(jarFile)).rootCause()
                .hasMessageContaining("Zipfile too short to have a central directory");
    }
}
