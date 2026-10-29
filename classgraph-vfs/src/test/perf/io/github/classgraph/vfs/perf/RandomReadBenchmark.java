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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.AuxCounters;
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
import org.openjdk.jmh.infra.ThreadParams;

import io.github.classgraph.vfs.perf.BenchmarkFixtures.Content;
import io.github.classgraph.vfs.perf.BenchmarkFixtures.Layout;

/**
 * Many threads reading entries from one open archive, in random order. The archive is opened once, before timing
 * starts, and shared by every thread; each operation looks one entry up by name and reads all of it.
 *
 * <p>
 * The entries are shuffled once, and each thread walks the shuffled list from its own starting point, so every
 * entry is read equally often and the bytes read per operation average out to the mean entry size. The bytes read
 * are counted, and reported as the secondary result {@code bytes}, in bytes per second over all threads.
 *
 * <p>
 * Run it through {@link RunBenchmarks}, which only pairs each library with the layouts it supports.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class RandomReadBenchmark {
    /** The library to read with. */
    @Param("CGVFS")
    public Library library;

    /** What the entries hold. */
    @Param("CLASSES")
    public Content content;

    /** How the entries are stored. */
    @Param("DEFLATED")
    public Layout layout;

    /** The open archive. */
    private Library.Reader reader;

    /** The entry names, shuffled. */
    private String[] names;

    /**
     * Open the archive.
     *
     * @throws IOException
     *             if it could not be opened.
     */
    @Setup(Level.Trial)
    public void open() throws IOException {
        final List<String> shuffled = new ArrayList<>(BenchmarkFixtures.entryNames(content));
        Collections.shuffle(shuffled, new Random(3));
        names = shuffled.toArray(new String[0]);
        reader = library.open(BenchmarkFixtures.path(content, layout), layout);
    }

    /**
     * Close the archive.
     *
     * @throws IOException
     *             if it could not be closed.
     */
    @TearDown(Level.Trial)
    public void close() throws IOException {
        reader.close();
    }

    /** One thread's position in the shuffled entry list, and its count of bytes read. */
    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.OPERATIONS)
    public static class ThreadState {
        /** The number of bytes this thread has read, reported per second by JMH. */
        public long bytes;

        /** The index of the next entry to read. */
        private int next;

        /**
         * Start each thread at a different point in the entry list.
         *
         * @param benchmark
         *            the benchmark state.
         * @param threadParams
         *            this thread's index and the thread count.
         */
        @Setup(Level.Trial)
        public void setup(final RandomReadBenchmark benchmark, final ThreadParams threadParams) {
            next = (int) ((long) benchmark.names.length * threadParams.getThreadIndex()
                    / threadParams.getThreadCount());
        }

        /** Clear the byte count before each iteration, as JMH does for its own counters. */
        @Setup(Level.Iteration)
        public void clear() {
            bytes = 0;
        }
    }

    /**
     * Read one entry.
     *
     * @param threadState
     *            the calling thread's state.
     * @return the entry's content, so the read cannot be optimized away.
     * @throws IOException
     *             if the entry could not be read.
     */
    @Benchmark
    public byte[] read(final ThreadState threadState) throws IOException {
        final var bytes = reader.read(names[threadState.next]);
        threadState.next = threadState.next + 1 == names.length ? 0 : threadState.next + 1;
        threadState.bytes += bytes.length;
        return bytes;
    }
}
