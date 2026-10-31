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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import io.github.classgraph.vfs.perf.BenchmarkFixtures.Content;
import io.github.classgraph.vfs.perf.BenchmarkFixtures.Layout;

/**
 * Runs {@link FirstReadBenchmark} at 1, 2, 4 and so on up to twice the number of CPUs (at most 32) threads, and
 * {@link WholeArchiveBenchmark} on one thread, for every {@link Library} on every {@link Content} type and every
 * {@link Layout} the library supports. Each JMH run's results are written as JSON to the results directory, and the
 * tables in the README are written to {@code results.md} there.
 *
 * <p>
 * The libraries are compared on each combination of content type and layout by running all of them on it, one after
 * another, so that a change in the speed of the machine over the course of the run (from other work, or from its
 * temperature) does not favor the libraries that happen to run while it is fast. This is done {@link #ROUNDS}
 * times, the libraries in reverse order each other time, and each table cell is the mean of the rounds. A full run
 * of all three content types takes about an hour per content type on four CPUs.
 *
 * <p>
 * How far the two rounds of a cell differ shows how small a difference between libraries the run can resolve. On
 * GitHub's runners (run 41189422120), the median difference was 0.7-1.5% on Linux, 1.1-3.9% on Windows, and 13-22%
 * on macOS. On macOS the ratio of a library's time to cgvfs's in the same round varied as much as the times did, so
 * the speed of those runners changes within seconds, faster than running the libraries side by side can cancel out,
 * and a difference of less than about 30% between libraries cannot be resolved there.
 *
 * <p>
 * Run it with {@code cgvfs.bench.dir} on a disk-backed filesystem, not a RAM-backed one such as {@code /tmp} on
 * many Linux systems, since that is where jarfiles are read from in practice. The archives are in the page cache
 * after they are generated, so they are read from RAM either way, but a RAM-backed filesystem serves reads and
 * memory mapping by a different path in the kernel.
 *
 * <p>
 * Build the test classes and write out the test classpath, in one build so that classgraph-base resolves to its
 * build directory, then run it:
 *
 * <pre>
 * ./mvnw -q -pl classgraph-vfs -am test-compile dependency:build-classpath -DskipTests \
 *     -Dmdep.includeScope=test -Dmdep.outputFile=/tmp/cp.txt
 * java -cp "classgraph-vfs/target/test-classes:classgraph-vfs/target/classes:$(cat /tmp/cp.txt)" \
 *     io.github.classgraph.vfs.perf.RunBenchmarks /tmp/cgvfs-bench-results
 * </pre>
 *
 * <p>
 * {@link Library#NETBEANS} is run on text and binary content only when the classpath starts with a
 * {@code JarFileSystem} that has the fix from apache/netbeans#9762 (see {@link Library#supports(Content)}). Compile
 * {@code platform/openide.filesystems/src/org/openide/filesystems/JarFileSystem.java} from that pull request
 * against the NetBeans jarfiles in {@code /tmp/cp.txt}, jar it, and put the jar first in {@code -cp}.
 *
 * <p>
 * System properties:
 * <ul>
 * <li>{@code cgvfs.bench.dir}: where the archives are generated (see {@link BenchmarkFixtures#dir()}).
 * <li>{@code cgvfs.bench.libraries}: a comma-separated list of {@link Library} names to run, instead of all.
 * <li>{@code cgvfs.bench.contents}: a comma-separated list of {@link Content} names to run, instead of all.
 * <li>{@code cgvfs.bench.layouts}: a comma-separated list of {@link Layout} names to run, instead of all.
 * <li>{@code cgvfs.bench.threads}: a comma-separated list of the thread counts to run {@link FirstReadBenchmark}
 * at, instead of the default.
 * <li>{@code cgvfs.bench.quick}: if {@code true}, run every combination once, briefly, on 1 and 16 threads, to
 * check that they all work. The numbers are not meaningful.
 * </ul>
 */
