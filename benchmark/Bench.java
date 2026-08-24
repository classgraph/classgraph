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
 * Measures how much difference memory-mapping jarfiles makes to scan time.
 *
 * Both arms run in the same JVM, alternating, and the order within each pair is swapped every other pair, so that
 * JIT warmup and machine drift affect the two arms equally.
 *
 * Run with: java -cp <classgraph-classes> Bench.java <jar-dir> <warm|cold> <numPairs> <evict-command|none>
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
     *            the jar directory, "warm" or "cold", the number of pairs of runs, and the eviction command (which
     *            may be several arguments, or the single argument "none")
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
        final int numPairs = Integer.parseInt(args[2]);
        if (cold) {
            evictCommand = java.util.Arrays.copyOfRange(args, 3, args.length);
        }

        final List<Double> unmapped = new ArrayList<>();
        final List<Double> mapped = new ArrayList<>();
        for (int i = 0; i < numPairs; i++) {
            if (i % 2 == 0) {
                unmapped.add(scan(false));
                mapped.add(scan(true));
            } else {
                mapped.add(scan(true));
                unmapped.add(scan(false));
            }
        }
        // In warm mode the first third of the runs are JIT warmup, so discard them
        final int firstSteadyPair = cold ? 0 : numPairs / 3;
        System.out.printf("corpus: %d jars, %.0f MB, %d classes; %s page cache%n", jars.size(), corpusBytes / 1e6,
                numClasses, cold ? "cold" : "warm");
        report("mmap=false", unmapped.subList(firstSteadyPair, numPairs));
        report("mmap=true ", mapped.subList(firstSteadyPair, numPairs));
    }

    /**
     * Run one scan.
     *
     * @param memoryMapping
     *            whether to memory-map files
     * @return how many milliseconds the scan took
     * @throws IOException
     *             if the corpus could not be evicted from the page cache
     * @throws InterruptedException
     *             if interrupted while evicting the corpus from the page cache
     */
    private static double scan(final boolean memoryMapping) throws IOException, InterruptedException {
        if (evictCommand != null) {
            evict();
        }
        final long startTime = System.nanoTime();
        final ClassGraph classGraph = new ClassGraph().enableClasspathEntries(classpath).enableAllInfo();
        setMemoryMapping(classGraph, memoryMapping);
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

    /**
     * Turn memory mapping on or off. ClassGraph chooses this by platform, and the setter that overrides its choice
     * is hidden from the API docs, so it is reached reflectively here -- setting it explicitly also means this
     * benchmark measures both arms on Windows, where mapping is otherwise always on.
     *
     * @param classGraph
     *            the ClassGraph instance to configure
     * @param memoryMapping
     *            whether to memory-map files
     */
    private static void setMemoryMapping(final ClassGraph classGraph, final boolean memoryMapping) {
        try {
            final java.lang.reflect.Field scanSpecField = ClassGraph.class.getDeclaredField("scanSpec");
            scanSpecField.setAccessible(true);
            final Object scanSpec = scanSpecField.get(classGraph);
            // ScanSpec is not a public class, so its field has to be opened up even though the field is public
            final java.lang.reflect.Field vfsSpecField = scanSpec.getClass().getDeclaredField("vfsSpec");
            vfsSpecField.setAccessible(true);
            final Object vfsSpec = vfsSpecField.get(scanSpec);
            vfsSpec.getClass().getMethod("setMemoryMappingFiles", boolean.class).invoke(vfsSpec, memoryMapping);
        } catch (final ReflectiveOperationException e) {
            throw new RuntimeException("Could not set memory mapping", e);
        }
    }
}
