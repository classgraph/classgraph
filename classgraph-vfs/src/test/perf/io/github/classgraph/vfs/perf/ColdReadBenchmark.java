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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.classgraph.vfs.perf.BenchmarkFixtures.Layout;

/**
 * The same work as {@link FirstReadBenchmark}, done once, as the first thing a new JVM does: the cost of a library
 * the first time an application uses it, which includes loading its classes and running its code in the
 * interpreter. {@link RunBenchmarks} starts a new JVM for each measurement.
 *
 * <p>
 * This does not use JMH, because a JMH fork has already used much of the JDK by the time its benchmark starts, and
 * generating the entry names has used the JDK's {@code Files} API, which would favor the libraries built on those
 * parts of the JDK. The entry names are read from a file, and {@link BenchmarkFixtures} is not initialized here.
 */
public final class ColdReadBenchmark {
    /** Constructor. */
    private ColdReadBenchmark() {
        // Not instantiable
    }

    /**
     * Read every entry once, and print the time taken, in nanoseconds, as the last line of output.
     *
     * @param args
     *            the {@link Library} name, the {@link Layout} name, the number of threads, the path of the archive,
     *            the path of a file holding the entry names to read, one per line and in the order to read them,
     *            and the total size of the entries.
     * @throws Exception
     *             if the archive could not be read, or not every byte was read.
     */
    public static void main(final String[] args) throws Exception {
        final var library = Library.valueOf(args[0]);
        final var layout = Layout.valueOf(args[1]);
        final var threads = Integer.parseInt(args[2]);
        final var archive = Path.of(args[3]);
        final var names = Files.readAllLines(Path.of(args[4]));
        final var totalBytes = Long.parseLong(args[5]);
        final var executor = Executors.newFixedThreadPool(threads);
        // Start every worker thread now, as FirstReadBenchmark does, so that starting them is not timed
        final Callable<Object> noop = () -> null;
        executor.invokeAll(Collections.nCopies(threads, noop));

        final var startTime = System.nanoTime();
        var bytesRead = 0L;
        try (var reader = library.open(archive, layout)) {
            final var next = new AtomicInteger();
            final Callable<Long> task = () -> {
                var threadBytesRead = 0L;
                for (int i; (i = next.getAndIncrement()) < names.size();) {
                    threadBytesRead += reader.read(names.get(i)).length;
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
        final var elapsedNanos = System.nanoTime() - startTime;
        if (bytesRead != totalBytes) {
            throw new IOException("Read " + bytesRead + " bytes, expected " + totalBytes);
        }
        System.out.println(elapsedNanos);
        // A library may have left non-daemon threads running
        System.exit(0);
    }
}
