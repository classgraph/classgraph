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
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * A benchmark of the {@code "cgvfs:"} filesystem against the JDK's own zipfs, reading the same two zipfiles through
 * the same {@link java.nio.file.Files} calls, so that the only difference between the two runs is which provider
 * opened the filesystem.
 *
 * <p>
 * The zipfiles are named on the command line, and are built outside this program so that they are not checked into
 * the repository:
 *
 * <pre>
 * mkdir -p /tmp/cgvfs-bench/random
 * for i in $(seq -w 1 5120); do head -c 1048576 /dev/urandom &gt; /tmp/cgvfs-bench/random/file-$i.bin; done
 * (cd /tmp/cgvfs-bench/random &amp;&amp; zip -q -0 ../random.zip *.bin)
 *
 * # /tmp/cgvfs-bench/books holds 256 ebooks downloaded from Project Gutenberg; they are replicated
 * # to 5120 entries, which costs what 5120 downloaded ebooks would, since a zip does not
 * # deduplicate between entries
 * mkdir -p /tmp/cgvfs-bench/books20
 * for c in $(seq -w 1 20); do
 *     for f in /tmp/cgvfs-bench/books/*.txt; do cp "$f" "/tmp/cgvfs-bench/books20/copy$c-$(basename $f)"; done
 * done
 * (cd /tmp/cgvfs-bench/books20 &amp;&amp; zip -q -9 ../books.zip *.txt)
 * </pre>
 *
 * <p>
 * Run it with the test classpath of {@code classgraph-vfs}:
 *
 * <pre>
 * java -cp "classgraph-vfs/target/test-classes:classgraph-vfs/target/classes:classgraph-base/target/classes" \
 *     io.github.classgraph.vfs.perf.ZipfsVsCgvfsBenchmark /tmp/cgvfs-bench/random.zip /tmp/cgvfs-bench/books.zip
 * </pre>
 */
public class ZipfsVsCgvfsBenchmark {
    /** How many times each archive is opened, enumerated and closed in the timed run. */
    private static final int OPEN_LIST_CLOSE_ITERATIONS = 100;

    /** How many times each archive is opened, enumerated and closed before the timed run, to warm the JIT up. */
    private static final int OPEN_LIST_CLOSE_WARMUP_ITERATIONS = 20;

    /** The thread counts the whole-archive read is spread across. */
    private static final int[] THREAD_COUNTS = { 1, 2, 4, 8, 16, 32 };

    /** How a filesystem is opened over a zipfile. */
    private enum Provider {
        /** The JDK's own zipfs, addressed as a {@link Path}, which is how zipfs is normally opened. */
        ZIPFS("zipfs") {
            @Override
            FileSystem open(final Path zipFile) throws IOException {
                return FileSystems.newFileSystem(zipFile, Map.of());
            }
        },
        /** ClassGraph's filesystem, addressed by a {@code "cgvfs:"} URI. */
        CGVFS("cgvfs") {
            @Override
            FileSystem open(final Path zipFile) throws IOException {
                return FileSystems.newFileSystem(URI.create("cgvfs:" + zipFile), Map.of());
            }
        };

        /** The name this provider is reported under. */
        private final String displayName;

        /**
         * Constructor.
         *
         * @param displayName
         *            the name this provider is reported under.
         */
        Provider(final String displayName) {
            this.displayName = displayName;
        }

        /**
         * Open a filesystem over a zipfile.
         *
         * @param zipFile
         *            the zipfile.
         * @return the filesystem.
         * @throws IOException
         *             if the zipfile could not be opened.
         */
        abstract FileSystem open(Path zipFile) throws IOException;
    }

