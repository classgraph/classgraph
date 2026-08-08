package nonapi.io.github.classgraph.classpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;

/**
 * Test that an application directory containing a bundled JRE is not mistaken for a JDK root, which would cause
 * the application's own jars to be classified as JRE lib jars and silently dropped from the classpath (#816).
 */
public class SystemJarFinderTest {

    /**
     * Create an empty file, creating any missing parent directories.
     *
     * @param dir
     *            the base directory.
     * @param relativePath
     *            the path of the file to create, relative to the base directory.
     * @throws IOException
     *             if the file could not be created.
     */
    private static void touch(final Path dir, final String relativePath) throws IOException {
        final Path path = dir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.createFile(path);
    }

    /**
     * An application directory with a bundled JRE in {@code jre/} and the application's own jars in {@code lib/}
     * must not be treated as a JDK root -- this is the #816 layout.
     */
    @Test
    public void appDirWithBundledJreIsNotAJDKRoot(@TempDir final Path tmpDir) throws IOException {
        touch(tmpDir, "jre/lib/rt.jar");
        touch(tmpDir, "lib/myapp.jar");
        touch(tmpDir, "lib/some-dependency.jar");
        assertThat(SystemJarFinder.isJDKRoot(tmpDir.toFile())).isFalse();
    }

    /** A JDK 8 style root, identified by {@code lib/tools.jar}. */
    @Test
    public void jdkRootWithToolsJarIsAJDKRoot(@TempDir final Path tmpDir) throws IOException {
        touch(tmpDir, "jre/lib/rt.jar");
        touch(tmpDir, "lib/tools.jar");
        assertThat(SystemJarFinder.isJDKRoot(tmpDir.toFile())).isTrue();
    }

    /** A JDK root identified by {@code bin/javac}, on platforms where executables have no suffix. */
    @Test
    public void jdkRootWithJavacIsAJDKRoot(@TempDir final Path tmpDir) throws IOException {
        touch(tmpDir, "jre/lib/rt.jar");
        touch(tmpDir, "bin/javac");
        assertThat(SystemJarFinder.isJDKRoot(tmpDir.toFile())).isTrue();
    }

    /** A JDK root identified by {@code bin/javac.exe}, on Windows. */
    @Test
    public void jdkRootWithJavacExeIsAJDKRoot(@TempDir final Path tmpDir) throws IOException {
        touch(tmpDir, "jre/lib/rt.jar");
        touch(tmpDir, "bin/javac.exe");
        assertThat(SystemJarFinder.isJDKRoot(tmpDir.toFile())).isTrue();
    }

    /** A directory that does not exist is not a JDK root. */
    @Test
    public void nonExistentDirIsNotAJDKRoot(@TempDir final Path tmpDir) {
        assertThat(SystemJarFinder.isJDKRoot(new File(tmpDir.toFile(), "does-not-exist"))).isFalse();
    }

    /**
     * A JRE lib jar that is a symlink is reachable both by the symlink path and by the path it resolves to, so both
     * paths must be recorded -- otherwise a classpath entry naming the other path is not recognized as a system jar.
     */
    @Test
    public void canonicalPathOfSymlinkedJreLibJarIsAlsoAdded(@TempDir final Path tmpDir) throws IOException {
        final Path realJar = tmpDir.resolve("real/some-lib.jar");
        Files.createDirectories(realJar.getParent());
        Files.createFile(realJar);
        final Path libDir = tmpDir.resolve("lib");
        Files.createDirectories(libDir);
        final Path symlinkedJar = libDir.resolve("linked-lib.jar");
        try {
            Files.createSymbolicLink(symlinkedJar, realJar);
        } catch (IOException | UnsupportedOperationException e) {
            // Creating a symlink requires a privileged account or developer mode on Windows
            assumeTrue(false, "Cannot create symlinks on this platform: " + e);
        }

        final Set<String> libOrExtJars = new LinkedHashSet<>();
        assertThat(SystemJarFinder.addJREPath(libDir.toFile(), libOrExtJars)).isTrue();

        final String symlinkPathResolved = FastPathResolver.resolve(FileUtils.currDirPath(),
                symlinkedJar.toFile().getPath());
        final String canonicalPathResolved = FastPathResolver.resolve(FileUtils.currDirPath(),
                symlinkedJar.toFile().getCanonicalPath());
        assertThat(canonicalPathResolved).isNotEqualTo(symlinkPathResolved);
        assertThat(libOrExtJars).containsExactlyInAnyOrder(symlinkPathResolved, canonicalPathResolved);
    }

    /**
     * {@code {java.home}/lib/jrt-fs.jar} is the jrt: filesystem provider, not part of the class library -- its
     * classes are already in java.base, so scanning it would duplicate them. It must not be classified as a JRE lib
     * jar.
     */
    @Test
    public void jrtFsJarIsNotAJreLibJar() {
        assertThat(SystemJarFinder.getJreLibOrExtJars()).noneMatch(new java.util.function.Predicate<String>() {
            @Override
            public boolean test(final String path) {
                return path.endsWith("/jrt-fs.jar");
            }
        });
    }
}
