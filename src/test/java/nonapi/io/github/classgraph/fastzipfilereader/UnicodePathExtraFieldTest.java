package nonapi.io.github.classgraph.fastzipfilereader;

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

import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * Tests that the entry name in an Info-ZIP Unicode path extra field (tag
 * 0x7075) is read in full.
 *
 * <p>
 * The extra field's data area is {@code version(1) + nameCRC32(4) + name}, so
 * the name is {@code size - 5} bytes long. It used to be read as
 * {@code size - 9} bytes, which dropped the last four bytes of every such entry
 * name.
 */
public class UnicodePathExtraFieldTest {
    /** The entry name stored in the normal zip entry name field. */
    private static final String LEGACY_NAME = "testpkg/legacy.txt";

    /** The entry name stored in the Info-ZIP Unicode path extra field. */
    private static final String UNICODE_NAME = "testpkg/unicode.txt";

    /**
     * Build an Info-ZIP Unicode path extra field (tag 0x7075) holding
     * {@link #UNICODE_NAME}.
     *
     * @return the extra field bytes
     */
    private static byte[] makeUnicodePathExtraField() {
        final var nameBytes = UNICODE_NAME.getBytes(StandardCharsets.UTF_8);
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

    /** An entry name held in a Unicode path extra field must not be truncated. */
    @Test
    public void unicodePathExtraFieldNameIsNotTruncated(@TempDir final File tempDir) throws Exception {
        final var jarFile = new File(tempDir, "unicode-path.jar");
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            final var entry = new ZipEntry(LEGACY_NAME);
            entry.setExtra(makeUnicodePathExtraField());
            zipOut.putNextEntry(entry);
            zipOut.write("contents".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }

        final var nestedJarHandler = new NestedJarHandler(new ScanSpec(), new InterruptionChecker(),
                new ReflectionUtils());
        final List<String> entryNames = new ArrayList<>();
        try {
            final var logicalZipFileAndPackageRoot = nestedJarHandler.nestedPathToLogicalZipFileAndPackageRootMap()
                    .get(jarFile.getPath(), /* log = */ null);
            for (final FastZipEntry zipEntry : logicalZipFileAndPackageRoot.getKey().entries) {
                entryNames.add(zipEntry.entryName);
            }
        } finally {
            // The jarfile must not be left open, otherwise the temporary directory cannot
            // be deleted on Windows
            nestedJarHandler.close(/* log = */ null);
        }
        assertThat(entryNames).containsExactly(UNICODE_NAME);
    }
}
