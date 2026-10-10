package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.ProviderNotFoundException;
import java.nio.file.spi.FileSystemProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import io.github.classgraph.base.internal.path.FastPathResolver;

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
     * The separators of the path are translated first, so that the URI holds the path in the syntax the virtual
     * filesystem uses. Without that, a Windows path would be written with its backslashes quoted as {@code "%5C"},
     * and a separator that is quoted is not a separator: it names a file whose name holds those three characters.
     * This is why {@link VfsPath#toCgvfsUri()} builds its URIs from a resolved path.
     *
     * <p>
     * The URI is then built with the {@link URI#URI(String, String, String)} constructor, which quotes the
     * characters that a URI cannot hold, rather than by concatenating the scheme onto the path: a space is illegal
     * in the scheme-specific part of an opaque URI, so concatenation would throw.
     *
     * @param path
     *            the path.
     * @return the URI.
     */
    private static URI cgvfsUri(final String path) {
        try {
            return new URI(VfsFileSystemProvider.SCHEME,
                    FastPathResolver.normalizePath(path, /* percentDecode = */ false), /* fragment = */ null);
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException("Path cannot be written as a URI: " + path, e);
        }
    }

    /**
     * The root of the filesystem of a file. No public method hands out the root of a filesystem that the provider
     * created, so this reaches it through the package-private accessors, as code that reaches it by reflection
     * would.
     *
     * @param file
     *            a file of a {@code "cgvfs:"} filesystem.
     * @return the root of that filesystem.
     */
    private static VfsRoot rootOf(final Path file) {
        return ((VfsPath) file).vfsFileSystem().getRoot();
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
     * Every filesystem reports the installed provider as its provider, whether it was created by that provider from
     * a URI or by {@link VfsRoot#asFileSystem()}, so that code which compares providers by identity accepts its
     * paths.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be read.
     */
    @Test
    public void aFilesystemReportsTheInstalledProvider(@TempDir final Path tempDir) throws IOException {
        assumeTrue(VfsFileSystemProvider.isInstalled(), "The \"cgvfs:\" scheme is not installed");
        final var installed = FileSystemProvider.installedProviders().stream()
                .filter(p -> "cgvfs".equals(p.getScheme())).findFirst().orElseThrow();
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);
        final var jarPath = jarFile.getPath();

        try (var fileSystem = FileSystems.newFileSystem(cgvfsUri(jarPath), Map.of())) {
            assertThat(fileSystem.provider()).isSameAs(installed);
            assertThat(Paths.get(cgvfsUri(jarPath + "!/root.txt")).getFileSystem().provider()).isSameAs(installed);
        }
        try (var vfs = new Vfs()) {
            assertThat(vfs.open(jarPath).asFileSystem().provider()).isSameAs(installed);
        }
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
     * A filesystem created from a URI owns the {@link Vfs} that was created to open its root, since that filesystem
     * is the only reference the caller is given to it, so closing the filesystem closes that {@link Vfs} and
     * releases everything that was opened to read the jarfile. A filesystem view of a root the caller opened
     * themselves owns nothing, and closes nothing but itself.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be read.
     */
    @Test
    public void closingAFilesystemCreatedFromAUriClosesTheVfsItOwns(@TempDir final Path tempDir)
            throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        final VfsRoot root;
        try (var fileSystem = FileSystems.newFileSystem(cgvfsUri(jarFile.getPath()), Map.of())) {
            root = ((VfsFileSystem) fileSystem).getRoot();
            assertThat(Files.readAllBytes(fileSystem.getPath("/root.txt"))).isEqualTo(contentOf("root.txt"));
            assertThat(root.getVfs().isClosed()).isFalse();
        }

        // Nothing else could have released what the Vfs opened, so closing the filesystem had to close it
        assertThat(root.isClosed()).isTrue();
        assertThat(root.getVfs().isClosed()).isTrue();
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
     * Closing the root of a filesystem created from a URI, or the {@link Vfs} behind it, from outside the
     * filesystem -- which no public method allows, but reflection does -- frees the name just as closing the
     * filesystem does: the closed filesystem is no longer found by URI, and the path can be opened again. Closing
     * the dead filesystem afterwards still closes the {@link Vfs} it owns, and leaves the new filesystem at that
     * path registered.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be read.
     */
    @Test
    public void closingTheRootOfAUriFilesystemUnregistersIt(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);
        final var uri = cgvfsUri(jarFile.getPath());

        final List<Consumer<VfsRoot>> closers = List.of(VfsRoot::close, root -> root.getVfs().close());
        for (final var closer : closers) {
            final VfsRoot root;
            final FileSystem reopened;
            try (var fileSystem = FileSystems.newFileSystem(uri, Map.of())) {
                root = rootOf(fileSystem.getPath("/root.txt"));
                closer.accept(root);
                assertThat(fileSystem.isOpen()).isFalse();
                assertThatThrownBy(() -> FileSystems.getFileSystem(uri))
                        .isInstanceOf(FileSystemNotFoundException.class);

                reopened = FileSystems.newFileSystem(uri, Map.of());
            }
            // The dead filesystem has now been closed, while the new one at its path is open
            try (reopened) {
                assertThat(root.getVfs().isClosed()).isTrue();
                assertThat(FileSystems.getFileSystem(uri)).isSameAs(reopened);
                assertThat(Files.readAllBytes(reopened.getPath("/root.txt"))).isEqualTo(contentOf("root.txt"));
            }
        }
    }

    /**
     * A second {@link FileSystems#newFileSystem(URI, Map)} for a path that already has a filesystem open is turned
     * away before it opens anything. Code written for zipfs commonly calls it and falls back to
     * {@link FileSystems#getFileSystem(URI)} when it throws {@link FileSystemAlreadyExistsException}, and that must
     * not download a remote jarfile, or extract a nested one, again on every call only to discard it.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be written or read, or the server could not be started.
     */
    @Test
    public void aSecondOpenOfARegisteredUriDoesNotRepeatTheOpen(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);
        final var jarBytes = Files.readAllBytes(jarFile.toPath());
        final var numRequests = new AtomicInteger();
        // A backlog of 0 means the system default, not a backlog of zero
        final var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                /* backlog = */ 0);
        server.createContext("/library.jar", exchange -> {
            numRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, jarBytes.length);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(jarBytes);
            }
        });
        server.start();
        try {
            final var uri = URI.create(
                    VfsFileSystemProvider.SCHEME + ":http://" + server.getAddress().getAddress().getHostAddress()
                            + ":" + server.getAddress().getPort() + "/library.jar");
            try (var fileSystem = FileSystems.newFileSystem(uri, Map.of())) {
                assertThat(Files.readAllBytes(fileSystem.getPath("/root.txt"))).isEqualTo(contentOf("root.txt"));
                assertThat(numRequests).hasValue(1);

                assertThatThrownBy(() -> FileSystems.newFileSystem(uri, Map.of()))
                        .isInstanceOf(FileSystemAlreadyExistsException.class);
                assertThat(FileSystems.getFileSystem(uri)).isSameAs(fileSystem);
                assertThat(numRequests).hasValue(1);
            }
        } finally {
            server.stop(0);
        }
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
     * A percent escape in a URI is never decoded into a separator, so a jarfile whose name holds the three
     * characters of one is named by a URI that holds them, and is the file that {@link Vfs#open(String)} opens for
     * the same path string. Decoding {@code "%2F"} into a slash would name a file in a directory that was never in
     * the path: no filesystem allows a slash in a name, so that reading can only name the wrong thing.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be read.
     */
    @Test
    public void aPercentEscapeInANameIsNotDecodedIntoASeparator(@TempDir final Path tempDir) throws IOException {
        // Coursier writes cache paths that percent-escape characters of a name like this (#255)
        final var jarFile = tempDir.resolve("a%2Fb.jar").toFile();
        writeJar(jarFile);

        // Written by hand, with the escape left as it is: the URI constructor would quote the '%' itself, which is
        // the unambiguous spelling that VfsPath#toCgvfsUri emits
        final var dir = cgvfsUri(tempDir.toString()).getRawSchemeSpecificPart();
        final var uri = URI.create(VfsFileSystemProvider.SCHEME + ":" + dir + "/a%2Fb.jar");

        try (var fileSystem = FileSystems.newFileSystem(uri, Map.of())) {
            assertThat(Files.readAllBytes(fileSystem.getPath("/root.txt"))).isEqualTo(contentOf("root.txt"));
        }
        // The same file, named the way Vfs#open names it
        try (var vfs = new Vfs()) {
            assertThat(vfs.open(jarFile.getPath()).reportedPath()).endsWith("a%2Fb.jar");
        }
    }

    /**
     * The percent encoding of a path is decoded exactly once, whether or not the path is written with a
     * {@code "file:"} scheme -- {@code FastPathResolver#resolve} decodes the one that has a scheme, so
     * {@code VfsFileSystemProvider#pathOf} must not decode it a second time.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be read.
     */
    @Test
    public void aPathWrittenWithAFileSchemeIsNotDecodedTwice(@TempDir final Path tempDir) throws IOException {
        // A file whose name really contains the three characters "%20", rather than a space
        final var jarFile = tempDir.resolve("a%20b.jar").toFile();
        writeJar(jarFile);

        // "%20" quoted as "%2520", so that it survives being read back out of the URI, in both spellings
        final var dir = cgvfsUri(tempDir.toString()).getRawSchemeSpecificPart();
        for (final var scheme : new String[] { "", "file:" }) {
            final var uri = URI.create(VfsFileSystemProvider.SCHEME + ":" + scheme + dir + "/a%2520b.jar");
            try (var fileSystem = FileSystems.newFileSystem(uri, Map.of())) {
                assertThat(Files.readAllBytes(fileSystem.getPath("/root.txt"))).isEqualTo(contentOf("root.txt"));
            }
        }
    }

    /**
     * An entry name that a URI has to quote is decoded when the URI is read, so the URI of a path names that path
     * again. Only a separator is left quoted; every other character a URI cannot hold is decoded back.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be read.
     */
    @Test
    public void anEntryNameThatNeedsQuotingRoundTrips(@TempDir final Path tempDir) throws IOException {
        final var entryName = "com/xyz/a b.class";
        final var jarFile = tempDir.resolve("library.jar").toFile();
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry(entryName));
            zipOut.write(contentOf(entryName));
            zipOut.closeEntry();
        }

        try (var fileSystem = FileSystems.newFileSystem(cgvfsUri(jarFile.getPath()), Map.of())) {
            final var path = (VfsPath) fileSystem.getPath("/" + entryName);
            final var uri = path.toCgvfsUri();
            assertThat(uri.getRawSchemeSpecificPart()).endsWith("!/com/xyz/a%20b.class");
            assertThat(Paths.get(uri)).isEqualTo(path);
            assertThat(Files.readAllBytes(Paths.get(uri))).isEqualTo(contentOf(entryName));
        }
    }

    /**
     * Write a jarfile with the given entries, each holding its own content.
     *
     * @param jarFile
     *            the jarfile to write.
     * @param entryNames
     *            the names of the entries.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJar(final File jarFile, final String... entryNames) throws IOException {
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            for (final var entryName : entryNames) {
                zipOut.putNextEntry(new ZipEntry(entryName));
                zipOut.write(contentOf(entryName));
                zipOut.closeEntry();
            }
        }
    }

    /**
     * The entry part of a URI, after the last {@code "!/"}, is decoded exactly once whichever spelling names the
     * jarfile before it. A {@code "file:"} or {@code "jar:file:"} spelling of the jarfile is decoded by the path
     * resolver rather than by the provider, but the entry part never reaches the resolver, so it has to be decoded
     * on its own.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be read.
     */
    @Test
    public void anEntryPartIsDecodedOnceWhateverTheRootSpelling(@TempDir final Path tempDir) throws IOException {
        // The second entry is what a URI names if its entry part is not decoded at all
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile, "c d.txt", "c%20d.txt", "d/é.txt");

        final var rawPath = cgvfsUri(jarFile.getPath()).getRawSchemeSpecificPart();
        final var absPath = rawPath.startsWith("/") ? rawPath : "/" + rawPath;
        try (var fileSystem = FileSystems.newFileSystem(cgvfsUri(jarFile.getPath()), Map.of())) {
            for (final var spelling : List.of(rawPath, "file:" + absPath, "file://" + absPath,
                    "jar:file:" + absPath)) {
                final var spaced = Paths
                        .get(URI.create(VfsFileSystemProvider.SCHEME + ":" + spelling + "!/c%20d.txt"));
                assertThat(spaced).as(spelling).isEqualTo(fileSystem.getPath("/c d.txt"));
                assertThat(Files.readAllBytes(spaced)).as(spelling).isEqualTo(contentOf("c d.txt"));

                final var accented = Paths
                        .get(URI.create(VfsFileSystemProvider.SCHEME + ":" + spelling + "!/d/%C3%A9.txt"));
                assertThat(Files.readAllBytes(accented)).as(spelling).isEqualTo(contentOf("d/é.txt"));
            }
        }
    }

    /**
     * A name that holds a backslash round-trips through {@link VfsPath#toCgvfsUri()}. A backslash is an ordinary
     * character in a jar entry name on every platform, since an entry name is split on {@code '/'} only.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be read.
     */
    @Test
    public void anEntryNameWithABackslashRoundTrips(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile, "a\\b.txt", "c/d\\e.txt");

        try (var fileSystem = FileSystems.newFileSystem(cgvfsUri(jarFile.getPath()), Map.of())) {
            for (final var entryName : List.of("a\\b.txt", "c/d\\e.txt")) {
                final var path = (VfsPath) fileSystem.getPath("/" + entryName);
                final var uri = path.toCgvfsUri();
                assertThat(Paths.get(uri)).as(uri.toString()).isEqualTo(path);
                assertThat(Files.readAllBytes(Paths.get(uri))).isEqualTo(contentOf(entryName));
            }
        }
    }

    /**
     * A file of a directory whose name holds a backslash round-trips through {@link VfsPath#toCgvfsUri()}. Only
     * Windows forbids a backslash in a filename.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the directory could not be read.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void aFileNameWithABackslashRoundTrips(@TempDir final Path tempDir) throws IOException {
        final var dir = Files.createDirectory(tempDir.resolve("dir"));
        Files.write(dir.resolve("back\\slash.txt"), contentOf("back\\slash.txt"));

        try (var fileSystem = FileSystems.newFileSystem(cgvfsUri(dir.toString()), Map.of())) {
            final var path = (VfsPath) fileSystem.getPath("/back\\slash.txt");
            final var uri = path.toCgvfsUri();
            assertThat(Paths.get(uri)).as(uri.toString()).isEqualTo(path);
            assertThat(Files.readAllBytes(Paths.get(uri))).isEqualTo(contentOf("back\\slash.txt"));
        }
    }

    /**
     * A jarfile fetched over http is fetched from the URL the {@code "cgvfs:"} URI holds, with its percent escapes
     * as written: that URL is what {@link Vfs#open(String)} takes, and decoding it would request a different
     * resource. Its paths also round-trip through {@link VfsPath#toCgvfsUri()}.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a jarfile could not be read.
     */
    @Test
    public void aRemoteJarWhoseUrlHoldsAnEscapeIsFetchedFromThatUrl(@TempDir final Path tempDir)
            throws IOException {
        // Each jarfile is served only at its raw request path, so a decoded request finds nothing. The decoy is at
        // the path that a request for "/a%20b.jar" is cut down to once the space is decoded. Each jarfile holds one
        // entry, whose name is its own raw path, so a test can tell which jarfile it read
        final var rawPaths = List.of("/a%20b.jar", "/q%3Fx.jar", "/h%23x.jar", "/p%25x.jar", "/a");
        final var jarBytesByRawPath = new HashMap<String, byte[]>();
        for (final var rawPath : rawPaths) {
            final var jarFile = tempDir.resolve("served" + jarBytesByRawPath.size() + ".jar").toFile();
            writeJar(jarFile, rawPath.substring(1) + ".txt");
            jarBytesByRawPath.put(rawPath, Files.readAllBytes(jarFile.toPath()));
        }
        final var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                /* backlog = */ 0);
        server.createContext("/", exchange -> {
            final var jarBytes = jarBytesByRawPath.get(exchange.getRequestURI().getRawPath());
            if (jarBytes == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, jarBytes.length);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(jarBytes);
            }
        });
        server.start();
        try {
            final var base = "http://" + server.getAddress().getAddress().getHostAddress() + ":"
                    + server.getAddress().getPort();
            for (final var rawPath : rawPaths.subList(0, 4)) {
                final var uri = URI.create(VfsFileSystemProvider.SCHEME + ":" + base + rawPath);
                try (var fileSystem = FileSystems.newFileSystem(uri, Map.of())) {
                    final var entryName = rawPath.substring(1) + ".txt";
                    final var path = (VfsPath) fileSystem.getPath("/" + entryName);
                    assertThat(Files.readAllBytes(path)).as(rawPath).isEqualTo(contentOf(entryName));

                    // A path within it is named by appending the entry name, quoted
                    final var entryUri = URI.create(uri + "!/" + entryName.replace("%", "%25"));
                    assertThat(Paths.get(entryUri)).as(entryUri.toString()).isEqualTo(path);

                    // The URL is written back with its escapes as they are, not quoted a second time
                    final var cgvfsUri = path.toCgvfsUri();
                    assertThat(cgvfsUri).isEqualTo(entryUri);
                    assertThat(Paths.get(cgvfsUri)).as(cgvfsUri.toString()).isEqualTo(path);
                    assertThat(FileSystems.getFileSystem(((VfsPath) fileSystem.getPath("/")).toCgvfsUri()))
                            .isSameAs(fileSystem);
                }
            }
        } finally {
            server.stop(0);
        }
    }

    /**
     * A jarfile nested inside a jarfile fetched over http is named by the URL of the outer jarfile, kept as it is
     * written, followed by the name of the nested jarfile, which is a name within an archive and so is decoded like
     * the entry name of any other URI. Its paths round-trip through {@link VfsPath#toCgvfsUri()}.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a jarfile could not be read.
     */
    @Test
    public void aJarNestedInARemoteJarCanBeNamedByUri(@TempDir final Path tempDir) throws IOException {
        final var outerJarFile = tempDir.resolve("outer.jar").toFile();
        writeNestedJar(outerJarFile, "lib/in ner.jar");
        final var outerJarBytes = Files.readAllBytes(outerJarFile.toPath());
        final var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                /* backlog = */ 0);
        server.createContext("/outer.jar", exchange -> {
            exchange.sendResponseHeaders(200, outerJarBytes.length);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(outerJarBytes);
            }
        });
        server.start();
        try {
            final var uri = URI.create(
                    VfsFileSystemProvider.SCHEME + ":http://" + server.getAddress().getAddress().getHostAddress()
                            + ":" + server.getAddress().getPort() + "/outer.jar!/lib/in%20ner.jar");
            try (var fileSystem = FileSystems.newFileSystem(uri, Map.of())) {
                final var path = (VfsPath) fileSystem.getPath("/root.txt");
                assertThat(Files.readAllBytes(path)).isEqualTo(contentOf("root.txt"));
                assertThat(Paths.get(URI.create(uri + "!/root.txt"))).isEqualTo(path);

                final var cgvfsUri = path.toCgvfsUri();
                assertThat(cgvfsUri).isEqualTo(URI.create(uri + "!/root.txt"));
                assertThat(Paths.get(cgvfsUri)).as(cgvfsUri.toString()).isEqualTo(path);
            }
        } finally {
            server.stop(0);
        }
    }

    /**
     * A directory named by a {@link Path} can be opened, which is what {@link FileSystems#newFileSystem(Path, Map)}
     * calls, since no built-in provider reads a directory as a filesystem.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the directory could not be read.
     */
    @Test
    public void aDirectoryCanBeOpenedByPath(@TempDir final Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("root.txt"), "root");
        try (var fileSystem = new VfsFileSystemProvider().newFileSystem(tempDir, Map.of())) {
            assertThat(fileSystem.provider().getScheme()).isEqualTo("cgvfs");
            assertThat(Files.readString(fileSystem.getPath("/root.txt"))).isEqualTo("root");
        }
    }

    /**
     * Each {@link FileSystems#newFileSystem(Path)} call over a directory returns a new filesystem, as
     * {@link FileSystemProvider#newFileSystem(Path, Map)} specifies and as zipfs does for a zipfile: a second call
     * for the same directory does not throw, and closing one of the two leaves the other readable.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the directory could not be read.
     */
    @Test
    public void twoFilesystemsCanBeOpenedOnOneDirectoryByPath(@TempDir final Path tempDir) throws IOException {
        assumeTrue(VfsFileSystemProvider.isInstalled(), "The \"cgvfs:\" scheme is not installed");
        Files.writeString(tempDir.resolve("root.txt"), "root");
        try (var kept = FileSystems.newFileSystem(tempDir)) {
            try (var closedFirst = FileSystems.newFileSystem(tempDir)) {
                assertThat(kept.provider().getScheme()).isEqualTo("cgvfs");
                assertThat(closedFirst).isNotSameAs(kept);
                assertThat(kept.isOpen()).isTrue();
                assertThat(closedFirst.isOpen()).isTrue();
            }
            assertThat(kept.isOpen()).isTrue();
            assertThat(Files.readString(kept.getPath("/root.txt"))).isEqualTo("root");
        }
    }

    /**
     * A filesystem created from a {@link Path} is not found by URI, since
     * {@link FileSystemProvider#getFileSystem(URI)} returns only the filesystems created from a URI. Nor does it
     * stop a filesystem being created from a URI of the same directory, or the other way round.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the directory could not be read.
     */
    @Test
    public void aFilesystemOpenedByPathIsNotFoundByUri(@TempDir final Path tempDir) throws IOException {
        assumeTrue(VfsFileSystemProvider.isInstalled(), "The \"cgvfs:\" scheme is not installed");
        Files.writeString(tempDir.resolve("root.txt"), "root");
        final var uri = cgvfsUri(tempDir.toString());
        try (var byPath = FileSystems.newFileSystem(tempDir)) {
            assertThatThrownBy(() -> FileSystems.getFileSystem(uri))
                    .isInstanceOf(FileSystemNotFoundException.class);
            assertThatThrownBy(() -> Paths.get(cgvfsUri(tempDir + "!/root.txt")))
                    .isInstanceOf(FileSystemNotFoundException.class);

            try (var byUri = FileSystems.newFileSystem(uri, Map.of())) {
                assertThat(FileSystems.getFileSystem(uri)).isSameAs(byUri);
                try (var byPathAgain = FileSystems.newFileSystem(tempDir)) {
                    assertThat(byPathAgain).isNotSameAs(byPath);
                    assertThat(Files.readString(byPathAgain.getPath("/root.txt"))).isEqualTo("root");
                }
                assertThat(FileSystems.getFileSystem(uri)).isSameAs(byUri);
            }
            assertThat(Files.readString(byPath.getPath("/root.txt"))).isEqualTo("root");
        }
    }

    /**
     * A filesystem created by the provider, from a URI or from a {@link Path}, is the view that its root hands out,
     * so code that reaches the root from the filesystem and asks it for its filesystem gets the filesystem the
     * caller holds rather than a second view whose paths would not be equal to the caller's.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile or the directory could not be read.
     */
    @Test
    public void anOwningFilesystemIsTheRootsView(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);
        try (var byUri = FileSystems.newFileSystem(cgvfsUri(jarFile.getPath()), Map.of());
                var byPath = new VfsFileSystemProvider().newFileSystem(tempDir, Map.of())) {
            assertThat(rootOf(byUri.getPath("/root.txt")).asFileSystem()).isSameAs(byUri);
            assertThat(rootOf(byPath.getPath("/library.jar")).asFileSystem()).isSameAs(byPath);
        }
    }

    /**
     * A {@link Path} that is not a directory -- a jarfile, any other file, or nothing at all -- is declined with
     * {@link UnsupportedOperationException}, so that {@link FileSystems#newFileSystem(Path, Map)} goes on to zipfs.
     * The order in which that method tries the installed providers is not defined for providers in named modules,
     * and with classgraph-vfs on the module path, this provider is tried before zipfs: if it took a jarfile, the
     * caller would get a read-only filesystem instead of zipfs, and if it failed a path that does not exist yet,
     * zipfs could not be asked to create a zipfile there.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the files could not be written.
     */
    @Test
    public void aPathThatIsNotADirectoryIsLeftToZipfs(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar");
        writeJar(jarFile.toFile());
        final var notAnArchive = Files.writeString(tempDir.resolve("notes.txt"), "not an archive");
        final var absent = tempDir.resolve("absent.zip");
        final var provider = new VfsFileSystemProvider();
        for (final var path : List.of(jarFile, notAnArchive, absent)) {
            assertThatThrownBy(() -> provider.newFileSystem(path, Map.of("create", "true")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(Files.exists(absent)).isFalse();

        // The consequence that matters: the search over the installed providers reaches zipfs, rather than being
        // cut short by this one
        assumeTrue(VfsFileSystemProvider.isInstalled(), "The \"cgvfs:\" scheme is not installed");
        try (var fileSystem = FileSystems.newFileSystem(jarFile, (ClassLoader) null)) {
            assertThat(fileSystem.provider().getScheme()).isEqualTo("jar");
        }
        try (var fileSystem = FileSystems.newFileSystem(absent, Map.of("create", "true"))) {
            assertThat(fileSystem.provider().getScheme()).isEqualTo("jar");
        }
        assertThatThrownBy(() -> FileSystems.newFileSystem(notAnArchive, (ClassLoader) null))
                .isInstanceOf(ProviderNotFoundException.class);
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
     * {@link java.nio.channels.FileChannel#transferTo(long, long, java.nio.channels.WritableByteChannel)} transfers
     * all of an entry that is larger than the buffer it copies through, from any position, to a blocking target,
     * and a count larger than the rest of the entry transfers the rest of the entry.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be written or read.
     */
    @Test
    public void transferToCopiesAnEntryLargerThanItsBuffer(@TempDir final Path tempDir) throws IOException {
        final var content = new byte[100_000];
        for (var i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31 + (i >> 8));
        }
        final var jarFile = tempDir.resolve("large.jar").toFile();
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry("large.bin"));
            zipOut.write(content);
            zipOut.closeEntry();
        }
        try (var fileSystem = FileSystems.newFileSystem(cgvfsUri(jarFile.getPath()), Map.of());
                var channel = java.nio.channels.FileChannel.open(fileSystem.getPath("/large.bin"))) {
            final var sink = new ByteArrayOutputStream();
            final var fromPosition = 12_345;
            assertThat(
                    channel.transferTo(fromPosition, Long.MAX_VALUE, java.nio.channels.Channels.newChannel(sink)))
                    .isEqualTo(content.length - fromPosition);
            assertThat(sink.toByteArray())
                    .isEqualTo(java.util.Arrays.copyOfRange(content, fromPosition, content.length));
            // Nothing is transferred from the end of the entry or beyond it
            assertThat(channel.transferTo(content.length, 10, java.nio.channels.Channels.newChannel(sink)))
                    .isZero();
            assertThat(channel.transferTo(content.length + 10, 10, java.nio.channels.Channels.newChannel(sink)))
                    .isZero();
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
     * The filesystem of a module names itself by {@code "jrt:/<module>"}, the only spelling of a module in a
     * {@code "cgvfs:"} URI, both in the URIs of its paths and in the registry. The bare module name names a
     * directory relative to the working directory, so a filesystem registered under it would be found in place of
     * that directory, and a URI written with it would open that directory once the module's filesystem is closed.
     *
     * @throws IOException
     *             if the module could not be read.
     */
    @Test
    public void aModuleFilesystemIsNamedByItsJrtPath() throws IOException {
        assumeTrue(ModuleLayer.boot().findModule("java.logging").isPresent(),
                "java.logging is not in the boot layer");

        final URI rootUri;
        try (var fileSystem = FileSystems.newFileSystem(cgvfsUri("jrt:/java.logging"), Map.of())) {
            final var path = (VfsPath) fileSystem.getPath("/java/util/logging/Logger.class");
            final var uri = path.toCgvfsUri();
            assertThat(uri).isEqualTo(URI.create("cgvfs:jrt:/java.logging!/java/util/logging/Logger.class"));
            assertThat(Paths.get(uri)).isEqualTo(path);

            rootUri = ((VfsPath) fileSystem.getPath("/")).toCgvfsUri();
            assertThat(rootUri).isEqualTo(URI.create("cgvfs:jrt:/java.logging"));

            // A directory named java.logging must stay free to be opened by its relative path while the module is
            // open
            assertThatThrownBy(() -> FileSystems.getFileSystem(URI.create("cgvfs:java.logging")))
                    .isInstanceOf(FileSystemNotFoundException.class);
        }

        // Once the module's filesystem is closed, the URI of its root opens the module again
        try (var fileSystem = FileSystems.newFileSystem(rootUri, Map.of())) {
            assertThat(Files.exists(fileSystem.getPath("/java/util/logging/Logger.class"))).isTrue();
        }
    }

    /**
     * A module that no layer reachable from the given one has is reported as absent rather than opened empty, with
     * the same checked exception that opening a path that names nothing throws, both from {@link Vfs} and from
     * {@link FileSystems#newFileSystem(URI, Map)}, whose contract allows only an {@link IOException}.
     *
     * @throws IOException
     *             if the Vfs could not be closed.
     */
    @Test
    public void aModuleThatNoLayerHasIsReportedAsAbsent() throws IOException {
        try (var vfs = new Vfs()) {
            assertThatThrownBy(() -> vfs.openModule("no.such.module.exists"))
                    .isInstanceOf(NoSuchFileException.class).hasMessageContaining("no.such.module.exists");
        }
        assertThatThrownBy(() -> FileSystems.newFileSystem(cgvfsUri("jrt:/no.such.module.exists"), Map.of()))
                .isInstanceOf(NoSuchFileException.class).hasMessageContaining("no.such.module.exists");
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
