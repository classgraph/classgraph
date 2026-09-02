package io.github.classgraph.vfs.internal.zip;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.vfs.VfsSpec;
import io.github.classgraph.vfs.Vfs;

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
        // An MS-DOS timestamp has no timezone, and is read as UTC
        assertThat(msdosLastModifiedMillis(tempDir))
                .isEqualTo(LAST_MODIFIED.toInstant(ZoneOffset.UTC).toEpochMilli());
    }

    /**
     * An MS-DOS timestamp is read the same way whatever the default locale is.
     *
     * <p>
     * The year, month and day of an MS-DOS date are Gregorian, but a default locale of {@code th-TH-u-ca-buddhist}
     * or {@code ja-JP-u-ca-japanese} makes {@link java.util.Calendar#getInstance(java.util.TimeZone)} hand back a
     * calendar of that locale's own system, which reads the same year as a year of a different era.
     */
    @Test
    public void msdosTimeIsReadTheSameWayInEveryLocale(@TempDir final File tempDir) throws Exception {
        final var defaultLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist"));
            assertThat(msdosLastModifiedMillis(tempDir))
                    .isEqualTo(LAST_MODIFIED.toInstant(ZoneOffset.UTC).toEpochMilli());
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    /**
     * Write a jarfile holding one entry timestamped {@link #LAST_MODIFIED}, and read that entry's last modified
     * time back.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into.
     * @return the last modified time of the entry, in millis since the epoch.
     * @throws Exception
     *             if the jarfile could not be written or read.
     */
    private static long msdosLastModifiedMillis(final File tempDir) throws Exception {
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

        final var vfs = new Vfs(new VfsSpec(), new InterruptionChecker());
        final long lastModifiedTimeMillis;
        try {
            final var entries = JarOpener.openJarFile(jarFile, vfs, /* log = */ null).zipFile().entries;
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).entryName).isEqualTo(ENTRY_NAME);
            lastModifiedTimeMillis = entries.get(0).getLastModifiedMillis();
        } finally {
            // The jarfile must not be left open, otherwise the temporary directory cannot be deleted on Windows
            vfs.close(/* log = */ null);
        }
        return lastModifiedTimeMillis;
    }
}
