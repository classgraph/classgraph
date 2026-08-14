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
 * Does the advantage of memory mapping grow with the number of scanning threads? Without mapping, every thread
 * reads through one shared FileChannel per jar, and FileChannel's positioned read takes a per-channel monitor
 * (sun.nio.ch.NativeThreadSet). With mapping there is no shared channel to contend on.
 *
 * Run with: java -cp <classgraph-classes> ThreadScaling.java <jar-dir> <numPairs> <threadCount>...
 */
public class ThreadScaling {
    public static void main(final String[] args) throws Exception {
        final List<Path> jars;
        try (var files = Files.list(Path.of(args[0]))) {
            jars = files.filter(f -> f.toString().endsWith(".jar")).sorted().collect(Collectors.toList());
        }
        final String classpath = jars.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        final int numPairs = Integer.parseInt(args[1]);

        for (int argIdx = 2; argIdx < args.length; argIdx++) {
            final int numThreads = Integer.parseInt(args[argIdx]);
            final List<Long> withoutMapping = new ArrayList<>();
            final List<Long> withMapping = new ArrayList<>();
            for (int pair = 0; pair < numPairs; pair++) {
                // Swap the order every other pair, so that neither arm always runs first
                final boolean mappedFirst = pair % 2 == 1;
                final long a = time(classpath, mappedFirst, numThreads);
                final long b = time(classpath, !mappedFirst, numThreads);
                (mappedFirst ? withMapping : withoutMapping).add(a);
                (mappedFirst ? withoutMapping : withMapping).add(b);
            }
            // Discard the first third of each arm as JIT warm-up
            final int warmUp = numPairs / 3;
            System.out.printf("threads=%-3d  mmap=false median=%4d ms   mmap=true median=%4d ms%n", numThreads,
                    median(withoutMapping.subList(warmUp, numPairs)), median(withMapping.subList(warmUp, numPairs)));
        }
    }

    /**
     * Time one scan.
     *
     * @param classpath
     *            the classpath to scan
     * @param memoryMapping
     *            whether to memory-map files
     * @param numThreads
     *            the number of scanning threads
     * @return the elapsed time in milliseconds
     */
    private static long time(final String classpath, final boolean memoryMapping, final int numThreads) {
        final ClassGraph classGraph = new ClassGraph().overrideClasspath(classpath).enableAllInfo();
        setMemoryMapping(classGraph, memoryMapping);
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

    /**
     * Turn memory mapping on or off. ClassGraph chooses this by platform and offers no API to change it, so the
     * scan spec's testing override is reached reflectively here -- setting it explicitly also means this benchmark
     * measures both arms on Windows, where mapping is otherwise always on.
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
            final Object vfsScanSpec = scanSpec.getClass().getField("vfsScanSpec").get(scanSpec);
            vfsScanSpec.getClass().getField("memoryMapFiles").setBoolean(vfsScanSpec, memoryMapping);
        } catch (final ReflectiveOperationException e) {
            throw new RuntimeException("Could not set memory mapping", e);
        }
    }
}
