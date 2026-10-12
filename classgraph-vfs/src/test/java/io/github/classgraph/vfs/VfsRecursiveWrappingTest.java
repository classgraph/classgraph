package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A filesystem view is a supported filesystem input, including another view of a view. */
public class VfsRecursiveWrappingTest {
    /** The resource text in every fixture. */
    private static final String CONTENT = "recursive wrapping";

    /**
     * Build a jar with one resource under a package root.
     *
     * @return the jar bytes.
     * @throws IOException
     *             if the fixture cannot be built.
     */
    private static byte[] jarBytes() throws IOException {
        final var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("pkg/file.txt"));
            zip.write(CONTENT.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /**
     * Wrap three layers and verify reading, listing, metadata, and that the requested filesystem really backs each.
     *
     * @param source
     *            the source directory path.
     * @param resource
     *            its root-relative resource name.
     * @throws IOException
     *             if wrapping or reading fails.
     */
    private static void assertRepeatedWrapping(final Path source, final String resource) throws IOException {
        final var wrappers = new ArrayList<Vfs>();
        var path = source;
        try {
            for (var level = 0; level < 3; level++) {
                final var wrapper = new Vfs();
                wrappers.add(wrapper);
                final var root = wrapper.open(path);
                assertThat(wrapper.open(path)).isSameAs(root);
                assertThat(root).isInstanceOf(DirRoot.class);
                assertThat(((DirRoot) root).getNioPath().getFileSystem()).isSameAs(path.getFileSystem());
                assertThat(root.getEntries()).extracting(VfsEntry::getPathFromRoot).contains(resource);
                final var view = root.asFileSystem();
                assertThat(Files.readString(view.getPath("/" + resource))).isEqualTo(CONTENT);
                assertThat(Files.size(view.getPath("/" + resource))).isEqualTo(CONTENT.length());
                path = view.getPath("/");
            }
        } finally {
            for (var i = wrappers.size() - 1; i >= 0; i--) {
                wrappers.get(i).close();
            }
        }
        assertThat(source.getFileSystem().isOpen()).isTrue();
        assertThat(Files.readString(source.resolve(resource))).isEqualTo(CONTENT);
    }

    /**
     * Native, archive, package-root, and RAM views can all be wrapped recursively.
     *
     * @param dir
     *            the fixture directory.
     * @throws IOException
     *             if fixture creation or reading fails.
     */
    @Test
    public void differentRootKindsCanBeWrappedRepeatedly(@TempDir final Path dir) throws IOException {
        Files.createDirectory(dir.resolve("pkg"));
        Files.writeString(dir.resolve("pkg/file.txt"), CONTENT);
        final var bytes = jarBytes();
        final var jar = dir.resolve("test.jar");
        Files.write(jar, bytes);
        try (var source = new Vfs()) {
            assertRepeatedWrapping(source.open(dir).asFileSystem().getPath("/"), "pkg/file.txt");
            assertRepeatedWrapping(source.open(jar).asFileSystem().getPath("/"), "pkg/file.txt");
            assertRepeatedWrapping(source.open(jar + "!/pkg").asFileSystem().getPath("/"), "file.txt");
            assertRepeatedWrapping(source.open(bytes, "memory.jar").asFileSystem().getPath("/"), "pkg/file.txt");
        }
    }

    /**
     * The same VFS can wrap its own views without reusing a root backed by a different view.
     *
     * @param dir
     *            the fixture directory.
     * @throws IOException
     *             if fixture creation or reading fails.
     */
    @Test
    public void oneVfsCanWrapItsOwnViews(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("file"), CONTENT);
        try (var vfs = new Vfs()) {
            var root = vfs.open(dir);
            for (var level = 0; level < 3; level++) {
                final var source = root.asFileSystem();
                final var wrapped = vfs.open(source.getPath("/"));
                assertThat(wrapped).isNotSameAs(root);
                assertThat(((DirRoot) wrapped).getNioPath().getFileSystem()).isSameAs(source);
                assertThat(Files.readString(wrapped.asFileSystem().getPath("/file"))).isEqualTo(CONTENT);
                root = wrapped;
            }
        }
    }

