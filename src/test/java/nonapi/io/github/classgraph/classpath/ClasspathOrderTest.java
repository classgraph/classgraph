package nonapi.io.github.classgraph.classpath;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassGraph.ClasspathElementURLFilter;
import io.github.classgraph.ScanResult;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/** Tests for {@link ClasspathOrder}, which finds the unique ordered classpath elements. */
public class ClasspathOrderTest {
    /**
     * Create a directory containing a single file of the same name, to use as a classpath element.
     *
     * @param tempDir
     *            the temporary directory to create the directory in.
     * @param name
     *            the name of the directory, and the base name of the file in it.
     * @return the directory.
     * @throws IOException
     *             if the directory could not be created.
     */
    private static Path classpathElementDir(final Path tempDir, final String name) throws IOException {
        final Path dir = Files.createDirectory(tempDir.resolve(name));
        Files.write(dir.resolve(name + ".txt"), name.getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    /**
     * An empty classpath entry is not added. It used to be resolved against the current directory before the
     * emptiness check was applied, which turned it into the current directory, and scanned the whole directory tree
     * below it.
     */
    @Test
    public void anEmptyClasspathEntryIsRejected() {
        final ScanSpec scanSpec = new ScanSpec();
        final ClasspathOrder classpathOrder = new ClasspathOrder(scanSpec, new ReflectionUtils());
        assertThat(classpathOrder.addClasspathEntry("", null, scanSpec, null)).isFalse();
        assertThat(classpathOrder.getOrder()).isEmpty();
    }

    /**
     * Every classpath element is offered to the URL filters, including the ones whose "file:" or "jar:file:" scheme
     * was stripped off when their path was resolved.
     */
    @Test
    public void classpathElementsAreFilteredByURL(@TempDir final Path tempDir) throws IOException {
        final Path dirA = classpathElementDir(tempDir, "a");
        final Path dirB = classpathElementDir(tempDir, "b");
        final List<URL> urls = new ArrayList<>();
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(dirA.toString(), dirB.toString())
                .filterClasspathElementsByURL(new ClasspathElementURLFilter() {
                    @Override
                    public boolean includeClasspathElement(final URL classpathElementURL) {
                        urls.add(classpathElementURL);
                        return classpathElementURL.toString().endsWith("/b/");
                    }
                }).scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly("b.txt");
        }
        assertThat(urls).extracting(URL::toString).containsExactly(dirA.toFile().toURI().toString(),
                dirB.toFile().toURI().toString());
    }
}
