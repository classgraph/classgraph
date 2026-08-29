package io.github.classgraph.vfs.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.vfs.Vfs;

/**
 * Tests that {@link RandomAccessOrSequentialReader#close()} closes only what the reader itself opened. Reading a
 * {@link io.github.classgraph.vfs.VfsEntry} opens a stream that belongs to the reader, and so is closed by it,
 * whereas a {@link java.io.InputStream} that the caller handed to the reader belongs to the caller, which closes it
 * in its own try-with-resources; closing that as well would close it out from under a caller that is still using
 * it, or close it twice.
 */
public class RandomAccessOrSequentialReaderCloseTest {
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

    /** A temporary directory to write the classpath element that the entry is read from. */
    @TempDir
    private Path tempDir;

    /** The virtual filesystem the classpath element is opened through, closed when the test ends. */
    private final Vfs vfs = new Vfs();

    /** Close the classpath element that the test opened. */
    @AfterEach
    public void closeVfs() {
        vfs.close();
    }

    /** A stream that the caller opened is left open, so that the caller can close it itself. */
    @Test
    public void aStreamOpenedByTheCallerIsNotClosedByTheReader() {
        final var inputStream = new RecordingInputStream();
        new RandomAccessOrSequentialReader(inputStream).close();
        assertThat(inputStream.closed).isFalse();
    }

    /**
     * Reading an entry leaves the classpath element it came from open, so that the rest of it can still be read.
     * The reader opens a stream on the entry, and closing the reader closes that stream and nothing above it.
     *
     * @throws IOException
     *             if the classpath element could not be written or read
     */
    @Test
    public void theClasspathElementAnEntryCameFromIsNotClosedByTheReader() throws IOException {
        final var content = new byte[] { 0x01, 0x23, 0x45, 0x67 };
        final var dir = Files.createDirectories(tempDir.resolve("dir"));
        Files.write(dir.resolve("Test.class"), content);

        final var root = vfs.open(dir.toFile());
        final var entry = Objects.requireNonNull(root.getEntry("Test.class"));
        try (var reader = new RandomAccessOrSequentialReader(entry)) {
            assertThat(reader.readInt()).isEqualTo(0x01234567);
        }

        // The entry can still be read, so the reader closed only the stream it opened
        assertThat(entry.load()).containsExactly(content);
    }

    /**
     * Closing the reader twice is safe. ({@link RandomAccessOrSequentialReader#close()} does not declare a checked
     * exception, so a failure to close the stream the reader opened is swallowed rather than being reported to the
     * caller.)
     *
     * @throws IOException
     *             if the reader could not be read from
     */
    @Test
    public void closingTheReaderTwiceIsSafe() throws IOException {
        final var reader = new RandomAccessOrSequentialReader(
                new ByteArrayInputStream(new byte[] { 0x01, 0x23, 0x45, 0x67 }));
        assertThat(reader.readInt()).isEqualTo(0x01234567);
        reader.close();
        assertThatCode(reader::close).doesNotThrowAnyException();
    }
}
