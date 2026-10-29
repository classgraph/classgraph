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
 * Runs {@link FirstReadBenchmark} at 1, 2, 4, 8 and 16 threads and {@link WholeArchiveBenchmark} on one thread, for
 * every {@link Library} on every {@link Content} type and every {@link Layout} the library supports. Each JMH run's
 * results are written as JSON to the results directory, and the tables in the README are written to
 * {@code results.md} there. A full run takes about an hour and a half.
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
 * {@link Library#NETBEANS} is run on binary content only when the classpath starts with a {@code JarFileSystem}
 * that has the fix from apache/netbeans#9762 (see {@link Library#supports(Content)}). Compile
 * {@code platform/openide.filesystems/src/org/openide/filesystems/JarFileSystem.java} from that pull request
 * against the NetBeans jarfiles in {@code /tmp/cp.txt}, jar it, and put the jar first in {@code -cp}.
 *
 * <p>
 * System properties:
 * <ul>
 * <li>{@code cgvfs.bench.dir}: where the archives are generated (see {@link BenchmarkFixtures#dir()}).
 * <li>{@code cgvfs.bench.libraries}: a comma-separated list of {@link Library} names to run, instead of all.
 * <li>{@code cgvfs.bench.quick}: if {@code true}, run every combination once, briefly, on 1 and 16 threads, to
 * check that they all work. The numbers are not meaningful.
 * </ul>
 */
public final class RunBenchmarks {
    /** The thread counts {@link FirstReadBenchmark} is run at. */
    private static final int[] THREAD_COUNTS = { 1, 2, 4, 8, 16 };

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

        // Generate every archive up front, so that generating one is never timed
        for (final var content : Content.values()) {
            for (final var layout : Layout.values()) {
                BenchmarkFixtures.path(content, layout);
            }
        }

        final var firstResults = new ArrayList<RunResult>();
        final var wholeResults = new ArrayList<RunResult>();
        for (final var library : libraries) {
            final var contents = Arrays.stream(Content.values()).filter(library::supports).map(Content::name)
                    .toArray(String[]::new);
            final var firstLayouts = Arrays.stream(Layout.values()).filter(library::supports).map(Layout::name)
                    .toArray(String[]::new);
            if (firstLayouts.length > 0) {
                final var threadCounts = Arrays.stream(quick ? new int[] { 1, 16 } : THREAD_COUNTS)
                        .mapToObj(String::valueOf).toArray(String[]::new);
                firstResults.addAll(run(FirstReadBenchmark.class, library, contents, firstLayouts, threadCounts,
                        quick, resultsDir.resolve("first-" + library.name().toLowerCase() + ".json")));
            }
            final var wholeLayouts = Arrays.stream(Layout.values()).filter(library::supportsWholeArchive)
                    .map(Layout::name).toArray(String[]::new);
            if (wholeLayouts.length > 0) {
                wholeResults.addAll(run(WholeArchiveBenchmark.class, library, contents, wholeLayouts, null, quick,
                        resultsDir.resolve("whole-" + library.name().toLowerCase() + ".json")));
            }
        }

        final var markdown = new StringBuilder();
        markdown.append(firstReadTables(firstResults));
        markdown.append(wholeArchiveTables(wholeResults));
        Files.writeString(resultsDir.resolve("results.md"), markdown);
        System.out.println(markdown);
    }

    /**
     * Run one benchmark for one library, on the given content types and layouts.
     *
     * @param benchmark
     *            the benchmark class.
     * @param library
     *            the library.
     * @param contents
     *            the names of the content types.
     * @param layouts
     *            the names of the layouts.
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
    private static List<RunResult> run(final Class<?> benchmark, final Library library, final String[] contents,
            final String[] layouts, final String[] threadCounts, final boolean quick, final Path resultFile)
            throws RunnerException {
        final var optionsBuilder = new OptionsBuilder() //
                .include(benchmark.getName()) //
                .param("library", library.name()) //
                .param("content", contents) //
                .param("layout", layouts);
        if (threadCounts != null) {
            optionsBuilder.param("threads", threadCounts);
        }
        final var options = optionsBuilder //
                .forks(1) //
                .warmupIterations(quick ? 1 : 2) //
                .warmupTime(TimeValue.milliseconds(quick ? 200 : 1000)) //
                .measurementIterations(quick ? 1 : 3) //
                // An operation reads a whole archive, which can take a tenth of a second, so iterations are long
                .measurementTime(TimeValue.milliseconds(quick ? 200 : 2000)) //
                .jvmArgsAppend("-Xmx8g", "--enable-native-access=ALL-UNNAMED",
                        "-Dcgvfs.bench.dir=" + BenchmarkFixtures.dir()) //
                .shouldFailOnError(true) //
                .resultFormat(ResultFormatType.JSON) //
                .result(resultFile.toString()) //
                .build();
        return new ArrayList<>(new Runner(options).run());
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
     *            the results.
     * @return the tables, in Markdown.
     * @throws IOException
     *             if the entry sizes could not be read.
     */
    private static String firstReadTables(final List<RunResult> results) throws IOException {
        final var sb = new StringBuilder();
        for (final var content : Content.values()) {
            final var totalMB = BenchmarkFixtures.totalBytes(content) / 1e6;
            // Row label -> thread count -> MB/s
            final Map<String, Map<Integer, Double>> rows = new LinkedHashMap<>();
            for (final var layout : Layout.values()) {
                for (final var library : Library.values()) {
                    for (final var result : results) {
                        if (param(result, "content").equals(content.name())
                                && param(result, "layout").equals(layout.name())
                                && param(result, "library").equals(library.name())) {
                            // (The score is in milliseconds per operation)
                            rows.computeIfAbsent(layout.label() + " | " + library.displayName(),
                                    _ -> new LinkedHashMap<>()).put(Integer.parseInt(param(result, "threads")),
                                            totalMB / (result.getPrimaryResult().getScore() / 1e3));
                        }
                    }
                }
            }
            if (rows.isEmpty()) {
                continue;
            }
            sb.append("\nOpen, read every entry once in random order, close, ").append(content.label())
                    .append(", MB/s:\n\n| Layout | Library |");
            for (final var threads : THREAD_COUNTS) {
                sb.append(' ').append(threads).append(threads == 1 ? " thread |" : " threads |");
            }
            sb.append("\n|---|---|");
            sb.append("--:|".repeat(THREAD_COUNTS.length));
            sb.append('\n');
            for (final var row : rows.entrySet()) {
                sb.append("| ").append(row.getKey()).append(" |");
                for (final var threads : THREAD_COUNTS) {
                    final var score = row.getValue().get(threads);
                    sb.append(' ').append(score == null ? "" : String.format("%,.0f", score)).append(" |");
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Format the whole-archive results as one table per content type: a row per library, a column per layout, in
     * milliseconds per archive.
     *
     * @param results
     *            the results.
     * @return the tables, in Markdown.
     */
    private static String wholeArchiveTables(final List<RunResult> results) {
        final var sb = new StringBuilder();
        for (final var content : Content.values()) {
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
                    String cell = "";
                    for (final var result : results) {
                        if (param(result, "content").equals(content.name())
                                && param(result, "layout").equals(layout.name())
                                && param(result, "library").equals(library.name())) {
                            cell = String.format("%,.1f", result.getPrimaryResult().getScore());
                            any = true;
                        }
                    }
                    row.append(' ').append(cell).append(" |");
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