public final class RunBenchmarks {
    /**
     * The number of times each library is run on each combination of content type and layout. Two rounds, in
     * opposite orders, cancel out a steady change in the speed of the machine while they run.
     */
    private static final int ROUNDS = 2;

    /**
     * The largest number of threads {@link FirstReadBenchmark} is run at by default. ClassGraph uses 16 worker
     * threads at most.
     */
    private static final int MAX_THREADS = 32;

    /** Constructor. */
    private RunBenchmarks() {
        // Not instantiable
    }

    /**
     * Run the benchmarks.
     *
     * @param args
     *            the directory to write results to.
     * @throws IOException
     *             if the archives could not be generated or the results written.
     * @throws RunnerException
     *             if JMH failed.
     */
    public static void main(final String[] args) throws IOException, RunnerException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: RunBenchmarks <results directory>");
        }
        final var resultsDir = Path.of(args[0]);
        Files.createDirectories(resultsDir);
        final var quick = Boolean.getBoolean("cgvfs.bench.quick");
        final var librariesProperty = System.getProperty("cgvfs.bench.libraries");
        final var libraries = librariesProperty == null ? List.of(Library.values())
                : Arrays.stream(librariesProperty.split(",")).map(String::trim).map(Library::valueOf).toList();
        final var contentsProperty = System.getProperty("cgvfs.bench.contents");
        final var includedContents = contentsProperty == null ? List.of(Content.values())
                : Arrays.stream(contentsProperty.split(",")).map(String::trim).map(Content::valueOf).toList();
        final var layoutsProperty = System.getProperty("cgvfs.bench.layouts");
        final var includedLayouts = layoutsProperty == null ? List.of(Layout.values())
                : Arrays.stream(layoutsProperty.split(",")).map(String::trim).map(Layout::valueOf).toList();
        final var threadsProperty = System.getProperty("cgvfs.bench.threads");
        final List<Integer> threadCounts = new ArrayList<>();
        if (threadsProperty != null) {
            Arrays.stream(threadsProperty.split(",")).map(String::trim).map(Integer::valueOf)
                    .forEach(threadCounts::add);
        } else if (quick) {
            threadCounts.addAll(List.of(1, 16));
        } else {
            // Up to twice the number of CPUs, which includes the number of worker threads ClassGraph uses by
            // default on a machine with few CPUs (8 on 4 CPUs)
            final var maxThreads = Math.min(MAX_THREADS, 2 * Runtime.getRuntime().availableProcessors());
            for (var threads = 1; threads <= maxThreads; threads *= 2) {
                threadCounts.add(threads);
            }
        }
        final var threadCountNames = threadCounts.stream().map(String::valueOf).toArray(String[]::new);
        final var rounds = quick ? 1 : ROUNDS;

        // Generate every archive up front, so that generating one is never timed
        for (final var content : includedContents) {
            for (final var layout : includedLayouts) {
                BenchmarkFixtures.path(content, layout);
            }
        }

        final var firstResults = new ArrayList<RunResult>();
        final var wholeResults = new ArrayList<RunResult>();
        for (final var content : includedContents) {
            for (final var layout : includedLayouts) {
                final var cell = content.label() + "-" + layout.label();
                final var firstLibraries = libraries.stream()
                        .filter(library -> library.supports(content) && library.supports(layout)).toList();
                final var wholeLibraries = libraries.stream()
                        .filter(library -> library.supports(content) && library.supportsWholeArchive(layout))
                        .toList();
                for (var round = 1; round <= rounds; round++) {
                    final var reversed = round % 2 == 0;
                    if (!firstLibraries.isEmpty()) {
                        firstResults.addAll(run(FirstReadBenchmark.class, firstLibraries, reversed, content, layout,
                                threadCountNames, quick,
                                resultsDir.resolve("first-" + cell + "-" + round + ".json")));
                    }
                    if (!wholeLibraries.isEmpty()) {
                        wholeResults.addAll(run(WholeArchiveBenchmark.class, wholeLibraries, reversed, content,
                                layout, null, quick, resultsDir.resolve("whole-" + cell + "-" + round + ".json")));
                    }
                }
            }
        }

        final var markdown = new StringBuilder();
        markdown.append(firstReadTables(firstResults, threadCounts));
        markdown.append(wholeArchiveTables(wholeResults));
        Files.writeString(resultsDir.resolve("results.md"), markdown);
        System.out.println(markdown);
    }

    /**
     * Run one benchmark for several libraries, one after another, on one content type and layout.
     *
     * @param benchmark
     *            the benchmark class.
     * @param libraries
     *            the libraries.
     * @param reversed
     *            true to run the libraries in reverse order.
     * @param content
     *            the content type.
     * @param layout
     *            the layout.
     * @param threadCounts
     *            the values of the benchmark's {@code threads} parameter, or null if it has none.
     * @param quick
     *            true to run briefly, to check that the benchmark works.
     * @param resultFile
     *            where JMH writes its JSON results.
     * @return the results.
     * @throws RunnerException
     *             if JMH failed.
     */
    private static List<RunResult> run(final Class<?> benchmark, final List<Library> libraries,
            final boolean reversed, final Content content, final Layout layout, final String[] threadCounts,
            final boolean quick, final Path resultFile) throws RunnerException {
        final var ordered = new ArrayList<>(libraries);
        if (reversed) {
            Collections.reverse(ordered);
        }
        final var optionsBuilder = new OptionsBuilder() //
                .include(benchmark.getName()) //
                .param("library", ordered.stream().map(Library::name).toArray(String[]::new)) //
                .param("content", content.name()) //
                .param("layout", layout.name());
        if (threadCounts != null) {
            optionsBuilder.param("threads", threadCounts);
        }
        final var options = optionsBuilder //
                .forks(1) //
                // The slowest libraries were still 4-5% faster in the last of three iterations after two seconds
                // of warmup
                .warmupIterations(quick ? 1 : 4) //
                .warmupTime(TimeValue.milliseconds(quick ? 200 : 1000)) //
                .measurementIterations(quick ? 1 : 4) //
                .measurementTime(TimeValue.milliseconds(quick ? 200 : 1000)) //
                // A fixed heap, so that resizing the heap is never timed, and small enough for a runner with 7GB
                // of RAM
                .jvmArgsAppend("-Xms2g", "-Xmx2g", "--enable-native-access=ALL-UNNAMED",
                        "-Dcgvfs.bench.dir=" + BenchmarkFixtures.dir()) //
                .shouldFailOnError(true) //
                .resultFormat(ResultFormatType.JSON) //
                .result(resultFile.toString()) //
                .build();
        return new ArrayList<>(new Runner(options).run());
    }

    /**
     * The mean score of each combination of parameters, over the rounds.
     *
     * @param results
     *            the results of every round.
     * @param content
     *            the content type to keep the results of.
     * @return the mean score in milliseconds per operation, by layout name, then library name, then thread count
     *         (or "" if the benchmark has no thread count).
     */
    private static Map<String, Map<String, Map<String, Double>>> meanScores(final List<RunResult> results,
            final Content content) {
        final Map<String, Map<String, Map<String, List<Double>>>> scores = new LinkedHashMap<>();
        for (final var result : results) {
            if (param(result, "content").equals(content.name())) {
                final var threads = result.getParams().getParamsKeys().contains("threads")
                        ? param(result, "threads")
                        : "";
                scores.computeIfAbsent(param(result, "layout"), k -> new LinkedHashMap<>())
                        .computeIfAbsent(param(result, "library"), k -> new LinkedHashMap<>())
                        .computeIfAbsent(threads, k -> new ArrayList<>()).add(result.getPrimaryResult().getScore());
            }
        }
        final Map<String, Map<String, Map<String, Double>>> means = new LinkedHashMap<>();
        scores.forEach((layout, byLibrary) -> byLibrary.forEach((library, byThreads) -> byThreads
                .forEach((threads, list) -> means.computeIfAbsent(layout, k -> new LinkedHashMap<>())
                        .computeIfAbsent(library, k -> new LinkedHashMap<>())
                        .put(threads, list.stream().mapToDouble(Double::doubleValue).average().orElseThrow()))));
        return means;
    }

    /**
     * Get a parameter of a result.
     *
     * @param result
     *            the result.
     * @param name
     *            the parameter name.
     * @return the parameter value.
     */
    private static String param(final RunResult result, final String name) {
        return result.getParams().getParam(name);
    }

    /**
     * Format the first-read results as one table per content type: a row per library and layout, a column per
     * thread count, in MB/s, the total size of the entries divided by the time taken to open the archive, read them
     * all, and close it.
     *
     * @param results
     *            the results of every round.
     * @param threadCounts
     *            the thread counts, one column each.
     * @return the tables, in Markdown.
     * @throws IOException
     *             if the entry sizes could not be read.
     */
    private static String firstReadTables(final List<RunResult> results, final List<Integer> threadCounts)
            throws IOException {
        final var sb = new StringBuilder();
        for (final var content : Content.values()) {
            final var means = meanScores(results, content);
            if (means.isEmpty()) {
                continue;
            }
            final var totalMB = BenchmarkFixtures.totalBytes(content) / 1e6;
            sb.append("\nOpen, read every entry once in random order, close, ").append(content.label())
                    .append(", MB/s:\n\n| Layout | Library |");
            for (final var threads : threadCounts) {
                sb.append(' ').append(threads).append(threads == 1 ? " thread |" : " threads |");
            }
            sb.append("\n|---|---|");
            sb.append("--:|".repeat(threadCounts.size()));
            sb.append('\n');
            for (final var layout : Layout.values()) {
                final var byLibrary = means.get(layout.name());
                if (byLibrary == null) {
                    continue;
                }
                for (final var library : Library.values()) {
                    final var byThreads = byLibrary.get(library.name());
                    if (byThreads == null) {
                        continue;
                    }
                    sb.append("| ").append(layout.label()).append(" | ").append(library.displayName()).append(" |");
                    for (final var threads : threadCounts) {
                        // (The score is in milliseconds per operation)
                        final var score = byThreads.get(String.valueOf(threads));
                        sb.append(' ').append(score == null ? "" : String.format("%,.0f", totalMB / (score / 1e3)))
                                .append(" |");
                    }
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    /**
     * Format the whole-archive results as one table per content type: a row per library, a column per layout, in
     * milliseconds per archive.
     *
     * @param results
     *            the results of every round.
     * @return the tables, in Markdown.
     */
    private static String wholeArchiveTables(final List<RunResult> results) {
        final var sb = new StringBuilder();
        for (final var content : Content.values()) {
            final var means = meanScores(results, content);
            final var header = new StringBuilder("| Library |");
            final var rule = new StringBuilder("|---|");
            for (final var layout : Layout.values()) {
                header.append(' ').append(layout.label()).append(" |");
                rule.append("--:|");
            }
            final var rows = new StringBuilder();
            for (final var library : Library.values()) {
                final var row = new StringBuilder("| " + library.displayName() + " |");
                var any = false;
                for (final var layout : Layout.values()) {
                    final var score = means.getOrDefault(layout.name(), Map.of())
                            .getOrDefault(library.name(), Map.of()).get("");
                    if (score != null) {
                        any = true;
                    }
                    row.append(' ').append(score == null ? "" : String.format("%,.1f", score)).append(" |");
                }
                if (any) {
                    rows.append(row).append('\n');
                }
            }
            if (rows.length() > 0) {
                sb.append("\nOpen, read every entry, close, ").append(content.label()).append(", ms:\n\n")
                        .append(header).append('\n').append(rule).append('\n').append(rows);
            }
        }
        return sb.toString();
    }
}
