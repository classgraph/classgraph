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
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;

import io.github.classgraph.vfs.perf.BenchmarkFixtures.Content;
import io.github.classgraph.vfs.perf.BenchmarkFixtures.Layout;

/**
 * One thread opening an archive, reading every entry, and closing it again, which is what reading a jarfile once
 * costs, including the cost of opening it. Entries are read by name in the order they were written, except by
 * {@code ZipInputStream}, which reads them in the same order by streaming through the archive.
 *
 * <p>
 * Run it through {@link RunBenchmarks}, which only pairs each library with the layouts it supports.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Threads(1)
public class WholeArchiveBenchmark {
    /** The library to read with. */
    @Param("CGVFS")
    public Library library;

    /** What the entries hold. */
    @Param("CLASSES")
    public Content content;

    /** How the entries are stored. */
    @Param("DEFLATED")
    public Layout layout;

    /** The directory or outer jarfile. */
    private Path archive;

    /** The entry names, in the order they were written. */
    private List<String> names;

    /** The total size of the entries, to check that every byte was read. */
    private long totalBytes;

    /**
     * Find, and if necessary generate, the archive.
     *
     * @throws IOException
     *             if it could not be generated.
     */
    @Setup(Level.Trial)
    public void setup() throws IOException {
        archive = BenchmarkFixtures.path(content, layout);
        names = BenchmarkFixtures.entryNames(content);
        totalBytes = BenchmarkFixtures.totalBytes(content);
    }

    /**
     * Open the archive, read every entry, and close it.
     *
     * @return the number of bytes read.
     * @throws IOException
     *             if the archive could not be read, or not every byte was read.
     */
    @Benchmark
    public long readWholeArchive() throws IOException {
        final var bytesRead = library.readWholeArchive(archive, layout, names);
        if (bytesRead != totalBytes) {
            throw new IOException("Read " + bytesRead + " bytes, expected " + totalBytes);
        }
        return bytesRead;
    }
}
