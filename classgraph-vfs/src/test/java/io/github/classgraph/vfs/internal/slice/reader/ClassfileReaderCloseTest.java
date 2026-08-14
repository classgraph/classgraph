package io.github.classgraph.vfs.internal.slice.reader;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

/**
 * Tests that {@link ClassfileReader#close()} closes the resource it was opened on, even when closing the input
 * stream it was reading through fails.
 */
public class ClassfileReaderCloseTest {
    /** A resource that records whether it was closed. */
    private static class RecordingResource implements AutoCloseable {
        /** True once {@link #close()} has been called. */
        boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    /** An input stream that is at EOF, and that fails to close. */
    private static class UncloseableInputStream extends InputStream {
        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() throws IOException {
            throw new IOException("Could not close stream");
        }
    }

    /**
     * A failure to close the input stream must not stop the underlying resource from being closed, otherwise the
     * resource's file handle or memory mapping is leaked.
     */
    @Test
    public void resourceIsClosedEvenIfClosingTheStreamFails() {
        final var resource = new RecordingResource();
        final var classfileReader = new ClassfileReader(new UncloseableInputStream(), resource);
        classfileReader.close();
        assertThat(resource.closed).isTrue();
    }
}
