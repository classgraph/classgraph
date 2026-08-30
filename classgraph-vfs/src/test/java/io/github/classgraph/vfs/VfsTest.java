package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.abort;

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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Tests the public API of the virtual filesystem. */
public class VfsTest {
    /** The content of the test resource. */
    private static final String RESOURCE_CONTENT = "vfs-test";

    /**
     * Build a virtual filesystem that refuses to fetch jarfiles from URLs with the given schemes, leaving every
     * other option at its default.
     *
     * @param urlSchemes
     *            the URL schemes to deny.
     * @return the virtual filesystem.
     */
    private static Vfs vfsWithoutURLSchemes(final String... urlSchemes) {
        final var vfsSpec = new VfsSpec();
        for (final var urlScheme : urlSchemes) {
            vfsSpec.disableURLScheme(urlScheme);
        }
        return new Vfs(vfsSpec);
    }

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
     * Write a jarfile holding an entry for each of the given names, and no manifest, so that it holds exactly the
     * entries the test asks for.
     *
     * @param jarFile
     *            the jarfile to write.
     * @param entryNames
     *            the names of the entries to write.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJarWithEntries(final File jarFile, final String... entryNames) throws IOException {
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            for (final String entryName : entryNames) {
                zipOut.putNextEntry(new ZipEntry(entryName));
                zipOut.write(RESOURCE_CONTENT.getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
            }
        }
    }

