package io.github.classgraph.base.internal.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the canonicalization of file and directory paths. Every part of ClassGraph has to give the same canonical
 * path for a file, otherwise the same file reached through two different paths is opened twice, and is reported
 * under two different paths.
 */
public class FileUtilsCanonicalizeTest {
    /** The content of the test file. */
    private static final byte[] CONTENT = "content".getBytes(StandardCharsets.UTF_8);

    /**
     * Create a symlink, or skip the test if the filesystem does not allow it (creating a symlink needs a privilege
     * that is not granted by default on Windows).
     *
     * @param link
     *            the symlink to create
     * @param target
     *            the target of the symlink
     * @return the symlink
     */
    private static Path createSymbolicLinkOrSkip(final Path link, final Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            abort("Symlinks cannot be created: " + e);
            return link;
        }
    }

    /**
     * A file reached through a symlink is canonicalized to the path of the file the symlink points at, whether it
     * is passed as a {@link java.io.File} or as a {@link Path}.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the file could not be written
     */
    @Test
    public void aSymlinkedFileIsCanonicalizedToItsTarget(@TempDir final Path tempDir) throws IOException {
        final var realFile = Files.write(tempDir.resolve("real.bin"), CONTENT);
        final var link = createSymbolicLinkOrSkip(tempDir.resolve("link.bin"), realFile);

        assertThat(FileUtils.canonicalize(link)).isEqualTo(realFile.toRealPath());
        assertThat(FileUtils.canonicalize(link.toFile())).isEqualTo(realFile.toRealPath().toFile());
    }

    /**
     * A file reached through a symlinked parent directory is canonicalized to the path of the file within the
     * directory the symlink points at.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the file could not be written
     */
    @Test
    public void aFileInASymlinkedDirIsCanonicalizedToItsTarget(@TempDir final Path tempDir) throws IOException {
        final var realDir = Files.createDirectory(tempDir.resolve("real"));
        final var realFile = Files.write(realDir.resolve("file.bin"), CONTENT);
        final var linkedDir = createSymbolicLinkOrSkip(tempDir.resolve("link"), realDir);

        assertThat(FileUtils.canonicalize(linkedDir.resolve("file.bin"))).isEqualTo(realFile.toRealPath());
        assertThat(FileUtils.canonicalize(linkedDir.toFile())).isEqualTo(realDir.toRealPath().toFile());
    }

    /**
     * A path that does not exist cannot be resolved by {@link Path#toRealPath(java.nio.file.LinkOption...)}, but is
     * still normalized, and the symlink in the part of the path that does exist is still resolved. (On Windows,
     * {@link java.io.File#getCanonicalPath()} does not resolve the symlinked parent directory here, which is why it
     * is not used as the fallback.)
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the directory could not be created
     */
    @Test
    public void aPathThatDoesNotExistIsStillNormalized(@TempDir final Path tempDir) throws IOException {
        final var realDir = Files.createDirectory(tempDir.resolve("real"));
        final var linkedDir = createSymbolicLinkOrSkip(tempDir.resolve("link"), realDir);
        final var missingViaLink = linkedDir.resolve("sub/../does-not-exist");

        assertThat(FileUtils.canonicalize(missingViaLink))
                .isEqualTo(realDir.toRealPath().resolve("does-not-exist"));
        assertThat(FileUtils.canonicalize(missingViaLink.toFile()))
                .isEqualTo(realDir.toRealPath().resolve("does-not-exist").toFile());
    }

    /**
     * A {@code ".."} that follows a symlinked directory names the parent of the directory the symlink points at,
     * not the parent of the symlink. The filesystem is the only thing that knows this, so the {@code ".."} must not
     * be collapsed before the part of the path that exists has been resolved.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the directory could not be created
     */
    @Test
    public void aParentSegmentAfterASymlinkIsResolvedByTheFilesystem(@TempDir final Path tempDir)
            throws IOException {
        final var realDir = Files.createDirectory(tempDir.resolve("real"));
        final var innerDir = Files.createDirectory(realDir.resolve("inner"));
        final var linkedDir = createSymbolicLinkOrSkip(tempDir.resolve("link"), innerDir);

        // "link/.." is "real", not tempDir -- which is what the system canonicalizer says too
        assertThat(FileUtils.canonicalize(linkedDir.resolve(".."))).isEqualTo(realDir.toRealPath());
        assertThat(FileUtils.canonicalize(linkedDir.resolve("../missing")))
                .isEqualTo(realDir.toRealPath().resolve("missing"));
        assertThat(FileUtils.canonicalize(new File(linkedDir.toFile(), "../missing")))
                .isEqualTo(realDir.toRealPath().resolve("missing").toFile());
    }

    /**
     * When several segments at the end of a path do not exist, the closest ancestor directory that does exist is
     * the one that is canonicalized, and all the missing segments are appended to it.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the directory could not be created
     */
    @Test
    public void everyMissingSegmentIsAppendedToTheClosestExistingAncestor(@TempDir final Path tempDir)
            throws IOException {
        final var realDir = Files.createDirectory(tempDir.resolve("real"));
        final var linkedDir = createSymbolicLinkOrSkip(tempDir.resolve("link"), realDir);

        assertThat(FileUtils.canonicalize(linkedDir.resolve("a/b/c")))
                .isEqualTo(realDir.toRealPath().resolve("a").resolve("b").resolve("c"));
    }

    /**
     * A path inside a zipfile is canonicalized through its own filesystem, whether or not it exists. It cannot be
     * converted to a {@link java.io.File}, so there is no fallback to {@link java.io.File#getCanonicalFile()}.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the zipfile could not be created or written to
     */
    @Test
    public void thePathsInsideAZipfileAreCanonicalizedThroughTheirOwnFilesystem(@TempDir final Path tempDir)
            throws IOException {
        try (var zipFileSystem = FileSystems.newFileSystem(tempDir.resolve("test.zip"), Map.of("create", "true"))) {
            Files.createDirectory(zipFileSystem.getPath("/dir"));
            final var fileInZip = Files.write(zipFileSystem.getPath("/dir/file.bin"), CONTENT);

            assertThat(FileUtils.canonicalize(zipFileSystem.getPath("/dir/../dir/file.bin"))).isEqualTo(fileInZip);
            assertThat(FileUtils.canonicalize(zipFileSystem.getPath("/dir/sub/../does-not-exist")))
                    .isEqualTo(zipFileSystem.getPath("/dir/does-not-exist"));
        }
    }
}
