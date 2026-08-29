package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.ProviderNotFoundException;
import java.nio.file.spi.FileSystemProvider;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests of the {@code "cgvfs:"} URL scheme: that it is registered by {@link java.util.ServiceLoader}, that a URI
 * names the same things {@link Vfs#open(String)} names, and that a filesystem created from a URI owns the
 * {@link Vfs} behind it.
 */
public class VfsFileSystemProviderUriTest {
    /** The names of the entries written into every jarfile under test. */
    private static final List<String> ENTRY_NAMES = List.of("root.txt", "com/xyz/Widget.class",
            "com/xyz/sub/Nested.class");

    /**
     * The content of an entry, which is its own name, so that a test can check that it read the entry it asked for.
     *
     * @param entryName
     *            the name of the entry.
     * @return the content of the entry.
     */
    private static byte[] contentOf(final String entryName) {
        return ("content of " + entryName).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Write {@link #ENTRY_NAMES} into a jarfile.
     *
     * @param jarFile
     *            the jarfile to write.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJar(final File jarFile) throws IOException {
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            for (final var entryName : ENTRY_NAMES) {
                zipOut.putNextEntry(new ZipEntry(entryName));
                zipOut.write(contentOf(entryName));
                zipOut.closeEntry();
            }
        }
    }

    /**
     * Write a jarfile that holds another jarfile, stored rather than deflated, so that the nested one can be read
     * in place.
     *
     * @param outerJarFile
     *            the jarfile to write.
     * @param nestedJarName
     *            the name to store the nested jarfile under.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeNestedJar(final File outerJarFile, final String nestedJarName) throws IOException {
        final var nestedBytes = new ByteArrayOutputStream();
        try (var zipOut = new ZipOutputStream(nestedBytes)) {
            for (final var entryName : ENTRY_NAMES) {
                zipOut.putNextEntry(new ZipEntry(entryName));
                zipOut.write(contentOf(entryName));
                zipOut.closeEntry();
            }
        }
        final var nested = nestedBytes.toByteArray();
        try (var fileOut = new FileOutputStream(outerJarFile); var zipOut = new ZipOutputStream(fileOut)) {
            // A nested jarfile is only read in place if it is stored rather than deflated, and a stored entry has
            // to carry its own size and CRC
            final var entry = new ZipEntry(nestedJarName);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(nested.length);
            entry.setCompressedSize(nested.length);
            final var crc = new java.util.zip.CRC32();
            crc.update(nested);
            entry.setCrc(crc.getValue());
            zipOut.putNextEntry(entry);
            zipOut.write(nested);
            zipOut.closeEntry();
        }
    }

    /**
     * The {@code "cgvfs:"} URI of a path string.
     *
     * <p>
     * This is built with the {@link URI#URI(String, String, String)} constructor, which quotes the characters that
     * a URI cannot hold, rather than by concatenating the scheme onto the path: a Windows path holds backslashes,
     * which are illegal in the scheme-specific part of an opaque URI, so concatenation would throw. This is what
     * {@link VfsPath#toCgvfsUri()} does, and {@code pathOf} decodes the quoting back out again.
     *
     * @param path
     *            the path.
     * @return the URI.
     */
    private static URI cgvfsUri(final String path) {
        try {
            return new URI(VfsFileSystemProvider.SCHEME, path, /* fragment = */ null);
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException("Path cannot be written as a URI: " + path, e);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The scheme is registered by {@link java.util.ServiceLoader}, so it is installed without the caller having to
     * do anything but put classgraph-vfs on the classpath or the module path.
     */
    @Test
    public void theSchemeIsInstalledByServiceLoader() {
        assertThat(FileSystemProvider.installedProviders()).anyMatch(p -> "cgvfs".equals(p.getScheme()));
        assertThat(VfsFileSystemProvider.isInstalled()).isTrue();
    }

    /**
     * A jarfile can be opened by URI, with or without a {@code "file:"} scheme in front of the path, and the paths
     * of the resulting filesystem read the jarfile's entries.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be read.
     */
    @Test
    public void aJarfileCanBeOpenedByUriWithOrWithoutTheFileScheme(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);
        final var jarPath = jarFile.getPath();

        for (final var uri : List.of(cgvfsUri(jarPath), cgvfsUri("file:" + jarPath))) {
            try (var fileSystem = FileSystems.newFileSystem(uri, Map.of())) {
                assertThat(fileSystem.provider().getScheme()).isEqualTo("cgvfs");
                assertThat(Files.readAllBytes(fileSystem.getPath("/root.txt"))).isEqualTo(contentOf("root.txt"));
                assertThat(Files.isDirectory(fileSystem.getPath("/com/xyz"))).isTrue();
            }
        }
    }

    /**
     * A URI passed to {@code newFileSystem} names a root, and the same URI with one more {@code "!/"} section names
     * a path within it, which is how a {@code "jar:"} URI works for zipfs.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be read.
     */
    @Test
    public void getPathReadsTheLastSectionAsAPathWithinTheFilesystem(@TempDir final Path tempDir)
            throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);
        final var jarPath = jarFile.getPath();

        try (var fileSystem = FileSystems.newFileSystem(cgvfsUri(jarPath), Map.of())) {
            // A URI that names the filesystem exactly is its root directory
            assertThat(Paths.get(cgvfsUri(jarPath))).isEqualTo(fileSystem.getPath("/"));

            // A URI with an entry after the "!/" is that entry
            final var entryPath = Paths.get(cgvfsUri(jarPath + "!/com/xyz/Widget.class"));
            assertThat(entryPath).isEqualTo(fileSystem.getPath("/com/xyz/Widget.class"));
            assertThat(Files.readAllBytes(entryPath)).isEqualTo(contentOf("com/xyz/Widget.class"));

            // A directory within the jarfile is a path of the jarfile's filesystem, not a filesystem of its own
            final var dirPath = Paths.get(cgvfsUri(jarPath + "!/com/xyz"));
            assertThat(dirPath).isEqualTo(fileSystem.getPath("/com/xyz"));
            assertThat(Files.isDirectory(dirPath)).isTrue();
        }
    }

    /**
     * A jarfile nested inside another one is named by a {@code "!/"} URI, and a path within the nested jarfile is
     * read against the nested jarfile's filesystem rather than against the enclosing one.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfiles could not be read.
     */
    @Test
    public void aNestedJarfileIsItsOwnFilesystem(@TempDir final Path tempDir) throws IOException {
        final var outerJarFile = tempDir.resolve("outer.jar").toFile();
        writeNestedJar(outerJarFile, "lib/inner.jar");
        final var innerPath = outerJarFile.getPath() + "!/lib/inner.jar";

        try (var fileSystem = FileSystems.newFileSystem(cgvfsUri(innerPath), Map.of())) {
            assertThat(Files.readAllBytes(fileSystem.getPath("/root.txt"))).isEqualTo(contentOf("root.txt"));

            // The longest prefix wins, so this is a path of the nested jarfile, not of the enclosing one
            final var entryPath = Paths.get(cgvfsUri(innerPath + "!/com/xyz/Widget.class"));
            assertThat(entryPath.getFileSystem()).isSameAs(fileSystem);
            assertThat(Files.readAllBytes(entryPath)).isEqualTo(contentOf("com/xyz/Widget.class"));
        }
    }

    /**
     * A package root within a jarfile is a filesystem in its own right, whose entries are named relative to that
     * root, which is what {@link Vfs#open(String)} gives for the same path.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be read.
     */
    @Test
    public void aPackageRootIsAFilesystem(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("boot.jar").toFile();
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            for (final var entryName : ENTRY_NAMES) {
                zipOut.putNextEntry(new ZipEntry("BOOT-INF/classes/" + entryName));
                zipOut.write(contentOf(entryName));
                zipOut.closeEntry();
            }
        }
        final var packageRootPath = jarFile.getPath() + "!/BOOT-INF/classes";

        try (var fileSystem = FileSystems.newFileSystem(cgvfsUri(packageRootPath), Map.of())) {
            // The package root is stripped from the entry names, so this is "/root.txt" and not
            // "/BOOT-INF/classes/root.txt"
            assertThat(Files.readAllBytes(fileSystem.getPath("/root.txt"))).isEqualTo(contentOf("root.txt"));
            assertThat(Files.exists(fileSystem.getPath("/BOOT-INF"))).isFalse();

            final var entryPath = Paths.get(cgvfsUri(packageRootPath + "!/com/xyz/Widget.class"));
            assertThat(entryPath.getFileSystem()).isSameAs(fileSystem);
        }
    }

    /**
     * Only one filesystem can be open at a path at a time, and closing it frees the name, which is the contract
     * {@link FileSystems#newFileSystem(URI, Map)} specifies.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be read.
     */
    @Test
    public void onlyOneFilesystemIsOpenAtAPathAtATime(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);
        final var uri = cgvfsUri(jarFile.getPath());

        final FileSystem fileSystem;
        try (var fs = FileSystems.newFileSystem(uri, Map.of())) {
            fileSystem = fs;
            assertThatThrownBy(() -> FileSystems.newFileSystem(uri, Map.of()))
                    .isInstanceOf(FileSystemAlreadyExistsException.class);
            // The same filesystem is found whichever of its names the caller writes
            assertThat(FileSystems.getFileSystem(uri)).isSameAs(fs);
            assertThat(FileSystems.getFileSystem(cgvfsUri("file:" + jarFile.getPath()))).isSameAs(fs);
        }
        // Closing frees the name, so it can be opened again, and the closed one is no longer found
        assertThat(fileSystem.isOpen()).isFalse();
        assertThatThrownBy(() -> FileSystems.getFileSystem(uri)).isInstanceOf(FileSystemNotFoundException.class);
        FileSystems.newFileSystem(uri, Map.of()).close();
    }

    /**
     * A filesystem created from a URI owns the {@link Vfs} that opened its root, so closing the filesystem releases
     * the file handles and temporary files that reading through it took.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be read.
     */
    @Test
    public void closingAFilesystemCreatedFromAUriClosesItsVfs(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        final VfsRoot root;
        try (var fileSystem = FileSystems.newFileSystem(cgvfsUri(jarFile.getPath()), Map.of())) {
            root = ((VfsFileSystem) fileSystem).getRoot();
            assertThat(root.isClosed()).isFalse();
        }
        assertThat(root.isClosed()).isTrue();
        assertThatThrownBy(root::getEntries).isInstanceOf(IOException.class);
    }

    /**
     * A path of a filesystem created from a URI can be written back as a {@code "cgvfs:"} URI, and reading that URI
     * gives an equal path. {@link VfsPath#toUri()} still gives the URI of the underlying storage.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be read.
     */
    @Test
    public void aPathCanBeWrittenBackAsACgvfsUri(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var fileSystem = FileSystems.newFileSystem(cgvfsUri(jarFile.getPath()), Map.of())) {
            final var path = (VfsPath) fileSystem.getPath("/com/xyz/Widget.class");
            final var uri = path.toCgvfsUri();
            assertThat(uri.getScheme()).isEqualTo("cgvfs");
            assertThat(uri.getSchemeSpecificPart()).endsWith("library.jar!/com/xyz/Widget.class");
            assertThat(Paths.get(uri)).isEqualTo(path);

            // toUri still names the storage, so that code that has never heard of ClassGraph can read it
            assertThat(path.toUri().getScheme()).isEqualTo("jar");

            // The root directory has no entry name after the "!/"
            final var rootUri = ((VfsPath) fileSystem.getPath("/")).toCgvfsUri();
            assertThat(rootUri.getSchemeSpecificPart()).endsWith("library.jar");
            assertThat(Paths.get(rootUri)).isEqualTo(fileSystem.getPath("/"));
        }
    }

    /**
     * A jarfile whose path holds characters that a URI cannot hold unquoted can be opened by URI, and its paths can
     * be written back as URIs. A space is such a character on every platform, and a Windows path holds two more of
     * them in every path it names: the backslash separator, and the colon after the drive letter.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be read.
     */
    @Test
    public void aPathThatNeedsQuotingInAUriCanBeOpened(@TempDir final Path tempDir) throws IOException {
        // Characters that are legal in a filename on every platform ClassGraph supports, but that a URI has to
        // quote. '!' is left out, since that is the nested jar separator rather than part of the name
        final var awkwardDir = Files.createDirectory(tempDir.resolve("lib dir & more#1"));
        final var jarFile = awkwardDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var fileSystem = FileSystems.newFileSystem(cgvfsUri(jarFile.getPath()), Map.of())) {
            assertThat(Files.readAllBytes(fileSystem.getPath("/root.txt"))).isEqualTo(contentOf("root.txt"));

            // The quoting round-trips: the URI of a path names that path again, with the characters decoded
            final var path = (VfsPath) fileSystem.getPath("/com/xyz/Widget.class");
            final var uri = path.toCgvfsUri();
            assertThat(uri.getSchemeSpecificPart()).contains("lib dir & more#1");
            assertThat(Paths.get(uri)).isEqualTo(path);
        }
    }

    /**
     * A jarfile named by a {@link Path} of any filesystem can be opened, which is what
     * {@link FileSystems#newFileSystem(Path, Map)} calls.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be read.
     */
    @Test
    public void aJarfileCanBeOpenedByPath(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar");
        writeJar(jarFile.toFile());

        try (var fileSystem = new VfsFileSystemProvider().newFileSystem(jarFile, Map.of())) {
            assertThat(fileSystem.provider().getScheme()).isEqualTo("cgvfs");
            assertThat(Files.readAllBytes(fileSystem.getPath("/root.txt"))).isEqualTo(contentOf("root.txt"));
        }
    }

    /**
     * A URI that names no open filesystem, or that is not a {@code "cgvfs:"} URI at all, is rejected rather than
     * silently opening something.
     *
     * @param tempDir
     *            a temporary directory.
     */
    @Test
    public void aUriThatNamesNothingIsRejected(@TempDir final Path tempDir) {
        final var provider = new VfsFileSystemProvider();
        assertThatThrownBy(() -> provider.getFileSystem(cgvfsUri(tempDir.resolve("absent.jar").toString())))
                .isInstanceOf(FileSystemNotFoundException.class);
        assertThatThrownBy(() -> provider.getPath(cgvfsUri(tempDir.resolve("absent.jar!/x.txt").toString())))
                .isInstanceOf(FileSystemNotFoundException.class);
        assertThatThrownBy(() -> provider.getFileSystem(URI.create("jar:file:/x.jar!/")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.getFileSystem(URI.create("cgvfs:")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A path this provider cannot read as a filesystem is declined with {@link UnsupportedOperationException}
     * rather than reported as an {@link IOException}, so that {@link FileSystems#newFileSystem(Path, ClassLoader)}
     * goes on to try the providers installed after this one instead of ending its search here. A path that is not
     * there at all is still an {@link IOException}, since that is not this provider declining it.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the file could not be written.
     */
    @Test
    public void aPathThatIsNotAnArchiveIsDeclinedRatherThanFailed(@TempDir final Path tempDir) throws IOException {
        final var notAnArchive = Files.writeString(tempDir.resolve("notes.txt"), "not an archive");
        final var provider = new VfsFileSystemProvider();
        assertThatThrownBy(() -> provider.newFileSystem(notAnArchive, Map.of()))
                .isInstanceOf(UnsupportedOperationException.class).hasCauseInstanceOf(IOException.class);
        assertThatThrownBy(() -> provider.newFileSystem(tempDir.resolve("absent.jar"), Map.of()))
                .isInstanceOf(IOException.class);

        // The consequence that matters: the search over the installed providers reaches its end, rather than
        // being cut short by this one
        assumeTrue(VfsFileSystemProvider.isInstalled(), "The \"cgvfs:\" scheme is not installed");
        assertThatThrownBy(() -> FileSystems.newFileSystem(notAnArchive, (ClassLoader) null))
                .isInstanceOf(ProviderNotFoundException.class);
    }

    /**
     * An entry can be read through a {@link java.nio.channels.FileChannel}, which is what
     * {@link java.nio.channels.FileChannel#open(Path, java.nio.file.OpenOption...)} gives, since zipfs supports it
     * and code written against zipfs may ask for one.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be read.
     */
    @Test
    public void anEntryCanBeReadThroughAFileChannel(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);
        final var expected = contentOf("root.txt");

        try (var fileSystem = FileSystems.newFileSystem(cgvfsUri(jarFile.getPath()), Map.of());
                var channel = java.nio.channels.FileChannel.open(fileSystem.getPath("/root.txt"))) {
            assertThat(channel.size()).isEqualTo(expected.length);

            // A sequential read moves the position; a positional read does not
            final var buf = java.nio.ByteBuffer.allocate(expected.length);
            assertThat(channel.read(buf)).isEqualTo(expected.length);
            assertThat(buf.array()).isEqualTo(expected);
            assertThat(channel.position()).isEqualTo(expected.length);
            assertThat(channel.read(java.nio.ByteBuffer.allocate(8))).isEqualTo(-1);

            final var atFive = java.nio.ByteBuffer.allocate(3);
            assertThat(channel.read(atFive, 5)).isEqualTo(3);
            assertThat(channel.position()).isEqualTo(expected.length);
            assertThat(atFive.array()).isEqualTo(new byte[] { expected[5], expected[6], expected[7] });

            // transferTo writes the requested range to another channel
            final var sink = new ByteArrayOutputStream();
            assertThat(channel.transferTo(0, expected.length, java.nio.channels.Channels.newChannel(sink)))
                    .isEqualTo(expected.length);
            assertThat(sink.toByteArray()).isEqualTo(expected);

            // The channel is read-only, and an entry has no region of a file that could be mapped or locked
            assertThatThrownBy(() -> channel.write(java.nio.ByteBuffer.allocate(1)))
                    .isInstanceOf(java.nio.channels.NonWritableChannelException.class);
            assertThatThrownBy(() -> channel.truncate(0))
                    .isInstanceOf(java.nio.channels.NonWritableChannelException.class);
            assertThatThrownBy(() -> channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, 1))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> channel.lock()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    /**
     * A module of the boot layer can be opened by name, and by a {@code "cgvfs:jrt:/<module>"} URI.
     *
     * @throws IOException
     *             if the module could not be read.
     */
    @Test
    public void aModuleOfTheBootLayerCanBeOpenedByName() throws IOException {
        assumeTrue(ModuleLayer.boot().findModule("java.logging").isPresent(),
                "java.logging is not in the boot layer");

        try (var vfs = new Vfs()) {
            final var root = vfs.openModule("java.logging");
            assertThat(root.getModuleName()).isEqualTo("java.logging");
            assertThat(root.getEntry("java/util/logging/Logger.class")).isNotNull();

            // The same module is opened however it is named, so the root is shared rather than read twice
            assertThat(vfs.openModule("java.logging", ModuleLayer.boot())).isSameAs(root);
        }

        try (var fileSystem = FileSystems.newFileSystem(cgvfsUri("jrt:/java.logging"), Map.of())) {
            assertThat(Files.exists(fileSystem.getPath("/java/util/logging/Logger.class"))).isTrue();
        }
    }

    /**
     * A module that no layer reachable from the given one has is reported as absent rather than opened empty.
     *
     * @throws IOException
     *             if the Vfs could not be closed.
     */
    @Test
    public void aModuleThatNoLayerHasIsReportedAsAbsent() throws IOException {
        try (var vfs = new Vfs()) {
            assertThatThrownBy(() -> vfs.openModule("no.such.module.exists"))
                    .isInstanceOf(FileSystemNotFoundException.class).hasMessageContaining("no.such.module.exists");
        }
    }

    /**
     * A module is looked for in the parents of a layer as well as in the layer itself, since a layer can see the
     * modules of the layers it was built on top of.
     *
     * @throws IOException
     *             if the module could not be read.
     */
    @Test
    public void aModuleOfAParentLayerIsFound() throws IOException {
        assumeTrue(ModuleLayer.boot().findModule("java.logging").isPresent(),
                "java.logging is not in the boot layer");
        // An empty layer whose only parent is the boot layer: it has no modules of its own, so java.logging can
        // only be found by walking up to its parent
        final var emptyLayer = ModuleLayer.boot()
                .defineModulesWithOneLoader(ModuleLayer.boot().configuration()
                        .resolve(java.lang.module.ModuleFinder.of(), java.lang.module.ModuleFinder.of(), List.of()),
                        ClassLoader.getSystemClassLoader());

        try (var vfs = new Vfs()) {
            assertThat(emptyLayer.configuration().modules()).isEmpty();
            assertThat(vfs.openModule("java.logging", emptyLayer).getModuleName()).isEqualTo("java.logging");
        }
    }
}
