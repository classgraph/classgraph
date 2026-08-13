/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2026 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph.vfs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.Deflater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

import io.github.classgraph.base.internal.recycler.Recycler;

/** Tests for {@link RecycledInflaterInputStream}. */
class RecycledInflaterInputStreamTest {
    /** Some compressible test data. */
    private static byte[] rawBytes() {
        final var buf = new StringBuilder();
        for (var i = 0; i < 2000; i++) {
            buf.append("the quick brown fox jumps over the lazy dog ").append(i).append('\n');
        }
        return buf.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Deflate the given bytes with the "nowrap" option, as zip entry data is deflated. */
    private static byte[] deflate(final byte[] rawBytes) {
        final var deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, /* nowrap = */ true);
        try {
            deflater.setInput(rawBytes);
            deflater.finish();
            final var deflatedBuf = new byte[rawBytes.length * 2 + 32];
            final var deflatedLen = deflater.deflate(deflatedBuf);
            return Arrays.copyOf(deflatedBuf, deflatedLen);
        } finally {
            deflater.end();
        }
    }

    private static Recycler<RecyclableInflater, RuntimeException> inflaterRecycler() {
        return new Recycler<>() {
            @Override
            public RecyclableInflater newInstance() {
                return new RecyclableInflater();
            }
        };
    }

    /** Read the given deflated bytes back through a {@link RecycledInflaterInputStream}. */
    private static byte[] inflate(final byte[] deflatedBytes) throws IOException {
        try (var recycler = inflaterRecycler();
                var inflaterInputStream = new RecycledInflaterInputStream(new ByteArrayInputStream(deflatedBytes),
                        recycler)) {
            return inflaterInputStream.readAllBytes();
        }
    }

    /** A complete deflated stream must inflate back to the original bytes. */
    @Test
    void completeStreamIsInflated() throws IOException {
        final var rawBytes = rawBytes();
        assertThat(inflate(deflate(rawBytes))).isEqualTo(rawBytes);
    }

    /**
     * A deflated stream that has been truncated must throw, rather than looping forever. The "nowrap" option
     * requires a dummy byte at the end of the input, and if that byte is supplied afresh every time the inflater
     * asks for more input, the read loop never terminates.
     *
     * <p>
     * The timeout runs the test in a separate thread, since a same-thread timeout is only checked once the test
     * method returns, which a non-terminating loop never does.
     */
    @Test
    @Timeout(value = 60, threadMode = ThreadMode.SEPARATE_THREAD)
    void truncatedStreamThrowsRatherThanLoopingForever() {
        final var deflatedBytes = deflate(rawBytes());
        // Truncating a deflated stream close to its end leaves the inflater wanting more input after every
        // byte of the stream has been consumed; truncating it close to its start instead tends to produce
        // invalid deflate data, which throws a DataFormatException
        for (final var truncatedLength : new int[] { deflatedBytes.length - 1, deflatedBytes.length - 5,
                deflatedBytes.length / 2 }) {
            final var truncatedBytes = Arrays.copyOf(deflatedBytes, truncatedLength);
            assertThatThrownBy(() -> inflate(truncatedBytes)).isInstanceOf(EOFException.class);
        }
    }

    /**
     * {@link java.io.InputStream#mark(int)} has to be a no-op when mark is not supported, and
     * {@link java.io.InputStream#reset()} has to throw an {@link IOException}, not an unchecked exception.
     */
    @Test
    void markIsANoOpAndResetThrowsIOException() throws IOException {
        try (var recycler = inflaterRecycler();
                var inflaterInputStream = new RecycledInflaterInputStream(
                        new ByteArrayInputStream(deflate(rawBytes())), recycler)) {
            assertThat(inflaterInputStream.markSupported()).isFalse();
            inflaterInputStream.mark(1024);
            assertThatThrownBy(inflaterInputStream::reset).isInstanceOf(IOException.class);
        }
    }

