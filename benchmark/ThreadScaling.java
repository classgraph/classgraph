import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * How does scan time change with the number of scanning threads? Where jarfiles are not memory-mapped, every thread
 * reads through one shared FileChannel per jar, and FileChannel's positioned read takes a per-channel monitor
 * (sun.nio.ch.NativeThreadSet); where they are mapped, which is on Windows, there is no shared channel to contend
 * on.
 *
 * Run with: java -cp <classgraph-classes> ThreadScaling.java <jar-dir> <numRuns> <threadCount>...
 */
public class ThreadScaling {
    public static void main(final String[] args) throws Exception {
        final List<Path> jars;
        try (var files = Files.list(Path.of(args[0]))) {
            jars = files.filter(f -> f.toString().endsWith(".jar")).sorted().collect(Collectors.toList());
        }
        final String classpath = jars.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        final int numRuns = Integer.parseInt(args[1]);

        for (int argIdx = 2; argIdx < args.length; argIdx++) {
            final int numThreads = Integer.parseInt(args[argIdx]);
            final List<Long> times = new ArrayList<>();
            for (int run = 0; run < numRuns; run++) {
                times.add(time(classpath, numThreads));
            }
            // Discard the first third of the runs as JIT warm-up
            final int warmUp = numRuns / 3;
            System.out.printf("threads=%-3d  median=%4d ms%n", numThreads, median(times.subList(warmUp, numRuns)));
        }
    }

    /**
     * Time one scan.
     *
     * @param classpath
     *            the classpath to scan
     * @param numThreads
     *            the number of scanning threads
     * @return the elapsed time in milliseconds
     */
    private static long time(final String classpath, final int numThreads) {
        final ClassGraph classGraph = new ClassGraph().enableClasspathEntries(classpath).enableClassInfo()
                .enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility();
        final long startTime = System.nanoTime();
        try (ScanResult scanResult = classGraph.scan(numThreads)) {
            scanResult.getAllClasses().size();
        }
        return (System.nanoTime() - startTime) / 1_000_000L;
    }

    /**
     * The median of a list of timings.
     *
     * @param timings
     *            the timings
     * @return the median
     */
    private static long median(final List<Long> timings) {
        final List<Long> sorted = new ArrayList<>(timings);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }
}
