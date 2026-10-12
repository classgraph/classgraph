import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipOutputStream;

/** Standalone argument and warm-mode regression tests, run by the benchmark-drivers CI job. */
public class BenchTest {
    /**
     * Exercise validation before filesystem access, and the supported warm-mode sentinel.
     *
     * @param args
     *            ignored.
     * @throws Exception
     *             if a regression is found or fixture creation fails.
     */
    public static void main(final String[] args) throws Exception {
        reject(new String[0]);
        reject(new String[] { "missing", "warm", "1" });
        reject(new String[] { "missing", "typo", "1", "none" });
        reject(new String[] { "missing", "warm", "0", "none" });
        reject(new String[] { "missing", "warm", "-1", "none" });
        reject(new String[] { "missing", "warm", "not-a-number", "none" });
        reject(new String[] { "missing", "cold", "1", "none" });

        final Path dir = Files.createTempDirectory("classgraph-bench-test-");
        final Path jar = dir.resolve("empty.jar");
        try {
            try (var out = new ZipOutputStream(Files.newOutputStream(jar))) {
                out.finish();
            }
            // Simulate retained cold-mode state; warm mode must not execute it, or try executing "none".
            final var field = Bench.class.getDeclaredField("evictCommand");
            field.setAccessible(true);
            field.set(null, new String[] { "not-a-real-classgraph-eviction-command" });
            Bench.main(new String[] { dir.toString(), "warm", "1", "none" });
            if (field.get(null) != null) {
                throw new AssertionError("Warm mode retained the cold-mode eviction command");
            }
        } finally {
            Files.deleteIfExists(jar);
            Files.delete(dir);
        }
    }

    /**
     * Invalid arguments must fail before attempting to list the deliberately missing corpus directory.
     *
     * @param args
     *            invalid arguments.
     * @throws Exception
     *             if validation fails at the wrong boundary.
     */
    private static void reject(final String[] args) throws Exception {
        try {
            Bench.main(args);
            throw new AssertionError("Invalid benchmark arguments were accepted");
        } catch (IllegalArgumentException expected) {
            // Expected, including NumberFormatException for a nonnumeric run count.
        }
    }
}
