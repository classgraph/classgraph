package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.fileslice.reader.ClassfileReader;

/**
 * Tests that {@link ClassfileReader#close()} closes the resource it was opened on, even when closing the input
 * stream it was reading through fails. This test is in the {@code io.github.classgraph} package because
 * {@link Resource} has a package-private constructor, so it can only be subclassed here.
 */
public class ClassfileReaderCloseTest {
    /** A resource that records whether it was closed, and that supports nothing else. */
    private static class RecordingResource extends Resource {
        /** True once {@link #close()} has been called. */
        boolean closed;

        /** Constructor. */
        RecordingResource() {
            super(/* classpathElement = */ null, /* length = */ 0L);
        }

        @Override
        public String getPath() {
            return "test";
        }

        @Override
        public InputStream open() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ByteBuffer read() {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] load() {
            throw new UnsupportedOperationException();
        }

        @Override
        ClassfileReader openClassfile() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getLastModifiedMillis() {
            return 0L;
        }

        @Override
        public Set<PosixFilePermission> getPosixFilePermissions() {
            throw new UnsupportedOperationException();
        }

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
     *
     * @throws IOException
     *             if the reader could not be opened
     */
    @Test
    public void resourceIsClosedEvenIfClosingTheStreamFails() throws IOException {
        final var resource = new RecordingResource();
        final var classfileReader = new ClassfileReader(new UncloseableInputStream(), resource);
        classfileReader.close();
        assertThat(resource.closed).isTrue();
    }
}
