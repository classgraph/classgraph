package io.github.classgraph.vfs.internal.slice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.utils.VersionFinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link FileMapping}. */
// #939
public class FileMappingTest {
    /**
     * A mapped file can be read until it is unmapped, and reading it afterwards throws
     * {@link IllegalStateException} rather than reading memory that is no longer mapped. Only from JDK 22, where
     * the file is mapped through an arena: below that, the read would take a SIGSEGV.
     *
     * @param tempDir
     *            a temporary directory to write the file to be mapped into.
     * @throws IOException
     *             if the file could not be written or opened.
     */
    @Test
    public void aMappedFileCanBeReadUntilItIsUnmapped(@TempDir final Path tempDir) throws IOException {
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION >= 22, "below JDK 22 a read after the unmap is a SIGSEGV");
        final var file = Files.write(tempDir.resolve("mapped.bin"), new byte[] { 1, 2, 3, 4 });
        try (var fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            final var mapping = FileMapping.map(fileChannel, 4L, file, /* log = */ null);
            assertThat(mapping).isNotNull();
            final var buf = mapping.byteBuffer;
            assertThat(buf.isDirect()).isTrue();
            assertThat(buf.capacity()).isEqualTo(4);
            assertThat(buf.get(2)).isEqualTo((byte) 3);
            assertThat(mapping.unmap()).isTrue();
            assertThatThrownBy(() -> buf.get(0)).isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * A file that cannot be mapped because the mapping fails with an {@link IOException} -- here because the
     * mapping would run past the end of a file opened read-only -- is not mapped, and the log says why.
     *
     * @param tempDir
     *            a temporary directory to write the file to be mapped into.
     * @throws IOException
     *             if the file could not be written or opened.
     */
    @Test
    public void aMappingThatFailsWithAnIOExceptionIsNotMadeAndTheReasonIsLogged(@TempDir final Path tempDir)
            throws IOException {
        final var file = Files.write(tempDir.resolve("short.bin"), new byte[] { 1, 2, 3, 4 });
        try (var fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            final var log = new LogNode();
            assertThat(FileMapping.map(fileChannel, 1024L, file, log)).isNull();
            assertThat(log.toString()).contains("cannot be memory mapped");
        }
    }

    /**
     * A file channel that does not support memory mapping, as the channel of a file in a filesystem other than the
     * default one usually does not, is not mapped, and the log says why.
     *
     * @param tempDir
     *            a temporary directory to create the zipfile in.
     * @throws IOException
     *             if the zipfile could not be written or read.
     */
    @Test
    public void aChannelThatCannotBeMappedIsNotMappedAndTheReasonIsLogged(@TempDir final Path tempDir)
            throws IOException {
        try (var zipFileSystem = FileSystems.newFileSystem(tempDir.resolve("test.zip"), Map.of("create", "true"))) {
            final var entry = Files.write(zipFileSystem.getPath("entry.bin"), new byte[] { 1, 2, 3, 4 });
            try (var fileChannel = FileChannel.open(entry, StandardOpenOption.READ)) {
                final var log = new LogNode();
                assertThat(FileMapping.map(fileChannel, 4L, entry, log)).isNull();
                assertThat(log.toString()).contains("cannot be memory mapped");
            }
        }
    }
}
