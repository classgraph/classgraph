package nonapi.io.github.classgraph.classloaderhandler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.runner.RuntimeClassLoader;

/**
 * Test that {@code QuarkusClassLoaderHandler} skips classpath elements that are null or of an unexpected type,
 * rather than throwing and failing the scan (Quarkus retypes the fields the handler reads between releases).
 */
public class QuarkusElementTypeTest {
    /** A classpath element whose root is found by calling {@code getRoot}, as in Quarkus 3.11 and later. */
    public static class RootedElement {
        /** The root. */
        private final Path root;

        /**
         * Constructor.
         *
         * @param root
         *            the root.
         */
        public RootedElement(final Path root) {
            this.root = root;
        }

        /**
         * Get the root.
         *
         * @return the root.
         */
        public Path getRoot() {
            return root;
        }
    }

    /**
     * Get the classpath of a scan that uses only the given classloader.
     *
     * @param classLoader
     *            the classloader.
     * @return the classpath.
     */
    private static List<File> classpathOf(final ClassLoader classLoader) {
        try (ScanResult scanResult = new ClassGraph().overrideClassLoaders(classLoader).scan()) {
            return scanResult.getClasspathFiles();
        }
    }

    /**
     * A null element of a {@code QuarkusClassLoader} is skipped.
     *
     * @param classesDir
     *            a classes directory.
     * @throws IOException
     *             if the classes directory could not be resolved.
     */
    @Test
    public void aNullQuarkusClassLoaderElementIsSkipped(@TempDir final Path classesDir) throws IOException {
        final Path dir = classesDir.toRealPath();
        assertThat(classpathOf(new QuarkusClassLoader(Arrays.asList(null, new RootedElement(dir)))))
                .contains(dir.toFile());
    }

    /**
     * An element of a {@code RuntimeClassLoader} that is null or not a {@link Path} is skipped.
     *
     * @param classesDir
     *            a classes directory.
     * @throws IOException
     *             if the classes directory could not be resolved.
     */
    @Test
    public void aRuntimeClassLoaderElementThatIsNotAPathIsSkipped(@TempDir final Path classesDir)
            throws IOException {
        final Path dir = classesDir.toRealPath();
        assertThat(classpathOf(new RuntimeClassLoader(Arrays.asList("not a path", null, dir))))
                .contains(dir.toFile());
    }
}
