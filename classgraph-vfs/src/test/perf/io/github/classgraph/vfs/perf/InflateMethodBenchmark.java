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
package io.github.classgraph.vfs.perf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;

import io.github.classgraph.vfs.perf.BenchmarkFixtures.Content;
import io.github.classgraph.vfs.perf.BenchmarkFixtures.Layout;

/**
 * Compares ways of inflating the entries of a jarfile that is held in one array, as classgraph-vfs holds a nested
 * jarfile that it has inflated: every entry is inflated once, in random order, from one or more threads.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Threads(1)
public class InflateMethodBenchmark {
    /** A way of inflating an entry. */
    public enum Method {
        /** Inflate in one call from the array that holds the jarfile, as {@code FastZipEntry#load} does. */
        WHOLE_IN_PLACE,
        /** Copy the deflated bytes into an array of their own, then inflate them in one call. */
        WHOLE_COPIED,
        /** Inflate from the array that holds the jarfile, giving the inflater 8 KB of it at a time. */
        CHUNKED_IN_PLACE,
        /** Read an {@link InflaterInputStream} over the deflated bytes, with an 8 KB buffer. */
        STREAM
    }

    /** The way to inflate. */
    @Param("WHOLE_IN_PLACE")
    public Method method;

    /** What the entries hold. */
    @Param("TEXT")
    public Content content;

    /** The number of threads that inflate the entries. */
    @Param("1")
    public int threads;

    /** The inner jarfile. */
    private byte[] jar;

    /**
     * The shuffled data offsets, deflated sizes and inflated sizes, which each thread takes the next of whenever it
     * finishes one.
     */
    private List<int[]> shuffled;

    /** The total inflated size of the entries, to check that every byte was inflated. */
    private long totalBytes;

    /** The worker threads. */
    private ExecutorService executor;

    /**
     * Read the inner jarfile into an array, find its entries, and start the worker threads.
     *
     * @throws IOException
     *             if the jarfile could not be generated or read.
     * @throws InterruptedException
     *             if interrupted while starting the worker threads.
     */
    @Setup(Level.Trial)
    public void setup() throws IOException, InterruptedException {
        totalBytes = BenchmarkFixtures.totalBytes(content);
        try (var outer = ZipFile.builder().setPath(BenchmarkFixtures.path(content, Layout.NESTED_DEFLATED)).get();
                var in = outer.getInputStream(outer.getEntries().nextElement())) {
            jar = in.readAllBytes();
        }
        final List<int[]> entries = new ArrayList<>();
        try (var inner = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(jar)).get()) {
            for (final var entry : Collections.list(inner.getEntries())) {
                entries.add(new int[] { (int) entry.getDataOffset(), (int) entry.getCompressedSize(),
                        (int) entry.getSize() });
            }
        }
        Collections.shuffle(entries, new Random(3));
        shuffled = entries;
        executor = Executors.newFixedThreadPool(threads);
        final Callable<Object> noop = () -> null;
        executor.invokeAll(Collections.nCopies(threads, noop));
    }

    /** Stop the worker threads. */
    @TearDown(Level.Trial)
    public void tearDown() {
        executor.shutdownNow();
    }

    /**
     * Inflate every entry once.
     *
     * @return the number of bytes inflated.
     * @throws Exception
     *             if an entry could not be inflated, or not every byte was inflated.
     */
    @Benchmark
    public long inflateEveryEntryOnce() throws Exception {
        var bytesInflated = 0L;
        final var next = new AtomicInteger();
        final Callable<Long> task = () -> {
            var threadBytes = 0L;
            final var inflater = new Inflater(/* nowrap = */ true);
            try {
                for (int i; (i = next.getAndIncrement()) < shuffled.size();) {
                    final var entry = shuffled.get(i);
                    threadBytes += inflate(inflater, entry[0], entry[1], entry[2]).length;
                    inflater.reset();
                }
            } finally {
                inflater.end();
            }
            return threadBytes;
        };
        for (final var future : executor.invokeAll(Collections.nCopies(threads, task))) {
            try {
                bytesInflated += future.get();
            } catch (final ExecutionException e) {
                throw e.getCause() instanceof Exception cause ? cause : e;
            }
        }
        if (bytesInflated != totalBytes) {
            throw new IOException("Inflated " + bytesInflated + " bytes, expected " + totalBytes);
        }
        return bytesInflated;
    }

    /**
     * Inflate one entry.
     *
     * @param inflater
     *            the inflater, which has been reset.
     * @param off
     *            the offset of the deflated data in {@link #jar}.
     * @param len
     *            the length of the deflated data.
     * @param size
     *            the inflated size.
     * @return the inflated bytes.
     * @throws IOException
     *             if the entry could not be inflated.
     * @throws DataFormatException
     *             if the deflated data is invalid.
     */
    private byte[] inflate(final Inflater inflater, final int off, final int len, final int size)
            throws IOException, DataFormatException {
        final var output = new byte[size];
        switch (method) {
        case WHOLE_IN_PLACE -> inflateWhole(inflater, jar, off, len, output);
        case WHOLE_COPIED -> inflateWhole(inflater, Arrays.copyOfRange(jar, off, off + len), 0, len, output);
        case CHUNKED_IN_PLACE -> {
            var inputPos = off;
            var n = 0;
            while (n < size) {
                if (inflater.needsInput()) {
                    final var chunk = Math.min(8192, off + len - inputPos);
                    // The nowrap inflater can need one byte past the end of the deflated data
                    inflater.setInput(chunk > 0 ? jar : new byte[1], chunk > 0 ? inputPos : 0, Math.max(chunk, 1));
                    inputPos += chunk;
                }
                n += inflater.inflate(output, n, size - n);
            }
        }
        case STREAM -> {
            try (var in = new InflaterInputStream(new ByteArrayInputStream(jar, off, len + 1), inflater, 8192) {
                @Override
                public void close() {
                    // The inflater is reused
                }
            }) {
                if (in.readNBytes(output, 0, size) != size) {
                    throw new IOException("Entry ended early");
                }
            }
        }
        default -> throw new IllegalArgumentException(method.toString());
        }
        return output;
    }

    /**
     * Inflate deflated data in one call.
     *
     * @param inflater
     *            the inflater, which has been reset.
     * @param input
     *            the array holding the deflated data.
     * @param off
     *            the offset of the deflated data.
     * @param len
     *            the length of the deflated data.
     * @param output
     *            the array to inflate into, of the inflated size.
     * @throws IOException
     *             if the data inflates to fewer bytes than the output holds.
     * @throws DataFormatException
     *             if the deflated data is invalid.
     */
    private static void inflateWhole(final Inflater inflater, final byte[] input, final int off, final int len,
            final byte[] output) throws IOException, DataFormatException {
        inflater.setInput(input, off, len);
        var n = 0;
        while (n < output.length) {
            final var k = inflater.inflate(output, n, output.length - n);
            if (k == 0 && inflater.needsInput()) {
                // The nowrap inflater can need one byte past the end of the deflated data
                inflater.setInput(new byte[1]);
            } else if (k == 0 && inflater.finished()) {
                throw new IOException("Entry ended early");
            }
            n += k;
        }
    }
}
