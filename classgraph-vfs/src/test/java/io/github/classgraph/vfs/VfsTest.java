package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests the public API of the virtual filesystem. */
public class VfsTest {
    /** The content of the test resource. */
    private static final String RESOURCE_CONTENT = "vfs-test";

    /**
     * Write a jarfile containing a single deflated entry, plus a manifest that declares an automatic module name.
     *
     * @param jarFile
     *            the jarfile to write.
     * @param entryName
     *            the name of the entry to write.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJar(final File jarFile, final String entryName) throws IOException {
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zipOut.write("Manifest-Version: 1.0\nAutomatic-Module-Name: com.xyz.widget\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();

            zipOut.putNextEntry(new ZipEntry(entryName));
            zipOut.write(RESOURCE_CONTENT.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
    }

    /**
     * Write a jarfile containing another jarfile, stored rather than deflated, so that the inner jarfile can be
     * read in place through a slice of the outer jarfile.
     *
     * @param outerJarFile
     *            the outer jarfile to write.
     * @param innerJarEntryName
     *            the name of the inner jarfile within the outer jarfile.
     * @param innerJarBytes
     *            the content of the inner jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJarContainingJar(final File outerJarFile, final String innerJarEntryName,
            final byte[] innerJarBytes) throws IOException {
        try (var fileOut = new FileOutputStream(outerJarFile); var zipOut = new ZipOutputStream(fileOut)) {
            final var entry = new ZipEntry(innerJarEntryName);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(innerJarBytes.length);
            entry.setCompressedSize(innerJarBytes.length);
            final var crc = new CRC32();
            crc.update(innerJarBytes);
            entry.setCrc(crc.getValue());
            zipOut.putNextEntry(entry);
            zipOut.write(innerJarBytes);
            zipOut.closeEntry();
        }
    }

    /**
     * Write a multi-release jarfile holding the same resource path three times: once unversioned, once under a
     * version the running JVM supports, and once under a version far newer than any JVM that exists.
     *
     * @param jarFile
     *            the jarfile to write.
     * @param entryName
     *            the unversioned name of the resource.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeMultiReleaseJar(final File jarFile, final String entryName) throws IOException {
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zipOut.write("Manifest-Version: 1.0\nMulti-Release: true\n\n".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();

            for (final var entry : new String[][] { { entryName, "base" },
                    { "META-INF/versions/9/" + entryName, "version 9" },
                    { "META-INF/versions/9999/" + entryName, "version 9999" } }) {
                zipOut.putNextEntry(new ZipEntry(entry[0]));
                zipOut.write(entry[1].getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
            }
        }
    }

    /**
     * The content of an entry of a root.
     *
     * @param root
     *            the root.
     * @param entryName
     *            the name of the entry.
     * @return the content of the entry.
     * @throws IOException
     *             if the entry could not be read.
     */
    private static String entryContent(final VfsRoot root, final String entryName) throws IOException {
        return Objects.requireNonNull(root.getEntry(entryName)).loadAsString();
    }

