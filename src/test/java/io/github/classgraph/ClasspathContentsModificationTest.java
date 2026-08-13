package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link ScanResult#classpathContentsModifiedSinceScan()} and
 * {@link ScanResult#classpathContentsLastModifiedTime()}, which compare the timestamps recorded during a scan
 * against the current timestamps of the same files, so that a caller can tell whether it needs to scan again.
 */
public class ClasspathContentsModificationTest {
    /**
     * Write a file with a given last modified time.
     *
     * @param file
     *            the file to write.
     * @param lastModifiedMillis
     *            the last modified time to set on it.
     * @throws IOException
     *             if the file could not be written.
     */
    private static void writeFile(final Path file, final long lastModifiedMillis) throws IOException {
        Files.write(file, ("contents of " + file.getFileName()).getBytes("UTF-8"));
        Files.setLastModifiedTime(file, FileTime.fromMillis(lastModifiedMillis));
    }

    /**
     * Scan a directory as the only classpath element. The last modified time of the directory itself is recorded by
     * the scan too, since adding or removing a file changes it, so it is set to older than any file in the
     * directory, to keep it out of the way of the timestamps under test.
     *
     * @param dir
     *            the directory to scan.
     * @return the scan result.
     * @throws IOException
     *             if the last modified time of the directory could not be set.
     */
    private static ScanResult scanDir(final Path dir) throws IOException {
        Files.setLastModifiedTime(dir, FileTime.fromMillis(System.currentTimeMillis() - 600_000L));
        return new ClassGraph().overrideClasspath(dir.toString()).scan();
    }

    /**
     * The classpath contents are unmodified if nothing has changed since the scan, and the last modified time of
     * the classpath contents is that of the most recently modified file in it.
     *
     * @param tempDir
     *            a temporary directory to use as a classpath element.
     * @throws IOException
     *             if the files could not be written.
     */
    @Test
    public void unmodifiedClasspathContents(@TempDir final Path tempDir) throws IOException {
        final long now = System.currentTimeMillis();
        writeFile(tempDir.resolve("older.txt"), now - 60_000L);
        writeFile(tempDir.resolve("newer.txt"), now - 30_000L);

        try (ScanResult scanResult = scanDir(tempDir)) {
            assertThat(scanResult.classpathContentsModifiedSinceScan()).isFalse();
            assertThat(scanResult.classpathContentsLastModifiedTime()).isEqualTo(now - 30_000L);
        }
    }

    /**
     * The classpath contents are modified if a file in them has been given a different timestamp since the scan.
     *
     * @param tempDir
     *            a temporary directory to use as a classpath element.
     * @throws IOException
     *             if the files could not be written.
     */
    @Test
    public void modifiedClasspathContents(@TempDir final Path tempDir) throws IOException {
        final long now = System.currentTimeMillis();
        final Path file = tempDir.resolve("modified.txt");
        writeFile(file, now - 60_000L);

        try (ScanResult scanResult = scanDir(tempDir)) {
            assertThat(scanResult.classpathContentsModifiedSinceScan()).isFalse();

            writeFile(file, now - 10_000L);
            assertThat(scanResult.classpathContentsModifiedSinceScan()).isTrue();

            // The last modified time is read from the file rather than remembered from the scan, so it changes too
            assertThat(scanResult.classpathContentsLastModifiedTime()).isEqualTo(now - 10_000L);
        }
    }

    /**
     * A timestamp later than the current time cannot be the time anything was really modified, so it is ignored
     * rather than returned as the last modified time of the classpath contents.
     *
     * @param tempDir
     *            a temporary directory to use as a classpath element.
     * @throws IOException
     *             if the files could not be written.
     */
    @Test
    public void timestampsInTheFutureAreIgnored(@TempDir final Path tempDir) throws IOException {
        final long now = System.currentTimeMillis();
        writeFile(tempDir.resolve("past.txt"), now - 60_000L);
        writeFile(tempDir.resolve("future.txt"), now + 3_600_000L);

        try (ScanResult scanResult = scanDir(tempDir)) {
            assertThat(scanResult.classpathContentsLastModifiedTime()).isEqualTo(now - 60_000L);

            // A file with a timestamp in the future is still a file whose timestamp can change
            assertThat(scanResult.classpathContentsModifiedSinceScan()).isFalse();
        }
    }
}
