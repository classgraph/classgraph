package nonapi.io.github.classgraph.classpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
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

        final Set<String> rtJars = new LinkedHashSet<>();
        final Set<String> libOrExtJars = new LinkedHashSet<>();
        assertThat(SystemJarFinder.addJREPath(libDir.toFile(), rtJars, libOrExtJars)).isTrue();

        final String symlinkPathResolved = FastPathResolver.resolve(FileUtils.currDirPath(),
                symlinkedJar.toFile().getPath());
        // Canonicalize the file the symlink points at, rather than the symlink itself: on Windows,
        // File#getCanonicalPath does not follow a symlink (it only expands an 8.3 short name such as
        // "RUNNER~1"), whereas the Path#toRealPath that ClassGraph canonicalizes with follows it on every
        // platform. There is no symlink in the path of the target, so the two agree on it everywhere.
        final String canonicalPathResolved = FastPathResolver.resolve(FileUtils.currDirPath(),
                realJar.toFile().getCanonicalPath());
        assertThat(canonicalPathResolved).isNotEqualTo(symlinkPathResolved);
        assertThat(libOrExtJars).containsExactlyInAnyOrder(symlinkPathResolved, canonicalPathResolved);
        assertThat(rtJars).isEmpty();
    }

    /**
     * A directory lists its entries in whatever order the filesystem stores them, which differs between filesystems
     * and platforms, and changes as files are added and removed. The JRE lib jars have to be put into a fixed order,
     * otherwise the same JRE would produce a different classpath order on different machines.
     */
    @Test
    public void jreLibJarsAreSorted(@TempDir final Path tmpDir) throws IOException {
        for (final String name : new String[] { "zebra.jar", "alpha.jar", "mango.jar", "01first.jar", "beta.jar",
                "yankee.jar", "delta.jar" }) {
            touch(tmpDir, "lib/" + name);
        }
        final Set<String> rtJars = new LinkedHashSet<>();
        final Set<String> libOrExtJars = new LinkedHashSet<>();
        assertThat(SystemJarFinder.addJREPath(new File(tmpDir.toFile(), "lib"), rtJars, libOrExtJars)).isTrue();

        // Compare the leafnames rather than the whole paths, and drop repeats: if the temporary directory is
        // reached by a path that is not already canonical -- through a symlink, as on macOS, where "/var" is a
        // symlink to "/private/var", or through an 8.3 short name, as on Windows -- then each jar is added twice,
        // once under each path. The two entries for the same jar are adjacent, so removing repeats leaves the
        // order that is under test here unchanged.
        final List<String> fileNames = new ArrayList<>();
        for (final String jarPath : libOrExtJars) {
            final String fileName = jarPath.substring(jarPath.lastIndexOf('/') + 1);
            if (fileNames.isEmpty() || !fileNames.get(fileNames.size() - 1).equals(fileName)) {
                fileNames.add(fileName);
            }
        }
        assertThat(fileNames).containsExactly("01first.jar", "alpha.jar", "beta.jar", "delta.jar", "mango.jar",
                "yankee.jar", "zebra.jar");
    }

    /**
     * {@code {java.home}/lib/jrt-fs.jar} is the jrt: filesystem provider, not part of the class library -- its
     * classes are already in java.base, so scanning it would duplicate them. It must not be classified as a JRE lib
     * jar.
     */
    @Test
    @EnabledForJreRange(min = JRE.JAVA_9)
    public void jrtFsJarIsNotAJreLibJar() {
        assertThat(SystemJarFinder.getJreLibOrExtJars()).noneMatch(new java.util.function.Predicate<String>() {
            @Override
            public boolean test(final String path) {
                return path.endsWith("/jrt-fs.jar");
            }
        });
    }
}
