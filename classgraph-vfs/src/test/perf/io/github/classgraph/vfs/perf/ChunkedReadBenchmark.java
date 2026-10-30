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

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
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

/**
 * Compares reading the whole of a file into a new array through a {@link FileChannel} in one read with reading it
 * in chunks of a fixed size, by file size, from one or more threads. A read into an array goes through a temporary
 * native buffer as large as the read, which the JDK keeps for each thread, so the chunk size decides how much
 * memory the copy out of that buffer touches.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Threads(1)
public class ChunkedReadBenchmark {
    /** The size of each file, in KB. */
    @Param({ "64", "256", "1024", "4096", "16384" })
    public int fileKB;

    /** The most that one read asks for, in KB, or 0 to read each file in one read. */
    @Param({ "0", "16", "64", "256", "1024" })
    public int chunkKB;

    /** The number of threads that read the files. */
    @Param("1")
    public int threads;

    /** The files, 64 MB in all, and at least 16 of them. */
    private List<Path> files;

    /** The files split into one share per thread. */
    private List<List<Path>> shares;

    /** The worker threads. */
    private ExecutorService executor;

    /**
     * Generate the files, if they do not already exist, and start the worker threads.
     *
     * @throws IOException
     *             if the files could not be generated.
     * @throws InterruptedException
     *             if interrupted while starting the worker threads.
     */
    @Setup(Level.Trial)
    public void setup() throws IOException, InterruptedException {
        final var dir = BenchmarkFixtures.dir().resolve("chunked-read").resolve(fileKB + "k");
        Files.createDirectories(dir);
        final var count = Math.max(16, 65536 / fileKB);
        final var random = new Random(4);
        files = new ArrayList<>(count);
        for (var i = 0; i < count; i++) {
            final var file = dir.resolve(i + ".bin");
            if (!Files.exists(file) || Files.size(file) != fileKB * 1024L) {
                final var bytes = new byte[fileKB * 1024];
                random.nextBytes(bytes);
                Files.write(file, bytes);
            }
            files.add(file);
        }
        shares = new ArrayList<>();
        for (var i = 0; i < threads; i++) {
            shares.add(files.subList(count * i / threads, count * (i + 1) / threads));
        }
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
     * Open a file, read it into a new array, and close it.
     *
     * @param file
     *            the file.
     * @return its content.
     * @throws IOException
     *             if the file could not be read.
     */
    private byte[] read(final Path file) throws IOException {
        try (var channel = FileChannel.open(file)) {
            final var bytes = new byte[(int) channel.size()];
            final var buffer = ByteBuffer.wrap(bytes);
            final var chunkSize = chunkKB == 0 ? bytes.length : chunkKB * 1024;
            while (buffer.position() < bytes.length) {
                buffer.limit(Math.min(bytes.length, buffer.position() + chunkSize));
                if (channel.read(buffer) < 0) {
                    throw new EOFException();
                }
            }
            return bytes;
        }
    }

    /**
     * Read every file once.
     *
     * @return the number of bytes read.
     * @throws Exception
     *             if a file could not be read, or not every byte was read.
     */
    @Benchmark
    public long readEveryFileOnce() throws Exception {
        final List<Callable<Long>> tasks = new ArrayList<>(threads);
        for (final var share : shares) {
            tasks.add(() -> {
                var shareBytesRead = 0L;
                for (final var file : share) {
                    shareBytesRead += read(file).length;
                }
                return shareBytesRead;
            });
        }
        var bytesRead = 0L;
        for (final var future : executor.invokeAll(tasks)) {
            try {
                bytesRead += future.get();
            } catch (final ExecutionException e) {
                throw e.getCause() instanceof Exception cause ? cause : e;
            }
        }
        if (bytesRead != files.size() * fileKB * 1024L) {
            throw new IOException("Read " + bytesRead + " bytes, expected " + files.size() * fileKB * 1024L);
        }
        return bytesRead;
    }
}