    /**
     * List every regular file of a filesystem, in the order the walk reaches them.
     *
     * @param fileSystem
     *            the filesystem.
     * @return the paths of the regular files.
     * @throws IOException
     *             if the filesystem could not be walked.
     */
    private static List<Path> listFiles(final FileSystem fileSystem) throws IOException {
        final var files = new ArrayList<Path>();
        for (final var root : fileSystem.getRootDirectories()) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(files::add);
            }
        }
        return files;
    }

    /**
     * Time opening, enumerating and closing an archive, repeatedly.
     *
     * @param provider
     *            the provider to open the archive with.
     * @param zipFile
     *            the archive.
     * @return the mean time in milliseconds of one open-list-close cycle.
     * @throws IOException
     *             if the archive could not be read.
     */
    private static double timeOpenListClose(final Provider provider, final Path zipFile) throws IOException {
        var numFiles = -1;
        for (var i = 0; i < OPEN_LIST_CLOSE_WARMUP_ITERATIONS; i++) {
            try (var fileSystem = provider.open(zipFile)) {
                numFiles = listFiles(fileSystem).size();
            }
        }
        final var startTime = System.nanoTime();
        for (var i = 0; i < OPEN_LIST_CLOSE_ITERATIONS; i++) {
            try (var fileSystem = provider.open(zipFile)) {
                if (listFiles(fileSystem).size() != numFiles) {
                    throw new IOException("The number of files read changed between iterations");
                }
            }
        }
        final var elapsedNanos = System.nanoTime() - startTime;
        System.err.println("    (" + numFiles + " files enumerated)");
        return elapsedNanos / 1.0e6 / OPEN_LIST_CLOSE_ITERATIONS;
    }

    /**
     * Read every file of an archive, spread across a given number of threads.
     *
     * @param fileSystem
     *            the filesystem to read through.
     * @param files
     *            the files to read.
     * @param numThreads
     *            the number of threads to spread the reads across.
     * @return the total number of bytes read.
     * @throws IOException
     *             if a file could not be read.
     */
    private static long readAll(final FileSystem fileSystem, final List<Path> files, final int numThreads)
            throws IOException {
        final var numBytesRead = new AtomicLong();
        final var checksum = new AtomicLong();
        final List<Callable<Void>> tasks = new ArrayList<>(files.size());
        for (final var file : files) {
            tasks.add(() -> {
                final var content = Files.readAllBytes(file);
                numBytesRead.addAndGet(content.length);
                // Touch the last byte, so that the whole content has to be materialized
                checksum.addAndGet(content.length == 0 ? 0 : content[content.length - 1]);
                return null;
            });
        }
        final var executor = Executors.newFixedThreadPool(numThreads);
        try {
            for (final var future : executor.invokeAll(tasks)) {
                try {
                    future.get();
                } catch (final ExecutionException e) {
                    throw new IOException("Could not read an entry of " + fileSystem, e.getCause());
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } finally {
            executor.shutdown();
        }
        return numBytesRead.get();
    }

    /**
     * Run the benchmark.
     *
     * @param args
     *            the zipfiles to benchmark.
     * @throws IOException
     *             if a zipfile could not be read.
     */
    public static void main(final String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("Usage: ZipfsVsCgvfsBenchmark <zipfile> ...");
            System.exit(1);
        }
        final var zipFiles = new ArrayList<Path>();
        for (final var arg : args) {
            final var zipFile = Path.of(arg).toAbsolutePath().normalize();
            if (!Files.isRegularFile(zipFile)) {
                throw new IOException("No such file: " + zipFile);
            }
            zipFiles.add(zipFile);
        }

        System.out.println("JDK " + System.getProperty("java.version") + " on " + System.getProperty("os.name")
                + " " + System.getProperty("os.arch") + ", " + Runtime.getRuntime().availableProcessors()
                + " available processors");
        System.out.println();

        // Open, enumerate and close, one provider at a time so that the two do not interfere
        System.out.println(
                "### Opening, enumerating and closing (mean of " + OPEN_LIST_CLOSE_ITERATIONS + " iterations, ms)");
        System.out.println();
        System.out.print("| Archive |");
        for (final var provider : Provider.values()) {
            System.out.print(" " + provider.displayName + " |");
        }
        System.out.println(" speedup |");
        System.out.print("| --- |");
        for (var i = 0; i <= Provider.values().length; i++) {
            System.out.print(" ---: |");
        }
        System.out.println();
        for (final var zipFile : zipFiles) {
            final var times = new double[Provider.values().length];
            for (var i = 0; i < Provider.values().length; i++) {
                final var provider = Provider.values()[i];
                System.err.println("open/list/close: " + provider.displayName + " " + zipFile);
                times[i] = timeOpenListClose(provider, zipFile);
            }
            System.out.printf("| `%s` |", zipFile.getFileName());
            for (final var time : times) {
                System.out.printf(" %.2f |", time);
            }
            System.out.printf(" %.2f× |%n", times[0] / times[1]);
        }
        System.out.println();

        // Read every file, one provider at a time, at each thread count
        System.out.println("### Reading every file (total wall time, ms)");
        System.out.println();
        System.out.println("| Archive | Threads | zipfs | cgvfs | speedup |");
        System.out.println("| --- | ---: | ---: | ---: | ---: |");
        for (final var zipFile : zipFiles) {
            final var timesByProvider = new double[Provider.values().length][THREAD_COUNTS.length];
            for (var i = 0; i < Provider.values().length; i++) {
                final var provider = Provider.values()[i];
                // One filesystem for the whole sweep of this provider, so that the archive is opened once and the
                // reads are timed rather than the open, and so that the two providers never run at the same time
                try (var fileSystem = provider.open(zipFile)) {
                    final var files = listFiles(fileSystem);
                    System.err.println(
                            "read: " + provider.displayName + " " + zipFile + " (" + files.size() + " files)");
                    // A warmup pass, so that the first timed thread count is not the one that pays for the JIT and
                    // for the operating system's file cache
                    readAll(fileSystem, files, THREAD_COUNTS[THREAD_COUNTS.length - 1]);
                    for (var j = 0; j < THREAD_COUNTS.length; j++) {
                        final var startTime = System.nanoTime();
                        final var numBytesRead = readAll(fileSystem, files, THREAD_COUNTS[j]);
                        timesByProvider[i][j] = (System.nanoTime() - startTime) / 1.0e6;
                        System.err.println("    " + THREAD_COUNTS[j] + " threads: " + numBytesRead + " bytes in "
                                + String.format("%.1f", timesByProvider[i][j]) + " ms");
                    }
                }
            }
            for (var j = 0; j < THREAD_COUNTS.length; j++) {
                System.out.printf("| `%s` | %d | %.1f | %.1f | %.2f× |%n", zipFile.getFileName(), THREAD_COUNTS[j],
                        timesByProvider[0][j], timesByProvider[1][j],
                        timesByProvider[0][j] / timesByProvider[1][j]);
            }
        }
    }
}
