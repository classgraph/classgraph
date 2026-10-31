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
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions
 * of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph.vfs.perf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * Opening an archive, reading every entry exactly once in random order, split between several threads, and closing
 * the archive again. This is how a classloader or a scanner reads a jarfile: each entry is read once, so a library
 * that caches the content of entries gets no benefit from its cache.
 *
 * <p>
 * The entries are shuffled once, and the {@link #threads} worker threads take the next entry of the shuffled list
 * whenever they finish one, as ClassGraph's work queue does, so that a thread that is given large entries does not
 * hold up the others. The worker threads are started before timing starts, so starting them is not timed.
 *
 * <p>
 * Run it through {@link RunBenchmarks}, which only pairs each library with the layouts it supports.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Threads(1)
public class FirstReadBenchmark {
    /** The library to read with. */
    @Param("CGVFS")
    public Library library;

    /** What the entries hold. */
    @Param("CLASSES")
    public Content content;

    /** How the entries are stored. */
    @Param("DEFLATED")
    public Layout layout;

    /** The number of threads that read the entries. */
    @Param("1")
    public int threads;

    /** The directory or outer jarfile. */
    private Path archive;

    /** The shuffled entry names. */
    private List<String> shuffled;

    /** The total size of the entries, to check that every byte was read. */
    private long totalBytes;

    /** The worker threads. */
    private ExecutorService executor;

    /**
     * Find, and if necessary generate, the archive, and start the worker threads.
     *
     * @throws IOException
     *             if the archive could not be generated.
     * @throws InterruptedException
     *             if interrupted while starting the worker threads.
     */
    @Setup(Level.Trial)
    public void setup() throws IOException, InterruptedException {
        archive = BenchmarkFixtures.path(content, layout);
        totalBytes = BenchmarkFixtures.totalBytes(content);
        shuffled = new ArrayList<>(BenchmarkFixtures.entryNames(content));
        Collections.shuffle(shuffled, new Random(3));
        executor = Executors.newFixedThreadPool(threads);
        // Start every worker thread now, rather than during the first timed operation (a fixed thread pool starts
        // a new thread for each task submitted until it has its full number of threads)
        final Callable<Object> noop = () -> null;
        executor.invokeAll(Collections.nCopies(threads, noop));
    }

    /** Stop the worker threads. */
    @TearDown(Level.Trial)
    public void tearDown() {
        executor.shutdownNow();
    }

    /**
     * Open the archive, read every entry once, and close it.
     *
     * @return the number of bytes read.
     * @throws Exception
     *             if the archive could not be read, or not every byte was read.
     */
    @Benchmark
    public long readEveryEntryOnce() throws Exception {
        var bytesRead = 0L;
        try (var reader = library.open(archive, layout)) {
            final var next = new AtomicInteger();
            final Callable<Long> task = () -> {
                var threadBytesRead = 0L;
                for (int i; (i = next.getAndIncrement()) < shuffled.size();) {
                    threadBytesRead += reader.read(shuffled.get(i)).length;
                }
                return threadBytesRead;
            };
            for (final var future : executor.invokeAll(Collections.nCopies(threads, task))) {
                try {
                    bytesRead += future.get();
                } catch (final ExecutionException e) {
                    throw e.getCause() instanceof Exception cause ? cause : e;
                }
            }
        }
        if (bytesRead != totalBytes) {
            throw new IOException("Read " + bytesRead + " bytes, expected " + totalBytes);
        }
        return bytesRead;
    }
}
