package io.github.classgraph.vfs.internal.slice.reader;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Tests the sequential {@link ClassfileReader#readString(int)} overload.
 *
 * <p>
 * A {@link ClassfileReader} built on an {@link java.io.InputStream} starts with an allocated but completely
 * unfilled buffer, so a read that does not first grow the buffer returns the zero bytes the array was allocated
 * with, rather than the stream contents. Every other sequential read method delegates to its random access
 * counterpart, which buffers the requested range first; {@code readString} used to read straight out of the buffer
 * instead.
 */
public class ClassfileReaderSequentialReadStringTest {
    /**
     * A sequential readString from an unfilled buffer must return the stream contents, not NUL bytes.
     */
    @Test
    public void sequentialReadStringBuffersBeforeReading() throws IOException {
        final var data = "Hello".getBytes(StandardCharsets.UTF_8);
        try (var reader = new ClassfileReader(new ByteArrayInputStream(data), null)) {
            assertThat(reader.readString(5)).isEqualTo("Hello");
        }
    }

    /**
     * Consecutive sequential reads advance the read position and stay in step with the buffering.
     */
    @Test
    public void consecutiveSequentialReadStringsAdvancePosition() throws IOException {
        final var data = "HelloWorld".getBytes(StandardCharsets.UTF_8);
        try (var reader = new ClassfileReader(new ByteArrayInputStream(data), null)) {
            assertThat(reader.readString(5)).isEqualTo("Hello");
            assertThat(reader.currPos()).isEqualTo(5);
            assertThat(reader.readString(5)).isEqualTo("World");
            assertThat(reader.currPos()).isEqualTo(10);
        }
    }

    /**
     * A sequential readString interleaved with other sequential reads stays consistent with them.
     */
    @Test
    public void sequentialReadStringInterleavesWithOtherReads() throws IOException {
        final byte[] data = { 0, 3, 'a', 'b', 'c' };
        try (var reader = new ClassfileReader(new ByteArrayInputStream(data), null)) {
            final var len = reader.readUnsignedShort();
            assertThat(len).isEqualTo(3);
            assertThat(reader.readString(len)).isEqualTo("abc");
        }
    }
}
