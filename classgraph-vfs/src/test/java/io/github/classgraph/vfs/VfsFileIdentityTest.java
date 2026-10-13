package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** File identity is not merely normalized path spelling or view identity. */
public class VfsFileIdentityTest {
    /**
     * Archive identity uses its opened namespace, not just a possibly reused name or URI.
     *
     * @throws IOException
     *             if fixture creation or lookup fails.
     */
    @Test
    public void archivesUseRootAndEntryIdentityAcrossViews() throws IOException {
        final var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("payload"));
            zip.write(7);
            zip.closeEntry();
        }
        final var bytes = buffer.toByteArray();
        try (var firstVfs = new Vfs(); var secondVfs = new Vfs()) {
            final var root = firstVfs.open(bytes, "same.jar");
            final var first = root.asFileSystem();
            final var independent = secondVfs.open(bytes, "same.jar").asFileSystem();
            try (var another = new VfsFileSystem(root, null)) {
                assertThat(Files.isSameFile(first.getPath("/payload"), another.getPath("/./payload"))).isTrue();
                assertThat(Files.isSameFile(first.getPath("/payload"), independent.getPath("/payload"))).isFalse();
                assertThatThrownBy(() -> Files.isSameFile(first.getPath("/missing"), another.getPath("/missing")))
                        .isInstanceOf(NoSuchFileException.class);
            }
        }
    }

    /**
     * Unequal aliases require existence, different native views can identify the same file, and both must be open.
     *
     * @param dir
     *            the fixture directory.
     * @throws IOException
     *             if fixture creation or lookup fails.
     */
    @Test
    public void identityChecksExistenceAndBothViewLifetimes(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("file"), "content");
        try (var firstVfs = new Vfs(); var secondVfs = new Vfs()) {
            final var first = firstVfs.open(dir).asFileSystem();
            final var second = secondVfs.open(dir).asFileSystem();
            final var missing = first.getPath("/missing");
            assertThat(Files.isSameFile(missing, missing)).isTrue();
            assertThatThrownBy(() -> Files.isSameFile(missing, first.getPath("/./missing")))
                    .isInstanceOf(NoSuchFileException.class);
            assertThat(Files.isSameFile(first.getPath("/file"), second.getPath("/file"))).isTrue();
            assertThat(Files.isSameFile(first.getPath("/file"), first.getPath("/./file"))).isTrue();
            second.close();
            assertThatThrownBy(() -> Files.isSameFile(first.getPath("/file"), second.getPath("/file")))
                    .isInstanceOf(ClosedFileSystemException.class);
        }
    }

    /**
     * Native symbolic-link aliases refer to the same underlying file.
     *
     * @param dir
     *            the fixture directory.
     * @throws IOException
     *             if fixture creation or lookup fails.
     */
    @Test
    public void nativeSymlinkAliasesHaveTheSameIdentity(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("file"), "content");
        try {
            Files.createSymbolicLink(dir.resolve("alias"), dir.resolve("file"));
        } catch (final IOException | UnsupportedOperationException e) {
            assumeTrue(false, "Symbolic links unavailable: " + e);
        }
        try (var vfs = new Vfs()) {
            final var view = vfs.open(dir).asFileSystem();
            assertThat(Files.isSameFile(view.getPath("/file"), view.getPath("/alias"))).isTrue();
        }
    }

    /**
     * Native hard-link aliases refer to the same underlying file.
     *
     * @param dir
     *            the fixture directory.
     * @throws IOException
     *             if fixture creation or lookup fails.
     */
    @Test
    public void nativeHardLinkAliasesHaveTheSameIdentity(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("file"), "content");
        try {
            Files.createLink(dir.resolve("alias"), dir.resolve("file"));
        } catch (final IOException | UnsupportedOperationException e) {
            assumeTrue(false, "Hard links unavailable: " + e);
        }
        try (var vfs = new Vfs()) {
            final var view = vfs.open(dir).asFileSystem();
            assertThat(Files.isSameFile(view.getPath("/file"), view.getPath("/alias"))).isTrue();
        }
    }
}