    /**
     * Read a file into a byte array.
     *
     * @param file
     *            the file to read.
     * @return the content of the file.
     * @throws IOException
     *             if the file could not be read.
     */
    private static byte[] readFile(final File file) throws IOException {
        try (var inputStream = new FileInputStream(file)) {
            return inputStream.readAllBytes();
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    /** The entries of a jarfile can be listed, looked up by name, and read in every way an entry can be read. */
    @Test
    public void entriesCanBeListedAndRead(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");

        try (var vfs = new Vfs()) {
            final var root = vfs.open(jarFile.getPath());
            assertThat(root.getKind()).isEqualTo(VfsRoot.Kind.ARCHIVE);
            // The path is canonicalized, so that the same jarfile reached by two different paths is only opened
            // once. On Windows that expands an 8.3 short name, and on macOS it resolves a symlink, so the path of
            // the temp directory is not necessarily the path it is reported as.
            assertThat(root.getPath()).isEqualTo(jarFile.getCanonicalPath().replace(File.separatorChar, '/'));
            assertThat(root.getPackageRoot()).isEmpty();
            assertThat(root.toString()).isEqualTo(root.getPath());
            assertThat(root.getFile()).isNotNull();
            assertThat(root.getNioPath()).isNotNull();
            assertThat(root.getModuleReference()).isNull();
            assertThat(root.getURI().getScheme()).isEqualTo("file");
            assertThat(root.getURL().getProtocol()).isEqualTo("file");
            assertThat(root.getEntries()).extracting(VfsEntry::getName)
                    .containsExactlyInAnyOrder("META-INF/MANIFEST.MF", "com/xyz/widget.txt");

            final var entry = Objects.requireNonNull(root.getEntry("com/xyz/widget.txt"));
            assertThat(entry.getRoot()).isSameAs(root);
            assertThat(entry.getPath()).isEqualTo(root.getPath() + "!/com/xyz/widget.txt");
            assertThat(entry.toString()).isEqualTo(entry.getPath());
            assertThat(entry.getURI().toString()).startsWith("jar:file:").endsWith("!/com/xyz/widget.txt");
            assertThat(entry.getLength()).isEqualTo(RESOURCE_CONTENT.length());
            assertThat(entry.getCompressedSize()).isPositive();
            assertThat(entry.getLastModifiedTimeMillis()).isPositive();

            // Every way of reading an entry gives the same content
            assertThat(entry.load()).asString(StandardCharsets.UTF_8).isEqualTo(RESOURCE_CONTENT);
            assertThat(entry.loadAsString()).isEqualTo(RESOURCE_CONTENT);

            final var readViaStream = new ByteArrayOutputStream();
            try (var inputStream = entry.open()) {
                inputStream.transferTo(readViaStream);
            }
            assertThat(readViaStream.toString(StandardCharsets.UTF_8)).isEqualTo(RESOURCE_CONTENT);

            final var readViaChannel = ByteBuffer.allocate(RESOURCE_CONTENT.length());
            try (var channel = entry.openChannel()) {
                while (readViaChannel.hasRemaining() && channel.read(readViaChannel) >= 0) {
                    // Keep reading until the buffer is full or the channel is exhausted
                }
            }
            assertThat(new String(readViaChannel.array(), StandardCharsets.UTF_8)).isEqualTo(RESOURCE_CONTENT);

            try (var buffer = entry.read()) {
                final var readViaBuffer = new byte[Objects.requireNonNull(buffer.getByteBuffer()).remaining()];
                Objects.requireNonNull(buffer.getByteBuffer()).get(readViaBuffer);
                assertThat(new String(readViaBuffer, StandardCharsets.UTF_8)).isEqualTo(RESOURCE_CONTENT);
            }

            // Two lookups of the same entry produce equal entries
            assertThat(root.getEntry("com/xyz/widget.txt")).isEqualTo(entry)
                    .hasSameHashCodeAs(entry.getPath().hashCode());
        }
    }

    /** Looking up an entry that is not in the jarfile returns null. */
    @Test
    public void anEntryThatIsNotPresentIsNull(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");

        try (var vfs = new Vfs()) {
            assertThat(vfs.open(jarFile.getPath()).getEntry("com/xyz/nonexistent.txt")).isNull();
        }
    }

    /** The Automatic-Module-Name manifest entry is reported as the module name of a jarfile. */
    @Test
    public void theAutomaticModuleNameIsRead(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");

        try (var vfs = new Vfs()) {
            assertThat(vfs.open(jarFile.getPath()).getModuleName()).isEqualTo("com.xyz.widget");
        }
    }

    /** Opening the same path twice returns the same root. */
    @Test
    public void aRootIsOnlyOpenedOnce(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");

        try (var vfs = new Vfs()) {
            assertThat(vfs.open(jarFile.getPath())).isSameAs(vfs.open(jarFile.getPath()));
        }
    }

    /** A directory is opened as a root of its own, and its entries are named relative to it. */
    @Test
    public void aDirectoryCanBeOpenedAsARoot(@TempDir final File tempDir) throws IOException {
        final var dir = new File(tempDir, "classes");
        assertThat(new File(dir, "com/xyz").mkdirs()).isTrue();
        Files.writeString(new File(dir, "com/xyz/widget.txt").toPath(), RESOURCE_CONTENT);
        Files.writeString(new File(dir, "root.txt").toPath(), RESOURCE_CONTENT);

        try (var vfs = new Vfs()) {
            final var root = vfs.open(dir.getPath());
            assertThat(root.getKind()).isEqualTo(VfsRoot.Kind.DIRECTORY);
            assertThat(root.getPath()).endsWith("/classes");
            assertThat(root.getPackageRoot()).isEmpty();
            assertThat(root.getModuleName()).isNull();
            assertThat(root.getNioPath()).isEqualTo(dir.toPath().toAbsolutePath().normalize());
            assertThat(root.getFile()).isNotNull();
            assertThat(root.getURI().getScheme()).isEqualTo("file");
            // A directory is listed recursively, and its subdirectories are not themselves entries. Each
            // directory's own files come before its subdirectories.
            assertThat(root.getEntries()).extracting(VfsEntry::getName).containsExactly("root.txt",
                    "com/xyz/widget.txt");

            final var entry = Objects.requireNonNull(root.getEntry("com/xyz/widget.txt"));
            assertThat(entry.getPath()).isEqualTo(root.getPath() + "/com/xyz/widget.txt");
            assertThat(entry.getNioPath()).isNotNull();
            assertThat(entry.getLength()).isEqualTo(RESOURCE_CONTENT.length());
            assertThat(entry.getLastModifiedTimeMillis()).isPositive();
            assertThat(entry.loadAsString()).isEqualTo(RESOURCE_CONTENT);
            try (var inputStream = entry.open()) {
                assertThat(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8))
                        .isEqualTo(RESOURCE_CONTENT);
            }
            try (var buffer = entry.read()) {
                assertThat(Objects.requireNonNull(buffer.getByteBuffer()).remaining())
                        .isEqualTo(RESOURCE_CONTENT.length());
            }
            final var permissions = entry.getPosixFilePermissions();
            if (permissions != null) {
                // Windows does not record POSIX permissions, and reports null rather than an empty set
                assertThat(permissions).contains(PosixFilePermission.OWNER_READ);
            }

            // A name cannot reach outside the directory it is resolved against
            assertThat(root.getEntry("com/xyz/../../../outside.txt")).isNull();
            assertThat(root.getEntry("com/xyz")).isNull();
            assertThat(root.getEntry("")).isNull();
        }
    }

    /** The same jarfile can be named by a path string, a File, a Path, a URI or a URL. */
    @Test
    public void aJarfileCanBeNamedInEveryWayJavaNamesAFile(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");
        final var uri = jarFile.toURI();

        try (var vfs = new Vfs()) {
            // A File and a Path in the default filesystem name the same thing as the path string, so the same root
            // is returned for all three
            final var root = vfs.open(jarFile.getPath());
            assertThat(vfs.open(jarFile)).isSameAs(root);
            assertThat(vfs.open(jarFile.toPath())).isSameAs(root);

            // A URI and a URL are cached under the string they were named by, but reach the same jarfile
            for (final var fromURI : new VfsRoot[] { vfs.open(uri), vfs.open(uri.toURL()) }) {
                assertThat(fromURI.getPath()).isEqualTo(root.getPath());
                assertThat(entryContent(fromURI, "com/xyz/widget.txt")).isEqualTo(RESOURCE_CONTENT);
            }
        }
    }

    /** A directory can be named by a File, a Path, a URI or a URL, just as a jarfile can. */
    @Test
    public void aDirectoryCanBeNamedInEveryWayJavaNamesAFile(@TempDir final File tempDir) throws IOException {
        Files.writeString(new File(tempDir, "root.txt").toPath(), RESOURCE_CONTENT);

        try (var vfs = new Vfs()) {
            final var root = vfs.open(tempDir);
            assertThat(root.getKind()).isEqualTo(VfsRoot.Kind.DIRECTORY);
            assertThat(vfs.open(tempDir.toPath())).isSameAs(root);
            for (final var named : new VfsRoot[] { vfs.open(tempDir.toURI()), vfs.open(tempDir.toURI().toURL()) }) {
                assertThat(named.getKind()).isEqualTo(VfsRoot.Kind.DIRECTORY);
                assertThat(entryContent(named, "root.txt")).isEqualTo(RESOURCE_CONTENT);
            }
        }
    }

    /** A jarfile stored in a filesystem other than the default one is read through its Path. */
    @Test
    public void aJarfileInANonDefaultFilesystemCanBeRead(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");

        try (var fileSystem = Jimfs.newFileSystem(Configuration.unix()); var vfs = new Vfs()) {
            final var jarPath = fileSystem.getPath("/widget.jar");
            Files.write(jarPath, readFile(jarFile));

            final var root = vfs.open(jarPath);
            assertThat(root.getKind()).isEqualTo(VfsRoot.Kind.ARCHIVE);
            assertThat(root.getNioPath()).isEqualTo(jarPath);
            assertThat(entryContent(root, "com/xyz/widget.txt")).isEqualTo(RESOURCE_CONTENT);
            // Opening the same Path twice returns the same root, so the file is only opened once
            assertThat(vfs.open(jarPath)).isSameAs(root);
        }
    }

    /** A directory in a filesystem other than the default one is read through its Path. */
    @Test
    public void aDirectoryInANonDefaultFilesystemCanBeRead() throws IOException {
        try (var fileSystem = Jimfs.newFileSystem(Configuration.unix()); var vfs = new Vfs()) {
            final var dir = fileSystem.getPath("/classes");
            Files.createDirectories(dir.resolve("com/xyz"));
            Files.writeString(dir.resolve("com/xyz/widget.txt"), RESOURCE_CONTENT);

            final var root = vfs.open(dir);
            assertThat(root.getKind()).isEqualTo(VfsRoot.Kind.DIRECTORY);
            assertThat(root.getNioPath()).isEqualTo(dir);
            // A filesystem that has no File API reports no File
            assertThat(root.getFile()).isNull();
            assertThat(root.getEntries()).extracting(VfsEntry::getName).containsExactly("com/xyz/widget.txt");
            assertThat(entryContent(root, "com/xyz/widget.txt")).isEqualTo(RESOURCE_CONTENT);
            assertThat(vfs.open(dir)).isSameAs(root);
        }
    }

    /** A jarfile held in RAM can be read from a byte array or from a stream, without being written to disk. */
    @Test
    public void aJarfileCanBeReadFromABuiltInStreamOrByteArray(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");
        final var jarBytes = readFile(jarFile);

        try (var vfs = new Vfs()) {
            final var fromBytes = vfs.open(jarBytes, "in-memory.jar");
            assertThat(fromBytes.getKind()).isEqualTo(VfsRoot.Kind.ARCHIVE);
            assertThat(fromBytes.getPath()).isEqualTo("in-memory.jar");
            assertThat(fromBytes.getModuleName()).isEqualTo("com.xyz.widget");
            assertThat(entryContent(fromBytes, "com/xyz/widget.txt")).isEqualTo(RESOURCE_CONTENT);

            try (InputStream inputStream = new ByteArrayInputStream(jarBytes)) {
                final var fromStream = vfs.open(inputStream, "streamed.jar");
                assertThat(entryContent(fromStream, "com/xyz/widget.txt")).isEqualTo(RESOURCE_CONTENT);
                // A stream is read once, so what it produced is not cached and shared with the next reader
                assertThat(fromStream).isNotSameAs(vfs.open(jarBytes, "streamed.jar"));
            }
        }
    }

    /** A module of the running JDK is read through its ModuleReference. */
    @Test
    public void aModuleCanBeRead() throws IOException {
        final ModuleReference moduleReference = ModuleFinder.ofSystem().find("java.logging").orElseThrow();

        try (var vfs = new Vfs()) {
            final var root = vfs.open(moduleReference);
            assertThat(root.getKind()).isEqualTo(VfsRoot.Kind.MODULE);
            assertThat(root.getPath()).isEqualTo("java.logging");
            assertThat(root.getModuleName()).isEqualTo("java.logging");
            assertThat(root.getModuleReference()).isSameAs(moduleReference);
            assertThat(root.getPackageRoot()).isEmpty();
            assertThat(root.getURI().getScheme()).isEqualTo("jrt");
            // A module of the running JDK is read out of the runtime image, not out of a file
            assertThat(root.getNioPath()).isNull();
            assertThat(root.getFile()).isNull();
            // Opening the same module twice returns the same root
            assertThat(vfs.open(moduleReference)).isSameAs(root);

            assertThat(root.getEntries()).extracting(VfsEntry::getName).contains("java/util/logging/Logger.class")
                    .doesNotContain("java/util/logging/");

            final var entry = Objects.requireNonNull(root.getEntry("java/util/logging/Logger.class"));
            assertThat(entry.getPath()).isEqualTo("java.logging/java/util/logging/Logger.class");
            assertThat(entry.getURI().getScheme()).isEqualTo("jrt");
            // A module reader reports neither a length nor a modification time
            assertThat(entry.getLength()).isEqualTo(-1L);
            assertThat(entry.getLastModifiedTimeMillis()).isZero();
            assertThat(entry.getPosixFilePermissions()).isNull();

            // Every way of reading a module resource gives back a classfile
            assertThat(entry.load()).startsWith((byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE);
            try (var inputStream = entry.open()) {
                assertThat(inputStream.readNBytes(4)).containsExactly((byte) 0xCA, (byte) 0xFE, (byte) 0xBA,
                        (byte) 0xBE);
            }
            try (var buffer = entry.read()) {
                assertThat(Objects.requireNonNull(buffer.getByteBuffer()).getInt()).isEqualTo(0xCAFEBABE);
            }

            assertThat(root.getEntry("java/util/logging/Nonexistent.class")).isNull();
            assertThat(root.getEntry("")).isNull();
        }
    }

    /** A jarfile nested inside another jarfile is read in place. */
    @Test
    public void aNestedJarfileCanBeRead(@TempDir final File tempDir) throws IOException {
        final var innerJarFile = new File(tempDir, "inner.jar");
        writeJar(innerJarFile, "com/xyz/widget.txt");
        final var outerJarFile = new File(tempDir, "outer.jar");
        writeJarContainingJar(outerJarFile, "lib/inner.jar", readFile(innerJarFile));

        try (var vfs = new Vfs()) {
            final var root = vfs.open(outerJarFile.getPath() + "!/lib/inner.jar");
            assertThat(entryContent(root, "com/xyz/widget.txt")).isEqualTo(RESOURCE_CONTENT);
        }
    }

    /** With nested jarfiles disabled, a nested jarfile is not opened. */
    @Test
    public void aNestedJarfileIsNotOpenedIfNestedJarsAreDisabled(@TempDir final File tempDir) throws IOException {
        final var innerJarFile = new File(tempDir, "inner.jar");
        writeJar(innerJarFile, "com/xyz/widget.txt");
        final var outerJarFile = new File(tempDir, "outer.jar");
        writeJarContainingJar(outerJarFile, "lib/inner.jar", readFile(innerJarFile));

        try (var vfs = new Vfs().disableNestedJars()) {
            assertThatThrownBy(() -> vfs.open(outerJarFile.getPath() + "!/lib/inner.jar"))
                    .isInstanceOf(IOException.class);
        }
    }

    /** A trailing "!/" section that names a directory is used as the package root. */
    @Test
    public void aPackageRootStripsThePrefixFromEntryNames(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "app.jar");
        writeJar(jarFile, "BOOT-INF/classes/com/xyz/widget.txt");

        try (var vfs = new Vfs()) {
            final var root = vfs.open(jarFile.getPath() + "!/BOOT-INF/classes");
            assertThat(root.getPackageRoot()).isEqualTo("BOOT-INF/classes");
            assertThat(root.toString()).isEqualTo(root.getPath() + "!/BOOT-INF/classes");
            assertThat(root.getEntries()).extracting(VfsEntry::getName).containsExactly("com/xyz/widget.txt");
            final var entry = Objects.requireNonNull(root.getEntry("com/xyz/widget.txt"));
            assertThat(entry.getPath()).isEqualTo(root.getPath() + "!/BOOT-INF/classes/com/xyz/widget.txt");
            assertThat(entry.loadAsString()).isEqualTo(RESOURCE_CONTENT);
        }
    }

    /** Nothing can be opened after the Vfs has been closed. */
    @Test
    public void aRootCannotBeOpenedAfterTheVfsIsClosed(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");

        final var vfs = new Vfs();
        vfs.open(jarFile.getPath());
        vfs.close();
        assertThatThrownBy(() -> vfs.open(jarFile.getPath())).isInstanceOf(IOException.class)
                .hasMessageContaining("closed");
        assertThatThrownBy(() -> vfs.open(tempDir.toPath())).isInstanceOf(IOException.class)
                .hasMessageContaining("closed");
        assertThatThrownBy(() -> vfs.open(new byte[0], "in-memory.jar")).isInstanceOf(IOException.class)
                .hasMessageContaining("closed");
        // Closing twice is harmless
        vfs.close();
    }

    /** Opening a path that is neither a directory nor a jarfile fails with an IOException. */
    @Test
    public void openingSomethingThatIsNotAJarfileFails(@TempDir final File tempDir) throws IOException {
        final var notAJarFile = new File(tempDir, "not-a-jar.jar");
        try (var fileOut = new FileOutputStream(notAJarFile)) {
            fileOut.write("this is not a jarfile".getBytes(StandardCharsets.UTF_8));
        }

        try (var vfs = new Vfs()) {
            assertThatThrownBy(() -> vfs.open(notAJarFile.getPath())).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> vfs.open(new File(tempDir, "missing.jar").getPath()))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> vfs.open(new File(tempDir, "missing.jar").toPath()))
                    .isInstanceOf(IOException.class);
        }
    }

    /** A string that is not a URL scheme is rejected, rather than being stored where it can never match. */
    @Test
    public void aStringThatIsNotAURLSchemeIsRejected() throws IOException {
        try (var vfs = new Vfs()) {
            assertThat(vfs.enableURLScheme("https")).isSameAs(vfs);
            // A one-character scheme cannot be told apart from a Windows drive letter
            assertThatThrownBy(() -> vfs.enableURLScheme("c")).isInstanceOf(IllegalArgumentException.class);
            // The commonest mistake: including the scheme's trailing ':'
            assertThatThrownBy(() -> vfs.enableURLScheme("https:")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * By default, a multi-release jarfile looks like an ordinary jarfile: the versioned copies of a resource are
     * reported under the unversioned path, and only the newest copy the running JVM can use is visible.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into.
     * @throws IOException
     *             if the jarfile could not be written or read.
     */
    @Test
    public void aMultiReleaseJarfileReportsOneVersionOfEachResource(@TempDir final File tempDir)
            throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeMultiReleaseJar(jarFile, "com/xyz/widget.txt");

        try (var vfs = new Vfs()) {
            final var root = vfs.open(jarFile.getPath());
            // The version 9 copy masks the unversioned one. The version 9999 copy is newer than any JVM that
            // exists, so it cannot be used, and is left under its versioned path rather than masking anything
            assertThat(root.getEntries()).extracting(VfsEntry::getName).containsExactlyInAnyOrder(
                    "META-INF/MANIFEST.MF", "com/xyz/widget.txt", "META-INF/versions/9999/com/xyz/widget.txt");
            assertThat(entryContent(root, "com/xyz/widget.txt")).isEqualTo("version 9");
        }
    }

    /**
     * With multi-release versions enabled, every versioned copy of a resource is reported separately, under the
     * path it has in the jarfile, so that a caller can see all of them rather than the one the JVM would use.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into.
     * @throws IOException
     *             if the jarfile could not be written or read.
     */
    @Test
    public void everyVersionOfAResourceIsVisibleIfMultiReleaseVersionsAreEnabled(@TempDir final File tempDir)
            throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeMultiReleaseJar(jarFile, "com/xyz/widget.txt");

        try (var vfs = new Vfs()) {
            assertThat(vfs.enableMultiReleaseVersions()).isSameAs(vfs);
            final var root = vfs.open(jarFile.getPath());
            assertThat(root.getEntries()).extracting(VfsEntry::getName).containsExactlyInAnyOrder(
                    "META-INF/MANIFEST.MF", "com/xyz/widget.txt", "META-INF/versions/9/com/xyz/widget.txt",
                    "META-INF/versions/9999/com/xyz/widget.txt");
            assertThat(entryContent(root, "com/xyz/widget.txt")).isEqualTo("base");
            assertThat(entryContent(root, "META-INF/versions/9/com/xyz/widget.txt")).isEqualTo("version 9");
        }
    }

    /** A negative RAM size is rejected. */
    @Test
    public void aNegativeMaxBufferedJarRAMSizeIsRejected() throws IOException {
        try (var vfs = new Vfs()) {
            assertThat(vfs.maxBufferedJarRAMSize(1024)).isSameAs(vfs);
            assertThatThrownBy(() -> vfs.maxBufferedJarRAMSize(-1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** Null arguments are rejected. */
    @Test
    public void nullArgumentsAreRejected(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");

        try (var vfs = new Vfs(); InputStream inputStream = new ByteArrayInputStream(new byte[0])) {
            assertThatThrownBy(() -> vfs.open((String) null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> vfs.open((File) null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> vfs.open((Path) null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> vfs.open((java.net.URI) null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> vfs.open((java.net.URL) null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> vfs.open((ModuleReference) null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> vfs.open((InputStream) null, "in-memory.jar"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> vfs.open(inputStream, null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> vfs.open((byte[]) null, "in-memory.jar"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> vfs.open(new byte[0], null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> vfs.enableURLScheme(null)).isInstanceOf(NullPointerException.class);

            final var root = vfs.open(jarFile.getPath());
            assertThatThrownBy(() -> root.getEntry(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> vfs.open(tempDir).getEntry(null)).isInstanceOf(NullPointerException.class);
        }
    }
}
