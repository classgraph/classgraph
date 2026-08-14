package io.github.classgraph.vfs.internal.zip;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.vfs.internal.spec.VfsScanSpec;

/**
 * Tests {@link LogicalZipFile#nestedJarPaths(String[])}, which finds the jarfiles stored in a jarfile's library
 * directories. These have to be found even though they lie outside the jarfile's package root, since an executable
 * jar stores its own classes under a package root and the jarfiles it depends on alongside it.
 */
public class NestedJarPathsTest {
    /** The library directories to look in, standing in for the classpath library's list of them. */
    private static final String[] LIB_DIR_PREFIXES = { "BOOT-INF/lib/", "lib/" };

    /**
     * Write a jar laid out like a Spring Boot executable jar, with a package root and library directories alongside
     * it, then read it and hand it to a consumer.
     *
     * @param tempDir
     *            a temporary directory to write the jar into.
     * @param entryNames
     *            the names of the entries to write into the jar, after the manifest.
     * @param consumer
     *            given the jarfile that was read.
     * @throws Exception
     *             if the jar could not be written or read.
     */
    private static void withTestJar(final File tempDir, final String[] entryNames,
            final Consumer<LogicalZipFile> consumer) throws Exception {
        final var jarFile = new File(tempDir, "app.jar");
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zipOut.write(("Manifest-Version: 1.0\r\n" //
                    + "Spring-Boot-Classes: BOOT-INF/classes/\r\n" //
                    + "Spring-Boot-Lib: BOOT-INF/lib/\r\n" //
                    + "\r\n").getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
            for (final String entryName : entryNames) {
                zipOut.putNextEntry(new ZipEntry(entryName));
                zipOut.write(entryName.getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
            }
        }

        final var nestedJarHandler = new NestedJarHandler(new VfsScanSpec(), new InterruptionChecker());
        try {
            consumer.accept(nestedJarHandler.nestedPathToLogicalZipFileAndPackageRootMap()
                    .get(jarFile.getPath(), /* log = */ null).getKey());
        } finally {
            // The jarfile must not be left open, otherwise the temporary dir cannot be deleted on Windows
            nestedJarHandler.close(/* log = */ null);
        }
    }

    /**
     * A jarfile in a library directory is found, even though it lies outside the package root that the jarfile's
     * own classes are stored under.
     */
    @Test
    public void jarsInLibraryDirectoriesAreFound(@TempDir final File tempDir) throws Exception {
        withTestJar(tempDir,
                new String[] { "BOOT-INF/classes/com/xyz/App.class", "BOOT-INF/lib/mylib.jar",
                        "BOOT-INF/lib/other.jar", "lib/toplevel.jar" },
                logicalZipFile -> assertThat(logicalZipFile.nestedJarPaths(LIB_DIR_PREFIXES)).containsExactly(
                        logicalZipFile.getPath() + "!/BOOT-INF/lib/mylib.jar",
                        logicalZipFile.getPath() + "!/BOOT-INF/lib/other.jar",
                        logicalZipFile.getPath() + "!/lib/toplevel.jar"));
    }

    /** Only jarfiles are returned, and only the jarfiles in the library directories. */
    @Test
    public void otherEntriesAreNotReturned(@TempDir final File tempDir) throws Exception {
        withTestJar(tempDir,
                new String[] { "BOOT-INF/lib/notajar.txt", "BOOT-INF/classes/embedded.jar", "elsewhere/stray.jar",
                        "BOOT-INF/lib/mylib.jar" },
                logicalZipFile -> assertThat(logicalZipFile.nestedJarPaths(LIB_DIR_PREFIXES))
                        .containsExactly(logicalZipFile.getPath() + "!/BOOT-INF/lib/mylib.jar"));
    }

    /** A jarfile with no library directories yields nothing. */
    @Test
    public void aJarWithNoLibraryDirectoriesYieldsNothing(@TempDir final File tempDir) throws Exception {
        withTestJar(tempDir, new String[] { "BOOT-INF/classes/com/xyz/App.class" },
                logicalZipFile -> assertThat(logicalZipFile.nestedJarPaths(LIB_DIR_PREFIXES)).isEmpty());
    }
}
