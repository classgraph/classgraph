package io.github.classgraph.vfs.internal.zip;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.vfs.internal.spec.VfsScanSpec;

/**
 * Tests for the entry name held in an Info-ZIP Unicode path extra field (tag 0x7075), which replaces the entry's
 * own name.
 *
 * <p>
 * The extra field's data area is {@code version(1) + nameCRC32(4) + name}, so the name is {@code size - 5} bytes
 * long. It used to be read as {@code size - 9} bytes, which dropped the last four bytes of every such entry name.
 * The replacement name also used to be used as read, skipping the sanitization and directory-entry check that the
 * name it replaces goes through.
 */
public class UnicodePathExtraFieldTest {
    /** The entry name stored in the normal zip entry name field. */
    private static final String LEGACY_NAME = "testpkg/legacy.txt";

    /** The entry name stored in the Info-ZIP Unicode path extra field. */
    private static final String UNICODE_NAME = "testpkg/unicode.txt";

    /**
     * Build an Info-ZIP Unicode path extra field (tag 0x7075) holding the given entry name.
     *
     * @param unicodeName
     *            the entry name to store in the extra field
     * @return the extra field bytes
     */
    private static byte[] makeUnicodePathExtraField(final String unicodeName) {
        final var nameBytes = unicodeName.getBytes(StandardCharsets.UTF_8);
        final var buf = new ByteArrayOutputStream();
        // Header ID 0x7075, little-endian
        buf.write(0x75);
        buf.write(0x70);
        // Data size = version(1) + nameCRC32(4) + name, little-endian
        final var dataSize = 5 + nameBytes.length;
        buf.write(dataSize & 0xff);
        buf.write((dataSize >> 8) & 0xff);
        // Version
        buf.write(1);
        // CRC32 of the legacy name (not checked by the reader)
        for (var i = 0; i < 4; i++) {
            buf.write(0);
        }
        for (final byte nameByte : nameBytes) {
            buf.write(nameByte);
        }
        return buf.toByteArray();
    }

    /**
     * Write a jar whose entries each carry a Unicode path extra field, and return the entry names ClassGraph reads
     * back from its central directory.
     *
     * @param tempDir
     *            the directory to write the jar into
     * @param jarName
     *            the name of the jar to write
     * @param legacyAndUnicodeNames
     *            for each entry, the name stored in the entry name field, then the name stored in the extra field
     * @return the entry names read back from the jar
     * @throws Exception
     *             if the jar could not be written or read
     */
    private static List<String> entryNamesReadBack(final File tempDir, final String jarName,
            final String[][] legacyAndUnicodeNames) throws Exception {
        final var jarFile = new File(tempDir, jarName);
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            for (final String[] names : legacyAndUnicodeNames) {
                final var entry = new ZipEntry(names[0]);
                entry.setExtra(makeUnicodePathExtraField(names[1]));
                zipOut.putNextEntry(entry);
                zipOut.write("contents".getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
            }
        }

        final var nestedJarHandler = new NestedJarHandler(new VfsScanSpec(), new InterruptionChecker(),
                new ReflectionUtils());
        final List<String> entryNames = new ArrayList<>();
        try {
            final var logicalZipFileAndPackageRoot = nestedJarHandler.nestedPathToLogicalZipFileAndPackageRootMap()
                    .get(jarFile.getPath(), /* log = */ null);
            for (final FastZipEntry zipEntry : logicalZipFileAndPackageRoot.getKey().entries) {
                entryNames.add(zipEntry.entryName);
            }
        } finally {
            // The jarfile must not be left open, otherwise the temporary directory cannot be deleted on Windows
            nestedJarHandler.close(/* log = */ null);
        }
        return entryNames;
    }

    /** An entry name held in a Unicode path extra field must not be truncated. */
    @Test
    public void unicodePathExtraFieldNameIsNotTruncated(@TempDir final File tempDir) throws Exception {
        assertThat(
                entryNamesReadBack(tempDir, "unicode-path.jar", new String[][] { { LEGACY_NAME, UNICODE_NAME } }))
                .containsExactly(UNICODE_NAME);
    }

    /**
     * An entry name held in a Unicode path extra field replaces the entry's own name, so it has to be sanitized in
     * the same way -- otherwise an entry can carry a path that escapes its package root, or that is absolute,
     * simply by declaring it in an extra field.
     */
    @Test
    public void unicodePathExtraFieldNameIsSanitized(@TempDir final File tempDir) throws Exception {
        assertThat(entryNamesReadBack(tempDir, "unsanitized-unicode-path.jar",
                new String[][] { { "pkg/dots.txt", "pkg/../../escaped/dots.txt" },
                        { "pkg/absolute.txt", "/absolute.txt" }, { "pkg/doubled.txt", "pkg//doubled.txt" },
                        { "pkg/dot.txt", "pkg/./dot.txt" } }))
                .containsExactly("escaped/dots.txt", "absolute.txt", "pkg/doubled.txt", "pkg/dot.txt");
    }

    /**
     * A Unicode path extra field can rename a file entry into a directory entry, or into nothing at all. Directory
     * entries are not listed, so such an entry is dropped rather than being listed under an empty or
     * separator-terminated name.
     */
    @Test
    public void unicodePathExtraFieldCanRenameAnEntryToADirectory(@TempDir final File tempDir) throws Exception {
        assertThat(entryNamesReadBack(tempDir, "directory-unicode-path.jar", new String[][] {
                { "pkg/dir.txt", "pkg/dir/" }, { "pkg/root.txt", "/" }, { "pkg/kept.txt", "pkg/kept.txt" } }))
                .containsExactly("pkg/kept.txt");
    }
}
