import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Can a jar be deleted or replaced while ClassGraph is holding a ScanResult over it? Windows refuses to delete a
 * file that is memory-mapped, and refuses to delete an open file at all, whereas Unix allows both.
 *
 * Run with: java -cp <classgraph-classes> LockProbe.java <a-jar-to-copy>
 */
public class LockProbe {
    public static void main(final String[] args) throws Exception {
        for (final boolean memoryMapping : new boolean[] { false, true }) {
            final Path dir = Files.createTempDirectory("lockprobe");
            final Path jar = dir.resolve("probe.jar");
            Files.copy(Path.of(args[0]), jar);

            final ClassGraph classGraph = new ClassGraph().overrideClasspath(jar.toString()).enableClassInfo();
            setMemoryMapping(classGraph, memoryMapping);
            final ScanResult scanResult = classGraph.scan();
            System.out.printf("memoryMapping=%-5s  classes=%d%n", memoryMapping,
                    scanResult.getAllClasses().size());
            System.out.println("  delete while the ScanResult is open:   " + tryDelete(jar, Path.of(args[0])));
            scanResult.close();
            System.out.println("  delete after the ScanResult is closed: " + tryDelete(jar, Path.of(args[0])));
            deleteRecursively(dir);
        }
    }

    /**
     * Try to delete the given file, putting it back afterwards so that the next probe has something to delete.
     *
     * @param file
     *            the file to delete
     * @param source
     *            the file to copy back over it if the deletion succeeds
     * @return "deleted", or the exception that prevented the deletion
     */
    private static String tryDelete(final Path file, final Path source) throws Exception {
        try {
            Files.delete(file);
        } catch (final Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        final String result = Files.exists(file) ? "still there after delete returned" : "deleted";
        Files.copy(source, file);
        return result;
    }

    /**
     * Delete a directory and everything in it, ignoring anything that cannot be deleted.
     *
     * @param dir
     *            the directory to delete
     * @throws Exception
     *             if the directory could not be listed
     */
    private static void deleteRecursively(final Path dir) throws Exception {
        try (var files = Files.list(dir)) {
            for (final Path file : files.toList()) {
                try {
                    Files.delete(file);
                } catch (final Exception e) {
                    // Ignore -- this is what the probe is measuring
                }
            }
        }
        try {
            Files.delete(dir);
        } catch (final Exception e) {
            // Ignore
        }
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
