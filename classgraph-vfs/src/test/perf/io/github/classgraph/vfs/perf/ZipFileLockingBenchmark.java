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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsRoot;

/**
 * A benchmark of what {@link ZipFile}'s instance monitor costs as threads are added, against a {@link VfsRoot} over
 * the same jarfile, which takes no locks on either path.
 *
 * <p>
 * Two kinds of work are timed, because the monitor matters very differently to each. Entry <i>lookup</i> happens
 * entirely inside the monitor, so it does not parallelize at all under {@link ZipFile}. Entry <i>inflation</i>
 * happens outside it, so it does parallelize, and the monitor only costs what the lookup and the reads around the
 * inflater cost.
 *
 * <p>
 * Three ways of looking an entry up are compared: one {@link ZipFile} shared between the threads, which is what
 * most code does; one {@link ZipFile} per thread, which isolates the instance monitor as the cause by removing it
 * while keeping everything else, at the cost of parsing the central directory once per thread and holding one file
 * handle per thread; and one {@link VfsRoot} shared between the threads.
 *
 * <p>
 * Run it with the test classpath of {@code classgraph-vfs}, naming the jarfile to read:
 *
 * <pre>
 * java -cp "classgraph-vfs/target/test-classes:classgraph-vfs/target/classes:classgraph-base/target/classes" \
 *     io.github.classgraph.vfs.perf.ZipFileLockingBenchmark /path/to/some-big.jar
 * </pre>
 */
public class ZipFileLockingBenchmark {
    /** How many times every entry of the jarfile is looked up, in one timed run. */
    private static final int LOOKUP_REPETITIONS = 400;

    /** How many times every entry of the jarfile is inflated, in one timed run. */
    private static final int INFLATE_REPETITIONS = 20;

    /** The thread counts each kind of work is spread across. */
    private static final int[] THREAD_COUNTS = { 1, 2, 4, 8, 16, 32 };

    /** One measurement: a way of doing the work, at every thread count. */
    private interface Work {
        /**
         * Do the whole of the work once, spread across the given number of threads.
         *
         * @param numThreads
         *            the number of threads to spread the work across.
         * @return a value derived from everything that was read, so that nothing can be optimized away.
         * @throws Exception
         *             if the jarfile could not be read.
         */
        long run(int numThreads) throws Exception;
    }

    /**
     * Run one {@link Callable} per thread, and wait for all of them.
     *
     * @param numThreads
     *            the number of threads.
     * @param task
     *            the task, which is given the index of the thread it is running on, and the number of threads, so
     *            that it can take its own share of the work.
     * @return the sum of what the tasks returned.
     * @throws Exception
     *             if a task failed.
     */
    private static long inParallel(final int numThreads, final ShardedTask task) throws Exception {
        final var total = new AtomicLong();
        final List<Callable<Void>> tasks = new ArrayList<>(numThreads);
        for (var threadIdx = 0; threadIdx < numThreads; threadIdx++) {
            final var thisThreadIdx = threadIdx;
            tasks.add(() -> {
                total.addAndGet(task.run(thisThreadIdx, numThreads));
                return null;
            });
        }
        final var executor = Executors.newFixedThreadPool(numThreads);
        try {
            for (final var future : executor.invokeAll(tasks)) {
                try {
                    future.get();
                } catch (final ExecutionException e) {
                    throw new IOException("A thread failed", e.getCause());
                }
            }
        } finally {
            executor.shutdown();
        }
        return total.get();
    }

    /** The share of the work that one thread does. */
    @FunctionalInterface
    private interface ShardedTask {
        /**
         * Do one thread's share of the work.
         *
         * @param threadIdx
         *            the index of this thread.
         * @param numThreads
         *            the total number of threads.
         * @return a value derived from what was read.
         * @throws Exception
         *             if the jarfile could not be read.
         */
        long run(int threadIdx, int numThreads) throws Exception;
    }

    /**
     * Time one way of doing the work at every thread count, printing a row of the table.
     *
     * @param label
     *            the name of this way of doing the work.
     * @param work
     *            the work.
     * @throws Exception
     *             if the jarfile could not be read.
     */
    private static void timeRow(final String label, final Work work) throws Exception {
        // A warmup pass at the highest thread count, so that the first timed column does not pay for the JIT
        work.run(THREAD_COUNTS[THREAD_COUNTS.length - 1]);
        System.out.printf("%-38s", label);
        for (final var numThreads : THREAD_COUNTS) {
            final var startTime = System.nanoTime();
            final var result = work.run(numThreads);
            final var elapsedMillis = (System.nanoTime() - startTime) / 1.0e6;
            if (result == 0) {
                throw new IOException("Nothing was read");
            }
            System.out.printf("%7.0fms", elapsedMillis);
            System.out.flush();
        }
        System.out.println();
    }

