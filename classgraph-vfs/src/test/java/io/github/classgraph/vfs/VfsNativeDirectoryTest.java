package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.FileVisitOption;
import java.nio.file.FileSystemLoopException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Native filesystem views preserve live directory and symbolic-link semantics. */
public class VfsNativeDirectoryTest {
    /**
     * Native empty directories and subsequent additions and removals remain visible even after a root listing.
     *
     * @param dir
     *            the fixture directory.
     * @throws IOException
     *             if fixture creation or lookup fails.
     */
    @Test
    public void directoryViewsSeeEmptyDirectoriesAndChanges(@TempDir final Path dir) throws IOException {
        Files.createDirectories(dir.resolve("empty/nested"));
        try (var vfs = new Vfs()) {
            final var root = vfs.open(dir);
            assertThat(root.getEntries()).isEmpty();
            final var view = root.asFileSystem();
            assertThat(Files.isDirectory(view.getPath("/empty/nested"))).isTrue();
            try (var paths = Files.walk(view.getPath("/"))) {
                assertThat(paths.map(Path::toString)).containsExactly("/", "/empty", "/empty/nested");
            }
            try (var children = Files.newDirectoryStream(view.getPath("/empty/nested"))) {
                assertThat(children).isEmpty();
            }
            Files.writeString(dir.resolve("added"), "new");
            try (var children = Files.newDirectoryStream(view.getPath("/"))) {
                assertThat(children).extracting(Path::toString).containsExactly("/added", "/empty");
            }
            assertThat(Files.readString(view.getPath("/added"))).isEqualTo("new");
            Files.delete(dir.resolve("added"));
            assertThat(Files.exists(view.getPath("/added"))).isFalse();
            Files.delete(dir.resolve("empty/nested"));
            assertThat(Files.exists(view.getPath("/empty/nested"))).isFalse();
            view.close();
            Files.createDirectory(dir.resolve("later"));
            try (var replacement = root.asFileSystem();
                    var children = Files.newDirectoryStream(replacement.getPath("/"))) {
                assertThat(children).extracting(Path::toString).containsExactly("/empty", "/later");
            }
        }
    }

    /**
     * Default walks do not follow links, and following a native link cycle reports a loop rather than recursing.
     *
     * @param dir
     *            the fixture directory.
     * @throws IOException
     *             if fixture creation or lookup fails.
     */
    @Test
    public void symbolicLinkWalksHonorLinkOptions(@TempDir final Path dir) throws IOException {
        Files.createDirectory(dir.resolve("child"));
        Files.writeString(dir.resolve("child/file"), "content");
        try {
            Files.createSymbolicLink(dir.resolve("child/loop"), dir);
        } catch (final IOException | UnsupportedOperationException e) {
            assumeTrue(false, "Symbolic links unavailable: " + e);
        }
        try (var vfs = new Vfs()) {
            final var view = vfs.open(dir).asFileSystem();
            final var link = view.getPath("/child/loop");
            assertThat(Files.readAttributes(link, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .isSymbolicLink()).isTrue();
            assertThat(Files.getFileAttributeView(link, java.nio.file.attribute.BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes().isSymbolicLink()).isTrue();
            assertThat(Files.isDirectory(link)).isTrue();
            try (var paths = Files.walk(view.getPath("/"))) {
                assertThat(paths.map(Path::toString)).containsExactlyInAnyOrder("/", "/child", "/child/file",
                        "/child/loop");
            }
            try (var paths = Files.walk(view.getPath("/"), FileVisitOption.FOLLOW_LINKS)) {
                assertThatThrownBy(paths::toList).isInstanceOf(UncheckedIOException.class)
                        .hasCauseInstanceOf(FileSystemLoopException.class);
            }
        }
    }
}
