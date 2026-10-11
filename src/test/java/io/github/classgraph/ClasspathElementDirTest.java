package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * When scanning is extended to an external class, its classfile is looked up in every directory classpath element
 * that comes before the one that holds it. A directory that does not hold the class' package must not cost one
 * file lookup per class, so it looks up each package directory only once per scan. Whether a file was looked up
 * cannot be seen through the public API, so the classpath element is tested directly here.
 */
class ClasspathElementDirTest {
    /** Build a directory classpath element. */
    private static ClasspathElement classpathElementDir(final Path dir) {
        final ClasspathEntryWorkUnit workUnit = new ClasspathEntryWorkUnit(dir, /* classLoader = */ null,
                /* parentClasspathElement = */ null, /* orderWithinParentClasspathElement = */ 0,
                /* packageRootPrefix = */ "", ClassLoaderHandlerRegistry.NO_PACKAGE_ROOT_PREFIXES);
        return new ClasspathElementDir(workUnit, /* nestedJarHandler = */ null, new ScanSpec());
    }

    /** Create an empty file, and the directories above it. */
    private static void createFile(final Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.createFile(file);
    }

    /**
     * A package directory that is missing is looked up only once during the scan, but a lookup after the scan
     * still reads the filesystem.
     */
    @Test
    void missingPackageDirIsLookedUpOncePerScan(@TempDir final Path tempDir) throws IOException {
        final ClasspathElement classpathElement = classpathElementDir(tempDir);
        assertThat(classpathElement.getResourceDuringScan("lib/A.class")).isNull();

        // The package directory and a classfile in it are created after the package directory was found missing.
        // The classpath element still answers from what it found, so it did not look the file up.
        createFile(tempDir.resolve("lib/B.class"));
        assertThat(classpathElement.getResourceDuringScan("lib/B.class")).isNull();

        // A lookup by path after the scan, e.g. ScanResult#getResourcesWithPathIgnoringAccept, sees the file
        assertThat(classpathElement.getResource("lib/B.class")).isNotNull();
    }

    /** A classfile is still found in a package directory that exists, and in the root directory. */
    @Test
    void classfilesInExistingDirsAreFound(@TempDir final Path tempDir) throws IOException {
        createFile(tempDir.resolve("lib/A.class"));
        createFile(tempDir.resolve("Top.class"));
        final ClasspathElement classpathElement = classpathElementDir(tempDir);
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
