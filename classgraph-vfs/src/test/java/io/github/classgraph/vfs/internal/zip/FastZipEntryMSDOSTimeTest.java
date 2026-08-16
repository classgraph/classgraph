package io.github.classgraph.vfs.internal.zip;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.vfs.internal.VfsSpec;

/**
 * Tests that the month of an MS-DOS timestamp is read from all four of its bits.
 *
 * <p>
 * In an MS-DOS date, bits 0-4 hold the day, bits 5-8 the month, and bits 9-15 the year. The month used to be masked
 * with three bits rather than four, so the last five months of the year were read as the first five.
 */
public class FastZipEntryMSDOSTimeTest {
    /** The name of the zip entry written by the test. */
    private static final String ENTRY_NAME = "testpkg/test.txt";

    /** The last modified time of the zip entry written by the test. */
    private static final LocalDateTime LAST_MODIFIED = LocalDateTime.of(2020, 9, 15, 10, 30, 0);

    /** A zip entry timestamped in September must not be read as January. */
    @Test
    public void msdosMonthIsReadFromFourBits(@TempDir final File tempDir) throws Exception {
        final var jarFile = new File(tempDir, "msdos-time.jar");
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            final var entry = new ZipEntry(ENTRY_NAME);
            // ZipEntry#setTime(long) records only an MS-DOS timestamp, converted using the default timezone -- it
            // does not add an extended timestamp extra field, so ClassGraph has to decode the MS-DOS date and time
            // fields
            entry.setTime(LAST_MODIFIED.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            zipOut.putNextEntry(entry);
            zipOut.write("contents".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }

        final var nestedJarHandler = new NestedJarHandler(new VfsSpec(), new InterruptionChecker());
        final long lastModifiedTimeMillis;
        try {
            final var logicalZipFileAndPackageRoot = nestedJarHandler.nestedPathToLogicalZipFileAndPackageRootMap()
                    .get(jarFile.getPath(), /* log = */ null);
            final var entries = logicalZipFileAndPackageRoot.getKey().entries;
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).entryName).isEqualTo(ENTRY_NAME);
            lastModifiedTimeMillis = entries.get(0).getLastModifiedTimeMillis();
        } finally {
            // The jarfile must not be left open, otherwise the temporary directory cannot be deleted on Windows
            nestedJarHandler.close(/* log = */ null);
        }

        // An MS-DOS timestamp has no timezone, and is read as UTC
        assertThat(lastModifiedTimeMillis).isEqualTo(LAST_MODIFIED.toInstant(ZoneOffset.UTC).toEpochMilli());
    }
}
