package io.github.classgraph.vfs.internal.zip;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.vfs.VfsSpec;
import io.github.classgraph.vfs.Vfs;

/**
 * The extended timestamp extra field (tag 0x5455) and the deprecated Info-ZIP Unix extra field (tag 0x5855) both
 * store times as 4-byte signed Unix times, in seconds. They used to be read as 8-byte values, which made the size
 * checks unsatisfiable for a conforming central directory entry, so the times were never read, and the entry's last
 * modified time always fell back to the MS-DOS timestamp, which has a resolution of only two seconds.
 *
 * <p>
 * The zipfiles here are written byte by byte rather than with {@link java.util.zip.ZipOutputStream}, because
 * {@code ZipOutputStream} drops any extended timestamp extra field it is handed and writes its own.
 */
public class ExtendedTimestampExtraFieldTest {
    /** The name of the entry written by the test. */
    private static final String ENTRY_NAME = "testpkg/test.txt";

    /** The contents of the entry, which is stored uncompressed. */
    private static final byte[] CONTENTS = "contents".getBytes(StandardCharsets.UTF_8);

    /** A last modified time after the Unix epoch: 2020-09-15T10:30:00Z. */
    private static final int MODIFICATION_TIME = 1600165800;

    /** A last modified time before the Unix epoch, which is only read correctly if the time is signed. */
    private static final int MODIFICATION_TIME_BEFORE_EPOCH = -152391233;

    /** An access time, which is stored before the modification time in the Info-ZIP Unix extra field. */
    private static final int ACCESS_TIME = 1600000000;

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
     * Build an extended timestamp extra field (tag 0x5455) as it appears in a central directory entry, which holds
     * the flags byte followed by the modification time alone.
     *
     * @param modificationTime
     *            the modification time, as a signed Unix time in seconds
     * @return the extra field bytes
     */
    private static byte[] makeExtendedTimestampExtraField(final int modificationTime) {
        final var buf = new ByteArrayOutputStream();
        write16(buf, 0x5455);
        // Data size = flags(1) + modificationTime(4)
        write16(buf, 5);
        // Bit 0 says the modification time is present
        buf.write(1);
        write32(buf, modificationTime);
        return buf.toByteArray();
    }

    /**
     * Build an Info-ZIP Unix extra field (tag 0x5855) as it appears in a central directory entry, which holds the
     * access time and the modification time, but not the optional UID and GID.
     *
     * @param accessTime
     *            the access time, as a signed Unix time in seconds
     * @param modificationTime
     *            the modification time, as a signed Unix time in seconds
     * @return the extra field bytes
     */
    private static byte[] makeInfoZipUnixExtraField(final int accessTime, final int modificationTime) {
        final var buf = new ByteArrayOutputStream();
        write16(buf, 0x5855);
        // Data size = accessTime(4) + modificationTime(4)
        write16(buf, 8);
        write32(buf, accessTime);
        write32(buf, modificationTime);
        return buf.toByteArray();
    }

    /**
     * Write a jarfile holding a single stored entry whose central directory extra field area is the given extra
     * field, and return the last modified time ClassGraph reads back for the entry.
     *
     * @param tempDir
     *            the directory to write the jar into
     * @param jarName
     *            the name of the jar to write
     * @param extraField
     *            the extra field of the entry
     * @return the last modified time read back from the jar, in milliseconds since the Unix epoch
     * @throws Exception
     *             if the jar could not be written or read
     */
    private static long lastModifiedTimeReadBack(final File tempDir, final String jarName, final byte[] extraField)
            throws Exception {
        final var nameBytes = ENTRY_NAME.getBytes(StandardCharsets.UTF_8);
        final var crc = new CRC32();
        crc.update(CONTENTS);

        final var zip = new ByteArrayOutputStream();

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
        zip.writeBytes(nameBytes);
        zip.writeBytes(CONTENTS);

        // Central directory
        final var centralDirectoryOffset = zip.size();
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
        write16(zip, extraField.length);
        write16(zip, 0); // Comment length
        write16(zip, 0); // Disk number start
        write16(zip, 0); // Internal file attributes
        write32(zip, 0); // External file attributes
        write32(zip, 0); // Local file header offset
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

        final var vfs = new Vfs(new VfsSpec(), new InterruptionChecker());
        try {
            final var entries = JarOpener.openJarFile(jarFile, vfs, /* log = */ null).entries;
            assertThat(entries).hasSize(1);
            return entries.get(0).getLastModifiedMillis();
        } finally {
            // The jarfile must not be left open, otherwise the temporary directory cannot be deleted on Windows
            vfs.close(/* log = */ null);
        }
    }

    /** The modification time in an extended timestamp extra field overrides the MS-DOS timestamp. */
    @Test
    public void extendedTimestampExtraFieldIsRead(@TempDir final File tempDir) throws Exception {
        assertThat(lastModifiedTimeReadBack(tempDir, "extended-timestamp.jar",
                makeExtendedTimestampExtraField(MODIFICATION_TIME))).isEqualTo(MODIFICATION_TIME * 1000L);
    }

    /** A modification time before the Unix epoch is negative, and must not be read as a huge positive time. */
    @Test
    public void extendedTimestampExtraFieldBeforeEpochIsRead(@TempDir final File tempDir) throws Exception {
        assertThat(lastModifiedTimeReadBack(tempDir, "extended-timestamp-before-epoch.jar",
                makeExtendedTimestampExtraField(MODIFICATION_TIME_BEFORE_EPOCH)))
                .isEqualTo(MODIFICATION_TIME_BEFORE_EPOCH * 1000L);
    }

    /** The modification time in an Info-ZIP Unix extra field is the second of its two times, not the first. */
    @Test
    public void infoZipUnixExtraFieldIsRead(@TempDir final File tempDir) throws Exception {
        assertThat(lastModifiedTimeReadBack(tempDir, "info-zip-unix.jar",
                makeInfoZipUnixExtraField(ACCESS_TIME, MODIFICATION_TIME))).isEqualTo(MODIFICATION_TIME * 1000L);
    }
}
