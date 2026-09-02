import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Can a jar be deleted or replaced while ClassGraph is holding a ScanResult over it? Windows refuses to delete a
 * file that is memory-mapped, and refuses to delete an open file at all, whereas Unix allows both. Jarfiles are
 * memory-mapped on Windows only, so this probes whichever path the platform takes.
 *
 * Run with: java -cp <classgraph-classes> LockProbe.java <a-jar-to-copy>
 */
public class LockProbe {
    public static void main(final String[] args) throws Exception {
        final Path dir = Files.createTempDirectory("lockprobe");
        final Path jar = dir.resolve("probe.jar");
        Files.copy(Path.of(args[0]), jar);

        final ClassGraph classGraph = new ClassGraph().enableClasspathEntries(jar.toString()).enableClassInfo();
        final ScanResult scanResult = classGraph.scan();
        System.out.printf("classes=%d%n", scanResult.getAllClasses().size());
        System.out.println("  delete while the ScanResult is open:   " + tryDelete(jar, Path.of(args[0])));
        scanResult.close();
        System.out.println("  delete after the ScanResult is closed: " + tryDelete(jar, Path.of(args[0])));
        deleteRecursively(dir);
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
}
