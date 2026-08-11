package nonapi.io.github.classgraph.classpath;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;

/**
 * Tests that a classloader and the classloaders it delegates to are placed in the classpath in the order that the
 * classloader resolves classes in, since that is the order in which a class defined in more than one classpath
 * element masks the copies after it.
 */
public class ClassLoaderDelegationOrderTest {
    /**
     * A classloader that delegates to its parent first, which is the standard delegation order, contributes its
     * parent's classpath elements before its own.
     *
     * @param tempDir
     *            a temporary directory to create the classpath elements in
     * @throws IOException
     *             if the classpath elements could not be created
     */
    @Test
    public void parentFirstClassLoaderContributesItsParentsClasspathFirst(@TempDir final Path tempDir)
            throws IOException {
        final Path parentDir = Files.createDirectory(tempDir.resolve("parent"));
        final Path childDir = Files.createDirectory(tempDir.resolve("child"));
        // URLClassLoader delegates to its parent first, so the parent's directory masks the child's
        try (URLClassLoader parent = new URLClassLoader(new URL[] { parentDir.toUri().toURL() },
                /* parent = */ null);
                URLClassLoader child = new URLClassLoader(new URL[] { childDir.toUri().toURL() }, parent)) {
            assertThat(new ClassGraph().overrideClassLoaders(child).getClasspathFiles())
                    .containsExactly(parentDir.toFile(), childDir.toFile());
        }
    }
}
