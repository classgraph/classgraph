package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.github.classgraph.base.internal.utils.VersionFinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

        // Two views of one jarfile, one per Vfs, since a Vfs hands out one view of a root and closing it closes the
        // Vfs. The paths still have to be ordered apart: they name the same entry of the same jarfile, but they are
        // read through different filesystems, so they are not the same path
        try (var firstVfs = new Vfs(); var secondVfs = new Vfs()) {
            final var firstView = firstVfs.open(jarFile).asFileSystem();
            final var secondView = secondVfs.open(jarFile).asFileSystem();
            final var inFirstView = firstView.getPath("/com/xyz/Widget.class");
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
     * A channel over a resource of a module streams it only as far as the furthest offset that is read, buffering
     * what it has read so far, even though a {@link ModuleReader} cannot say how long a resource is without reading
     * it. A caller that reads a header therefore reads only the header, and a caller that asks how long the
     * resource is reads the whole of it, once.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the module could not be written or read.
     */
    @Test
    public void aModuleResourceIsReadInPiecesThroughAChannel(@TempDir final Path tempDir) throws IOException {
        // A resource long enough that reading a header out of it is a different amount of work from reading all of
        // it
        final var expected = new byte[256 * 1024];
        for (var i = 0; i < expected.length; i++) {
            expected[i] = (byte) (i % 251);
        }
        final var entryName = "com/xyz/big.dat";
        final var moduleDir = tempDir.resolve("mod");
        Files.createDirectories(moduleDir.resolve("com/xyz"));
        Files.write(moduleDir.resolve(entryName), expected);
        // The number of bytes the module reader has been asked for, so that reading a header can be told apart from
        // reading the whole resource
        final var numBytesStreamed = new AtomicLong();

        try (var vfs = new Vfs()) {
            final var path = vfs.open(countingModule(moduleDir, entryName, numBytesStreamed)).asFileSystem()
                    .getPath("/" + entryName);

            final var channel = Files.newByteChannel(path);
            try (channel) {
                // A header is read without the rest of the resource being streamed
                final var header = ByteBuffer.allocate(64);
                assertThat(channel.read(header)).isEqualTo(64);
                assertThat(header.array()).isEqualTo(Arrays.copyOfRange(expected, 0, 64));
                assertThat(numBytesStreamed.get()).isLessThan(expected.length);

                // A part that has already been read is handed back a second time
                assertThat(channel.position(0).read(header.clear())).isEqualTo(64);
                assertThat(header.array()).isEqualTo(Arrays.copyOfRange(expected, 0, 64));

                // The size is the length of the resource, which the channel only knows once it has read all of it
                assertThat(channel.size()).isEqualTo(expected.length);

                // Reading the whole resource in pieces gives back exactly what was written, and stops at the end
                final var readBack = ByteBuffer.allocate(expected.length);
                final var piece = ByteBuffer.allocate(3000);
                channel.position(0);
                while (channel.read(piece.clear()) > 0) {
                    readBack.put(piece.flip());
                }
                assertThat(readBack.array()).isEqualTo(expected);
                assertThat(channel.position(expected.length + 10L).read(ByteBuffer.allocate(8))).isEqualTo(-1);
            }
            assertThatThrownBy(() -> channel.read(ByteBuffer.allocate(8)))
                    .isInstanceOf(ClosedChannelException.class);

            // The whole resource is read the same way it would be through a stream
            assertThat(Files.readAllBytes(path)).isEqualTo(expected);
        }
    }

    /**
     * Create a module that is exploded into a directory and that counts the bytes that are streamed out of it.
     *
     * @param moduleDir
     *            the directory the module was exploded into.
     * @param entryName
     *            the name of the module's only resource.
     * @param numBytesStreamed
     *            the counter to add the number of bytes streamed to.
     * @return the module.
     */
    private static ModuleReference countingModule(final Path moduleDir, final String entryName,
            final AtomicLong numBytesStreamed) {
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
                    public Optional<InputStream> open(final String name) throws IOException {
                        if (!name.equals(entryName)) {
                            return Optional.empty();
                        }
                        final var fileStream = Files.newInputStream(moduleDir.resolve(name));
                        return Optional.of(new FilterInputStream(fileStream) {
                            @Override
                            public int read(final byte[] buf, final int off, final int len) throws IOException {
                                final var numRead = super.read(buf, off, len);
                                if (numRead > 0) {
                                    numBytesStreamed.addAndGet(numRead);
                                }
                                return numRead;
                            }
                        });
                    }

                    @Override
                    public Stream<String> list() {
                        return Stream.of(entryName);
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
            assertThat(fileSystem.provider().getScheme()).isEqualTo("cgvfs");

            assertThatThrownBy(fileSystem::newWatchService).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(fileSystem::getUserPrincipalLookupService)
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> fileSystem.getPath("/x").register(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(fileSystem.isOpen()).isFalse();
    }

    /**
     * A filesystem view of a root the caller opened owns nothing, so closing the view closes only the view: the
     * root it was a view of, and the {@link Vfs} that opened it, stay open and readable, and another view can be
     * taken of the same root. Closing the view twice has no effect.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void closingAViewOfARootLeavesTheRootOpen(@TempDir final Path tempDir) throws IOException {
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

            // The root and its Vfs did not go with the view: both are still readable, and a second view can be
            // taken of the same root
            assertThat(root.isClosed()).isFalse();
            assertThat(root.getEntries()).isNotEmpty();
            assertThat(vfs.open(jarFile)).isSameAs(root);
            try (var secondView = root.asFileSystem()) {
                assertThat(Files.readAllBytes(secondView.getPath("/root.txt"))).isEqualTo(contentOf("root.txt"));
            }

            // Closing the view twice has no effect
            fileSystem.close();
            assertThat(fileSystem.isOpen()).isFalse();
            assertThat(root.isClosed()).isFalse();
        }
    }

    /**
     * Closing the root closes every view of it, without the root having been closed by a view: a view reports
     * itself closed once its root is, and cannot be read through, but the {@link Vfs} that opened the root stays
     * open and can open it again.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the root could not be read.
     */
    @Test
    public void closingTheRootClosesEveryViewOfIt(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("library.jar").toFile();
        writeJar(jarFile);

        try (var vfs = new Vfs()) {
            final var root = vfs.open(jarFile);
            final var fileSystem = root.asFileSystem();
            assertThat(Files.readAllBytes(fileSystem.getPath("/root.txt"))).isEqualTo(contentOf("root.txt"));

            root.close();

            assertThat(fileSystem.isOpen()).isFalse();
            assertThatThrownBy(() -> Files.readAllBytes(fileSystem.getPath("/root.txt")))
                    .isInstanceOf(ClosedFileSystemException.class);
            assertThatThrownBy(root::asFileSystem).isInstanceOf(ClosedFileSystemException.class);
            // The Vfs is untouched, so the jarfile can be opened through it again
            assertThat(vfs.open(jarFile).getEntries()).isNotEmpty();
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
                    .hasMessageContaining("unmapped by closing what it was read through");
        }
    }

    /**
     * A channel over an entry that is stored uncompressed reads it where it lies, rather than bringing the whole
     * entry into memory first, so this checks that the channel still keeps every promise a channel makes: it
     * reports the size of the entry, hands back the content in whatever size of pieces it is asked for, leaves the
     * limit of the destination buffer alone, reports end-of-file past the end of the entry, and refuses to read
     * once it has been closed.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be written or read.
     */
    @Test
    public void aStoredEntryIsReadInPiecesThroughAChannel(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("stored.jar").toFile();
        writeStoredJar(jarFile);
        final var entryName = "com/xyz/sub/Nested.class";
        final var expected = contentOf(entryName);

        try (var vfs = new Vfs()) {
            final var path = vfs.open(jarFile).asFileSystem().getPath("/" + entryName);
            assertThat(Files.readAllBytes(path)).isEqualTo(expected);

            final var channel = Files.newByteChannel(path);
            try (channel) {
                assertThat(channel.size()).isEqualTo(expected.length);

                // Read the whole entry five bytes at a time, which is a size that does not divide the length of
                // the entry, so the last read is short
                final var readBack = ByteBuffer.allocate(expected.length);
                final var piece = ByteBuffer.allocate(5);
                for (var numRead = 0; (numRead = channel.read(piece.clear())) > 0;) {
                    assertThat(numRead).isEqualTo(Math.min(5, expected.length - readBack.position()));
                    readBack.put(piece.flip());
                }
                assertThat(readBack.array()).isEqualTo(expected);
                assertThat(channel.position()).isEqualTo(expected.length);
                // The end of the entry reads as end-of-file, and so does any position past it
                assertThat(channel.read(ByteBuffer.allocate(5))).isEqualTo(-1);
                assertThat(channel.position(expected.length + 10L).position()).isEqualTo(expected.length + 10L);
                assertThat(channel.read(ByteBuffer.allocate(5))).isEqualTo(-1);

                // A read fills the destination between its position and its limit, and moves neither the limit nor
                // any byte outside that window
                final var window = ByteBuffer.allocate(expected.length + 8);
                window.position(3).limit(9);
                assertThat(channel.position(0).read(window)).isEqualTo(6);
                assertThat(window.position()).isEqualTo(9);
                assertThat(window.limit()).isEqualTo(9);
                assertThat(window.array()[2]).isZero();
                assertThat(Arrays.copyOfRange(window.array(), 3, 9)).isEqualTo(Arrays.copyOfRange(expected, 0, 6));
            }
            assertThatThrownBy(() -> channel.read(ByteBuffer.allocate(5)))
                    .isInstanceOf(ClosedChannelException.class);
        }
    }

    /**
     * A channel over an entry that is stored compressed inflates it only as far as the furthest offset that is
     * read, buffering what it has inflated so far, so this checks that the channel still keeps every promise a
     * channel makes: it reports the size of the entry, hands back the content in whatever size of pieces it is
     * asked for, hands back a part that has already been read a second time, reads at an absolute position without
     * moving the position of the channel, reports end-of-file past the end of the entry, and refuses to read once
     * it has been closed.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be written or read.
     */
    @Test
    public void aDeflatedEntryIsReadInPiecesThroughAChannel(@TempDir final Path tempDir) throws IOException {
        // An entry long enough that reading a header out of it is a different amount of work from reading all of
        // it, and compressible enough to be stored deflated
        final var expected = new byte[256 * 1024];
        for (var i = 0; i < expected.length; i++) {
            expected[i] = (byte) (i % 251);
        }
        final var entryName = "com/xyz/big.dat";
        final var jarFile = tempDir.resolve("deflated.jar").toFile();
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            final var zipEntry = new ZipEntry(entryName);
            zipEntry.setMethod(ZipEntry.DEFLATED);
            zipOut.putNextEntry(zipEntry);
            zipOut.write(expected);
            zipOut.closeEntry();
        }

        try (var vfs = new Vfs()) {
            final var path = vfs.open(jarFile).asFileSystem().getPath("/" + entryName);
            assertThat(Files.readAllBytes(path)).isEqualTo(expected);

            final var channel = Files.newByteChannel(path);
            try (channel) {
                // A header can be read without the rest of the entry being inflated
                final var header = ByteBuffer.allocate(64);
                assertThat(channel.read(header)).isEqualTo(64);
                assertThat(header.array()).isEqualTo(Arrays.copyOfRange(expected, 0, 64));

                // A part that has already been read is handed back a second time
                assertThat(channel.position(0).read(header.clear())).isEqualTo(64);
                assertThat(header.array()).isEqualTo(Arrays.copyOfRange(expected, 0, 64));

                // The size is the inflated length, which the channel only knows once it has inflated the whole
                // entry, since the uncompressed size a zip entry declares for itself is not trustworthy
                assertThat(channel.size()).isEqualTo(expected.length);

                // Seeking forwards inflates as far as the offset asked for, and no further than the entry
                final var tail = ByteBuffer.allocate(32);
                assertThat(channel.position(expected.length - 32L).read(tail)).isEqualTo(32);
                assertThat(tail.array())
                        .isEqualTo(Arrays.copyOfRange(expected, expected.length - 32, expected.length));
                assertThat(channel.position()).isEqualTo(expected.length);
                assertThat(channel.read(ByteBuffer.allocate(8))).isEqualTo(-1);
                assertThat(channel.position(expected.length + 10L).read(ByteBuffer.allocate(8))).isEqualTo(-1);

                // Reading the whole entry in pieces gives back exactly what was written
                final var readBack = ByteBuffer.allocate(expected.length);
                final var piece = ByteBuffer.allocate(3000);
                channel.position(0);
                while (channel.read(piece.clear()) > 0) {
                    readBack.put(piece.flip());
                }
                assertThat(readBack.array()).isEqualTo(expected);
            }
            assertThatThrownBy(() -> channel.read(ByteBuffer.allocate(8)))
                    .isInstanceOf(ClosedChannelException.class);

            // A FileChannel over the same entry reads at an absolute position without moving its own position
            try (var fileChannel = FileChannel.open(path)) {
                final var window = ByteBuffer.allocate(16);
                assertThat(fileChannel.read(window, 1024)).isEqualTo(16);
                assertThat(window.array()).isEqualTo(Arrays.copyOfRange(expected, 1024, 1040));
                assertThat(fileChannel.position()).isZero();
                assertThat(fileChannel.size()).isEqualTo(expected.length);
            }
        }
    }

    /**
     * A {@link FileChannel} over an entry that is stored uncompressed can be read at an absolute position from
     * several threads at once, which is what {@link FileChannel} promises, even though the reader underneath it
     * keeps state between reads.
     *
     * @param memoryMapped
     *            whether the jarfile is memory-mapped, which decides which reader the channel reads through.
     * @param tempDir
     *            a temporary directory.
     * @throws Exception
     *             if the jarfile could not be written or read.
     */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    public void aStoredEntryCanBeReadAtAPositionFromSeveralThreadsAtOnce(final boolean memoryMapped,
            @TempDir final Path tempDir) throws Exception {
        // A long entry, so that a read takes long enough for two threads to be inside it at the same time
        final var entryName = "big.bin";
        final var expected = new byte[512 * 1024];
        for (var i = 0; i < expected.length; i++) {
            expected[i] = (byte) i;
        }
        final var jarFile = tempDir.resolve("stored.jar").toFile();
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            final var entry = new ZipEntry(entryName);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(expected.length);
            entry.setCompressedSize(expected.length);
            final var crc = new CRC32();
            crc.update(expected);
            entry.setCrc(crc.getValue());
            zipOut.putNextEntry(entry);
            zipOut.write(expected);
            zipOut.closeEntry();
        }
        final var numThreads = 8;
        final var numRepetitions = 200;
        final var chunkLength = expected.length / numThreads;

        // The reader underneath the channel differs between the two: an unmapped file is read through the shared
        // file channel, which is thread-safe in itself, while a memory-mapped file is read through a buffer whose
        // position and limit the reader moves, which is not
        try (var vfs = new Vfs(new VfsSpec().setMemoryMappingFiles(memoryMapped))) {
            final var path = vfs.open(jarFile).asFileSystem().getPath("/" + entryName);
            try (var channel = FileChannel.open(path)) {
                final var barrier = new CyclicBarrier(numThreads);
                final var executor = Executors.newFixedThreadPool(numThreads);
                try {
                    final List<Callable<Integer>> tasks = new ArrayList<>();
                    for (var threadIdx = 0; threadIdx < numThreads; threadIdx++) {
                        final var thisThreadIdx = threadIdx;
                        tasks.add(() -> {
                            // Every thread reads a different part of the entry, and a different number of bytes of
                            // it, so that two threads racing inside the reader ask it for different windows on the
                            // content, and one of them gets the other's bytes if the reader's state is shared
                            final var numBytesToRead = chunkLength - thisThreadIdx;
                            final var buf = ByteBuffer.allocate(numBytesToRead);
                            barrier.await();
                            for (var repetition = 0; repetition < numRepetitions; repetition++) {
                                final var fromPosition = ((thisThreadIdx + repetition) % numThreads) * chunkLength;
                                buf.clear();
                                while (buf.hasRemaining() && channel.read(buf, fromPosition + buf.position()) > 0) {
                                    // Keep reading until the whole chunk has been read
                                }
                                assertThat(buf.array()).isEqualTo(
                                        Arrays.copyOfRange(expected, fromPosition, fromPosition + numBytesToRead));
                            }
                            return numRepetitions;
                        });
                    }
                    for (final var future : executor.invokeAll(tasks)) {
                        assertThat(future.get()).isEqualTo(numRepetitions);
                    }
                } finally {
                    executor.shutdown();
                }
            }
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
