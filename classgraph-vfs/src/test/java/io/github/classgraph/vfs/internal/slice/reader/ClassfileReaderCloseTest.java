package io.github.classgraph.vfs.internal.slice.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.vfs.internal.ScanResources;
import io.github.classgraph.vfs.internal.slice.FileSlice;
import io.github.classgraph.vfs.internal.spec.VfsScanSpec;

/**
 * Tests that {@link ClassfileReader#close()} closes only what the reader itself opened. A
 * {@link java.io.InputStream} or a {@link io.github.classgraph.vfs.internal.slice.Slice} that the caller handed to
 * the reader belongs to the caller, which closes it in its own try-with-resources; closing it here as well would
 * close it out from under a caller that is still using it, or close it twice.
 */
public class ClassfileReaderCloseTest {
    /** An input stream that is at EOF, and that records whether it was closed. */
    private static final class RecordingInputStream extends InputStream {
        /** True once {@link #close()} has been called. */
        boolean closed;

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** A temporary directory to write the file that the file slice reads. */
    @TempDir
    private Path tempDir;

    /** The resources owned by the scan, closed when the test ends. */
    private final ScanResources scanResources = new ScanResources(new VfsScanSpec(), new InterruptionChecker());

    /** Close the slices that the test opened. */
    @AfterEach
    public void closeScanResources() {
        scanResources.close(/* log = */ null);
    }

    /** A stream that the caller opened is left open, so that the caller can close it itself. */
    @Test
    public void aStreamOpenedByTheCallerIsNotClosedByTheReader() {
        final var inputStream = new RecordingInputStream();
        new ClassfileReader(inputStream).close();
        assertThat(inputStream.closed).isFalse();
    }

    /**
     * A slice that the caller opened is left open, so that the caller can keep reading the classpath element the
     * classfile came from.
     *
     * @throws IOException
     *             if the file the slice reads could not be written or read
     */
    @Test
    public void aSliceOpenedByTheCallerIsNotClosedByTheReader() throws IOException {
        final var content = new byte[] { 0x01, 0x23, 0x45, 0x67 };
        final var file = Files.write(tempDir.resolve("content.bin"), content).toFile();
        try (var slice = new FileSlice(file, scanResources, /* log = */ null)) {
            final var reader = new ClassfileReader(slice);
            assertThat(reader.readInt()).isEqualTo(0x01234567);
            reader.close();

            // The slice can still be read, so the reader did not close it
            assertThat(slice.load()).containsExactly(content);
        }
    }

    /**
     * Closing the reader twice is safe. ({@link ClassfileReader#close()} does not declare a checked exception, so a
     * failure to close the stream the reader opened is swallowed rather than being reported to the caller.)
     *
     * @throws IOException
     *             if the reader could not be read from
     */
    @Test
    public void closingTheReaderTwiceIsSafe() throws IOException {
        final var reader = new ClassfileReader(new ByteArrayInputStream(new byte[] { 0x01, 0x23, 0x45, 0x67 }));
        assertThat(reader.readInt()).isEqualTo(0x01234567);
        reader.close();
        assertThatCode(reader::close).doesNotThrowAnyException();
    }
}
