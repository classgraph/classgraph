package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.github.classgraph.base.internal.utils.VersionFinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests the read-only {@link FileSystem} view of a {@link VfsRoot}. */
public class VfsFileSystemTest {
    /** The names of the entries written into every root under test. */
    private static final List<String> ENTRY_NAMES = List.of("root.txt", "com/xyz/Widget.class",
            "com/xyz/Widget.txt", "com/xyz/sub/Nested.class", "com/abc/Other.class");

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
     * Write {@link #ENTRY_NAMES} into a directory.
     *
     * @param dir
     *            the directory to write into.
     * @throws IOException
     *             if the files could not be written.
     */
    private static void writeDir(final Path dir) throws IOException {
        for (final var entryName : ENTRY_NAMES) {
            final var file = dir.resolve(entryName);
            Files.createDirectories(file.getParent());
            Files.write(file, contentOf(entryName));
        }
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
     * Every path of a filesystem, walked from the root directory and reported as a string.
     *
     * @param fileSystem
     *            the filesystem.
     * @return the paths, sorted.
     * @throws IOException
     *             if the filesystem could not be walked.
     */
    private static List<String> walk(final FileSystem fileSystem) throws IOException {
        try (Stream<Path> paths = Files.walk(fileSystem.getPath("/"))) {
            return paths.map(Path::toString).sorted().toList();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A directory, a jarfile and a jarfile nested inside another jarfile all walk to the same tree.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a root could not be read.
     */
    @Test
    public void everyKindOfRootWalksTheSameTree(@TempDir final Path tempDir) throws IOException {
        final var dir = tempDir.resolve("classes");
        Files.createDirectory(dir);
        writeDir(dir);
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        final var expected = Stream
                .of("/", "/com", "/com/abc", "/com/abc/Other.class", "/com/xyz", "/com/xyz/Widget.class",
                        "/com/xyz/Widget.txt", "/com/xyz/sub", "/com/xyz/sub/Nested.class", "/root.txt")
                .sorted().toList();

        try (var vfs = new Vfs()) {
            assertThat(walk(vfs.open(dir).asFileSystem())).isEqualTo(expected);
            assertThat(walk(vfs.open(jarFile).asFileSystem())).isEqualTo(expected);
        }
    }

    /**
     * Entry content is readable through {@link Files}, in every way {@link Files} offers.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a root could not be read.
     */
    @Test
    public void readsEntryContent(@TempDir final Path tempDir) throws IOException {
        final var dir = tempDir.resolve("classes");
        Files.createDirectory(dir);
        writeDir(dir);
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var vfs = new Vfs()) {
            for (final var fileSystem : List.of(vfs.open(dir).asFileSystem(), vfs.open(jarFile).asFileSystem())) {
                final var path = fileSystem.getPath("/com/xyz/Widget.class");
                final var expected = contentOf("com/xyz/Widget.class");

                assertThat(Files.readAllBytes(path)).isEqualTo(expected);
                assertThat(Files.readString(path)).isEqualTo(new String(expected, StandardCharsets.UTF_8));
                assertThat(Files.readAllLines(path)).containsExactly(new String(expected, StandardCharsets.UTF_8));
                try (var in = Files.newInputStream(path)) {
                    assertThat(in.readAllBytes()).isEqualTo(expected);
                }
                try (var channel = Files.newByteChannel(path)) {
                    assertThat(channel.size()).isEqualTo(expected.length);
                    assertThat(channel.position()).isZero();
                    channel.position(8);
                    assertThat(channel.position()).isEqualTo(8);
                }
                assertThat(Files.size(path)).isEqualTo(expected.length);

                // An entry hands out the same Path, whichever kind of storage it is held in
                final var entry = Objects
                        .requireNonNull(((VfsFileSystem) fileSystem).getRoot().getEntry("com/xyz/Widget.class"));
                assertThat(entry.asPath()).isEqualTo(path);
                assertThat(Files.readAllBytes(entry.asPath())).isEqualTo(expected);
            }
        }
    }

    /**
     * A path that is read relative to the root directory reads the same entry as an absolute one, and a path that
     * needs normalizing resolves to the same entry.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void resolvesRelativeAndUnnormalizedPaths(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);
        final var expected = contentOf("com/xyz/Widget.class");

        try (var vfs = new Vfs()) {
            final var fileSystem = vfs.open(jarFile).asFileSystem();
            assertThat(Files.readAllBytes(fileSystem.getPath("com/xyz/Widget.class"))).isEqualTo(expected);
            assertThat(Files.readAllBytes(fileSystem.getPath("com", "xyz", "Widget.class"))).isEqualTo(expected);
            assertThat(Files.readAllBytes(fileSystem.getPath("/com/abc/../xyz/./Widget.class")))
                    .isEqualTo(expected);
            assertThat(Files.readAllBytes(fileSystem.getPath("/../com/xyz/Widget.class"))).isEqualTo(expected);
        }
    }

    /**
     * Directory listings report the children of a directory, and a glob filters them.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void listsDirectories(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var vfs = new Vfs()) {
            final var fileSystem = vfs.open(jarFile).asFileSystem();

            assertThat(list(Files.newDirectoryStream(fileSystem.getPath("/")))).containsExactly("/com",
                    "/root.txt");
            assertThat(list(Files.newDirectoryStream(fileSystem.getPath("/com/xyz"))))
                    .containsExactly("/com/xyz/Widget.class", "/com/xyz/Widget.txt", "/com/xyz/sub");

            // A glob given to newDirectoryStream is matched against the file name only
            assertThat(list(Files.newDirectoryStream(fileSystem.getPath("/com/xyz"), "*.class")))
                    .containsExactly("/com/xyz/Widget.class");
            assertThat(list(Files.newDirectoryStream(fileSystem.getPath("/com/xyz"), "*.{class,txt}")))
                    .containsExactly("/com/xyz/Widget.class", "/com/xyz/Widget.txt");

            assertThatThrownBy(() -> Files.newDirectoryStream(fileSystem.getPath("/com/xyz/Widget.class")))
                    .isInstanceOf(NotDirectoryException.class);
            assertThatThrownBy(() -> Files.newDirectoryStream(fileSystem.getPath("/nonexistent")))
                    .isInstanceOf(NoSuchFileException.class);
        }
    }

    /**
     * An archive can hold a name that is both a file and a directory: an entry {@code "a/b"} alongside an entry
     * {@code "a/b/c"}. The file wins, as it does in the JDK's own zipfile filesystem, so listing the name as a
     * directory fails -- otherwise one name would be a regular file and a listable directory at the same time.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be written or read.
     */
    @Test
    public void aNameThatIsBothAFileAndADirectoryIsAFile(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("dual-name.jar").toFile();
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            for (final var entryName : List.of("a/b", "a/b/c", "a/d")) {
                zipOut.putNextEntry(new ZipEntry(entryName));
                zipOut.write(entryName.getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
            }
        }

        try (var vfs = new Vfs()) {
            final var fileSystem = vfs.open(jarFile).asFileSystem();
            final var dualName = fileSystem.getPath("/a/b");

            assertThat(Files.isRegularFile(dualName)).isTrue();
            assertThat(Files.isDirectory(dualName)).isFalse();
            assertThatThrownBy(() -> Files.newDirectoryStream(dualName)).isInstanceOf(NotDirectoryException.class);

            // The directory above it still lists both of its children, and the entry hidden below it is still
            // reachable by name
            assertThat(list(Files.newDirectoryStream(fileSystem.getPath("/a")))).containsExactly("/a/b", "/a/d");
            assertThat(Files.readAllBytes(fileSystem.getPath("/a/b/c")))
                    .isEqualTo("a/b/c".getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Read a directory stream into a sorted list of path strings.
     *
     * @param stream
     *            the directory stream, which is closed.
     * @return the paths, sorted.
     * @throws IOException
     *             if the stream could not be read.
     */
    private static List<String> list(final DirectoryStream<Path> stream) throws IOException {
        try (stream) {
            final List<String> paths = new ArrayList<>();
            stream.forEach(path -> paths.add(path.toString()));
            return paths.stream().sorted().toList();
        }
    }

    /**
     * File attributes report which paths are files and which are directories.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void readsFileAttributes(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var vfs = new Vfs()) {
            final var fileSystem = vfs.open(jarFile).asFileSystem();

            final var filePath = fileSystem.getPath("/com/xyz/Widget.class");
            final var fileAttrs = Files.readAttributes(filePath, BasicFileAttributes.class);
            assertThat(fileAttrs.isRegularFile()).isTrue();
            assertThat(fileAttrs.isDirectory()).isFalse();
            assertThat(fileAttrs.isSymbolicLink()).isFalse();
            assertThat(fileAttrs.isOther()).isFalse();
            assertThat(fileAttrs.size()).isEqualTo(contentOf("com/xyz/Widget.class").length);

            final var dirPath = fileSystem.getPath("/com/xyz");
            final var dirAttrs = Files.readAttributes(dirPath, BasicFileAttributes.class);
            assertThat(dirAttrs.isDirectory()).isTrue();
            assertThat(dirAttrs.isRegularFile()).isFalse();
            assertThat(dirAttrs.size()).isZero();

            assertThat(Files.exists(filePath)).isTrue();
            assertThat(Files.exists(dirPath)).isTrue();
            assertThat(Files.exists(fileSystem.getPath("/"))).isTrue();
            assertThat(Files.exists(fileSystem.getPath("/nonexistent"))).isFalse();
            assertThat(Files.isReadable(filePath)).isTrue();
            assertThat(Files.isWritable(filePath)).isFalse();
            assertThat(Files.isExecutable(filePath)).isFalse();
            assertThat(Files.isHidden(filePath)).isFalse();

            // The named form of readAttributes reads the same values
            final var named = Files.readAttributes(filePath, "size,isDirectory");
            assertThat(named).containsOnlyKeys("size", "isDirectory");
            assertThat(named.get("size")).isEqualTo((long) contentOf("com/xyz/Widget.class").length);
            assertThat(Files.readAttributes(filePath, "*")).containsKeys("size", "lastModifiedTime", "fileKey");

            assertThatThrownBy(
                    () -> Files.readAttributes(fileSystem.getPath("/nonexistent"), BasicFileAttributes.class))
                    .isInstanceOf(NoSuchFileException.class);
            assertThatThrownBy(() -> Files.readAllBytes(fileSystem.getPath("/nonexistent")))
                    .isInstanceOf(NoSuchFileException.class);
            // A directory exists but has no content to read, and is reported as a directory rather than as a
            // missing file, which is what the default provider reports too
            assertThatThrownBy(() -> Files.readAllBytes(dirPath)).isExactlyInstanceOf(FileSystemException.class)
                    .hasMessageContaining("Is a directory");
            assertThatThrownBy(() -> Files.newInputStream(dirPath)).isExactlyInstanceOf(FileSystemException.class);
            assertThatThrownBy(() -> Files.newByteChannel(dirPath)).isExactlyInstanceOf(FileSystemException.class);
        }
    }

    /**
     * A file last modified before the epoch reports the time it was really modified, not the epoch.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void reportsAModificationTimeFromBeforeTheEpoch(@TempDir final Path tempDir) throws IOException {
        final var dir = tempDir.resolve("dir");
        Files.createDirectory(dir);
        writeDir(dir);
        // 1960-01-01T00:00:00Z, which is before the epoch, so the time is negative
        final var beforeTheEpoch = FileTime.fromMillis(-315619200000L);
        Files.setLastModifiedTime(dir.resolve("root.txt"), beforeTheEpoch);
        // Not every filesystem can record a time before the epoch; only check the ones that can
        assumeTrue(Files.getLastModifiedTime(dir.resolve("root.txt")).equals(beforeTheEpoch));

        try (var vfs = new Vfs()) {
            final var root = vfs.open(dir.toFile());
            assertThat(Objects.requireNonNull(root.getEntry("root.txt")).getLastModifiedMillis())
                    .isEqualTo(beforeTheEpoch.toMillis());

            final var path = root.asFileSystem().getPath("/root.txt");
            assertThat(Files.getLastModifiedTime(path)).isEqualTo(beforeTheEpoch);
            assertThat(Files.readAttributes(path, BasicFileAttributes.class).creationTime())
                    .isEqualTo(beforeTheEpoch);
        }
    }

    /**
     * Every operation that would write throws {@link ReadOnlyFileSystemException}, since a virtual filesystem is
     * read-only.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void isReadOnly(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var vfs = new Vfs()) {
            final var fileSystem = vfs.open(jarFile).asFileSystem();
            assertThat(fileSystem.isReadOnly()).isTrue();
            assertThat(fileSystem.getFileStores()).allSatisfy(store -> assertThat(store.isReadOnly()).isTrue());

            final var path = fileSystem.getPath("/com/xyz/Widget.class");
            final var newPath = fileSystem.getPath("/new.txt");
            assertThatThrownBy(() -> Files.delete(path)).isInstanceOf(ReadOnlyFileSystemException.class);
            assertThatThrownBy(() -> Files.createDirectory(fileSystem.getPath("/newdir")))
                    .isInstanceOf(ReadOnlyFileSystemException.class);
            assertThatThrownBy(() -> Files.copy(path, newPath)).isInstanceOf(ReadOnlyFileSystemException.class);
            assertThatThrownBy(() -> Files.move(path, newPath)).isInstanceOf(ReadOnlyFileSystemException.class);
            assertThatThrownBy(() -> Files.write(newPath, new byte[] { 1 }))
                    .isInstanceOf(ReadOnlyFileSystemException.class);
            assertThatThrownBy(() -> Files.newOutputStream(newPath))
                    .isInstanceOf(ReadOnlyFileSystemException.class);
            assertThatThrownBy(() -> Files.newByteChannel(newPath, StandardOpenOption.WRITE))
                    .isInstanceOf(ReadOnlyFileSystemException.class);
            assertThatThrownBy(() -> Files.setLastModifiedTime(path, FileTime.fromMillis(0)))
                    .isInstanceOf(ReadOnlyFileSystemException.class);
            assertThatThrownBy(
                    () -> Files.getFileAttributeView(path, BasicFileAttributeView.class).setTimes(null, null, null))
                    .isInstanceOf(ReadOnlyFileSystemException.class);

            // The channel handed out by newByteChannel refuses to write too
            try (var channel = Files.newByteChannel(path)) {
                assertThatThrownBy(() -> channel.write(java.nio.ByteBuffer.allocate(1)))
                        .isInstanceOf(java.nio.channels.NonWritableChannelException.class);
                assertThatThrownBy(() -> channel.truncate(0))
                        .isInstanceOf(java.nio.channels.NonWritableChannelException.class);
            }
        }
    }

    /**
     * A jarfile nested inside another jarfile, and a package root within a jarfile, are both readable as
     * filesystems, which no filesystem provider of the JDK can do.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a root could not be read.
     */
    @Test
    public void readsNestedJarsAndPackageRoots(@TempDir final Path tempDir) throws IOException {
        final var innerJarFile = tempDir.resolve("inner.jar").toFile();
        writeJar(innerJarFile);
        final var innerJarBytes = Files.readAllBytes(innerJarFile.toPath());

        final var outerJarFile = tempDir.resolve("outer.jar").toFile();
        try (var fileOut = new FileOutputStream(outerJarFile); var zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry("BOOT-INF/lib/inner.jar"));
            zipOut.write(innerJarBytes);
            zipOut.closeEntry();
            for (final var entryName : ENTRY_NAMES) {
                zipOut.putNextEntry(new ZipEntry("BOOT-INF/classes/" + entryName));
                zipOut.write(contentOf(entryName));
                zipOut.closeEntry();
            }
        }

        try (var vfs = new Vfs()) {
            final var nested = vfs.open(outerJarFile.getPath() + "!/BOOT-INF/lib/inner.jar").asFileSystem();
            assertThat(Files.readAllBytes(nested.getPath("/com/xyz/Widget.class")))
                    .isEqualTo(contentOf("com/xyz/Widget.class"));

            // Names within a package root are relative to the package root, so this filesystem holds the same
            // tree as the jarfile that was nested inside the same outer jarfile
            final var packageRoot = vfs.open(outerJarFile.getPath() + "!/BOOT-INF/classes").asFileSystem();
            assertThat(Files.readAllBytes(packageRoot.getPath("/com/xyz/Widget.class")))
                    .isEqualTo(contentOf("com/xyz/Widget.class"));
            assertThat(walk(packageRoot)).isEqualTo(walk(nested));
        }
    }

    /**
     * A module of the running JDK is readable as a filesystem.
     *
     * @throws IOException
     *             if the module could not be read.
     */
    @Test
    public void readsAModule() throws IOException {
        final var moduleReference = ModuleFinder.ofSystem().find("java.logging").orElseThrow();
        try (var vfs = new Vfs()) {
            final var fileSystem = vfs.open(moduleReference).asFileSystem();

            final var path = fileSystem.getPath("/java/util/logging/Logger.class");
            assertThat(Files.exists(path)).isTrue();
            // Classfiles start with the magic number 0xCAFEBABE
            assertThat(Files.readAllBytes(path)).startsWith((byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE);
            // A module entry does not know its length without being read, but the size attribute still reports it
            assertThat(Files.size(path)).isEqualTo(Files.readAllBytes(path).length);

            assertThat(Files.isDirectory(fileSystem.getPath("/java/util/logging"))).isTrue();
            assertThat(list(Files.newDirectoryStream(fileSystem.getPath("/java/util/logging"), "Logger.class")))
                    .containsExactly("/java/util/logging/Logger.class");
        }
    }

    /**
     * Path arithmetic follows the {@link Path} contract.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void pathArithmetic(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var vfs = new Vfs()) {
            final var fileSystem = vfs.open(jarFile).asFileSystem();
            final var path = fileSystem.getPath("/com/xyz/Widget.class");

            assertThat(path.toString()).isEqualTo("/com/xyz/Widget.class");
            assertThat(path.isAbsolute()).isTrue();
            assertThat(path.getNameCount()).isEqualTo(3);
            assertThat(path.getName(0).toString()).isEqualTo("com");
            assertThat(path.getName(2).toString()).isEqualTo("Widget.class");
            assertThat(path.getFileName().toString()).isEqualTo("Widget.class");
            assertThat(path.getParent().toString()).isEqualTo("/com/xyz");
            assertThat(path.getRoot().toString()).isEqualTo("/");
            assertThat(path.subpath(0, 2).toString()).isEqualTo("com/xyz");
            assertThat(path.startsWith(fileSystem.getPath("/com"))).isTrue();
            assertThat(path.startsWith(fileSystem.getPath("com"))).isFalse();
            assertThat(path.endsWith(fileSystem.getPath("xyz/Widget.class"))).isTrue();
            assertThat(path.endsWith(fileSystem.getPath("yz/Widget.class"))).isFalse();
            assertThat(path.getFileSystem()).isSameAs(fileSystem);

            assertThat(fileSystem.getPath("/").getParent()).isNull();
            assertThat(fileSystem.getPath("/").getFileName()).isNull();
            assertThat(fileSystem.getPath("/com").getParent().toString()).isEqualTo("/");
            assertThat(fileSystem.getPath("com").getParent()).isNull();
            assertThat(fileSystem.getPath("").getNameCount()).isEqualTo(1);
            assertThat(fileSystem.getPath("").toString()).isEmpty();

            assertThat(fileSystem.getPath("/com/xyz/../abc").normalize().toString()).isEqualTo("/com/abc");
            assertThat(fileSystem.getPath("/../com").normalize().toString()).isEqualTo("/com");
            assertThat(fileSystem.getPath("../com").normalize().toString()).isEqualTo("../com");
            assertThat(fileSystem.getPath("com/xyz").resolve(fileSystem.getPath("Widget.class")).toString())
                    .isEqualTo("com/xyz/Widget.class");
            assertThat(fileSystem.getPath("/com").resolve(fileSystem.getPath("/abc")).toString()).isEqualTo("/abc");
            assertThat(fileSystem.getPath("/com/xyz").relativize(fileSystem.getPath("/com/abc")).toString())
                    .isEqualTo("../abc");
            assertThat(fileSystem.getPath("/com").relativize(fileSystem.getPath("/com/xyz")).toString())
                    .isEqualTo("xyz");
            assertThat(fileSystem.getPath("/com").relativize(fileSystem.getPath("/com")).toString()).isEmpty();
            assertThat(fileSystem.getPath("com").toAbsolutePath().toString()).isEqualTo("/com");
            assertThat(path.toRealPath().toString()).isEqualTo("/com/xyz/Widget.class");

            assertThat(path).isEqualTo(fileSystem.getPath("/com/xyz/Widget.class"));
            assertThat(path).hasSameHashCodeAs(fileSystem.getPath("/com/xyz/Widget.class"));
            assertThat(path).isNotEqualTo(fileSystem.getPath("com/xyz/Widget.class"));
            assertThat(path.compareTo(fileSystem.getPath("/com/xyz/Widget.txt"))).isNegative();

            // A Path iterates over its name elements
            final List<Path> nameElements = new ArrayList<>();
            path.forEach(nameElements::add);
            assertThat(nameElements).containsExactly(fileSystem.getPath("com"), fileSystem.getPath("xyz"),
                    fileSystem.getPath("Widget.class"));

            assertThatThrownBy(() -> path.getName(3)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> path.subpath(2, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> path.relativize(fileSystem.getPath("com")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> path.resolve(tempDir)).isInstanceOf(ProviderMismatchException.class);
            assertThatThrownBy(() -> path.relativize(tempDir)).isInstanceOf(ProviderMismatchException.class);
            // Path#compareTo specifies ClassCastException, where the other methods of Path throw
            // ProviderMismatchException
            assertThatThrownBy(() -> path.compareTo(tempDir)).isInstanceOf(ClassCastException.class);
            assertThatThrownBy(path::toFile).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    /**
     * {@link Path#compareTo(Path)} returns zero only for a path that is equal, so that a sorted collection does not
     * silently drop the path of another view of the same root.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void pathsOfTwoViewsOfOneRootAreOrderedApart(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var vfs = new Vfs()) {
            final var root = vfs.open(jarFile);
            final Path inFirstView;
            try (var firstView = root.asFileSystem()) {
                inFirstView = firstView.getPath("/com/xyz/Widget.class");
            }
            // Closing a view only discards that view, so the next call hands out a second view of the same root
            final var secondView = root.asFileSystem();
            final var inSecondView = secondView.getPath("/com/xyz/Widget.class");

            assertThat(inFirstView).isNotEqualTo(inSecondView);
            assertThat(inFirstView.compareTo(inSecondView)).isNegative();
            assertThat(inSecondView.compareTo(inFirstView)).isPositive();
            assertThat(new TreeSet<>(List.of(inFirstView, inSecondView))).hasSize(2);
        }
    }

    /**
     * A path names the storage it came from, so that it can be handed to code that reads by URI.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a root could not be read.
     */
    @Test
    public void pathsCarryTheURIOfTheUnderlyingStorage(@TempDir final Path tempDir) throws IOException {
        final var dir = tempDir.resolve("classes");
        Files.createDirectory(dir);
        writeDir(dir);
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var vfs = new Vfs()) {
            final var dirRoot = vfs.open(dir);
            final var dirFileSystem = dirRoot.asFileSystem();
            // A path of a directory root names a file, and reads back through the default filesystem provider
            final var fileURI = dirFileSystem.getPath("/com/xyz/Widget.class").toUri();
            assertThat(fileURI.getScheme()).isEqualTo("file");
            assertThat(Files.readAllBytes(Path.of(fileURI))).isEqualTo(contentOf("com/xyz/Widget.class"));
            assertThat(dirFileSystem.getPath("/").toUri()).isEqualTo(dirRoot.getURI());
            // A directory of the filesystem has no VfsEntry, but still names the directory it is a view of
            assertThat(dirFileSystem.getPath("/com/xyz").toUri().toString()).endsWith("/com/xyz");

            final var jarRoot = vfs.open(jarFile);
            final var jarFileSystem = jarRoot.asFileSystem();
            final var entryURI = jarFileSystem.getPath("/com/xyz/Widget.class").toUri();
            assertThat(entryURI.toString()).startsWith("jar:").endsWith("!/com/xyz/Widget.class");
            assertThat(entryURI)
                    .isEqualTo(Objects.requireNonNull(jarRoot.getEntry("com/xyz/Widget.class")).getURI());
            assertThat(jarFileSystem.getPath("/com/xyz").toUri().toString()).endsWith("!/com/xyz");
        }
    }

    /**
     * A module that was exploded into a directory is read from that directory, so a path of it names a file, not an
     * entry of a jarfile that does not exist.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the module could not be read.
     */
    @Test
    public void pathsOfAnExplodedModuleCarryTheURIOfTheDirectory(@TempDir final Path tempDir) throws IOException {
        final var moduleDir = tempDir.resolve("mod");
        Files.createDirectory(moduleDir);
        writeDir(moduleDir);

        try (var vfs = new Vfs()) {
            final var fileSystem = vfs.open(explodedModule(moduleDir)).asFileSystem();

            // A resource of the module has a URI of its own, which the module reader supplies
            assertThat(fileSystem.getPath("/com/xyz/Widget.class").toUri())
                    .isEqualTo(moduleDir.resolve("com/xyz/Widget.class").toUri());
            // A directory of the module has no resource to ask, so its URI is formed from the module's location
            assertThat(fileSystem.getPath("/com/xyz").toUri()).hasScheme("file").asString().endsWith("/com/xyz");
        }
    }

    /**
     * Create a module that is exploded into a directory, the way {@link ModuleFinder#of(Path...)} reports one.
     *
     * @param moduleDir
     *            the directory the module was exploded into.
     * @return the module.
     */
    private static ModuleReference explodedModule(final Path moduleDir) {
        return new ModuleReference(ModuleDescriptor.newModule("test.module").build(), moduleDir.toUri()) {
            @Override
            public ModuleReader open() {
                return new ModuleReader() {
                    @Override
                    public Optional<URI> find(final String name) {
                        final var file = moduleDir.resolve(name);
                        return Files.exists(file) ? Optional.of(file.toUri()) : Optional.empty();
                    }

                    @Override
                    public Stream<String> list() {
                        return ENTRY_NAMES.stream();
                    }

                    @Override
                    public void close() {
                        // A reader that reads through the default filesystem holds nothing open between reads
                    }
                };
            }
        };
    }

    /**
     * Glob and regex path matchers follow the {@link FileSystem#getPathMatcher(String)} contract.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void pathMatchers(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var vfs = new Vfs()) {
            final var fileSystem = vfs.open(jarFile).asFileSystem();
            final var path = fileSystem.getPath("/com/xyz/Widget.class");

            // "*" does not cross a directory boundary, "**" does
            assertThat(fileSystem.getPathMatcher("glob:*.class").matches(path)).isFalse();
            assertThat(fileSystem.getPathMatcher("glob:**.class").matches(path)).isTrue();
            assertThat(fileSystem.getPathMatcher("glob:/com/*/Widget.class").matches(path)).isTrue();
            assertThat(fileSystem.getPathMatcher("glob:/*/Widget.class").matches(path)).isFalse();
            assertThat(fileSystem.getPathMatcher("glob:/com/**/Widget.class").matches(path)).isTrue();
            assertThat(fileSystem.getPathMatcher("glob:/com/xyz/Widget.{class,txt}").matches(path)).isTrue();
            assertThat(fileSystem.getPathMatcher("glob:/com/xyz/Widget.{txt,html}").matches(path)).isFalse();
            assertThat(fileSystem.getPathMatcher("glob:/com/[wxy]yz/Widget.class").matches(path)).isTrue();
            assertThat(fileSystem.getPathMatcher("glob:/com/[!x]yz/Widget.class").matches(path)).isFalse();
            assertThat(fileSystem.getPathMatcher("glob:/com/?yz/Widget.class").matches(path)).isTrue();
            assertThat(fileSystem.getPathMatcher("glob:/com/??yz/Widget.class").matches(path)).isFalse();
            // A '.' in a glob is a literal, not a regex wildcard
            assertThat(fileSystem.getPathMatcher("glob:/com/xyz/Widget?class").matches(path)).isTrue();
            assertThat(fileSystem.getPathMatcher("glob:/com/xyzAWidget.class").matches(path)).isFalse();
            // A backslash escapes the next character
            assertThat(fileSystem.getPathMatcher("glob:/com/xyz/Widget\\.class").matches(path)).isTrue();
            assertThat(fileSystem.getPathMatcher("glob:/com/xyz/Widget\\*class").matches(path)).isFalse();

            assertThat(fileSystem.getPathMatcher("regex:.*/Widget\\.class").matches(path)).isTrue();
            assertThat(fileSystem.getPathMatcher("regex:.*/Widget\\.txt").matches(path)).isFalse();

            assertThatThrownBy(() -> fileSystem.getPathMatcher("nonsense:x"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> fileSystem.getPathMatcher("noSyntax"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * A filesystem is a view of its root, and stops being open when the {@link Vfs} that opened the root is closed.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void isAViewOfTheRoot(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        final FileSystem fileSystem;
        try (var vfs = new Vfs()) {
            final var root = vfs.open(jarFile);
            fileSystem = root.asFileSystem();
            // The same view is handed out every time, so that paths from two calls are equal
            assertThat(root.asFileSystem()).isSameAs(fileSystem);
            assertThat(fileSystem.isOpen()).isTrue();
            assertThat(fileSystem.getSeparator()).isEqualTo("/");
            assertThat(fileSystem.supportedFileAttributeViews()).containsExactly("basic");
            assertThat(fileSystem.getRootDirectories()).containsExactly(fileSystem.getPath("/"));
            assertThat(fileSystem.toString()).isEqualTo(root.toString());
            assertThat(fileSystem.provider().getScheme()).isEqualTo("vfs");

            assertThatThrownBy(fileSystem::newWatchService).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(fileSystem::getUserPrincipalLookupService)
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> fileSystem.getPath("/x").register(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(fileSystem.isOpen()).isFalse();
    }

    /**
     * Closing the filesystem closes that view of the root, so that it can be used in a try-with-resources, and
     * every subsequent read through it throws {@link ClosedFileSystemException}. It leaves the root itself working,
     * since a {@link Vfs} hands the same root to everything that opened the same path, and a view of it must not be
     * able to take it away from them.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void closingTheFilesystemLeavesTheRootOpen(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var vfs = new Vfs()) {
            final var root = vfs.open(jarFile);
            final FileSystem fileSystem;
            try (var fs = root.asFileSystem()) {
                fileSystem = fs;
                // Read an entry, so that the directory index is built before the close
                assertThat(Files.readAllBytes(fs.getPath("/root.txt"))).isEqualTo(contentOf("root.txt"));
            }
            assertThat(fileSystem.isOpen()).isFalse();

            // The index was built before the close, but is not served from after it
            assertThatThrownBy(() -> Files.readAllBytes(fileSystem.getPath("/root.txt")))
                    .isInstanceOf(ClosedFileSystemException.class);
            assertThatThrownBy(() -> Files.exists(fileSystem.getPath("/root.txt")))
                    .isInstanceOf(ClosedFileSystemException.class);
            assertThatThrownBy(() -> Files.newDirectoryStream(fileSystem.getPath("/")))
                    .isInstanceOf(ClosedFileSystemException.class);

            // Closing the view twice has no effect, and the root it was a view of is untouched: it is still cached,
            // still readable, and hands out a new working view rather than the closed one
            fileSystem.close();
            assertThat(root.isClosed()).isFalse();
            assertThat(vfs.open(jarFile)).isSameAs(root);
            assertThat(root.getEntries()).isNotEmpty();
            assertThat(root.asFileSystem()).isNotSameAs(fileSystem);
            assertThat(root.asFileSystem().isOpen()).isTrue();
            assertThat(fileSystem.isOpen()).isFalse();
            assertThat(Files.readAllBytes(root.asFileSystem().getPath("/root.txt")))
                    .isEqualTo(contentOf("root.txt"));
        }
    }

    /**
     * Open a {@link FileSystem} view of a root, read an entry through it so that its directory index is built, then
     * close it, handing back only a {@link WeakReference} to it, so that nothing in the caller's frame is left
     * holding it.
     *
     * @param root
     *            the root to view as a filesystem.
     * @return a {@link WeakReference} to the closed view.
     * @throws IOException
     *             if the entry could not be read.
     */
    private static WeakReference<FileSystem> readThroughAndCloseAView(final VfsRoot root) throws IOException {
        try (var fileSystem = root.asFileSystem()) {
            assertThat(Files.readAllBytes(fileSystem.getPath("/root.txt"))).isEqualTo(contentOf("root.txt"));
            return new WeakReference<>(fileSystem);
        }
    }

    /**
     * A view drops itself from the root it is a view of when it is closed, so that the root is not left holding a
     * filesystem that can no longer be read through -- together with the directory index it built, which holds
     * every entry of the root.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void closingTheFilesystemDropsItFromTheRoot(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var vfs = new Vfs()) {
            final var closedView = readThroughAndCloseAView(vfs.open(jarFile));
            // Nothing asks the root for a filesystem again, so the closed view is only collectable if the close
            // dropped it from the root, rather than the next call to asFileSystem() overwriting it
            for (var i = 0; i < 100 && closedView.get() != null; i++) {
                System.gc();
            }
            assertThat(closedView.get()).isNull();
        }
    }

    /**
     * {@link VfsRoot#asFileSystem()} returns the same instance every time, as it says it does, rather than building
     * a second filesystem for the second caller.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void asFileSystemReturnsOneInstance(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var vfs = new Vfs()) {
            final var root = vfs.open(jarFile);
            final var fileSystem = root.asFileSystem();
            assertThat(root.asFileSystem()).isSameAs(fileSystem);
        }
    }

    /**
     * A closed {@link Vfs} has released everything a filesystem view reads through, so it hands out no further
     * view, and a view that was taken before the close reports itself closed and turns away every read.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void aClosedVfsHandsOutNoFilesystemView(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        final var vfs = new Vfs();
        final var root = vfs.open(jarFile);
        final var entry = Objects.requireNonNull(root.getEntry("root.txt"));
        final var fileSystem = root.asFileSystem();
        vfs.close();

        assertThatThrownBy(root::asFileSystem).isInstanceOf(ClosedFileSystemException.class);
        // Including through the entry, which asks the root for the view it builds a Path in
        assertThatThrownBy(entry::asPath).isInstanceOf(ClosedFileSystemException.class);

        // The view that was taken before the close is still there, and still knows it cannot be read
        assertThat(fileSystem.isOpen()).isFalse();
        assertThatThrownBy(() -> Files.readAllBytes(fileSystem.getPath("/root.txt")))
                .isInstanceOf(ClosedFileSystemException.class);
    }

    /**
     * The provider methods that answer a question about a path's filesystem, rather than only about the path
     * itself, check that the filesystem is still open, as the JDK's own zip filesystem does.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void theProviderMethodsThatReadTheFilesystemCheckItIsOpen(@TempDir final Path tempDir)
            throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var vfs = new Vfs()) {
            final var fileSystem = vfs.open(jarFile).asFileSystem();
            final var path = fileSystem.getPath("/root.txt");
            // These work while the filesystem is open
            assertThat(Files.isSameFile(path, path)).isTrue();
            assertThat(Files.isHidden(path)).isFalse();
            assertThat(Files.getFileStore(path).isReadOnly()).isTrue();

            fileSystem.close();

            assertThatThrownBy(() -> Files.isSameFile(path, path)).isInstanceOf(ClosedFileSystemException.class);
            assertThatThrownBy(() -> Files.isHidden(path)).isInstanceOf(ClosedFileSystemException.class);
            assertThatThrownBy(() -> Files.getFileStore(path)).isInstanceOf(ClosedFileSystemException.class);
            // The purely syntactic Path methods go on working, as they do on a closed zip filesystem
            assertThat(path.getFileName()).hasToString("root.txt");
        }
    }

    /**
     * Write {@link #ENTRY_NAMES} into a jarfile, stored rather than deflated, so that an entry can be read straight
     * out of a memory mapping of the jarfile rather than being inflated into a buffer of its own.
     *
     * @param jarFile
     *            the jarfile to write.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeStoredJar(final File jarFile) throws IOException {
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            for (final var entryName : ENTRY_NAMES) {
                final var content = contentOf(entryName);
                final var entry = new ZipEntry(entryName);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(content.length);
                entry.setCompressedSize(content.length);
                final var crc = new CRC32();
                crc.update(content);
                entry.setCrc(crc.getValue());
                zipOut.putNextEntry(entry);
                zipOut.write(content);
                zipOut.closeEntry();
            }
        }
    }

    /**
     * Test that a read through a channel that was open when the {@link Vfs} was closed fails with an
     * {@link IOException}, rather than with the {@link IllegalStateException} that reading an unmapped buffer
     * throws.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be written or read.
     */
    @Test
    public void aChannelReadAfterTheVfsWasClosedThrowsIOException(@TempDir final Path tempDir) throws IOException {
        // Only a memory-mapped entry can have its storage taken away under a channel that is still open, and only
        // on JDK 22 or later -- below that the channel's own view of the mapping keeps the file mapped, so the
        // read after the close still returns the file content, exactly as it did when the entry was not mapped
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION >= 22);
        final var jarFile = tempDir.resolve("stored.jar").toFile();
        writeStoredJar(jarFile);

        final var vfs = new Vfs(new VfsSpec().setMemoryMappingFiles(true));
        try (var channel = Files.newByteChannel(vfs.open(jarFile).asFileSystem().getPath("/root.txt"))) {
            assertThat(channel.read(ByteBuffer.allocate(4))).isEqualTo(4);

            // Closing the Vfs unmaps the jarfile that the channel is reading a slice of
            vfs.close();

            assertThatThrownBy(() -> channel.read(ByteBuffer.allocate(4))).isInstanceOf(IOException.class)
                    .hasMessageContaining("unmapped by closing the Vfs");
        }
    }

    /**
     * A filesystem that is closed before anything has been read from it throws {@link ClosedFileSystemException}
     * too, rather than failing to build its directory index.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void aFilesystemClosedBeforeItWasReadThrowsTheSameException(@TempDir final Path tempDir)
            throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var vfs = new Vfs()) {
            final var fileSystem = vfs.open(jarFile).asFileSystem();
            fileSystem.close();
            assertThatThrownBy(() -> Files.readAllBytes(fileSystem.getPath("/root.txt")))
                    .isInstanceOf(ClosedFileSystemException.class);
        }
    }

    /**
     * A path of another filesystem provider is answered rather than rejected, wherever
     * {@link java.nio.file.spi.FileSystemProvider} says it should be, so that code that filters or compares a mixed
     * collection of paths works.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void comparesWithPathsOfOtherProviders(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);
        final var foreign = tempDir.resolve("com/xyz/Widget.class");

        try (var vfs = new Vfs()) {
            final var fileSystem = vfs.open(jarFile).asFileSystem();
            final var path = fileSystem.getPath("/com/xyz/Widget.class");

            // Path#startsWith and Path#endsWith return false for a path of a different filesystem, which is what
            // the default provider and the JDK's own zipfs both do
            assertThat(path.startsWith(foreign)).isFalse();
            assertThat(path.endsWith(foreign)).isFalse();
            assertThat(foreign.startsWith(path)).isFalse();
            assertThat(foreign.endsWith(path)).isFalse();

            // ...and so does Files#isSameFile, in both directions
            assertThat(Files.isSameFile(path, foreign)).isFalse();
            assertThat(Files.isSameFile(foreign, path)).isFalse();
            assertThat(Files.isSameFile(path, path)).isTrue();

            // The same name in a second virtual filesystem is a path of the same provider, but not the same file
            final var otherJarFile = tempDir.resolve("other.jar").toFile();
            writeJar(otherJarFile);
            final var otherFileSystem = vfs.open(otherJarFile).asFileSystem();
            final var samePathOtherFileSystem = otherFileSystem.getPath("/com/xyz/Widget.class");
            assertThat(Files.isSameFile(path, samePathOtherFileSystem)).isFalse();
            assertThat(path.startsWith(samePathOtherFileSystem)).isFalse();
            assertThat(path.endsWith(samePathOtherFileSystem)).isFalse();

            // ...whereas opening the same jarfile twice reaches the same root, so it is the same file
            assertThat(Files.isSameFile(path, vfs.open(jarFile).asFileSystem().getPath("/com/xyz/Widget.class")))
                    .isTrue();
        }
    }

    /**
     * Opening a file repeats the tolerances of the default provider: a repeated open option is accepted, and a
     * write option is refused.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void acceptsRepeatedOpenOptions(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var vfs = new Vfs()) {
            final var fileSystem = vfs.open(jarFile).asFileSystem();
            final var path = fileSystem.getPath("/root.txt");
            final var expected = contentOf("root.txt");

            try (var in = Files.newInputStream(path, StandardOpenOption.READ, StandardOpenOption.READ)) {
                assertThat(in.readAllBytes()).isEqualTo(expected);
            }
            try (var channel = Files.newByteChannel(path, StandardOpenOption.READ, StandardOpenOption.READ)) {
                assertThat(channel.size()).isEqualTo(expected.length);
            }
        }
    }

    /** Unknown open options and file attributes are rejected rather than silently ignored. */
    @Test
    public void rejectsUnsupportedOpenOptionsAndAttributes(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);
        final OpenOption unsupportedOption = new OpenOption() {
        };
        final FileAttribute<String> unsupportedAttribute = new FileAttribute<>() {
            @Override
            public String name() {
                return "unsupported:value";
            }

            @Override
            public String value() {
                return "value";
            }
        };

        try (var vfs = new Vfs()) {
            final var path = vfs.open(jarFile).asFileSystem().getPath("/root.txt");
            assertThatThrownBy(() -> Files.newByteChannel(path, Set.of(unsupportedOption)))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("Unsupported open option");
            assertThatThrownBy(
                    () -> Files.newByteChannel(path, Set.of(StandardOpenOption.READ), unsupportedAttribute))
                    .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("attributes");
        }
    }

    /**
     * A directory stream hands out its iterator once, and that iterator cannot remove anything.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void directoryStreamsHandOutOneIterator(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var vfs = new Vfs()) {
            final var fileSystem = vfs.open(jarFile).asFileSystem();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(fileSystem.getPath("/com/xyz"))) {
                final var iterator = stream.iterator();
                assertThatThrownBy(stream::iterator).isInstanceOf(IllegalStateException.class);
                assertThat(iterator.next()).isNotNull();
                assertThatThrownBy(iterator::remove).isInstanceOf(UnsupportedOperationException.class);
            }
            // ...and asking a closed stream for an iterator fails the same way
            final DirectoryStream<Path> closed = Files.newDirectoryStream(fileSystem.getPath("/com/xyz"));
            closed.close();
            assertThatThrownBy(closed::iterator).isInstanceOf(IllegalStateException.class);
        }
    }

    /** Concurrent calls cannot both acquire the single iterator of a directory stream. */
    @Test
    public void directoryStreamIteratorAcquisitionIsAtomic(@TempDir final Path tempDir) throws Exception {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);
        final var executor = Executors.newFixedThreadPool(2);
        try (var vfs = new Vfs()) {
            final var fileSystem = vfs.open(jarFile).asFileSystem();
            for (var repetition = 0; repetition < 100; repetition++) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(fileSystem.getPath("/com/xyz"))) {
                    final var barrier = new CyclicBarrier(2);
                    final var first = executor.submit(() -> {
                        barrier.await();
                        return stream.iterator();
                    });
                    final var second = executor.submit(() -> {
                        barrier.await();
                        return stream.iterator();
                    });
                    var successes = 0;
                    for (final var future : List.of(first, second)) {
                        try {
                            assertThat(future.get()).isNotNull();
                            successes++;
                        } catch (final ExecutionException e) {
                            assertThat(e.getCause()).isInstanceOf(IllegalStateException.class);
                        }
                    }
                    assertThat(successes).isOne();
                }
            }
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Seeking a byte channel beyond the end of a file is allowed, reports the position that was asked for, and
     * reads there return end-of-file.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void seeksBeyondTheEndOfAFile(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var vfs = new Vfs()) {
            final var fileSystem = vfs.open(jarFile).asFileSystem();
            try (var channel = Files.newByteChannel(fileSystem.getPath("/root.txt"))) {
                final var size = channel.size();
                channel.position(size + 1000);
                assertThat(channel.position()).isEqualTo(size + 1000);
                assertThat(channel.read(java.nio.ByteBuffer.allocate(8))).isEqualTo(-1);

                // Seeking back reads from there again
                channel.position(size - 3);
                final var tail = java.nio.ByteBuffer.allocate(8);
                assertThat(channel.read(tail)).isEqualTo(3);
                assertThat(channel.position()).isEqualTo(size);

                assertThatThrownBy(() -> channel.position(-1)).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    /**
     * Reading one named attribute does not compute the others, so that asking a module entry for its modification
     * time does not read the whole entry to find its size.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void readsOnlyTheNamedAttributes(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var vfs = new Vfs()) {
            final var fileSystem = vfs.open(jarFile).asFileSystem();
            final var path = fileSystem.getPath("/root.txt");

            assertThat(Files.readAttributes(path, "size")).containsOnlyKeys("size");
            assertThat(Files.readAttributes(path, "basic:lastModifiedTime")).containsOnlyKeys("lastModifiedTime");
            assertThat(Files.readAttributes(path, "size,isDirectory")).containsOnlyKeys("size", "isDirectory");
            assertThat(Files.readAttributes(path, "*")).containsOnlyKeys("lastModifiedTime", "lastAccessTime",
                    "creationTime", "size", "isRegularFile", "isDirectory", "isSymbolicLink", "isOther", "fileKey");

            assertThatThrownBy(() -> Files.readAttributes(path, "basic:bogus"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Files.readAttributes(path, "posix:permissions"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    /**
     * A glob accepts everything the default provider's glob syntax accepts, including a {@code '^'} at the start of
     * a bracket expression, which is a literal there rather than a negation.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void globsMatchTheDefaultProviderSyntax(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var vfs = new Vfs()) {
            final var fileSystem = vfs.open(jarFile).asFileSystem();
            // '^' is a literal at the start of a bracket expression, and '!' is the negation
            final var caret = fileSystem.getPathMatcher("glob:/root[^r]txt");
            assertThat(caret.matches(fileSystem.getPath("/root^txt"))).isTrue();
            assertThat(caret.matches(fileSystem.getPath("/root.txt"))).isFalse();
            final var negated = fileSystem.getPathMatcher("glob:/root[!r]txt");
            assertThat(negated.matches(fileSystem.getPath("/root.txt"))).isTrue();
            assertThat(negated.matches(fileSystem.getPath("/rootrtxt"))).isFalse();
            // A negated bracket expression does not match across a directory boundary, any more than '*' or '?' do
            assertThat(negated.matches(fileSystem.getPath("/root/txt"))).isFalse();
            // ']' is a literal at the start of a bracket expression, since there is no empty bracket expression
            final var closeBracket = fileSystem.getPathMatcher("glob:/root[]r]txt");
            assertThat(closeBracket.matches(fileSystem.getPath("/root]txt"))).isTrue();
            assertThat(closeBracket.matches(fileSystem.getPath("/rootrtxt"))).isTrue();
            assertThat(closeBracket.matches(fileSystem.getPath("/root.txt"))).isFalse();
            // The syntax is compared without regard to case
            assertThat(fileSystem.getPathMatcher("GLOB:/root.txt").matches(fileSystem.getPath("/root.txt")))
                    .isTrue();
            assertThat(fileSystem.getPathMatcher("ReGeX:/root\\.txt").matches(fileSystem.getPath("/root.txt")))
                    .isTrue();
        }
    }
}
