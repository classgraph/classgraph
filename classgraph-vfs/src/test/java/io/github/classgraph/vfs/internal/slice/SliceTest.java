package io.github.classgraph.vfs.internal.slice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.vfs.internal.ScanResources;
import io.github.classgraph.vfs.internal.spec.VfsScanSpec;

/** Tests for the identity of a {@link Slice}, and for the closing of the slices that a scan left open. */
public class SliceTest {
    /** The content of the test files. Two of them have the same content, and so the same length. */
    private static final byte[] CONTENT = "0123456789".getBytes(StandardCharsets.UTF_8);

    /**
     * Create the resources owned by a scan.
     *
     * @return the scan resources
     */
    private static ScanResources scanResources() {
        return new ScanResources(new VfsScanSpec(), new ReflectionUtils(), new InterruptionChecker());
    }

    /**
     * Write a file of the standard length.
     *
     * @param tempDir
     *            the temporary directory to write the file to
     * @param filename
     *            the name of the file
     * @return the path of the file
     * @throws IOException
     *             if the file could not be written
     */
    private static Path writeFile(final Path tempDir, final String filename) throws IOException {
        return Files.write(tempDir.resolve(filename), CONTENT);
    }

    /**
     * Two slices of the same length taken from two different files are different slices, even though they span the
     * same range. Otherwise one of them stands in for the other wherever slices are collected in a set or a map.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if a file could not be written or opened
     */
    @Test
    public void slicesOfDifferentFilesOfTheSameLengthAreDifferentSlices(@TempDir final Path tempDir)
            throws IOException {
        final var scanResources = scanResources();
        try (var first = new PathSlice(writeFile(tempDir, "first.bin"), scanResources, /* log = */ null);
                var second = new PathSlice(writeFile(tempDir, "second.bin"), scanResources, /* log = */ null)) {
            assertThat(first).isNotEqualTo(second);
            // A slice is equal to itself, and to a slice of the same range of the same file
            assertThat(first).isEqualTo(first);
            assertThat(first.slice(0, 4, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L))
                    .isEqualTo(first.slice(0, 4, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L));
        }
    }

    /**
     * Two sub-slices that span the same range of two different files are different slices too, since they inherit
     * the identity of the slices they were taken from.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if a file could not be written or opened
     */
    @Test
    public void subSlicesOfDifferentFilesAreDifferentSlices(@TempDir final Path tempDir) throws IOException {
        final var scanResources = scanResources();
        try (var first = new PathSlice(writeFile(tempDir, "first.bin"), scanResources, /* log = */ null);
                var second = new PathSlice(writeFile(tempDir, "second.bin"), scanResources, /* log = */ null)) {
            assertThat(first.slice(2, 4, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L))
                    .isNotEqualTo(
                            second.slice(2, 4, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L));
        }
    }

    /**
     * Closing the resources owned by a scan closes every slice that was left open, including slices that span the
     * same range of two different files. Otherwise one of the files stays open, which on Windows stops it from
     * being deleted.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if a file could not be written or opened
     */
    @Test
    public void closingTheScanResourcesClosesEverySliceThatWasLeftOpen(@TempDir final Path tempDir)
            throws IOException {
        final var scanResources = scanResources();
        final var first = new FileSlice(writeFile(tempDir, "first.bin").toFile(), scanResources, /* log = */ null);
        final var second = new FileSlice(writeFile(tempDir, "second.bin").toFile(), scanResources,
                /* log = */ null);

        scanResources.close(/* log = */ null);

        // A closed slice has released the file it was reading, so it can no longer be read
        assertThatThrownBy(first::load).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(second::load).isInstanceOf(NullPointerException.class);
    }
}
