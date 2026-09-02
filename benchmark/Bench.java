import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Measures scan time over a corpus of jarfiles, with the page cache warm or cold.
 *
 * Whether jarfiles are memory-mapped follows the platform -- it is done on Windows only -- so this measures
 * whichever path the platform takes. The mapped-against-unmapped comparison that decided that, which this driver
 * used to run as two arms in one JVM, is recorded at
 * https://github.com/classgraph/classgraph/wiki/Memory-Mapping-Benchmark .
 *
 * Run with: java -cp <classgraph-classes> Bench.java <jar-dir> <warm|cold> <numRuns> <evict-command|none>
 */
public class Bench {
    /** The classpath to scan: every jar in the directory named by the first argument. */
    private static String classpath;

    /** The command that drops the corpus from the OS page cache, or null in warm mode. */
    private static String[] evictCommand;

    /** The number of classes found by the last scan, printed as a sanity check. */
    private static int numClasses;

    /**
     * Run the benchmark.
     *
     * @param args
     *            the jar directory, "warm" or "cold", the number of runs, and the eviction command (which may be
     *            several arguments, or the single argument "none")
     * @throws Exception
     *             if the benchmark could not be run
     */
    public static void main(final String[] args) throws Exception {
        final List<Path> jars;
        try (var files = Files.list(Path.of(args[0]))) {
            jars = files.filter(f -> f.toString().endsWith(".jar")).sorted().collect(Collectors.toList());
        }
        classpath = jars.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        long corpusBytes = 0L;
        for (final Path jar : jars) {
            corpusBytes += Files.size(jar);
        }
        final boolean cold = args[1].equals("cold");
        final int numRuns = Integer.parseInt(args[2]);
        if (cold) {
            evictCommand = java.util.Arrays.copyOfRange(args, 3, args.length);
        }

        final List<Double> times = new ArrayList<>();
        for (int i = 0; i < numRuns; i++) {
            times.add(scan());
        }
        // In warm mode the first third of the runs are JIT warmup, so discard them
        final int firstSteadyRun = cold ? 0 : numRuns / 3;
        System.out.printf("corpus: %d jars, %.0f MB, %d classes; %s page cache%n", jars.size(), corpusBytes / 1e6,
                numClasses, cold ? "cold" : "warm");
        report("scan", times.subList(firstSteadyRun, numRuns));
    }

    /**
     * Run one scan.
     *
     * @return how many milliseconds the scan took
     * @throws IOException
     *             if the corpus could not be evicted from the page cache
     * @throws InterruptedException
     *             if interrupted while evicting the corpus from the page cache
     */
    private static double scan() throws IOException, InterruptedException {
        if (evictCommand != null) {
            evict();
        }
        final long startTime = System.nanoTime();
        final ClassGraph classGraph = new ClassGraph().enableClasspathEntries(classpath).enableClassInfo()
                .enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility();
        try (ScanResult scanResult = classGraph.scan()) {
            numClasses = scanResult.getAllClasses().size();
        }
        return (System.nanoTime() - startTime) / 1e6;
    }

    /**
     * Drop the corpus from the OS page cache, so that the next scan reads it from the storage device.
     *
     * @throws IOException
     *             if the eviction command could not be run, or failed
     * @throws InterruptedException
     *             if interrupted while waiting for the eviction command
     */
    private static void evict() throws IOException, InterruptedException {
        final var process = new ProcessBuilder(evictCommand).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (process.waitFor() != 0) {
            throw new IOException("Eviction command failed: " + String.join(" ", evictCommand));
        }
    }

    /**
     * Print the minimum, median and maximum of the given times.
     *
     * @param label
     *            the label to print before the times
     * @param times
     *            the times, in milliseconds
     */
    private static void report(final String label, final List<Double> times) {
        final List<Double> sorted = new ArrayList<>(times);
        Collections.sort(sorted);
        System.out.printf("  %s n=%2d  min=%.0f  median=%.0f  max=%.0f ms%n", label, sorted.size(), sorted.get(0),
                sorted.get(sorted.size() / 2), sorted.get(sorted.size() - 1));
    }
}
