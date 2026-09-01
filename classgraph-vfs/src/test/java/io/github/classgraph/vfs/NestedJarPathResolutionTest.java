package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests opening a path that names something inside a jarfile, which is either a nested jarfile or a package root
 * within the jarfile.
 */
public class NestedJarPathResolutionTest {
    /** The name of the entry holding the nested jarfile. */
    private static final String INNER_JAR_NAME = "lib/inner.jar";

    /** The directory within the outer jarfile that holds classes. */
    private static final String PACKAGE_ROOT = "BOOT-INF/classes";

    /** The virtual filesystem under test, closed when the test ends. */
    private final Vfs vfs = new Vfs();

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
    public void closeVfs() {
        vfs.close();
    }

    /** A path naming a jarfile inside a jarfile opens the nested jarfile, with no package root within it. */
    @Test
    public void aPathNamingANestedJarfileOpensIt() throws Exception {
        final var root = vfs.open(outerJarPath + "!/" + INNER_JAR_NAME);

        assertThat(root.getPackageRoot()).isEmpty();
        assertThat(root.getEntries()).extracting(VfsEntry::getPathFromRoot).containsExactly("testpkg/Inner.class");
    }

    /**
     * A trailing slash after the name of a nested jarfile is a mistake rather than a statement that the jarfile is
     * a directory, so the nested jarfile is still opened.
     */
    @Test
    public void aTrailingSlashDoesNotStopANestedJarfileFromBeingOpened() throws Exception {
        final var root = vfs.open(outerJarPath + "!/" + INNER_JAR_NAME + "/");

        assertThat(root.getPackageRoot()).isEmpty();
        assertThat(root.getEntries()).extracting(VfsEntry::getPathFromRoot).containsExactly("testpkg/Inner.class");
    }

    /**
     * A path naming a directory inside a jarfile is a package root within that jarfile, whether or not it is
     * written with a trailing slash.
     */
    @Test
    public void aPathNamingADirectoryIsAPackageRoot() throws Exception {
        for (final var path : new String[] { outerJarPath + "!/" + PACKAGE_ROOT,
                outerJarPath + "!/" + PACKAGE_ROOT + "/" }) {
            final var root = vfs.open(path);

            assertThat(root.getPackageRoot()).isEqualTo(PACKAGE_ROOT);
            assertThat(root.getPath()).endsWith("outer.jar");
            assertThat(root.getEntries()).extracting(VfsEntry::getPathFromRoot)
                    .containsExactly("testpkg/Outer.class");
        }
    }

    /**
     * A path naming an entry of the jarfile that is not itself a jarfile reports why the entry could not be opened
     * as a jarfile, with the reason chained so that it is reachable from the stack trace.
     */
    @Test
    public void anEntryThatIsNotAJarfileIsReportedWithTheReasonChained() {
        assertThatThrownBy(() -> vfs.open(outerJarPath + "!/" + PACKAGE_ROOT + "/testpkg/Outer.class"))
                .isInstanceOf(IOException.class).hasMessageContaining("Could not open").cause()
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Zipfile too short to have a central directory");
    }

    /** A path that names neither an entry nor a directory of the jarfile is reported, naming the path. */
    @Test
    public void aPathThatNamesNothingInTheJarfileIsReported() {
        assertThatThrownBy(() -> vfs.open(outerJarPath + "!/no/such/path")).isInstanceOf(IOException.class).cause()
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Path no/such/path does not exist in jarfile");
    }

    /**
     * The jarfile enclosing a nested one is opened as a root in its own right, cached under its own path, so
     * opening it separately gives back the same root, and it is the container of the package root view of it.
     */
    @Test
    public void theEnclosingJarfileIsARootInItsOwnRight() throws Exception {
        final var packageRootView = vfs.open(outerJarPath + "!/" + PACKAGE_ROOT);
        final var outerRoot = vfs.open(outerJarPath);

        assertThat(packageRootView.getContainerRoot()).isSameAs(outerRoot);
        assertThat(outerRoot.getPackageRoot()).isEmpty();
        assertThat(outerRoot.getEntries()).extracting(VfsEntry::getPathFromRoot).contains(INNER_JAR_NAME,
                PACKAGE_ROOT + "/testpkg/Outer.class");
        // The nested jarfile's own root is a separate root, but it is read out of the same enclosing jarfile
        assertThat(vfs.open(outerJarPath + "!/" + INNER_JAR_NAME)).isNotSameAs(outerRoot);
    }
}
