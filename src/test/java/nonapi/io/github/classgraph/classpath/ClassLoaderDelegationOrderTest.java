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
     * The application classloader's own classpath entries must be placed where the application classloader sits in
     * the delegation order, not appended after every other classloader's entries.
     *
     * <p>
     * No public API exposes those entries: the application classloader is not a {@link URLClassLoader}, and its
     * {@code jdk.internal.loader.URLClassPath ucp} field can only be read with {@code --add-opens} or Narcissus, so
     * the {@code java.class.path} system property normally stands in for it. That property lists the same entries,
     * but it is a property rather than a classloader, so it is easy to read it at the wrong point.
     *
     * @param tempDir
     *            a temporary directory to create the classpath element in
     * @throws IOException
     *             if the classpath element could not be created
     */
    @Test
    public void theApplicationClassLoadersEntriesArePlacedAtItsPositionInTheDelegationOrder(
            @TempDir final Path tempDir) throws IOException {
        final Path childDir = Files.createDirectory(tempDir.resolve("child"));
        // A child of the application classloader delegates to it first, so every one of the application
        // classloader's entries must precede the child's
        try (URLClassLoader child = new URLClassLoader(new URL[] { childDir.toUri().toURL() },
                ClassLoader.getSystemClassLoader())) {
            assertThat(canonicalFiles(new ClassGraph().addClassLoader(child).getClasspathFiles()))
                    .endsWith(childDir.toFile().getCanonicalFile());
        }
    }

    /**
     * {@link ClassGraph#ignoreParentClassLoaders()} leaves out only the classpath entries that a <i>parent</i>
     * classloader declares, so the application classloader's own entries are still searched when it is one of the
     * classloaders being searched rather than a parent of one -- which is the usual case, since the context
     * classloader is normally the application classloader.
     *
     * @throws IOException
     *             if the classpath could not be read
     */
    @Test
    public void ignoringParentClassLoadersKeepsTheApplicationClassLoadersOwnEntries() throws IOException {
        // This test class is loaded from a java.class.path entry, so that entry must have been searched
        final File thisTestsClasspathElement = new File(
                ClassLoaderDelegationOrderTest.class.getProtectionDomain().getCodeSource().getLocation().getPath())
                        .getCanonicalFile();
        assertThat(canonicalFiles(new ClassGraph().ignoreParentClassLoaders().getClasspathFiles()))
                .contains(thisTestsClasspathElement);
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
