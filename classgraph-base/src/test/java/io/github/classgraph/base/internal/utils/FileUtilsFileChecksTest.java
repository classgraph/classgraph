package io.github.classgraph.base.internal.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the checks that decide whether a classpath element is a readable file or a readable directory. A
 * {@link Path} in a filesystem other than the default one, such as a path inside a zipfile, cannot be converted to
 * a {@link java.io.File}, so each of these checks has a fallback for that case, and the fallback has to give the
 * same answers as the {@code File}-based check.
 */
public class FileUtilsFileChecksTest {
    /** The content of the test file. */
    private static final byte[] CONTENT = "content".getBytes(StandardCharsets.UTF_8);

    /**
     * A readable regular file is a file, and is not a directory.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the file could not be written
     */
    @Test
    public void aReadableFileIsAFileAndNotADirectory(@TempDir final Path tempDir) throws IOException {
        final var path = Files.write(tempDir.resolve("file.bin"), CONTENT);
        final var file = path.toFile();

        assertThat(FileUtils.canRead(file)).isTrue();
        assertThat(FileUtils.canRead(path)).isTrue();
        assertThat(FileUtils.canReadAndIsFile(file)).isTrue();
        assertThat(FileUtils.canReadAndIsFile(path)).isTrue();
        assertThat(FileUtils.isFile(path)).isTrue();
        assertThat(FileUtils.canReadAndIsDir(file)).isFalse();
        assertThat(FileUtils.canReadAndIsDir(path)).isFalse();
        assertThat(FileUtils.isDir(path)).isFalse();

        assertThatCode(() -> FileUtils.checkCanReadAndIsFile(file)).doesNotThrowAnyException();
        assertThatCode(() -> FileUtils.checkCanReadAndIsFile(path)).doesNotThrowAnyException();
        assertThatThrownBy(() -> FileUtils.checkCanReadAndIsDir(file)).isInstanceOf(IOException.class)
                .hasMessage("Not a directory: " + file);
    }

    /**
     * A readable directory is a directory, and is not a file.
     *
     * @param tempDir
     *            a temporary directory
     */
    @Test
    public void aReadableDirectoryIsADirectoryAndNotAFile(@TempDir final Path tempDir) {
        final var file = tempDir.toFile();

        assertThat(FileUtils.canRead(file)).isTrue();
        assertThat(FileUtils.canRead(tempDir)).isTrue();
        assertThat(FileUtils.canReadAndIsDir(file)).isTrue();
        assertThat(FileUtils.canReadAndIsDir(tempDir)).isTrue();
        assertThat(FileUtils.isDir(tempDir)).isTrue();
        assertThat(FileUtils.canReadAndIsFile(file)).isFalse();
        assertThat(FileUtils.canReadAndIsFile(tempDir)).isFalse();
        assertThat(FileUtils.isFile(tempDir)).isFalse();

        assertThatCode(() -> FileUtils.checkCanReadAndIsDir(file)).doesNotThrowAnyException();
        assertThatThrownBy(() -> FileUtils.checkCanReadAndIsFile(file)).isInstanceOf(IOException.class)
                .hasMessage("Not a regular file: " + file);
        assertThatThrownBy(() -> FileUtils.checkCanReadAndIsFile(tempDir)).isInstanceOf(IOException.class)
                .hasMessage("Not a regular file: " + file);
    }

    /**
     * A path that does not exist is neither a readable file nor a readable directory, and the check that reports
     * why names the path that could not be read.
     *
     * @param tempDir
     *            a temporary directory
     */
    @Test
    public void aPathThatDoesNotExistIsNeitherAFileNorADirectory(@TempDir final Path tempDir) {
        final var path = tempDir.resolve("does-not-exist");
        final var file = path.toFile();

        assertThat(FileUtils.canRead(file)).isFalse();
        assertThat(FileUtils.canRead(path)).isFalse();
        assertThat(FileUtils.canReadAndIsFile(file)).isFalse();
        assertThat(FileUtils.canReadAndIsFile(path)).isFalse();
        assertThat(FileUtils.isFile(path)).isFalse();
        assertThat(FileUtils.canReadAndIsDir(file)).isFalse();
        assertThat(FileUtils.canReadAndIsDir(path)).isFalse();
        assertThat(FileUtils.isDir(path)).isFalse();

        assertThatThrownBy(() -> FileUtils.checkCanReadAndIsFile(file)).isInstanceOf(FileNotFoundException.class)
                .hasMessage("File does not exist or cannot be read: " + file);
        assertThatThrownBy(() -> FileUtils.checkCanReadAndIsDir(file)).isInstanceOf(FileNotFoundException.class)
                .hasMessage("Directory does not exist or cannot be read: " + file);
    }

    /**
     * A path inside a zipfile cannot be converted to a {@link java.io.File}, so the checks fall back to reading the
     * attributes of the path through its own filesystem, and give the same answers as they do for a path on disk.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the zipfile could not be created or written to
     */
    @Test
    public void thePathsInsideAZipfileAreCheckedThroughTheirOwnFilesystem(@TempDir final Path tempDir)
            throws IOException {
        try (var zipFileSystem = FileSystems.newFileSystem(tempDir.resolve("test.zip"), Map.of("create", "true"))) {
            final var dirInZip = Files.createDirectory(zipFileSystem.getPath("/dir"));
            final var fileInZip = Files.write(zipFileSystem.getPath("/dir/file.bin"), CONTENT);
            final var missingInZip = zipFileSystem.getPath("/dir/does-not-exist");

            assertThat(FileUtils.canRead(fileInZip)).isTrue();
            assertThat(FileUtils.canReadAndIsFile(fileInZip)).isTrue();
            assertThat(FileUtils.isFile(fileInZip)).isTrue();
            assertThat(FileUtils.canReadAndIsDir(fileInZip)).isFalse();
            assertThat(FileUtils.isDir(fileInZip)).isFalse();
            assertThatCode(() -> FileUtils.checkCanReadAndIsFile(fileInZip)).doesNotThrowAnyException();

            assertThat(FileUtils.canRead(dirInZip)).isTrue();
            assertThat(FileUtils.canReadAndIsDir(dirInZip)).isTrue();
            assertThat(FileUtils.isDir(dirInZip)).isTrue();
            assertThat(FileUtils.canReadAndIsFile(dirInZip)).isFalse();
            assertThat(FileUtils.isFile(dirInZip)).isFalse();
            assertThatThrownBy(() -> FileUtils.checkCanReadAndIsFile(dirInZip)).isInstanceOf(IOException.class)
                    .hasMessage("Not a regular file: " + dirInZip);

            assertThat(FileUtils.canRead(missingInZip)).isFalse();
            assertThat(FileUtils.canReadAndIsFile(missingInZip)).isFalse();
            assertThat(FileUtils.isFile(missingInZip)).isFalse();
            assertThat(FileUtils.canReadAndIsDir(missingInZip)).isFalse();
            assertThat(FileUtils.isDir(missingInZip)).isFalse();
            assertThatThrownBy(() -> FileUtils.checkCanReadAndIsFile(missingInZip))
                    .isInstanceOf(FileNotFoundException.class)
                    .hasMessage("Path does not exist or cannot be read: " + missingInZip);
        }
    }
}
