package nonapi.io.github.classgraph.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link FileUtils#readAttributes(Path)}, and the {@link BasicFileAttributes} implementation it falls back to
 * when the attributes of a {@link Path} cannot be read.
 */
public class FileUtilsReadAttributesTest {
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
        final Path zipFile = tempDir.resolve("archive.zip");
        try (OutputStream out = Files.newOutputStream(zipFile); ZipOutputStream zipOut = new ZipOutputStream(out)) {
            zipOut.putNextEntry(new ZipEntry("entry.txt"));
            zipOut.write(new byte[] { 1, 2, 3 });
        }
        final Map<String, String> env = new HashMap<>();
        try (FileSystem zipFileSystem = FileSystems
                .newFileSystem(URI.create("jar:" + zipFile.toUri()), env)) {
            // Reading the attributes of an entry that is not in the zipfile fails, so the fallback is used
            final Path missingEntry = zipFileSystem.getPath("/no-such-entry.txt");
            assertThatThrownBy(missingEntry::toFile).isInstanceOf(UnsupportedOperationException.class);

            final BasicFileAttributes attributes = FileUtils.readAttributes(missingEntry);
            assertThat(attributes.size()).isZero();
            assertThat(attributes.lastModifiedTime()).isEqualTo(FileTime.fromMillis(0));
        }
    }
}
