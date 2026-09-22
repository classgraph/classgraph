package io.github.classgraph.vfs.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UTFDataFormatException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Tests the sequential {@link RandomAccessOrSequentialReader#readString(int)} and
 * {@link RandomAccessOrSequentialReader#readStringModifiedUtf8(int)} overloads.
 *
 * <p>
 * A {@link RandomAccessOrSequentialReader} built on an {@link java.io.InputStream} starts with an allocated but
 * completely unfilled buffer, so a read that does not first grow the buffer returns the zero bytes the array was
 * allocated with, rather than the stream contents. Every other sequential read method delegates to its random
 * access counterpart, which buffers the requested range first; the string reads used to read straight out of the
 * buffer instead.
 */
public class RandomAccessOrSequentialReaderReadStringTest {
    /**
     * A sequential readString from an unfilled buffer must return the stream contents, not NUL bytes.
     */
    @Test
    public void sequentialReadStringBuffersBeforeReading() throws IOException {
        final var data = "Hello".getBytes(StandardCharsets.UTF_8);
        try (var reader = new RandomAccessOrSequentialReader(new ByteArrayInputStream(data))) {
            assertThat(reader.readString(5)).isEqualTo("Hello");
        }
        try (var reader = new RandomAccessOrSequentialReader(new ByteArrayInputStream(data))) {
            assertThat(reader.readStringModifiedUtf8(5)).isEqualTo("Hello");
        }
    }

    /**
     * Bytes that are not valid modified UTF-8 are rejected with an {@link IOException}, as any other content that
     * cannot be read is.
     */
    @Test
    public void malformedModifiedUtf8IsRejectedWithAnIOException() {
        // 0xe0 starts a three-byte sequence, but the content ends after two bytes
        final byte[] data = { (byte) 0xe0, (byte) 0x80 };
        try (var reader = new RandomAccessOrSequentialReader(new ByteArrayInputStream(data))) {
            assertThatThrownBy(() -> reader.readStringModifiedUtf8(2)).isInstanceOf(UTFDataFormatException.class)
                    .hasMessageContaining("byte 0");
        }
    }

    /**
     * Consecutive sequential reads advance the read position and stay in step with the buffering.
     */
    @Test
    public void consecutiveSequentialReadStringsAdvancePosition() throws IOException {
        final var data = "HelloWorld".getBytes(StandardCharsets.UTF_8);
        try (var reader = new RandomAccessOrSequentialReader(new ByteArrayInputStream(data))) {
            assertThat(reader.readString(5)).isEqualTo("Hello");
            assertThat(reader.position()).isEqualTo(5);
            assertThat(reader.readString(5)).isEqualTo("World");
            assertThat(reader.position()).isEqualTo(10);
        }
    }

    /**
     * A sequential readString interleaved with other sequential reads stays consistent with them.
     */
    @Test
    public void sequentialReadStringInterleavesWithOtherReads() throws IOException {
        final byte[] data = { 0, 3, 'a', 'b', 'c' };
        try (var reader = new RandomAccessOrSequentialReader(new ByteArrayInputStream(data))) {
            final var len = reader.readUnsignedShort();
            assertThat(len).isEqualTo(3);
            assertThat(reader.readString(len)).isEqualTo("abc");
        }
    }
}