    /**
     * Write a directory holding a file for each of the given names, creating the directories on the way to each of
     * them.
     *
     * @param dir
     *            the directory to write.
     * @param fileNames
     *            the names of the files to write, relative to the directory.
     * @throws IOException
     *             if the directory could not be written.
     */
    private static void writeDirWithFiles(final File dir, final String... fileNames) throws IOException {
        for (final String fileName : fileNames) {
            final var file = new File(dir, fileName);
            final var parentDir = Objects.requireNonNull(file.getParentFile());
            assertThat(parentDir.mkdirs() || parentDir.isDirectory()).isTrue();
            Files.writeString(file.toPath(), RESOURCE_CONTENT);
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
     * Write a jarfile containing another jarfile, deflated rather than stored, so that the inner jarfile has to be
     * inflated before it can be read.
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
    private static void writeJarContainingDeflatedJar(final File outerJarFile, final String innerJarEntryName,
            final byte[] innerJarBytes) throws IOException {
        try (var fileOut = new FileOutputStream(outerJarFile); var zipOut = new ZipOutputStream(fileOut)) {
            // ZipOutputStream deflates by default
            zipOut.putNextEntry(new ZipEntry(innerJarEntryName));
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

    /**
     * Create a symlink, or skip the test if the filesystem does not allow it (creating a symlink needs a privilege
     * that is not granted by default on Windows).
     *
     * @param link
     *            the symlink to create.
     * @param target
     *            the target of the symlink.
     * @return the symlink.
     */
    private static Path createSymbolicLinkOrSkip(final Path link, final Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            abort("Symlinks cannot be created: " + e);
            return link;
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
            assertThat(root.getEntries()).extracting(VfsEntry::getPathFromRoot)
                    .containsExactlyInAnyOrder("META-INF/MANIFEST.MF", "com/xyz/widget.txt");

            final var entry = Objects.requireNonNull(root.getEntry("com/xyz/widget.txt"));
            assertThat(entry.getRoot()).isSameAs(root);
            assertThat(entry.getPath()).isEqualTo(root.getPath() + "!/com/xyz/widget.txt");
            assertThat(entry.toString()).isEqualTo(entry.getPath());
            assertThat(entry.getURI().toString()).startsWith("jar:file:").endsWith("!/com/xyz/widget.txt");
            assertThat(entry.getLength()).isEqualTo(RESOURCE_CONTENT.length());
            assertThat(entry.getCompressedSize()).isPositive();
            assertThat(entry.getLastModifiedMillis()).isPositive();

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

    /**
     * The Unix mode bits of a jarfile entry are decoded into an unmodifiable set of POSIX permissions that iterates
     * in {@link PosixFilePermission} declaration order.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be written or read.
     */
    @Test
    public void theUnixModeBitsOfAJarfileEntryAreDecoded(@TempDir final File tempDir) throws IOException {
        // A jarfile written by ZipOutputStream records no mode bits, so write this one through the zip filesystem
        // provider, which does record them
        final var jarFile = new File(tempDir, "permissions.jar");
        final var written = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_EXECUTE);
        try (var zipFileSystem = FileSystems.newFileSystem(jarFile.toPath(),
                Map.of("create", "true", "enablePosixFileAttributes", "true"))) {
            final var entry = zipFileSystem.getPath("com/xyz/widget.txt");
            Files.createDirectories(entry.getParent());
            Files.writeString(entry, RESOURCE_CONTENT);
            Files.setPosixFilePermissions(entry, written);
        }

        try (var vfs = new Vfs()) {
            final var permissions = Objects
                    .requireNonNull(vfs.open(jarFile.getPath()).getEntry("com/xyz/widget.txt"))
                    .getPosixFilePermissions();
            assertThat(permissions).containsExactlyElementsOf(written);
            assertThatThrownBy(() -> Objects.requireNonNull(permissions).clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    /** A root is named by its jarfile or directory, and an entry by the last segment of its name. */
    @Test
    public void rootsAndEntriesReportTheirLeafNames(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");

        try (var vfs = new Vfs()) {
            final var jarRoot = vfs.open(jarFile.getPath());
            assertThat(jarRoot.getLastSegment()).isEqualTo("widget.jar");
            assertThat(Objects.requireNonNull(jarRoot.getEntry("com/xyz/widget.txt")).getLastSegment())
                    .isEqualTo("widget.txt");
            // A directory root is named by the directory itself
            final var dirRoot = vfs.open(tempDir.getPath());
            assertThat(dirRoot.getLastSegment()).isEqualTo(tempDir.getCanonicalFile().getName());
            assertThat(Objects.requireNonNull(dirRoot.getEntry("widget.jar")).getLastSegment())
                    .isEqualTo("widget.jar");
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

    /**
     * The main section of a jarfile's manifest is read, keyed case-insensitively by attribute name, and an
     * attribute that the manifest does not declare is null rather than an error.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be written or read.
     */
    @Test
    public void theManifestOfAJarfileIsRead(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");

        try (var vfs = new Vfs()) {
            final var root = vfs.open(jarFile.getPath());
            assertThat(root.getManifest()).containsOnly(Map.entry("Manifest-Version", "1.0"),
                    Map.entry("Automatic-Module-Name", "com.xyz.widget"));
            assertThat(root.getManifestEntry("automatic-module-name")).isEqualTo("com.xyz.widget");
            assertThat(root.getManifestEntry("Main-Class")).isNull();
            // The manifest is read once and then cached, so the same map is handed out every time
            assertThat(root.getManifest()).isSameAs(root.getManifest());
        }
    }

    /**
     * A directory's manifest is read from the same place a jarfile's is, so an exploded jarfile is described by its
     * manifest just as the jarfile it was exploded from is.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the directory could not be written or read.
     */
    @Test
    public void theManifestOfADirectoryIsRead(@TempDir final File tempDir) throws IOException {
        final var dir = new File(tempDir, "exploded");
        assertThat(new File(dir, "META-INF").mkdirs()).isTrue();
        Files.writeString(new File(dir, "META-INF/MANIFEST.MF").toPath(),
                "Manifest-Version: 1.0\r\nClass-Path: lib/dep.jar\r\n\r\n");

        try (var vfs = new Vfs()) {
            assertThat(vfs.open(dir.getPath()).getManifestEntry("Class-Path")).isEqualTo("lib/dep.jar");
        }
    }

    /**
     * A root with no manifest file has no manifest at all, rather than an empty one, and every attribute of it is
     * null.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the directory could not be written or read.
     */
    @Test
    public void aRootWithNoManifestFileHasNoManifest(@TempDir final File tempDir) throws IOException {
        final var dir = new File(tempDir, "classes");
        writeDirWithFiles(dir, "com/xyz/widget.txt");

        try (var vfs = new Vfs()) {
            final var root = vfs.open(dir.getPath());
            assertThat(root.getManifest()).isNull();
            assertThat(root.getManifestEntry("Automatic-Module-Name")).isNull();
        }
    }

    /**
     * Only the entries of a jarfile whose names start with a prefix are listed, in the order the whole root lists
     * them in.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be written or read.
     */
    @Test
    public void theEntriesOfAJarfileUnderAPrefixAreListed(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "app.jar");
        writeJarWithEntries(jarFile, "root.txt", "BOOT-INF/classes/com/xyz/App.class", "BOOT-INF/lib/a.jar",
                "BOOT-INF/lib/b.jar", "BOOT-INF/lib-provided/c.jar");

        try (var vfs = new Vfs()) {
            final var root = vfs.open(jarFile.getPath());
            assertThat(root.getEntries("BOOT-INF/lib/")).extracting(VfsEntry::getPathFromRoot)
                    .containsExactly("BOOT-INF/lib/a.jar", "BOOT-INF/lib/b.jar");
            // A prefix is matched against the whole entry name, so it need not end at a directory boundary. A
            // jarfile lists its entries in the order its central directory holds them, which is the order they
            // were written in, so "lib-provided" comes last here rather than where sorting by name would put it.
            assertThat(root.getEntries("BOOT-INF/lib")).extracting(VfsEntry::getPathFromRoot)
                    .containsExactly("BOOT-INF/lib/a.jar", "BOOT-INF/lib/b.jar", "BOOT-INF/lib-provided/c.jar");
            assertThat(root.getEntries("BOOT-INF/classes/com/xyz/Ap")).extracting(VfsEntry::getPathFromRoot)
                    .containsExactly("BOOT-INF/classes/com/xyz/App.class");
            // The empty prefix lists the whole root, and a prefix nothing starts with lists nothing
            assertThat(root.getEntries("")).extracting(VfsEntry::getPathFromRoot)
                    .isEqualTo(root.getEntries().stream().map(VfsEntry::getPathFromRoot).toList());
            assertThat(root.getEntries("nothing/")).isEmpty();
        }
    }

    /**
     * Only the files of a directory tree whose names start with a prefix are listed, in the order the whole root
     * lists them in, however deeply nested they are.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the directory could not be written or read.
     */
    @Test
    public void theEntriesOfADirectoryUnderAPrefixAreListed(@TempDir final File tempDir) throws IOException {
        final var dir = new File(tempDir, "exploded");
        writeDirWithFiles(dir, "root.txt", "BOOT-INF/classes/com/xyz/App.class", "BOOT-INF/lib/a.jar",
                "BOOT-INF/lib/b.jar");

        try (var vfs = new Vfs()) {
            final var root = vfs.open(dir.getPath());
            assertThat(root.getEntries("BOOT-INF/lib/")).extracting(VfsEntry::getPathFromRoot)
                    .containsExactly("BOOT-INF/lib/a.jar", "BOOT-INF/lib/b.jar");
            assertThat(root.getEntries("root")).extracting(VfsEntry::getPathFromRoot).containsExactly("root.txt");
            assertThat(root.getEntries("")).extracting(VfsEntry::getPathFromRoot)
                    .isEqualTo(root.getEntries().stream().map(VfsEntry::getPathFromRoot).toList());
            assertThat(root.getEntries("nothing/")).isEmpty();
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

    /** The roots that are currently open can be iterated, sorted by path. */
    @Test
    public void theOpenRootsCanBeIterated(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");
        final var dir = new File(tempDir, "classes");
        writeDirWithFiles(dir, "com/xyz/gadget.txt");

        try (var vfs = new Vfs()) {
            // A Vfs that has not opened anything has no roots
            assertThat(vfs).isEmpty();

            // The roots come back sorted by path, so the directory comes before the jarfile beside it, whichever
            // order they were opened in
            final var jarRoot = vfs.open(jarFile.getPath());
            final var dirRoot = vfs.open(dir.getPath());
            assertThat(vfs).containsExactly(dirRoot, jarRoot);

            // A root opened from a byte array is not named by any path, so it is not cached, and not iterated
            final var inMemoryRoot = vfs.open(readFile(jarFile), "in-memory.jar");
            assertThat(inMemoryRoot.getEntry("com/xyz/widget.txt")).isNotNull();
            assertThat(vfs).containsExactly(dirRoot, jarRoot);

            // Evicting a root takes it out of the Vfs it was opened by
            vfs.evict(jarRoot);
            assertThat(vfs).containsExactly(dirRoot);
        }
    }

    /**
     * A root that is cached under more than one path -- under both the path it was opened from and the canonical
     * path of the jarfile that turned out to back it -- is still only iterated once.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    @Test
    public void aRootCachedUnderTwoPathsIsIteratedOnce(@TempDir final File tempDir) throws IOException {
        final var realDir = new File(tempDir, "real");
        assertThat(realDir.mkdir()).isTrue();
        final var jarFile = new File(realDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");
        final var linkedDir = createSymbolicLinkOrSkip(new File(tempDir, "link").toPath(), realDir.toPath());

        try (var vfs = new Vfs()) {
            final var root = vfs.open(linkedDir.resolve("widget.jar").toString());
            // The root is cached under the path it was opened from, through the symlink, and under the canonical
            // path of the jarfile that backs it, so that either path reaches it without reading the jarfile again
            assertThat(root.getPath()).contains("/real/").doesNotContain("/link/");
            assertThat(vfs.open(jarFile.getCanonicalPath())).isSameAs(root);
            assertThat(vfs).containsExactly(root);
        }
    }

    /** Concurrent opens through a symlink and its canonical path converge on one cached root. */
    @Test
    public void canonicalAliasesConvergeWhenOpenedConcurrently(@TempDir final File tempDir) throws Exception {
        final var realDir = new File(tempDir, "real");
        assertThat(realDir.mkdir()).isTrue();
        Files.writeString(realDir.toPath().resolve("entry.txt"), RESOURCE_CONTENT);
        final Path linkedDir = createSymbolicLinkOrSkip(new File(tempDir, "link").toPath(), realDir.toPath());
        final var executor = Executors.newFixedThreadPool(2);
        try {
            for (var repetition = 0; repetition < 50; repetition++) {
                try (var vfs = new Vfs()) {
                    final var barrier = new CyclicBarrier(2);
                    final var throughLink = executor.submit(() -> {
                        barrier.await();
                        return vfs.open(linkedDir.toString());
                    });
                    final var canonical = executor.submit(() -> {
                        barrier.await();
                        return vfs.open(realDir.getCanonicalPath());
                    });
                    assertThat(throughLink.get()).isSameAs(canonical.get());
                    assertThat(vfs.open(linkedDir.toString())).isSameAs(vfs.open(realDir.getCanonicalPath()));
                }
            }
        } finally {
            executor.shutdown();
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
            // The path is canonicalized, so that the same directory reached by two different paths is only opened
            // once. On Windows that expands an 8.3 short name, and on macOS it resolves a symlink, so the path of
            // the temp directory is not necessarily the path it is reported as.
            assertThat(root.getNioPath()).isEqualTo(dir.toPath().toRealPath());
            assertThat(root.getFile()).isNotNull();
            assertThat(root.getURI().getScheme()).isEqualTo("file");
            // A directory is listed recursively, and its subdirectories are not themselves entries. Each
            // directory's own files come before its subdirectories.
            assertThat(root.getEntries()).extracting(VfsEntry::getPathFromRoot).containsExactly("root.txt",
                    "com/xyz/widget.txt");

            final var entry = Objects.requireNonNull(root.getEntry("com/xyz/widget.txt"));
            assertThat(entry.getPath()).isEqualTo(root.getPath() + "/com/xyz/widget.txt");
            assertThat(entry.getNioPath()).isNotNull();
            assertThat(entry.getLength()).isEqualTo(RESOURCE_CONTENT.length());
            assertThat(entry.getLastModifiedMillis()).isPositive();
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
                // The permissions are unmodifiable, and iterate in PosixFilePermission declaration order
                assertThat(List.copyOf(permissions)).isSortedAccordingTo(Comparator.naturalOrder());
                assertThatThrownBy(() -> permissions.add(PosixFilePermission.OTHERS_WRITE))
                        .isInstanceOf(UnsupportedOperationException.class);
            }

            // A name cannot reach outside the directory it is resolved against
            assertThat(root.getEntry("com/xyz/../../../outside.txt")).isNull();
            assertThat(root.getEntry("com/xyz")).isNull();
            assertThat(root.getEntry("")).isNull();
        }
    }

    /**
     * A file that cannot be read is not reported as an entry, since an entry is something that can be read.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the directory could not be written or read.
     */
    @Test
    public void anUnreadableFileIsNotAnEntry(@TempDir final File tempDir) throws IOException {
        final var unreadable = new File(tempDir, "unreadable.txt");
        Files.writeString(unreadable.toPath(), RESOURCE_CONTENT);
        if (!unreadable.setReadable(false) || unreadable.canRead()) {
            // Running as root, or on a filesystem that does not enforce permissions, the file stays readable
            abort("Files cannot be made unreadable here");
        }

        try (var vfs = new Vfs()) {
            assertThat(vfs.open(tempDir).getEntry("unreadable.txt")).isNull();
        }
    }

    /**
     * A directory that cannot be read cannot be opened as a root, since nothing can be listed or read through it.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the directory could not be written.
     */
    @Test
    public void anUnreadableDirectoryCannotBeOpened(@TempDir final File tempDir) throws IOException {
        final var unreadable = new File(tempDir, "unreadable");
        assertThat(unreadable.mkdir()).isTrue();
        if (!unreadable.setReadable(false) || unreadable.canRead()) {
            // Running as root, or on a filesystem that does not enforce permissions, the directory stays readable
            abort("Directories cannot be made unreadable here");
        }

        try (var vfs = new Vfs()) {
            assertThatThrownBy(() -> vfs.open(unreadable)).isInstanceOf(IOException.class)
                    .hasMessageContaining("Not a readable directory");
        }
    }

    /**
     * A relative path may begin with something shaped like a URL scheme, since ':' is a legal filename character on
     * every platform but Windows. Such a path names something on disk, and must not be fetched as a URL.
     *
     * @throws IOException
     *             if the directory or jarfile could not be written.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "':' is not a legal character in a Windows filename")
    public void aRelativePathThatLooksLikeAURLIsOpenedFromDisk() throws IOException {
        // The ':' has to fall in the first segment of a relative path for the path to look like a URL, since a
        // scheme may not contain a '/'. A relative path is resolved against the working directory, so that is the
        // only place this directory can go.
        final var colonDir = new File("cgtest:relpath");
        final var fileInColonDir = new File(colonDir, "widget.txt");
        final var jarInColonDir = new File(colonDir, "widget.jar");
        try {
            assertThat(colonDir.mkdir()).isTrue();
            writeDirWithFiles(colonDir, "widget.txt");
            writeJar(jarInColonDir, "com/xyz/widget.txt");

            try (var vfs = new Vfs()) {
                // Without the filesystem being tested first, "cgtest:" is read as a URL scheme, and neither of
                // these is opened from disk at all
                assertThat(entryContent(vfs.open("cgtest:relpath"), "widget.txt")).isEqualTo(RESOURCE_CONTENT);
                assertThat(entryContent(vfs.open("cgtest:relpath/widget.jar"), "com/xyz/widget.txt"))
                        .isEqualTo(RESOURCE_CONTENT);
            }
        } finally {
            // Delete leaves before the directory that holds them
            for (final var file : new File[] { fileInColonDir, jarInColonDir, colonDir }) {
                Files.deleteIfExists(file.toPath());
            }
        }
    }

    /** The same jarfile can be named by a path string, a File, a Path, a URI or a URL. */
    @Test
    public void aJarfileCanBeNamedInEveryWayJavaNamesAFile(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");
        final var uri = jarFile.toURI();

        try (var vfs = new Vfs()) {
            // A File, a Path in the default filesystem, a URI and a URL all name what the path string names, so
            // each of them returns the root that the first of them opened, rather than opening the jarfile again
            final var root = vfs.open(jarFile.getPath());
            assertThat(vfs.open(jarFile)).isSameAs(root);
            assertThat(vfs.open(jarFile.toPath())).isSameAs(root);
            assertThat(vfs.open(uri)).isSameAs(root);
            assertThat(vfs.open(uri.toURL())).isSameAs(root);
            assertThat(vfs.open("jar:" + uri + "!/")).isSameAs(root);
            // A root is named by the canonical path of the jarfile that backs it, written with forward slashes on
            // every platform, so the path it reports is not always the path it was opened from -- but it is always
            // a path that reopens the same root
            assertThat(vfs.open(root.getPath())).isSameAs(root);
            assertThat(entryContent(root, "com/xyz/widget.txt")).isEqualTo(RESOURCE_CONTENT);
        }
    }

    /**
     * A jarfile reached through a symlinked directory is named by the jarfile it reaches, so that naming it either
     * way reaches the one root rather than reading the jarfile a second time.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    @Test
    public void aJarfileReachedThroughASymlinkIsOpenedOnce(@TempDir final File tempDir) throws IOException {
        final var realDir = new File(tempDir, "real");
        assertThat(realDir.mkdir()).isTrue();
        final var jarFile = new File(realDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");
        final var linkedDir = createSymbolicLinkOrSkip(new File(tempDir, "link").toPath(), realDir.toPath());

        try (var vfs = new Vfs()) {
            final var root = vfs.open(linkedDir.resolve("widget.jar").toString());
            // The root is named by the jarfile the symlink points at, rather than by the path it was opened from
            assertThat(root.getPath()).contains("/real/").doesNotContain("/link/");
            // So naming the jarfile directly reaches that same root, rather than reading it a second time. Only the
            // canonical path is guaranteed to do so: the temporary directory is itself reached through a symlink on
            // some platforms, and two paths that are both non-canonical are two different names for this purpose
            assertThat(vfs.open(root.getPath())).isSameAs(root);
            assertThat(vfs.open(jarFile.getCanonicalPath())).isSameAs(root);
            assertThat(entryContent(root, "com/xyz/widget.txt")).isEqualTo(RESOURCE_CONTENT);
        }
    }

    /**
     * A directory reached through a symlink is named by the directory it reaches, just as a jarfile is, so that
     * naming it either way reaches the one root rather than walking the directory a second time.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the directory could not be written.
     */
    @Test
    public void aDirectoryReachedThroughASymlinkIsOpenedOnce(@TempDir final File tempDir) throws IOException {
        final var realDir = new File(tempDir, "real");
        assertThat(realDir.mkdir()).isTrue();
        Files.writeString(new File(realDir, "widget.txt").toPath(), RESOURCE_CONTENT);
        final var linkedDir = createSymbolicLinkOrSkip(new File(tempDir, "link").toPath(), realDir.toPath());

        try (var vfs = new Vfs()) {
            final var root = vfs.open(linkedDir.toString());
            // The root is named by the directory the symlink points at, rather than by the path it was opened from
            assertThat(root.getPath()).endsWith("/real").doesNotContain("/link");
            // So naming the directory directly reaches that same root, rather than walking it a second time. Only
            // the canonical path is guaranteed to do so: the temporary directory is itself reached through a symlink
            // on some platforms, and two paths that are both non-canonical are two different names for this purpose
            assertThat(vfs.open(root.getPath())).isSameAs(root);
            assertThat(vfs.open(realDir.getCanonicalPath())).isSameAs(root);
            assertThat(entryContent(root, "widget.txt")).isEqualTo(RESOURCE_CONTENT);
        }
    }

    /**
     * A directory or jarfile opened at its own path, and then again through a symlink that reaches it, is opened
     * once: the second name reaches the root that the first one opened, rather than a second view of the same
     * directory or jarfile.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if the directory or jarfile could not be written.
     */
    @Test
    public void aSymlinkToAnAlreadyOpenedPathReachesTheSameRoot(@TempDir final File tempDir) throws IOException {
        final var realDir = new File(tempDir, "real");
        assertThat(realDir.mkdir()).isTrue();
        final var jarFile = new File(realDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");
        final var linkedDir = createSymbolicLinkOrSkip(new File(tempDir, "link").toPath(), realDir.toPath());

        try (var vfs = new Vfs()) {
            final var dirRoot = vfs.open(realDir.getCanonicalPath());
            assertThat(vfs.open(linkedDir.toString())).isSameAs(dirRoot);

            final var jarRoot = vfs.open(jarFile.getCanonicalPath());
            assertThat(vfs.open(linkedDir.resolve("widget.jar").toString())).isSameAs(jarRoot);
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
            assertThat(vfs.open(tempDir.toURI())).isSameAs(root);
            assertThat(vfs.open(tempDir.toURI().toURL())).isSameAs(root);
            assertThat(vfs.open(Path.of(root.getPath()))).isSameAs(root);
            assertThat(entryContent(root, "root.txt")).isEqualTo(RESOURCE_CONTENT);
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
            assertThat(root.getEntries()).extracting(VfsEntry::getPathFromRoot)
                    .containsExactly("com/xyz/widget.txt");
            assertThat(entryContent(root, "com/xyz/widget.txt")).isEqualTo(RESOURCE_CONTENT);
            assertThat(vfs.open(dir)).isSameAs(root);
            // The path of the directory in its own filesystem is "/classes", which names nothing outside that
            // filesystem, so the root is named by its URI instead
            assertThat(root.getPath()).startsWith("jimfs://").endsWith("/classes");
        }
    }

    /**
     * A directory of a mounted zipfile is named the way a package root within a jarfile is named everywhere else in
     * ClassGraph: the path of the jarfile, {@code "!/"}, and the path of the directory within it.
     */
    @Test
    public void aDirectoryInAMountedZipfileIsNamedByTheJarfileItIsIn(@TempDir final File tempDir)
            throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");

        try (var fileSystem = FileSystems.newFileSystem(jarFile.toPath()); var vfs = new Vfs()) {
            final var root = vfs.open(fileSystem.getPath("/com"));
            assertThat(root.getKind()).isEqualTo(VfsRoot.Kind.DIRECTORY);
            assertThat(root.getPath()).endsWith("/widget.jar!/com");
            assertThat(root.getEntries()).extracting(VfsEntry::getPathFromRoot).containsExactly("xyz/widget.txt");
            assertThat(entryContent(root, "xyz/widget.txt")).isEqualTo(RESOURCE_CONTENT);
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

    /**
     * The caller keeps ownership of a stream it hands to {@link Vfs#open(InputStream, String)}. The stream is read
     * to its end, but closing it is the caller's business: the caller may have opened it from something it still
     * needs, and closing another object's stream out from under it is not the {@link Vfs}'s decision to make.
     */
    @Test
    public void readingAJarfileFromAStreamDoesNotCloseTheStream(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");
        final var jarBytes = readFile(jarFile);

        // A stream that records whether it was closed
        final var closed = new AtomicBoolean();
        final var inputStream = new ByteArrayInputStream(jarBytes) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        try (var vfs = new Vfs()) {
            final var root = vfs.open(inputStream, "streamed.jar");
            assertThat(entryContent(root, "com/xyz/widget.txt")).isEqualTo(RESOURCE_CONTENT);
            assertThat(closed).isFalse();
        }
        // Closing the Vfs does not close it either -- the Vfs never owned it
        assertThat(closed).isFalse();
    }

    /**
     * A jarfile read from a stream that is larger than the maximum buffered jar RAM size is spilled to a temporary
     * file, which is a second path through the stream reader, and it must not close the stream either.
     */
    @Test
    public void readingALargeJarfileFromAStreamDoesNotCloseTheStream(@TempDir final File tempDir)
            throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");
        final var jarBytes = readFile(jarFile);

        final var closed = new AtomicBoolean();
        final var inputStream = new ByteArrayInputStream(jarBytes) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        // A maximum buffered jar RAM size of zero forces the stream to spill to a temporary file
        try (var vfs = new Vfs(new VfsSpec().setMaxBufferedJarRAMSize(0))) {
            final var root = vfs.open(inputStream, "streamed.jar");
            assertThat(entryContent(root, "com/xyz/widget.txt")).isEqualTo(RESOURCE_CONTENT);
            assertThat(closed).isFalse();
        }
        assertThat(closed).isFalse();
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

            assertThat(root.getEntries()).extracting(VfsEntry::getPathFromRoot)
                    .contains("java/util/logging/Logger.class").doesNotContain("java/util/logging/");

            final var entry = Objects.requireNonNull(root.getEntry("java/util/logging/Logger.class"));
            assertThat(entry.getPath()).isEqualTo("java.logging/java/util/logging/Logger.class");
            assertThat(entry.getURI().getScheme()).isEqualTo("jrt");
            // A module reader reports neither a length nor a modification time
            assertThat(entry.getLength()).isEqualTo(-1L);
            assertThat(entry.getLastModifiedMillis()).isZero();
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

    /**
     * A deflated nested jarfile cannot be read in place, so it is inflated, and spills to a temporary file if it is
     * not allowed to be buffered in RAM. Closing the virtual filesystem deletes the temporary file.
     */
    @Test
    public void temporaryFilesAreReportedUntilTheyAreDeleted(@TempDir final File tempDir) throws IOException {
        final var innerJarFile = new File(tempDir, "inner.jar");
        writeJar(innerJarFile, "com/xyz/widget.txt");
        final var outerJarFile = new File(tempDir, "outer.jar");
        writeJarContainingDeflatedJar(outerJarFile, "lib/inner.jar", readFile(innerJarFile));

        // A jarfile on disk is read in place, so no temporary file is needed
        try (var vfs = new Vfs()) {
            assertThat(vfs.open(outerJarFile).getEntries()).isNotEmpty();
            assertThat(vfs.hasTempFiles()).isFalse();
        }

        // The inner jarfile has to be inflated, and no RAM is allowed to hold it, so it spills to a temporary file
        final Vfs closedVfs;
        try (var vfs = new Vfs(new VfsSpec().setMaxBufferedJarRAMSize(0))) {
            final var root = vfs.open(outerJarFile.getPath() + "!/lib/inner.jar");
            assertThat(entryContent(root, "com/xyz/widget.txt")).isEqualTo(RESOURCE_CONTENT);
            assertThat(vfs.hasTempFiles()).isTrue();
            closedVfs = vfs;
        }
        // The temporary file was deleted by close()
        assertThat(closedVfs.hasTempFiles()).isFalse();
    }

    /**
     * A nested jarfile can equally be named by a {@code "jar:"} URL string, since the {@code "jar:"} and
     * {@code "file:"} prefixes are stripped before the path is read. The {@code "!/"} separator means the same
     * thing with or without them: it is identified by testing the filesystem, not by the URL scheme.
     */
    @Test
    public void aNestedJarfileCanAlsoBeNamedByAJarUrlString(@TempDir final File tempDir) throws IOException {
        final var innerJarFile = new File(tempDir, "inner.jar");
        writeJar(innerJarFile, "com/xyz/widget.txt");
        final var outerJarFile = new File(tempDir, "outer.jar");
        writeJarContainingJar(outerJarFile, "lib/inner.jar", readFile(innerJarFile));

        try (var vfs = new Vfs()) {
            final var root = vfs.open("jar:" + outerJarFile.toURI() + "!/lib/inner.jar");
            assertThat(entryContent(root, "com/xyz/widget.txt")).isEqualTo(RESOURCE_CONTENT);
            // The same jarfile, named without the URL scheme prefixes
            assertThat(root.getPath()).isEqualTo(vfs.open(outerJarFile.getPath() + "!/lib/inner.jar").getPath());
        }
    }

    /** With nested jarfiles disabled, a nested jarfile is not opened. */
    @Test
    public void aNestedJarfileIsNotOpenedIfNestedJarsAreDisabled(@TempDir final File tempDir) throws IOException {
        final var innerJarFile = new File(tempDir, "inner.jar");
        writeJar(innerJarFile, "com/xyz/widget.txt");
        final var outerJarFile = new File(tempDir, "outer.jar");
        writeJarContainingJar(outerJarFile, "lib/inner.jar", readFile(innerJarFile));

        try (var vfs = new Vfs(new VfsSpec().disableNestedJars())) {
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
            assertThat(root.getEntries()).extracting(VfsEntry::getPathFromRoot)
                    .containsExactly("com/xyz/widget.txt");
            final var entry = Objects.requireNonNull(root.getEntry("com/xyz/widget.txt"));
            assertThat(entry.getPath()).isEqualTo(root.getPath() + "!/BOOT-INF/classes/com/xyz/widget.txt");
            assertThat(entry.loadAsString()).isEqualTo(RESOURCE_CONTENT);
        }
    }

    /** Nothing can be opened, and nothing can be configured, after the Vfs has been closed. */
    @Test
    public void aRootCannotBeOpenedAfterTheVfsIsClosed(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");
        final ModuleReference moduleReference = ModuleFinder.ofSystem().find("java.logging").orElseThrow();

        final var vfs = new Vfs();
        vfs.open(jarFile.getPath());
        vfs.close();
        assertThatThrownBy(() -> vfs.open(jarFile.getPath())).isInstanceOf(IOException.class)
                .hasMessageContaining("closed");
        assertThatThrownBy(() -> vfs.open(tempDir.toPath())).isInstanceOf(IOException.class)
                .hasMessageContaining("closed");
        assertThatThrownBy(() -> vfs.open(new byte[0], "in-memory.jar")).isInstanceOf(IOException.class)
                .hasMessageContaining("closed");
        assertThatThrownBy(() -> vfs.open(new ByteArrayInputStream(new byte[0]), "in-memory.jar"))
                .isInstanceOf(IOException.class).hasMessageContaining("closed");
        assertThatThrownBy(() -> vfs.open(moduleReference)).isInstanceOf(IOException.class)
                .hasMessageContaining("closed");

        // Logging cannot be turned on after the close either, since there is nothing left for it to log
        assertThatThrownBy(vfs::verbose).isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");

        // Closing twice is harmless
        vfs.close();
    }

    /**
     * A single root can be evicted from the cache without closing the whole virtual filesystem, so that the memory
     * its entry list occupies can be reclaimed and the same path opens a fresh root. Eviction does not stop the
     * evicted root working, since a {@link Vfs} hands the same root to everything that opened the same path, and
     * one holder must not be able to take it away from the others.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into.
     * @throws IOException
     *             if the jarfile could not be written or read.
     */
    @Test
    public void aRootCanBeEvictedWithoutBreakingIt(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");

        try (var vfs = new Vfs()) {
            // Two holders of the same path get the same root
            final var root = vfs.open(jarFile.getPath());
            final var sameRoot = vfs.open(jarFile.getPath());
            assertThat(sameRoot).isSameAs(root);
            final var entry = Objects.requireNonNull(root.getEntry("com/xyz/widget.txt"));
            final var fileSystem = root.asFileSystem();

            vfs.evict(root);

            // The other holder is not left with a broken root: everything it was handed goes on working
            assertThat(vfs).as("evicted from the Vfs").isEmpty();
            assertThat(root.isClosed()).as("root closed").isFalse();
            assertThat(fileSystem.isOpen()).as("filesystem view open").isTrue();
            assertThat(root.getEntries()).as("entries").isNotEmpty();
            assertThat(entryContent(root, "com/xyz/widget.txt")).isEqualTo(RESOURCE_CONTENT);
            try (var content = entry.read()) {
                assertThat(Objects.requireNonNull(content.getByteBuffer()).remaining()).isPositive();
            }

            // Evicting twice, and evicting a root that was never cached, are both harmless
            vfs.evict(root);
            vfs.evict(vfs.open(readFile(jarFile), "in-memory.jar"));

            // The evicted root was dropped from the cache, so the same path now opens a fresh root
            final var reopened = vfs.open(jarFile.getPath());
            assertThat(reopened).isNotSameAs(root);
            assertThat(entryContent(reopened, "com/xyz/widget.txt")).isEqualTo(RESOURCE_CONTENT);
        }
    }

    /**
     * The container of a root that was opened at a package root is the same root that opening the jarfile by its
     * own path hands back, whichever of the two is asked for first, rather than a second root over one jarfile.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into.
     * @throws IOException
     *             if the jarfile could not be written or read.
     */
    @Test
    public void theContainerRootIsTheRootTheWholeJarfileOpensAs(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "app.jar");
        writeJar(jarFile, "BOOT-INF/classes/com/xyz/widget.txt");
        final var packageRootPath = jarFile.getPath() + "!/BOOT-INF/classes";

        try (var vfs = new Vfs()) {
            // The package root is opened first, so its container root is what the whole jarfile then opens as
            final var containerRoot = vfs.open(packageRootPath).getContainerRoot();
            assertThat(vfs.open(jarFile.getPath())).isSameAs(containerRoot);
        }
        try (var vfs = new Vfs()) {
            // The whole jarfile is opened first, so that root is what the package root reports as its container
            final var wholeJarRoot = vfs.open(jarFile.getPath());
            assertThat(vfs.open(packageRootPath).getContainerRoot()).isSameAs(wholeJarRoot);
        }
    }

    /**
     * A root of a closed {@link Vfs} hands out nothing that still reads from storage. Its container root is built
     * on demand, so it would otherwise manufacture a fresh working view of the whole jarfile after the storage
     * behind it was released, and its manifest is cached the first time it is read, so it would keep answering out
     * of a cache warmed while the {@link Vfs} was open.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into.
     * @throws IOException
     *             if the jarfile could not be written or read.
     */
    @Test
    public void aRootOfAClosedVfsHandsOutNothingLive(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "app.jar");
        writeJar(jarFile, "BOOT-INF/classes/com/xyz/widget.txt");

        final var vfs = new Vfs();
        final var root = vfs.open(jarFile.getPath() + "!/BOOT-INF/classes");
        // Warm both caches while the Vfs is still open
        assertThat(root.getContainerRoot().getPackageRoot()).isEmpty();
        assertThat(root.getManifest()).containsEntry("Automatic-Module-Name", "com.xyz.widget");

        vfs.close();

        assertThatThrownBy(root::getContainerRoot).as("getContainerRoot").isInstanceOf(IOException.class)
                .hasMessageContaining("closed");
        assertThatThrownBy(root::getManifest).as("getManifest").isInstanceOf(IOException.class)
                .hasMessageContaining("closed");
        assertThatThrownBy(root::getModuleName).as("getModuleName").isInstanceOf(IOException.class)
                .hasMessageContaining("closed");
    }

    /**
     * A root of a closed {@link Vfs} stops listing entries, as well as reading them, whichever kind of root it is.
     * (Reading an entry was already refused, but a directory root and an archive root went on listing entries.)
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into.
     * @throws IOException
     *             if the jarfile could not be written or read.
     */
    @Test
    public void theRootsOfAClosedVfsCannotBeListed(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");
        final ModuleReference moduleReference = ModuleFinder.ofSystem().find("java.logging").orElseThrow();
        final var visitEverything = new VfsVisitor() {
            @Override
            public boolean enterDirectory(final String dirName) {
                return true;
            }

            @Override
            public boolean visitEntry(final VfsEntry entry) {
                return true;
            }
        };

        final var vfs = new Vfs();
        final var roots = List.of(vfs.open(jarFile.getPath()), vfs.open(tempDir.getPath()),
                vfs.open(moduleReference));
        for (final var root : roots) {
            // Every kind of root lists entries while the Vfs is open
            assertThat(root.getEntries()).as(root.getKind().toString()).isNotEmpty();
        }

        vfs.close();

        for (final var root : roots) {
            final var kind = root.getKind().toString();
            assertThatThrownBy(() -> root.walk(visitEverything)).as(kind + " walk").isInstanceOf(IOException.class)
                    .hasMessageContaining("closed");
            assertThatThrownBy(root::getEntries).as(kind + " getEntries").isInstanceOf(IOException.class)
                    .hasMessageContaining("closed");
            assertThatThrownBy(() -> root.getEntry("com/xyz/widget.txt")).as(kind + " getEntry")
                    .isInstanceOf(IOException.class).hasMessageContaining("closed");
        }
    }

    /**
     * Closing the virtual filesystem closes every root it opened, whether the root was opened from a path or from a
     * module reference.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into.
     * @throws IOException
     *             if the jarfile could not be written or read.
     */
    @Test
    public void closingTheVfsClosesEveryRootItOpened(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");
        final ModuleReference moduleReference = ModuleFinder.ofSystem().find("java.logging").orElseThrow();

        final VfsRoot jarRoot;
        final VfsRoot dirRoot;
        final VfsRoot moduleRoot;
        final VfsEntry dirEntry;
        try (var vfs = new Vfs()) {
            jarRoot = vfs.open(jarFile.getPath());
            dirRoot = vfs.open(tempDir.getPath());
            moduleRoot = vfs.open(moduleReference);
            dirEntry = Objects.requireNonNull(dirRoot.getEntry("widget.jar"));
            assertThat(jarRoot.isClosed()).as("jar root closed before").isFalse();
        }

        assertThat(jarRoot.isClosed()).as("jar root closed").isTrue();
        assertThat(dirRoot.isClosed()).as("dir root closed").isTrue();
        assertThat(moduleRoot.isClosed()).as("module root closed").isTrue();
        assertThatThrownBy(dirEntry::load).isInstanceOf(IOException.class).hasMessageContaining("closed");
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
    public void aStringThatIsNotAURLSchemeIsRejected() {
        vfsWithoutURLSchemes("https").close();
        // A one-character scheme cannot be told apart from a Windows drive letter
        assertThatThrownBy(() -> vfsWithoutURLSchemes("c")).isInstanceOf(IllegalArgumentException.class);
        // The commonest mistake: including the scheme's trailing ':'
        assertThatThrownBy(() -> vfsWithoutURLSchemes("https:")).isInstanceOf(IllegalArgumentException.class);
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
            assertThat(root.getEntries()).extracting(VfsEntry::getPathFromRoot).containsExactlyInAnyOrder(
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

        try (var vfs = new Vfs(new VfsSpec().enableMultiReleaseVersions())) {
            final var root = vfs.open(jarFile.getPath());
            assertThat(root.getEntries()).extracting(VfsEntry::getPathFromRoot).containsExactlyInAnyOrder(
                    "META-INF/MANIFEST.MF", "com/xyz/widget.txt", "META-INF/versions/9/com/xyz/widget.txt",
                    "META-INF/versions/9999/com/xyz/widget.txt");
            assertThat(entryContent(root, "com/xyz/widget.txt")).isEqualTo("base");
            assertThat(entryContent(root, "META-INF/versions/9/com/xyz/widget.txt")).isEqualTo("version 9");
        }
    }

    /** A negative RAM size is rejected. */
    @Test
    public void aNegativeMaxBufferedJarRAMSizeIsRejected() {
        new Vfs(new VfsSpec().setMaxBufferedJarRAMSize(1024)).close();
        assertThatThrownBy(() -> new VfsSpec().setMaxBufferedJarRAMSize(-1))
                .isInstanceOf(IllegalArgumentException.class);
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
            assertThatThrownBy(() -> new VfsSpec().enableURLScheme(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new VfsSpec().disableURLScheme(null)).isInstanceOf(NullPointerException.class);

            final var root = vfs.open(jarFile.getPath());
            assertThatThrownBy(() -> root.getEntry(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> vfs.open(tempDir).getEntry(null)).isInstanceOf(NullPointerException.class);

            final var entry = Objects.requireNonNull(root.getEntry("com/xyz/widget.txt"));
            assertThatThrownBy(() -> entry.loadAsString(null)).isInstanceOf(NullPointerException.class);
        }
    }

    /**
     * A root can name a path within itself by URI without an entry first being looked up, which is how a directory,
     * a package root, or a name that is not there at all can be named.
     */
    @Test
    public void aRootCanNameAPathWithinItselfByURI(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");
        final var dir = new File(tempDir, "classes");
        assertThat(new File(dir, "com/xyz").mkdirs()).isTrue();
        Files.writeString(new File(dir, "com/xyz/widget.txt").toPath(), RESOURCE_CONTENT);

        try (var vfs = new Vfs()) {
            // The URI of an entry is the URI its root gives the path the entry is stored under
            final var jarRoot = vfs.open(jarFile.getPath());
            final var jarEntry = Objects.requireNonNull(jarRoot.getEntry("com/xyz/widget.txt"));
            assertThat(jarRoot.resolveURI(jarEntry.getRawPathFromRoot())).isEqualTo(jarEntry.getURI());

            final var dirRoot = vfs.open(dir.getPath());
            final var dirEntry = Objects.requireNonNull(dirRoot.getEntry("com/xyz/widget.txt"));
            assertThat(dirRoot.resolveURI(dirEntry.getRawPathFromRoot())).isEqualTo(dirEntry.getURI());

            // A path that no entry is stored at is still named, since nothing is looked up to name it
            assertThat(jarRoot.resolveURI("com/xyz").toString()).endsWith("!/com/xyz");
            assertThat(jarRoot.resolveURI("no/such/entry.txt").toString()).endsWith("!/no/such/entry.txt");
            assertThat(dirRoot.resolveURI("no/such/entry.txt").toString()).endsWith("/no/such/entry.txt");

            // A path that cannot be held by a URI as it stands is percent-encoded, so that the URI still names the
            // path it was given rather than a different one
            assertThat(jarRoot.resolveURI("a b/c#d.txt").toString()).endsWith("!/a%20b/c%23d.txt");
            assertThat(dirRoot.resolveURI("a b/c#d.txt").toString()).endsWith("/a%20b/c%23d.txt");

            // Only a module can say how it names something within itself, so a module names what it contains and
            // nothing else
            final var moduleRoot = vfs.open(ModuleFinder.ofSystem().find("java.logging").orElseThrow());
            final var moduleEntry = Objects.requireNonNull(moduleRoot.getEntry("java/util/logging/Logger.class"));
            assertThat(moduleRoot.resolveURI("java/util/logging/Logger.class")).isEqualTo(moduleEntry.getURI());
            assertThatThrownBy(() -> moduleRoot.resolveURI("no/such/entry.txt"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