    /**
     * Run the benchmark.
     *
     * @param args
     *            the jarfile to read.
     * @throws Exception
     *             if the jarfile could not be read.
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: ZipFileLockingBenchmark <jarfile>");
            System.exit(1);
        }
        final var jarFile = new File(args[0]).getAbsoluteFile();
        if (!jarFile.isFile()) {
            throw new IOException("No such file: " + jarFile);
        }

        try (var vfs = new Vfs()) {
            final var root = vfs.open(jarFile);
            final var entries = root.getEntries();
            final List<String> entryNames = new ArrayList<>(entries.size());
            for (final var entry : entries) {
                entryNames.add(entry.getRawPathFromRoot());
            }
            final var numEntries = entryNames.size();

            System.out.println(jarFile + ", " + numEntries + " entries, JDK " + System.getProperty("java.version")
                    + ", " + Runtime.getRuntime().availableProcessors() + " available processors");
            System.out.println();
            System.out.printf("%-38s", "getEntry() on " + numEntries + " entries, x" + LOOKUP_REPETITIONS);
            for (final var numThreads : THREAD_COUNTS) {
                System.out.printf("%6d thd", numThreads);
            }
            System.out.println();

            // Lookup, through one ZipFile shared between the threads
            try (var sharedZipFile = new ZipFile(jarFile)) {
                timeRow("java.util.zip, one shared ZipFile", numThreads -> inParallel(numThreads, (idx, n) -> {
                    var found = 0L;
                    for (var rep = idx; rep < LOOKUP_REPETITIONS; rep += n) {
                        for (final var entryName : entryNames) {
                            if (sharedZipFile.getEntry(entryName) != null) {
                                found++;
                            }
                        }
                    }
                    return found;
                }));
            }

            // Lookup, through one ZipFile per thread, which removes the instance monitor from the picture
            timeRow("java.util.zip, one ZipFile per thread", numThreads -> inParallel(numThreads, (idx, n) -> {
                try (var ownZipFile = new ZipFile(jarFile)) {
                    var found = 0L;
                    for (var rep = idx; rep < LOOKUP_REPETITIONS; rep += n) {
                        for (final var entryName : entryNames) {
                            if (ownZipFile.getEntry(entryName) != null) {
                                found++;
                            }
                        }
                    }
                    return found;
                }
            }));

            // Lookup, through one VfsRoot shared between the threads
            timeRow("classgraph-vfs, one shared VfsRoot", numThreads -> inParallel(numThreads, (idx, n) -> {
                var found = 0L;
                for (var rep = idx; rep < LOOKUP_REPETITIONS; rep += n) {
                    for (final var entryName : entryNames) {
                        if (root.getEntry(entryName) != null) {
                            found++;
                        }
                    }
                }
                return found;
            }));

            System.out.println();
            System.out.printf("%-38s", "inflate every entry, x" + INFLATE_REPETITIONS);
            for (final var numThreads : THREAD_COUNTS) {
                System.out.printf("%6d thd", numThreads);
            }
            System.out.println();

            // Inflation, through one ZipFile shared between the threads. The entries are looked up by the names
            // the VfsRoot reported, so that both rows below inflate exactly the same set of entries
            try (var sharedZipFile = new ZipFile(jarFile)) {
                final List<ZipEntry> zipEntries = new ArrayList<>(numEntries);
                for (final var entryName : entryNames) {
                    final var zipEntry = sharedZipFile.getEntry(entryName);
                    if (zipEntry == null) {
                        throw new IOException("ZipFile does not have the entry " + entryName
                                + ", which the VfsRoot reported, so the two rows would not be comparable");
                    }
                    zipEntries.add(zipEntry);
                }
                timeRow("java.util.zip, one shared ZipFile", numThreads -> inParallel(numThreads, (idx, n) -> {
                    var numBytesRead = 0L;
                    for (var rep = 0; rep < INFLATE_REPETITIONS; rep++) {
                        for (var i = idx; i < zipEntries.size(); i += n) {
                            try (var in = sharedZipFile.getInputStream(zipEntries.get(i))) {
                                numBytesRead += in.readAllBytes().length;
                            }
                        }
                    }
                    return numBytesRead;
                }));
            }

            // Inflation, through one VfsRoot shared between the threads
            timeRow("classgraph-vfs, one shared VfsRoot", numThreads -> inParallel(numThreads, (idx, n) -> {
                var numBytesRead = 0L;
                for (var rep = 0; rep < INFLATE_REPETITIONS; rep++) {
                    for (var i = idx; i < entries.size(); i += n) {
                        numBytesRead += entries.get(i).load().length;
                    }
                }
                return numBytesRead;
            }));
        }
    }
}
