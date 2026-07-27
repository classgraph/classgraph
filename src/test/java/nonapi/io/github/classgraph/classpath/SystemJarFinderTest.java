package nonapi.io.github.classgraph.classpath;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;

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
     * Regression guard for the #816 fix: on a real JDK 8 install, {@code java.home} is {@code <jdk>/jre}, so the
     * JDK root check must succeed, and the JDK's own {@code lib/} jars (e.g. {@code tools.jar}) must still be
     * classified as system jars.
     */
    @Test
    @EnabledForJreRange(max = JRE.JAVA_8)
    public void realJdkLibJarsAreStillFound() {
        final File javaHome = new File(System.getProperty("java.home"));
        if (!"jre".equals(javaHome.getName())) {
            // A standalone JRE install, rather than the JRE inside a JDK -- nothing to check
            return;
        }
        assertThat(SystemJarFinder.isJDKRoot(javaHome.getParentFile())).isTrue();
        assertThat(SystemJarFinder.getJreLibOrExtJars()).anyMatch(new java.util.function.Predicate<String>() {
            @Override
            public boolean test(final String path) {
                return path.endsWith("/tools.jar");
            }
        });
    }
}
