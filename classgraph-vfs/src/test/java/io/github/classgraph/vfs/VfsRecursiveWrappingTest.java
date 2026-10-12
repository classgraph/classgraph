package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.LogNode;

/** A filesystem view is a supported filesystem input, including another view of a view. */
public class VfsRecursiveWrappingTest {
    /** A module whose resource metadata lookups can be counted independently of filesystem timing. */
    private static final class CountingModule extends ModuleReference {
        /** Number of lookups of the resource URI. */
        final AtomicInteger lookups = new AtomicInteger();

        /** Construct the synthetic module. */
        CountingModule() {
            super(ModuleDescriptor.newModule("recursive.module").build(), URI.create("memory:/recursive.module"));
        }

        @Override
        public ModuleReader open() {
            return new ModuleReader() {
                @Override
                public Optional<URI> find(final String name) {
                    lookups.incrementAndGet();
                    return name.equals("file.txt") ? Optional.of(URI.create("memory:/recursive.module/file.txt"))
                            : Optional.empty();
                }

                @Override
                public Optional<InputStream> open(final String name) {
                    return name.equals("file.txt")
                            ? Optional.of(new ByteArrayInputStream(CONTENT.getBytes(StandardCharsets.UTF_8)))
                            : Optional.empty();
                }

                @Override
                public Stream<String> list() {
                    return Stream.of("file.txt");
                }

                @Override
                public void close() {
                    // The fixture owns no external resources.
                }
            };
        }
    }

    /** An already case-sensitive VFS namespace must not be revalidated recursively at every wrapper level. */
    @Test
    public void deepModuleWrappersDoNotMultiplyResourceLookups() throws IOException {
        final var module = new CountingModule();
        final var wrappers = new ArrayList<Vfs>();
        try (var source = new Vfs()) {
            var path = source.open(module).asFileSystem().getPath("/");
            final var depth = 8;
            for (var level = 0; level < depth; level++) {
                final var wrapper = new Vfs();
                wrappers.add(wrapper);
                path = wrapper.open(path).asFileSystem().getPath("/");
            }
            module.lookups.set(0);
            assertThat(Files.readString(path.resolve("file.txt"))).isEqualTo(CONTENT);
            assertThat(module.lookups.get()).isLessThanOrEqualTo(4 * (depth + 1));
        } finally {
            for (var i = wrappers.size() - 1; i >= 0; i--) {
                wrappers.get(i).close();
            }
        }
    }

    /**
     * Copies loaded by plugins remain case-sensitive VFS providers, not opaque native filesystems.
     *
     * @throws Exception
     *             if the isolated provider cannot be loaded or a wrapper cannot be opened or closed.
     */
    @Test
    public void isolatedProvidersDoNotMultiplyRecursiveLookups() throws Exception {
        final var module = new CountingModule();
        final var wrappers = new ArrayList<AutoCloseable>();
        final var urls = new URL[] { Vfs.class.getProtectionDomain().getCodeSource().getLocation(),
                LogNode.class.getProtectionDomain().getCodeSource().getLocation() };
        try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader()); var source = new Vfs()) {
            final var isolatedVfs = Class.forName(Vfs.class.getName(), true, loader);
            var path = source.open(module).asFileSystem().getPath("/");
            final var depth = 8;
            try {
                for (var level = 0; level < depth; level++) {
                    final var wrapper = level % 2 == 0 ? isolatedVfs.getConstructor().newInstance() : new Vfs();
                    wrappers.add((AutoCloseable) wrapper);
                    final var root = wrapper.getClass().getMethod("open", Path.class).invoke(wrapper, path);
                    final var view = (FileSystem) root.getClass().getMethod("asFileSystem").invoke(root);
                    path = view.getPath("/");
                }
                module.lookups.set(0);
                assertThat(Files.readString(path.resolve("file.txt"))).isEqualTo(CONTENT);
                assertThat(module.lookups.get()).isLessThanOrEqualTo(4 * (depth + 1));
            } finally {
                for (var i = wrappers.size() - 1; i >= 0; i--) {
                    wrappers.get(i).close();
                }
            }
        }
    }

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

    /** Cache eviction preserves ownership of a wrapper without acquiring ownership of its borrowed source. */
    @Test
    @SuppressWarnings("try")
    public void evictedWrappersAreClosedByTheirOwnerButNotTheirSource(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("file.txt"), CONTENT);
        try (var source = new Vfs(); var wrapper = new Vfs()) {
            final var sourceView = source.open(dir).asFileSystem();
            final var path = sourceView.getPath("/");
            final var root = wrapper.open(path);
            final var view = root.asFileSystem();
            wrapper.evict(root);
            assertThat(wrapper).isEmpty();
            assertThat(Files.readString(view.getPath("/file.txt"))).isEqualTo(CONTENT);
            final var replacement = wrapper.open(path);
            assertThat(replacement).isNotSameAs(root);
            final var replacementView = replacement.asFileSystem();
            wrapper.close();
            assertThat(view.isOpen()).isFalse();
            assertThat(replacementView.isOpen()).isFalse();
            assertThat(sourceView.isOpen()).isTrue();
            assertThat(Files.readString(sourceView.getPath("/file.txt"))).isEqualTo(CONTENT);
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
