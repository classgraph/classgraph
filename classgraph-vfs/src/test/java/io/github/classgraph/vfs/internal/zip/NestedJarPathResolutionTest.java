package io.github.classgraph.vfs.internal.zip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.vfs.VfsSpec;
import io.github.classgraph.vfs.internal.VfsSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests resolving a classpath element path that names something inside a jarfile, which is either a nested jarfile
 * or a package root within the jarfile.
 */
public class NestedJarPathResolutionTest {
    /** The name of the entry holding the nested jarfile. */
    private static final String INNER_JAR_NAME = "lib/inner.jar";

    /** The directory within the outer jarfile that holds classes. */
    private static final String PACKAGE_ROOT = "BOOT-INF/classes";

    /** The resources owned by the scan, closed when the test ends. */
    private final VfsSession session = new VfsSession(new VfsSpec(), new InterruptionChecker());

    /** The handler under test. */
    private final NestedJarHandler nestedJarHandler = new NestedJarHandler(session);

    /** The path of the outer jarfile. */
    private String outerJarPath;

    /**
     * Write a jarfile holding a nested jarfile and a class under a package root.
     *
     * @param tempDir
     *            a temporary directory to build the jarfiles in
     * @throws IOException
     *             if the jarfiles could not be written
     */
    @BeforeEach
    public void buildJars(@TempDir final Path tempDir) throws IOException {
        final var innerJar = tempDir.resolve("inner.jar");
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(innerJar))) {
            zipOut.putNextEntry(new ZipEntry("testpkg/Inner.class"));
            zipOut.write("inner".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        final var outerJar = tempDir.resolve("outer.jar");
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(outerJar))) {
            // Stored, not deflated, so that the nested jarfile can be read as a slice of the outer one
            final var innerJarBytes = Files.readAllBytes(innerJar);
            final var innerJarEntry = new ZipEntry(INNER_JAR_NAME);
            innerJarEntry.setMethod(ZipEntry.STORED);
            innerJarEntry.setSize(innerJarBytes.length);
            innerJarEntry.setCompressedSize(innerJarBytes.length);
            final var crc = new java.util.zip.CRC32();
            crc.update(innerJarBytes);
            innerJarEntry.setCrc(crc.getValue());
            zipOut.putNextEntry(innerJarEntry);
            zipOut.write(innerJarBytes);
            zipOut.closeEntry();

            zipOut.putNextEntry(new ZipEntry(PACKAGE_ROOT + "/testpkg/Outer.class"));
            zipOut.write("outer".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        outerJarPath = outerJar.toString();
    }

    /** The jarfiles must not be left open, otherwise the temporary directory cannot be deleted on Windows. */
    @AfterEach
    public void closeSession() {
        session.close(/* log = */ null);
    }

    /**
     * Resolve a path.
     *
     * @param path
     *            the path to resolve
     * @return the zipfile the path names, paired with the package root within it
     * @throws Exception
     *             if the path could not be resolved
     */
    private java.util.Map.Entry<LogicalZipFile, String> resolve(final String path) throws Exception {
        return nestedJarHandler.nestedPathToLogicalZipFileAndPackageRootMap().get(path, /* log = */ null);
    }

    /** A path naming a jarfile inside a jarfile opens the nested jarfile, with no package root within it. */
    @Test
    public void aPathNamingANestedJarfileOpensIt() throws Exception {
        final var resolved = resolve(outerJarPath + "!/" + INNER_JAR_NAME);

        assertThat(resolved.getValue()).isEmpty();
        assertThat(resolved.getKey().entries).extracting(entry -> entry.entryName)
                .containsExactly("testpkg/Inner.class");
    }

    /**
     * A trailing slash after the name of a nested jarfile is a mistake rather than a statement that the jarfile is
     * a directory, so the nested jarfile is still opened.
     */
    @Test
    public void aTrailingSlashDoesNotStopANestedJarfileFromBeingOpened() throws Exception {
        final var resolved = resolve(outerJarPath + "!/" + INNER_JAR_NAME + "/");

        assertThat(resolved.getValue()).isEmpty();
        assertThat(resolved.getKey().entries).extracting(entry -> entry.entryName)
                .containsExactly("testpkg/Inner.class");
    }

    /**
     * A path naming a directory inside a jarfile is a package root within that jarfile, whether or not it is
     * written with a trailing slash.
     */
    @Test
    public void aPathNamingADirectoryIsAPackageRoot() throws Exception {
        for (final var path : new String[] { outerJarPath + "!/" + PACKAGE_ROOT,
                outerJarPath + "!/" + PACKAGE_ROOT + "/" }) {
            final var resolved = resolve(path);

            assertThat(resolved.getValue()).isEqualTo(PACKAGE_ROOT);
            assertThat(resolved.getKey().getPath()).endsWith("outer.jar");
            assertThat(resolved.getKey().classpathRoots).contains(PACKAGE_ROOT);
        }
    }

    /**
     * A path naming an entry of the jarfile that is not itself a jarfile reports why the entry could not be opened
     * as a jarfile, with the reason chained so that it is reachable from the stack trace.
     */
    @Test
    public void anEntryThatIsNotAJarfileIsReportedWithTheReasonChained() {
        assertThatThrownBy(() -> resolve(outerJarPath + "!/" + PACKAGE_ROOT + "/testpkg/Outer.class")).cause()
                .isInstanceOf(IOException.class).hasMessageContaining("Could not open nested jar").cause()
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Zipfile too short to have a central directory");
    }

    /** A path that names neither an entry nor a directory of the jarfile is reported, naming the path. */
    @Test
    public void aPathThatNamesNothingInTheJarfileIsReported() {
        assertThatThrownBy(() -> resolve(outerJarPath + "!/no/such/path")).cause().isInstanceOf(IOException.class)
                .hasMessageContaining("Path no/such/path does not exist in jarfile");
    }
}
