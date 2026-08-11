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
}