    /** Bytes can be read one at a time, as the unsigned value of the byte, with -1 at the end of the stream. */
    @Test
    void bytesCanBeReadOneAtATime() throws IOException {
        final var rawBytes = "ÿ and a high byte".getBytes(StandardCharsets.ISO_8859_1);
        try (var recycler = inflaterRecycler();
                var inflaterInputStream = new RecycledInflaterInputStream(
                        new ByteArrayInputStream(deflate(rawBytes)), recycler)) {
            for (final byte rawByte : rawBytes) {
                // A byte with the high bit set has to be returned as a positive int, not as a negative byte
                assertThat(inflaterInputStream.read()).isEqualTo(rawByte & 0xff);
            }
            assertThat(inflaterInputStream.read()).isEqualTo(-1);
            // Once the end of the stream has been reached, it stays reached
            assertThat(inflaterInputStream.read()).isEqualTo(-1);
        }
    }

    /**
     * {@link java.io.InputStream#available()} has to report that there is more to read until the whole stream has
     * been inflated, since a caller that stops at the first zero would drop the rest of the entry.
     */
    @Test
    void availableIsNonZeroUntilTheStreamHasBeenRead() throws IOException {
        final var rawBytes = rawBytes();
        try (var recycler = inflaterRecycler();
                var inflaterInputStream = new RecycledInflaterInputStream(
                        new ByteArrayInputStream(deflate(rawBytes)), recycler)) {
            assertThat(inflaterInputStream.available()).isPositive();
            assertThat(inflaterInputStream.readNBytes(rawBytes.length / 2)).hasSize(rawBytes.length / 2);
            assertThat(inflaterInputStream.available()).isPositive();
            assertThat(inflaterInputStream.readAllBytes()).hasSize(rawBytes.length - rawBytes.length / 2);
            assertThat(inflaterInputStream.available()).isZero();
        }
    }

    /** Skipping moves the same distance through the stream that reading does, and stops at the end of it. */
    @Test
    void skippingAdvancesThroughTheStream() throws IOException {
        final var rawBytes = rawBytes();
        try (var recycler = inflaterRecycler();
                var inflaterInputStream = new RecycledInflaterInputStream(
                        new ByteArrayInputStream(deflate(rawBytes)), recycler)) {
            // Skipping nothing is a no-op, and does not count as reaching the end of the stream
            assertThat(inflaterInputStream.skip(0)).isZero();
            // More than one staging buffer's worth, so that the skip loop has to go around more than once
            final var numToSkip = 20_000;
            assertThat(inflaterInputStream.skip(numToSkip)).isEqualTo(numToSkip);
            assertThat(inflaterInputStream.read()).as("the byte after the skipped ones")
                    .isEqualTo(rawBytes[numToSkip] & 0xff);

            // Skipping past the end of the stream skips only what is left, and skipping again then returns zero
            assertThat(inflaterInputStream.skip(rawBytes.length * 2L)).isEqualTo(rawBytes.length - numToSkip - 1L);
            assertThat(inflaterInputStream.skip(1)).isZero();
        }
    }

    /** A negative length or skip distance is a programming error, not something to silently ignore. */
    @Test
    void negativeLengthsAreRejected() throws IOException {
        try (var recycler = inflaterRecycler();
                var inflaterInputStream = new RecycledInflaterInputStream(
                        new ByteArrayInputStream(deflate(rawBytes())), recycler)) {
            assertThatThrownBy(() -> inflaterInputStream.read(new byte[16], 0, -1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> inflaterInputStream.skip(-1)).isInstanceOf(IllegalArgumentException.class);
            // Reading zero bytes reads zero bytes, rather than reporting the end of the stream
            assertThat(inflaterInputStream.read(new byte[16], 0, 0)).isZero();
        }
    }

    /**
     * Every read method of a closed stream throws, rather than reading from an inflater that has been handed back
     * to the recycler and may since have been handed out to another stream. Closing twice is not an error.
     */
    @Test
    void aClosedStreamCannotBeReadFrom() throws IOException {
        try (var recycler = inflaterRecycler()) {
            final var inflaterInputStream = new RecycledInflaterInputStream(
                    new ByteArrayInputStream(deflate(rawBytes())), recycler);
            inflaterInputStream.close();
            inflaterInputStream.close();

            assertThatThrownBy(inflaterInputStream::read).isInstanceOf(IOException.class)
                    .hasMessageContaining("already closed");
            assertThatThrownBy(() -> inflaterInputStream.read(new byte[16], 0, 16)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> inflaterInputStream.skip(1)).isInstanceOf(IOException.class);
            assertThatThrownBy(inflaterInputStream::available).isInstanceOf(IOException.class);
        }
    }
}
