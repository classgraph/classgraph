package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * When scanning is extended to an external class, its classfile is looked up in every directory classpath element
 * that comes before the one that holds it. A directory that does not hold the class' package must not cost one
 * file lookup per class, so it looks up each package directory only once per scan. Whether a file was looked up
 * cannot be seen through the public API, so the classpath element is tested directly here. So is the progress
 * that scanning a directory reports, which the public API cannot see either.
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

    /** Once the scan has finished, the classpath element forgets which package directories it found missing. */
    @Test
    void classfileScanFinishedForgetsMissingPackageDirs(@TempDir final Path tempDir) throws IOException {
        final ClasspathElement classpathElement = classpathElementDir(tempDir);
        assertThat(classpathElement.getResourceDuringScan("lib/A.class")).isNull();
        createFile(tempDir.resolve("lib/B.class"));
        classpathElement.classfileScanFinished();
        assertThat(classpathElement.getResourceDuringScan("lib/B.class")).isNotNull();
    }

    /**
     * Scanning a directory classpath element reports progress for each file or subdirectory it lists, so that a
     * thread waiting for the scan of a large directory does not time out while the listing goes on.
     */
    @Test
    void scanningADirReportsProgressForEachEntry(@TempDir final Path tempDir) throws IOException {
        for (int i = 0; i < 5; i++) {
            createFile(tempDir.resolve("file" + i + ".txt"));
        }
        createFile(tempDir.resolve("sub/A.txt"));
        createFile(tempDir.resolve("sub/B.txt"));
        final AtomicInteger progressCount = new AtomicInteger();
        final NestedJarHandler nestedJarHandler = new NestedJarHandler(new ScanSpec(), new InterruptionChecker() {
            @Override
            public void progress() {
                progressCount.incrementAndGet();
            }
        }, new ReflectionUtils());
        try {
            final ClasspathEntryWorkUnit workUnit = new ClasspathEntryWorkUnit(tempDir, /* classLoader = */ null,
                    /* parentClasspathElement = */ null, /* orderWithinParentClasspathElement = */ 0,
                    /* packageRootPrefix = */ "", ClassLoaderHandlerRegistry.NO_PACKAGE_ROOT_PREFIXES);
            new ClasspathElementDir(workUnit, nestedJarHandler, new ScanSpec()).scanPaths(/* log = */ null);
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
        // Six entries in the directory (five files and sub), and two in sub
        assertThat(progressCount.get()).isGreaterThanOrEqualTo(8);
    }

    /**
     * A scan forgets which package directories each directory classpath element found missing once every
     * classfile has been scanned, so that what it found does not stay reachable from the {@link ScanResult}.
     */
    @Test
    void aScanForgetsMissingPackageDirs(@TempDir final Path tempDir) throws IOException {
        // p.A, in the first directory, extends q.B, in the second, so the scan looks for q/B.class in the first
        // directory, and finds that it has no directory q
        final Path srcDir = Files.createDirectories(tempDir.resolve("src"));
        final Path firstDir = Files.createDirectories(tempDir.resolve("first"));
        final Path secondDir = Files.createDirectories(tempDir.resolve("second"));
        final Path sourceA = Files.write(srcDir.resolve("A.java"),
                "package p; public class A extends q.B {}".getBytes(StandardCharsets.UTF_8));
        final Path sourceB = Files.write(srcDir.resolve("B.java"),
                "package q; public class B {}".getBytes(StandardCharsets.UTF_8));
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the JDK's compiler, which the test JVM has to be running on").isNotNull();
        assertThat(compiler.run(null, null, null, "-nowarn", "-d", firstDir.toString(), sourceA.toString(),
                sourceB.toString())).as("javac exit code").isZero();
        Files.move(firstDir.resolve("q"), secondDir.resolve("q"));

        try (ScanResult scanResult = new ClassGraph().enableClassInfo().enableExternalClasses().acceptPackages("p")
                .overrideClasspath(firstDir.toString(), secondDir.toString()).scan()) {
            final ClassInfo classInfoA = scanResult.getClassInfo("p.A");
            assertThat(classInfoA.getSuperclass().getName()).isEqualTo("q.B");
            assertThat(classInfoA.getSuperclass().isExternalClass()).isTrue();
            final ClasspathElement classpathElement = classInfoA.classpathElement;
            assertThat(classpathElement).isInstanceOf(ClasspathElementDir.class);

            // A classfile in a package directory created after the scan is found, so the classpath element no
            // longer remembers that the directory was missing
            createFile(firstDir.resolve("q/C.class"));
            assertThat(classpathElement.getResourceDuringScan("q/C.class")).isNotNull();
        }
    }
}
