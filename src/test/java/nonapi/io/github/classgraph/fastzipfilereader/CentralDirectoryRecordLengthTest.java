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
 * The length of a central directory record is the sum of the lengths of the name, the extra field area and the
 * comment it declares, and that is what says where the next record starts. A record that declares a length running
 * past the end of the central directory therefore leaves the position of every record after it unknown, so the
 * entries read so far are kept and the rest are dropped -- rather than the whole zipfile being rejected because
 * reading the record ran off the end of the central directory.
 *
 * <p>
 * The zipfiles here are written byte by byte rather than with {@link java.util.zip.ZipOutputStream}, because
 * {@code ZipOutputStream} will not write a malformed record.
 */
public class CentralDirectoryRecordLengthTest {
    /** The name of the entry whose central directory record is written correctly. */
    private static final String GOOD_NAME = "testpkg/good.txt";

    /** The name of the entry whose central directory record declares an impossible length. */
    private static final String BAD_NAME = "testpkg/bad.txt";

    /** The contents of every entry, which is stored uncompressed. */
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
     * Write a jarfile holding a correctly-written entry followed by an entry whose central directory record declares
     * the given extra field length and comment length, without writing either of them.
     *
     * @param tempDir
     *            the directory to write the jar into
     * @param jarName
     *            the name of the jar to write
     * @param badEntryExtraFieldLen
     *            the extra field length to declare in the second entry's central directory record
     * @param badEntryCommentLen
     *            the comment length to declare in the second entry's central directory record
     * @return the jarfile
     * @throws Exception
     *             if the jar could not be written
     */
    private static File writeZip(final File tempDir, final String jarName, final int badEntryExtraFieldLen,
            final int badEntryCommentLen) throws Exception {
        final String[] entryNames = { GOOD_NAME, BAD_NAME };
        final CRC32 crc = new CRC32();
        crc.update(CONTENTS);
        final ByteArrayOutputStream zip = new ByteArrayOutputStream();

        // Local file header of each entry, each immediately followed by the contents of the entry
        final List<Integer> localHeaderOffsets = new ArrayList<>();
        for (final String entryName : entryNames) {
            localHeaderOffsets.add(zip.size());
            final byte[] nameBytes = entryName.getBytes(StandardCharsets.UTF_8);
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
        }

        // Central directory record of each entry
        final int centralDirectoryOffset = zip.size();
        for (int i = 0; i < entryNames.length; i++) {
            final byte[] nameBytes = entryNames[i].getBytes(StandardCharsets.UTF_8);
            final boolean isBadEntry = i == 1;
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
            write16(zip, isBadEntry ? badEntryExtraFieldLen : 0);
            write16(zip, isBadEntry ? badEntryCommentLen : 0);
            write16(zip, 0); // Disk number start
            write16(zip, 0); // Internal file attributes
            write32(zip, 0); // External file attributes
            write32(zip, localHeaderOffsets.get(i));
            writeBytes(zip, nameBytes);
        }
        final int centralDirectorySize = zip.size() - centralDirectoryOffset;

        // End of central directory record
        write32(zip, 0x06054b50L);
        write16(zip, 0); // Disk number
        write16(zip, 0); // Disk on which the central directory starts
        write16(zip, entryNames.length); // Number of entries on this disk
        write16(zip, entryNames.length); // Total number of entries
        write32(zip, centralDirectorySize);
        write32(zip, centralDirectoryOffset);
        write16(zip, 0); // Comment length

        final File jarFile = new File(tempDir, jarName);
        try (OutputStream fileOut = new FileOutputStream(jarFile)) {
            fileOut.write(zip.toByteArray());
        }
        return jarFile;
    }

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

    /** A record whose extra field area extends past the end of the central directory ends the entries. */
    @Test
    public void anExtraFieldAreaExtendingPastTheCentralDirectory(@TempDir final File tempDir) throws Exception {
        assertThat(entryNamesReadBack(writeZip(tempDir, "long-extra-field-area.jar",
                /* badEntryExtraFieldLen = */ 60000, /* badEntryCommentLen = */ 0))).containsExactly(GOOD_NAME);
    }

    /**
     * A record whose comment extends past the end of the central directory ends the entries too, but the record
     * itself is still read: the comment is the one part of a record that is never read, so an entry whose comment is
     * the only part of it that does not fit is not damaged by that.
     */
    @Test
    public void aCommentExtendingPastTheCentralDirectory(@TempDir final File tempDir) throws Exception {
        assertThat(entryNamesReadBack(writeZip(tempDir, "long-comment.jar", /* badEntryExtraFieldLen = */ 0,
                /* badEntryCommentLen = */ 60000))).containsExactly(GOOD_NAME, BAD_NAME);
    }
}
