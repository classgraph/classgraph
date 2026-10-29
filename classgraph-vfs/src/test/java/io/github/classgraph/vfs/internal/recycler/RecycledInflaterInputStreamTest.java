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
package io.github.classgraph.vfs.internal.recycler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

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

    private static Recycler<Inflater, RuntimeException> inflaterRecycler() {
        return RecycledInflaterInputStream.newInflaterRecycler();
    }

    /**
     * A negative deflated size means that the size is not known, so the deflated bytes are read 64kb at a time,
     * rather than into a buffer sized by the negative number, which was one byte long.
     *
     * @throws IOException
     *             if the stream could not be read
     */
    @Test
    void aNegativeDeflatedSizeReadsTheDeflatedBytes64kbAtATime() throws IOException {
        final var rawBytes = rawBytes();
        final List<Integer> readLengths = new ArrayList<>();
        try (var inflaterInputStream = new RecycledInflaterInputStream(
                recordingStream(deflate(rawBytes), readLengths), -1L, inflaterRecycler())) {
            // Ask for 64kb at a time, since the first read of deflated bytes is no larger than the read asked for.
            // (InputStream#readNBytes and #readAllBytes ask for less than that)
            final var inflatedBytes = new ByteArrayOutputStream();
            final var outBuf = new byte[65536];
            for (int numBytesRead; (numBytesRead = inflaterInputStream.read(outBuf, 0, outBuf.length)) != -1;) {
                inflatedBytes.write(outBuf, 0, numBytesRead);
            }
            assertThat(inflatedBytes.toByteArray()).isEqualTo(rawBytes);
        }
        assertThat(readLengths).isNotEmpty().allSatisfy(len -> assertThat(len).isEqualTo(65536));
    }

    /**
     * Reading only the start of an entry reads only 4kb of deflated bytes, rather than 64kb, and the reads after
     * that are 64kb long again.
     *
     * @throws IOException
     *             if the stream could not be read
     */
    @Test
    void aShortFirstReadReadsOnly4kbOfDeflatedBytes() throws IOException {
        final var rawBytes = rawBytes();
        final var deflatedBytes = deflate(rawBytes);
        assertThat(deflatedBytes.length).isGreaterThan(4096);
        final List<Integer> readLengths = new ArrayList<>();
        try (var inflaterInputStream = new RecycledInflaterInputStream(recordingStream(deflatedBytes, readLengths),
                deflatedBytes.length, inflaterRecycler())) {
            assertThat(inflaterInputStream.readNBytes(64)).isEqualTo(Arrays.copyOf(rawBytes, 64));
            assertThat(readLengths).containsExactly(4096);
            assertThat(inflaterInputStream.readAllBytes())
                    .isEqualTo(Arrays.copyOfRange(rawBytes, 64, rawBytes.length));
        }
        final var laterFillSize = Math.min(deflatedBytes.length, 65536);
        assertThat(readLengths.subList(1, readLengths.size())).isNotEmpty()
                .allSatisfy(len -> assertThat(len).isEqualTo(laterFillSize));
    }

    /** A stream of the given bytes that records the length of every read of it. */
    private static ByteArrayInputStream recordingStream(final byte[] bytes, final List<Integer> readLengths) {
        return new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(final byte[] b, final int off, final int len) {
                readLengths.add(len);
                return super.read(b, off, len);
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

    /**
     * An inflater is reset when it is handed back to the pool, so the next stream that borrows it starts from a
     * clean state rather than from the end of the previous entry.
     *
     * @throws Exception
     *             if inflating failed.
     */
    @Test
    void aRecycledInflaterIsResetBeforeItIsReused() throws Exception {
        final var rawBytes = rawBytes();
        try (var recycler = inflaterRecycler()) {
            // Inflate a whole entry, leaving the inflater finished
            final var inflater = recycler.acquire();
            inflater.setInput(deflate(rawBytes));
            assertThat(inflater.inflate(new byte[rawBytes.length])).isEqualTo(rawBytes.length);
            assertThat(inflater.getBytesRead()).isPositive();
            recycler.recycle(inflater);

            // The pool hands the same inflater out again, reset
            final var reacquired = recycler.acquire();
            assertThat(reacquired).isSameAs(inflater);
            assertThat(reacquired.getBytesRead()).isZero();
            assertThat(reacquired.finished()).isFalse();
            recycler.recycle(reacquired);

            // A stream that borrows it inflates the next entry correctly
            try (var inflaterInputStream = new RecycledInflaterInputStream(
                    new ByteArrayInputStream(deflate(rawBytes)), recycler)) {
                assertThat(inflaterInputStream.readAllBytes()).isEqualTo(rawBytes);
            }
        }
    }

    /**
     * Closing the pool ends the inflaters that are not in use, and force-closing it ends the ones that are, freeing
     * their native memory. An ended inflater throws when it is used.
     */
    @Test
    void closingThePoolEndsItsInflaters() {
        final var recycler = inflaterRecycler();
        final var pooled = recycler.acquire();
        final var inUse = recycler.acquire();
        recycler.recycle(pooled);

        recycler.close();
        // (An ended inflater throws IllegalStateException from JDK 25 onward, and NullPointerException before)
        assertThatThrownBy(pooled::getBytesRead).isInstanceOfAny(IllegalStateException.class,
                NullPointerException.class);
        assertThat(inUse.getBytesRead()).isZero();

        recycler.forceClose();
        assertThatThrownBy(inUse::getBytesRead).isInstanceOfAny(IllegalStateException.class,
                NullPointerException.class);
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
     * Invalid deflate data must throw a {@link java.util.zip.ZipException} whose cause is the
     * {@link java.util.zip.DataFormatException} that the inflater threw, so that the stack trace shows where the
     * data was rejected.
     */
    @Test
    void invalidDataKeepsTheCause() {
        // The first three bits of a deflate block are BFINAL and a two-bit BTYPE, and BTYPE 11 is reserved
        final var invalidBytes = new byte[] { (byte) 0xff, 0, 0, 0 };
        assertThatThrownBy(() -> inflate(invalidBytes)).isInstanceOf(java.util.zip.ZipException.class)
                .hasCauseInstanceOf(java.util.zip.DataFormatException.class);
    }

    /**
     * {@link java.io.InputStream#mark(int)} has to be a no-op when mark is not supported, and
     * {@link java.io.InputStream#reset()} has to throw an {@link IOException}, not an unchecked exception.
     */
    @Test
    void markIsANoOpAndResetThrowsIOException() {
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

    /**
     * Closing the {@link io.github.classgraph.vfs.Vfs} ends the inflaters it handed out, including one that a
     * stream is still reading through. The read that was in flight then fails with an {@link IOException}, the way
     * every other read of a closed {@code Vfs} does, rather than with the unchecked exception an ended
     * {@link java.util.zip.Inflater} throws -- which is a {@link NullPointerException} on JDK 17 and an
     * {@link IllegalStateException} on JDK 25, so a caller could not usefully catch it either way.
     */
    @Test
    void aReadAfterTheInflaterWasEndedThrowsIOException() throws IOException {
        final var rawBytes = rawBytes();
        try (var recycler = inflaterRecycler();
                var inflaterInputStream = new RecycledInflaterInputStream(
                        new ByteArrayInputStream(deflate(rawBytes)), recycler)) {
            assertThat(inflaterInputStream.read()).isEqualTo(rawBytes[0] & 0xff);

            // This is what the Vfs teardown does to the inflaters it handed out
            recycler.forceClose();

            assertThatThrownBy(inflaterInputStream::read).isInstanceOf(IOException.class)
                    .hasMessageContaining("after the Vfs has been closed");
            assertThatThrownBy(() -> inflaterInputStream.read(new byte[16], 0, 16)).isInstanceOf(IOException.class)
                    .hasMessageContaining("after the Vfs has been closed");
            assertThatThrownBy(() -> inflaterInputStream.skip(16)).isInstanceOf(IOException.class)
                    .hasMessageContaining("after the Vfs has been closed");
        }
    }

    /**
     * A read into a range that is not within the destination array is rejected before anything is read, as
     * {@link java.io.InputStream#read(byte[], int, int)} requires.
     */
    @Test
    void aDestinationRangeOutsideTheArrayIsRejected() {
        try (var recycler = inflaterRecycler();
                var inflaterInputStream = new RecycledInflaterInputStream(
                        new ByteArrayInputStream(deflate(rawBytes())), recycler)) {
            assertThatThrownBy(() -> inflaterInputStream.read(new byte[16], 8, 16))
                    .isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> inflaterInputStream.read(new byte[16], -1, 4))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    /**
     * A negative length or skip distance is a programming error, not something to silently ignore. A negative read
     * length throws IndexOutOfBoundsException, as InputStream#read(byte[], int, int) specifies and as a stored
     * entry's stream and the JDK's inflater stream do; a negative skip distance throws IllegalArgumentException, as
     * java.util.zip.InflaterInputStream#skip does.
     */
    @Test
    void negativeLengthsAreRejected() throws IOException {
        try (var recycler = inflaterRecycler();
                var inflaterInputStream = new RecycledInflaterInputStream(
                        new ByteArrayInputStream(deflate(rawBytes())), recycler)) {
            assertThatThrownBy(() -> inflaterInputStream.read(new byte[16], 0, -1))
                    .isInstanceOf(IndexOutOfBoundsException.class);
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
    void aClosedStreamCannotBeReadFrom() {
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

    /**
     * Closing a stream from another thread while a read is in progress must not hand the
     * {@link java.util.zip.Inflater} back to the recycler until that read has returned. Otherwise the next stream
     * to acquire an inflater gets the one the read is still using, and the two streams corrupt each other's output.
     */
    @Test
    @Timeout(value = 60, threadMode = ThreadMode.SEPARATE_THREAD)
    void closingWhileAReadIsInFlightWaitsForTheReadBeforeRecycling() throws Exception {
        // Random bytes do not compress, so the deflated stream is longer than one staging buffer (64 kB at most),
        // and inflating the first read's worth (128 kB) needs a second read of the raw stream
        final var rawBytes = new byte[262144];
        new Random(1).nextBytes(rawBytes);
        final var deflatedBytes = deflate(rawBytes);

        final var enteredSecondRawRead = new CountDownLatch(1);
        final var releaseSecondRawRead = new CountDownLatch(1);
        final var rawInputStream = new ByteArrayInputStream(deflatedBytes) {
            private int numReads;

            @Override
            public synchronized int read(final byte[] b, final int off, final int len) {
                if (++numReads == 2) {
                    enteredSecondRawRead.countDown();
                    try {
                        releaseSecondRawRead.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.read(b, off, len);
            }
        };

        // The same pool as RecycledInflaterInputStream.newInflaterRecycler(), which also records each inflater it
        // creates
        final var createdInstances = new CopyOnWriteArrayList<Inflater>();
        try (var recycler = new Recycler<Inflater, RuntimeException>() {
            @Override
            public Inflater newInstance() {
                final var instance = new Inflater(/* nowrap = */ true);
                createdInstances.add(instance);
                return instance;
            }

            @Override
            protected void resetInstance(final Inflater inflater) {
                inflater.reset();
            }

            @Override
            protected void closeInstance(final Inflater inflater) {
                inflater.end();
            }
        }) {
            final var inflaterInputStream = new RecycledInflaterInputStream(rawInputStream, recycler);
            final var streamInflater = createdInstances.get(0);

            final var readBuf = new byte[131072];
            final AtomicReference<Object> readResult = new AtomicReference<>();
            final var reader = new Thread(() -> {
                try {
                    readResult.set(inflaterInputStream.read(readBuf, 0, readBuf.length));
                } catch (final IOException e) {
                    readResult.set(e);
                }
            });
            reader.start();
            final var closer = new Thread(inflaterInputStream::close);
            final Inflater acquiredDuringRead;
            try {
                enteredSecondRawRead.await();
                closer.start();
                closer.join(500);
                acquiredDuringRead = recycler.acquire();
                assertThat(acquiredDuringRead).as("the inflater of the read in progress is not handed out again")
                        .isNotSameAs(streamInflater);
                recycler.recycle(acquiredDuringRead);
                assertThat(closer.isAlive()).as("close() waits for the read in progress").isTrue();
            } finally {
                // Let the read finish even if an assertion failed, so that no thread is left blocked
                releaseSecondRawRead.countDown();
            }
            reader.join();
            closer.join();
            // Closing a ByteArrayInputStream does not stop it from being read, so the read in progress finishes,
            // and its inflater was not reset under it, so what it read is correct
            assertThat(readResult.get()).isEqualTo(readBuf.length);
            assertThat(readBuf).isEqualTo(Arrays.copyOf(rawBytes, readBuf.length));
            // Once the read has returned, close() hands the stream's inflater back to the recycler
            assertThat(List.of(recycler.acquire(), recycler.acquire())).containsExactlyInAnyOrder(streamInflater,
                    acquiredDuringRead);
        }
    }
}
