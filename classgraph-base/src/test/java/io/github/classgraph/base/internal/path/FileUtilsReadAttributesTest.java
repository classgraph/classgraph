package io.github.classgraph.base.internal.path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link FileUtils#readAttributes(Path)}, and the {@link java.nio.file.attribute.BasicFileAttributes}
 * implementation it falls back to when the attributes of a {@link Path} cannot be read.
 */
public class FileUtilsReadAttributesTest {
    /** A path that cannot exist, so that reading its attributes fails and the fallback is used. */
    private static final Path NONEXISTENT_PATH = Path
            .of("this-path-does-not-exist-" + FileUtilsReadAttributesTest.class.getName());

    /** The attributes of a real file are read through the {@code java.nio.file} API. */
    @Test
    public void attributesOfARealFileAreRead(@TempDir final Path tempDir) throws IOException {
        final var file = Files.write(tempDir.resolve("file.txt"), new byte[] { 1, 2, 3 });
        final var attributes = FileUtils.readAttributes(file);
        assertThat(attributes.isRegularFile()).isTrue();
        assertThat(attributes.isDirectory()).isFalse();
        assertThat(attributes.size()).isEqualTo(3);

        final var dirAttributes = FileUtils.readAttributes(tempDir);
        assertThat(dirAttributes.isDirectory()).isTrue();
        assertThat(dirAttributes.isRegularFile()).isFalse();
    }

    /**
     * When the attributes cannot be read, the accessors that the {@link java.io.File} API can answer are answered
     * from it, rather than the whole call failing.
     */
    @Test
    public void fallbackAnswersWhatTheFileApiCanAnswer() {
        final var attributes = FileUtils.readAttributes(NONEXISTENT_PATH);
        assertThat(attributes.isRegularFile()).isFalse();
        assertThat(attributes.isDirectory()).isFalse();
        assertThat(attributes.isSymbolicLink()).isFalse();
        // Neither a file nor a directory
        assertThat(attributes.isOther()).isTrue();
        assertThat(attributes.size()).isZero();
        assertThat(attributes.lastModifiedTime()).isEqualTo(FileTime.fromMillis(0));
        assertThat(attributes.creationTime()).isEqualTo(FileTime.fromMillis(0));
    }

    /**
     * A {@link Path} of a filesystem other than the default one cannot be converted to a {@link java.io.File} at
     * all, so the fallback answers from the {@code java.nio.file} API instead of letting the
     * {@link UnsupportedOperationException} that {@link Path#toFile()} throws escape.
     *
     * @param tempDir
     *            a temporary directory to create the zipfile in.
     * @throws IOException
     *             if the zipfile could not be created.
     */
    @Test
    public void fallbackDoesNotRequireAPathThatCanBecomeAFile(@TempDir final Path tempDir) throws IOException {
        final var zipFile = tempDir.resolve("archive.zip");
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zipOut.putNextEntry(new ZipEntry("entry.txt"));
            zipOut.write(new byte[] { 1, 2, 3 });
        }
        try (var zipFileSystem = FileSystems.newFileSystem(zipFile, (ClassLoader) null)) {
            // Reading the attributes of an entry that is not in the zipfile fails, so the fallback is used
            final var missingEntry = zipFileSystem.getPath("/no-such-entry.txt");
            assertThatThrownBy(missingEntry::toFile).isInstanceOf(UnsupportedOperationException.class);

            final var attributes = FileUtils.readAttributes(missingEntry);
            assertThat(attributes.size()).isZero();
            assertThat(attributes.lastModifiedTime()).isEqualTo(FileTime.fromMillis(0));
        }
    }

    /** The fallback refuses the two accessors that the {@link java.io.File} API cannot answer. */
    @Test
    public void fallbackRefusesWhatTheFileApiCannotAnswer() {
        final var attributes = FileUtils.readAttributes(NONEXISTENT_PATH);
        assertThatThrownBy(attributes::lastAccessTime).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(attributes::fileKey).isInstanceOf(UnsupportedOperationException.class);
    }
}
