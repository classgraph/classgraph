package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

/**
 * A {@link Path} in a filesystem whose URL scheme has been denied is refused by {@link Vfs#open(Path)}, as the same
 * directory or jarfile is refused when it is named by its URI.
 */
public class DeniedSchemePathTest {
    /** The path of the entry written into every directory and jarfile under test. */
    private static final String ENTRY_PATH = "com/xyz/widget.txt";

    /** The content of that entry. */
    private static final String ENTRY_CONTENT = "denied-scheme-path-test";

    /**
     * Write {@link #ENTRY_PATH} into a directory.
     *
     * @param dir
     *            the directory.
     * @return the directory.
     * @throws IOException
     *             if the entry could not be written.
     */
    private static Path writeDir(final Path dir) throws IOException {
        Files.createDirectories(dir.resolve("com/xyz"));
        Files.writeString(dir.resolve(ENTRY_PATH), ENTRY_CONTENT);
        return dir;
    }

    /**
     * Write a jarfile holding {@link #ENTRY_PATH}.
     *
     * @param jarFile
     *            the jarfile.
     * @return the jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static Path writeJar(final Path jarFile) throws IOException {
        Files.createDirectories(jarFile.getParent());
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(jarFile))) {
            zipOut.putNextEntry(new ZipEntry(ENTRY_PATH));
            zipOut.write(ENTRY_CONTENT.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        return jarFile;
    }

    /**
     * Read {@link #ENTRY_PATH} from a root.
     *
     * @param root
     *            the root.
     * @return the content of the entry.
     * @throws IOException
     *             if the entry could not be read.
     */
    private static String entryContent(final VfsRoot root) throws IOException {
        return Objects.requireNonNull(root.getEntry(ENTRY_PATH)).loadAsString();
    }

    /**
     * A directory, a jarfile, and a directory within a zipfile mounted on that jarfile, all in a filesystem whose
     * scheme has been denied, are refused when given as a {@link Path}, and are opened when the scheme is allowed.
     * A path refused once is still refused when it is named by its URI afterwards.
     *
     * @throws IOException
     *             if the test files could not be written or read.
     */
    @Test
    public void aPathInAFilesystemWhoseSchemeIsDeniedIsRefused() throws IOException {
        try (var fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            final var dir = writeDir(fileSystem.getPath("/classes"));
            final var jar = writeJar(fileSystem.getPath("/lib/widget.jar"));
            try (var zipFileSystem = FileSystems.newFileSystem(jar)) {
                final var dirInJar = zipFileSystem.getPath("/com");
                try (var vfs = new Vfs()) {
                    assertThat(entryContent(vfs.open(dir))).isEqualTo(ENTRY_CONTENT);
                    assertThat(entryContent(vfs.open(jar))).isEqualTo(ENTRY_CONTENT);
                    assertThat(vfs.open(dirInJar).getEntry("xyz/widget.txt")).isNotNull();
                }
                try (var vfs = new Vfs(new VfsSpec().denyURLScheme("jimfs"))) {
                    for (final var path : new Path[] { dir, jar, dirInJar }) {
                        assertThatThrownBy(() -> vfs.open(path)).as("%s", path.toUri())
                                .isInstanceOf(IOException.class)
                                .hasMessageContaining("The URL scheme \"jimfs:\" has been denied");
                        assertThatThrownBy(() -> vfs.open(path.toUri().toString())).as("%s", path.toUri())
                                .isInstanceOf(IOException.class)
                                .hasMessageContaining("The URL scheme \"jimfs:\" has been denied");
                    }
                }
            }
        }
    }

    /**
     * Denying {@code jar:} or {@code file:} has no effect on a {@link Path} either: a directory within a zipfile
     * mounted on a jarfile in the local filesystem, whose URI is a {@code "jar:file:"} URI, is still opened.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be written or read.
     */
    @Test
    public void aPathInAZipfileOnALocalJarfileIsOpenedWithJarAndFileDenied(@TempDir final Path tempDir)
            throws IOException {
        final var jar = writeJar(tempDir.resolve("widget.jar"));
        try (var zipFileSystem = FileSystems.newFileSystem(jar);
                var vfs = new Vfs(new VfsSpec().denyURLScheme("jar").denyURLScheme("file"))) {
            assertThat(vfs.open(zipFileSystem.getPath("/com")).getEntry("xyz/widget.txt")).isNotNull();
        }
    }
}
