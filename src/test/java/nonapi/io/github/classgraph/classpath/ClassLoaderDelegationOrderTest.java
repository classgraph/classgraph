package nonapi.io.github.classgraph.classpath;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
            assertThat(canonicalFiles(new ClassGraph().overrideClassLoaders(child).getClasspathFiles()))
                    .containsExactly(parentDir.toFile().getCanonicalFile(), childDir.toFile().getCanonicalFile());
        }
    }

    /**
     * Canonicalize files, since classpath elements are reported in canonical form, and a temporary directory is
     * not canonical on every platform (on macOS it is reached through a symlink, and on Windows it can be named by
     * a short path).
     *
     * @param files
     *            the files
     * @return the canonical form of each file
     * @throws IOException
     *             if a file could not be canonicalized
     */
    private static List<File> canonicalFiles(final List<File> files) throws IOException {
        final List<File> canonicalFiles = new ArrayList<>();
        for (final File file : files) {
            canonicalFiles.add(file.getCanonicalFile());
        }
        return canonicalFiles;
    }
}