    /**
     * Distinct source-view lifetimes must not be conflated in the wrapper cache.
     *
     * @param dir
     *            the fixture directory.
     * @throws IOException
     *             if fixture creation or reading fails.
     */
    @Test
    public void sourceViewClosureDoesNotPoisonAnotherWrapper(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("file"), CONTENT);
        try (var source = new Vfs(); var wrapper = new Vfs()) {
            final var root = source.open(dir);
            final var firstView = root.asFileSystem();
            try (var secondView = new VfsFileSystem(root, null)) {
                final var first = wrapper.open(firstView.getPath("/"));
                final var second = wrapper.open(secondView.getPath("/"));
                assertThat(first).isNotSameAs(second);
                firstView.close();
                assertThatThrownBy(() -> Files.readString(first.asFileSystem().getPath("/file")))
                        .isInstanceOf(ClosedFileSystemException.class);
                assertThat(Files.readString(second.asFileSystem().getPath("/file"))).isEqualTo(CONTENT);
            }
        }
    }

    /**
     * Closing outer wrappers must not close a provider-owned inner filesystem.
     *
     * @param dir
     *            the fixture directory.
     * @throws IOException
     *             if fixture creation or reading fails.
     */
    @Test
    public void providerOwnedViewsCanBeWrappedWithoutTransferringOwnership(@TempDir final Path dir)
            throws IOException {
        Files.writeString(dir.resolve("file"), CONTENT);
        try (var owned = new VfsFileSystemProvider().newFileSystem(dir, Map.of())) {
            assertRepeatedWrapping(owned.getPath("/"), "file");
        }
    }

    /**
     * A container file and its virtual root directory must not collide merely because storage URIs match.
     *
     * @param dir
     *            the fixture directory.
     * @throws IOException
     *             if fixture creation or reading fails.
     */
    @Test
    public void archiveRootDirectoriesAndContainerFilesHaveDistinctCacheKeys(@TempDir final Path dir)
            throws IOException {
        final var jar = dir.resolve("test.jar");
        Files.write(jar, jarBytes());
        try (var vfs = new Vfs()) {
            final var nativeView = vfs.open(dir).asFileSystem();
            final var archive = vfs.open(jar);
            final var wrappedDirectory = vfs.open(archive.asFileSystem().getPath("/"));
            assertThat(wrappedDirectory).isInstanceOf(DirRoot.class);
            final var wrappedArchive = vfs.open(nativeView.getPath("/test.jar"));
            assertThat(wrappedArchive).isInstanceOf(ArchiveRoot.class);
            assertThat(Files.readString(wrappedArchive.asFileSystem().getPath("/pkg/file.txt"))).isEqualTo(CONTENT);
        }
    }

    /** Distinct mounts of the same zip have independent lifetimes despite having identical path URIs. */
    @Test
    @SuppressWarnings("try")
    public void separateZipMountsDoNotShareWrappedRoots(@TempDir final Path dir) throws IOException {
        final var jar = dir.resolve("mounted.jar");
        Files.write(jar, jarBytes());
        try (var firstMount = FileSystems.newFileSystem(jar);
                var secondMount = FileSystems.newFileSystem(jar);
                var wrapper = new Vfs()) {
            final var firstPath = firstMount.getPath("/");
            final var secondPath = secondMount.getPath("/");
            assertThat(firstPath.toUri()).isEqualTo(secondPath.toUri());
            final var firstRoot = wrapper.open(firstPath);
            final var secondRoot = wrapper.open(secondPath);
            assertThat(firstRoot).isNotSameAs(secondRoot);
            firstMount.close();
            assertThatThrownBy(() -> Files.readString(firstRoot.asFileSystem().getPath("/pkg/file.txt")))
                    .isInstanceOf(ClosedFileSystemException.class);
            assertThat(Files.readString(secondRoot.asFileSystem().getPath("/pkg/file.txt"))).isEqualTo(CONTENT);
        }
    }
}
