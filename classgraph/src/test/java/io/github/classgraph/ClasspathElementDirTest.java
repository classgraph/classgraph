package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.vfs.Vfs;

/**
 * When scanning is extended to an external class, its classfile is looked up in every directory classpath element
 * that comes before the one that holds it. A directory that does not hold the class' package must not cost one file
 * lookup per class, so it looks up each package directory only once per scan. Whether a file was looked up cannot
 * be seen through the public API, so the classpath element is tested directly here.
 */
public class ClasspathElementDirTest {
    /** The virtual filesystem the classpath elements read through. */
    @AutoClose
    private final Vfs vfs = new Vfs();

    /**
     * Build a directory classpath element.
     *
     * @param dir
     *            the directory.
     * @return the classpath element.
     */
    private ClasspathElement classpathElementDir(final Path dir) {
        final var workUnit = new ClasspathEntryWorkUnit(dir, /* classLoaderStr = */ null,
                /* parentClasspathElement = */ null, /* classpathElementIdxWithinParent = */ 0,
                /* packageRootPrefix = */ "", ClassLoaderHandler.NO_PACKAGE_ROOT_PREFIXES,
                ClassLoaderHandler.NO_LIB_DIR_PREFIXES);
        return new ClasspathElementDir(workUnit, dir, vfs, new ScanSpec());
    }

    /**
     * Create an empty file, and the directories above it.
     *
     * @param file
     *            the file.
     * @throws IOException
     *             if the file could not be created.
     */
    private static void createFile(final Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.createFile(file);
    }

    /**
     * A package directory that is missing is looked up only once during the scan, but a lookup after the scan still
     * reads the filesystem.
     *
     * @param tempDir
     *            the directory of the classpath element.
     * @throws IOException
     *             if a file could not be created.
     */
    @Test
    public void missingPackageDirIsLookedUpOncePerScan(@TempDir final Path tempDir) throws IOException {
        final var classpathElement = classpathElementDir(tempDir);
        assertThat(classpathElement.getResourceDuringScan("lib/A.class")).isNull();

        // The package directory and a classfile in it are created after the package directory was found missing.
        // The classpath element still answers from what it found, so it did not look the file up.
        createFile(tempDir.resolve("lib/B.class"));
        assertThat(classpathElement.getResourceDuringScan("lib/B.class")).isNull();

        // A lookup by path after the scan, e.g. ScanResult#getResourcesWithPathIgnoringAccept, sees the file
        assertThat(classpathElement.getResource("lib/B.class")).isNotNull();
    }

    /**
     * A classfile is still found in a package directory that exists, and in the root directory.
     *
     * @param tempDir
     *            the directory of the classpath element.
     * @throws IOException
     *             if a file could not be created.
     */
    @Test
    public void classfilesInExistingDirsAreFound(@TempDir final Path tempDir) throws IOException {
        createFile(tempDir.resolve("lib/A.class"));
        createFile(tempDir.resolve("Top.class"));
        final var classpathElement = classpathElementDir(tempDir);
        assertThat(classpathElement.getResourceDuringScan("lib/A.class").getPath()).isEqualTo("lib/A.class");
        assertThat(classpathElement.getResourceDuringScan("lib/Missing.class")).isNull();
        // The package directory exists, so a classfile created in it later is found
        createFile(tempDir.resolve("lib/B.class"));
        assertThat(classpathElement.getResourceDuringScan("lib/B.class").getPath()).isEqualTo("lib/B.class");
        assertThat(classpathElement.getResourceDuringScan("Top.class").getPath()).isEqualTo("Top.class");
        // A package "directory" that is a regular file holds no classfiles
        createFile(tempDir.resolve("notadir"));
        assertThat(classpathElement.getResourceDuringScan("notadir/C.class")).isNull();
    }
}
