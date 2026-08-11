package nonapi.io.github.classgraph.fileslice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/** Tests for the identity of a {@link Slice}, and for the closing of the slices that a scan left open. */
public class SliceTest {
    /** The content of the test files. Both of them have the same content, and so the same length. */
    private static final byte[] CONTENT = "0123456789".getBytes(StandardCharsets.UTF_8);

    /**
     * Create the handler that owns the slices opened during a scan.
     *
     * @return the nested jar handler
     */
    private static NestedJarHandler nestedJarHandler() {
        return new NestedJarHandler(new ScanSpec(), new InterruptionChecker(), new ReflectionUtils());
    }

    /**
     * Write a file of the standard length.
     *
     * @param tempDir
     *            the temporary directory to write the file to
     * @param filename
     *            the name of the file
     * @return the file
     * @throws IOException
     *             if the file could not be written
     */
    private static File writeFile(final File tempDir, final String filename) throws IOException {
        final File file = new File(tempDir, filename);
        Files.write(file.toPath(), CONTENT);
        return file;
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
    public void slicesOfDifferentFilesOfTheSameLengthAreDifferentSlices(@TempDir final File tempDir)
            throws IOException {
        final NestedJarHandler nestedJarHandler = nestedJarHandler();
        final FileSlice first = new FileSlice(writeFile(tempDir, "first.bin"), nestedJarHandler, /* log = */ null);
        final FileSlice second = new FileSlice(writeFile(tempDir, "second.bin"), nestedJarHandler,
                /* log = */ null);
        try {
            assertThat(first).isNotEqualTo(second);
            // A slice is equal to itself, and to a slice of the same range of the same file
            assertThat(first).isEqualTo(first);
            assertThat(first.slice(0, 4, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L))
                    .isEqualTo(first.slice(0, 4, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L));
            // Two sub-slices that span the same range of two different files are different slices too, since they
            // inherit the identity of the slices they were taken from
            assertThat(first.slice(2, 4, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L))
                    .isNotEqualTo(
                            second.slice(2, 4, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L));
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
    }

    /**
     * Closing the handler that owns the slices opened during a scan closes every slice that was left open,
     * including slices that span the same range of two different files. Otherwise one of the files stays open,
     * which on Windows stops it from being deleted.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if a file could not be written or opened
     */
    @Test
    public void closingTheHandlerClosesEverySliceThatWasLeftOpen(@TempDir final File tempDir) throws IOException {
        final NestedJarHandler nestedJarHandler = nestedJarHandler();
        final FileSlice first = new FileSlice(writeFile(tempDir, "first.bin"), nestedJarHandler, /* log = */ null);
        final FileSlice second = new FileSlice(writeFile(tempDir, "second.bin"), nestedJarHandler,
                /* log = */ null);

        nestedJarHandler.close(/* log = */ null);

        // A closed slice has released the file it was reading, so it can no longer be read
        assertThatThrownBy(first::load).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(second::load).isInstanceOf(NullPointerException.class);
    }
}
