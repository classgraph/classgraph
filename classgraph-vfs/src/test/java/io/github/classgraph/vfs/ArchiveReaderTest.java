package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests the public API of the archive reader. */
public class ArchiveReaderTest {
    /** The content of the test resource. */
    private static final String RESOURCE_CONTENT = "archive-reader-test";

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
     * Read a file into a byte array.
     *
     * @param file
     *            the file to read.
     * @return the content of the file.
     * @throws IOException
     *             if the file could not be read.
     */
    private static byte[] readFile(final File file) throws IOException {
        try (var inputStream = new java.io.FileInputStream(file)) {
            return inputStream.readAllBytes();
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    /** The entries of a jarfile can be listed, looked up by name, and read. */
    @Test
    public void entriesCanBeListedAndRead(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");

        try (var archiveReader = new ArchiveReader()) {
            final var archive = archiveReader.open(jarFile.getPath());
            // The path is canonicalized, so that the same jarfile reached by two different paths is only opened
            // once. On Windows that expands an 8.3 short name, and on macOS it resolves a symlink, so the path of
            // the temp directory is not necessarily the path it is reported as.
            assertThat(archive.getPath()).isEqualTo(jarFile.getCanonicalPath().replace(File.separatorChar, '/'));
            assertThat(archive.getPackageRoot()).isEmpty();
            assertThat(archive.getEntries()).extracting(ArchiveEntry::getName)
                    .containsExactlyInAnyOrder("META-INF/MANIFEST.MF", "com/xyz/widget.txt");

            final var entry = Objects.requireNonNull(archive.getEntry("com/xyz/widget.txt"));
            assertThat(entry.getArchive()).isSameAs(archive);
            assertThat(entry.getPath()).isEqualTo(archive.getPath() + "!/com/xyz/widget.txt");
            assertThat(entry.toString()).isEqualTo(entry.getPath());
            assertThat(entry.getUncompressedSize()).isEqualTo(RESOURCE_CONTENT.length());
            assertThat(entry.getCompressedSize()).isPositive();
            assertThat(entry.getLastModifiedTimeMillis()).isPositive();
            assertThat(new String(entry.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(RESOURCE_CONTENT);

            final var readViaStream = new ByteArrayOutputStream();
            try (var inputStream = entry.open()) {
                inputStream.transferTo(readViaStream);
            }
            assertThat(readViaStream.toString(StandardCharsets.UTF_8)).isEqualTo(RESOURCE_CONTENT);
        }
    }

    /** Looking up an entry that is not in the jarfile returns null. */
    @Test
    public void anEntryThatIsNotPresentIsNull(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");

        try (var archiveReader = new ArchiveReader()) {
            assertThat(archiveReader.open(jarFile.getPath()).getEntry("com/xyz/nonexistent.txt")).isNull();
        }
    }

    /** The Automatic-Module-Name manifest entry is reported. */
    @Test
    public void theAutomaticModuleNameIsRead(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");

        try (var archiveReader = new ArchiveReader()) {
            assertThat(archiveReader.open(jarFile.getPath()).getAutomaticModuleName()).isEqualTo("com.xyz.widget");
        }
    }

    /** Opening the same path twice returns the same archive. */
    @Test
    public void anArchiveIsOnlyOpenedOnce(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");

        try (var archiveReader = new ArchiveReader()) {
            assertThat(archiveReader.open(jarFile.getPath())).isSameAs(archiveReader.open(jarFile.getPath()));
        }
    }

    /** A jarfile nested inside another jarfile is read in place. */
    @Test
    public void aNestedJarfileCanBeRead(@TempDir final File tempDir) throws IOException {
        final var innerJarFile = new File(tempDir, "inner.jar");
        writeJar(innerJarFile, "com/xyz/widget.txt");
        final var outerJarFile = new File(tempDir, "outer.jar");
        writeJarContainingJar(outerJarFile, "lib/inner.jar", readFile(innerJarFile));

        try (var archiveReader = new ArchiveReader()) {
            final var archive = archiveReader.open(outerJarFile.getPath() + "!/lib/inner.jar");
            final var entry = Objects.requireNonNull(archive.getEntry("com/xyz/widget.txt"));
            assertThat(new String(entry.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(RESOURCE_CONTENT);
        }
    }

    /** With nested jarfiles disabled, a nested jarfile is not opened. */
    @Test
    public void aNestedJarfileIsNotOpenedIfNestedJarsAreDisabled(@TempDir final File tempDir) throws IOException {
        final var innerJarFile = new File(tempDir, "inner.jar");
        writeJar(innerJarFile, "com/xyz/widget.txt");
        final var outerJarFile = new File(tempDir, "outer.jar");
        writeJarContainingJar(outerJarFile, "lib/inner.jar", readFile(innerJarFile));

        try (var archiveReader = new ArchiveReader().disableNestedJars()) {
            assertThatThrownBy(() -> archiveReader.open(outerJarFile.getPath() + "!/lib/inner.jar"))
                    .isInstanceOf(IOException.class);
        }
    }

    /** A trailing "!/" section that names a directory is used as the package root. */
    @Test
    public void aPackageRootStripsThePrefixFromEntryNames(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "app.jar");
        writeJar(jarFile, "BOOT-INF/classes/com/xyz/widget.txt");

        try (var archiveReader = new ArchiveReader()) {
            final var archive = archiveReader.open(jarFile.getPath() + "!/BOOT-INF/classes");
            assertThat(archive.getPackageRoot()).isEqualTo("BOOT-INF/classes");
            assertThat(archive.toString()).isEqualTo(archive.getPath() + "!/BOOT-INF/classes");
            assertThat(archive.getEntries()).extracting(ArchiveEntry::getName)
                    .containsExactly("com/xyz/widget.txt");
            final var entry = Objects.requireNonNull(archive.getEntry("com/xyz/widget.txt"));
            assertThat(entry.getPath()).isEqualTo(archive.getPath() + "!/BOOT-INF/classes/com/xyz/widget.txt");
            assertThat(new String(entry.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(RESOURCE_CONTENT);
        }
    }

    /** A jarfile cannot be opened after the reader has been closed. */
    @Test
    public void anArchiveCannotBeOpenedAfterTheReaderIsClosed(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");

        final var archiveReader = new ArchiveReader();
        archiveReader.open(jarFile.getPath());
        archiveReader.close();
        assertThatThrownBy(() -> archiveReader.open(jarFile.getPath())).isInstanceOf(IOException.class)
                .hasMessageContaining("closed");
        // Closing twice is harmless
        archiveReader.close();
    }

    /** Opening a path that is not a jarfile fails with an IOException. */
    @Test
    public void openingSomethingThatIsNotAJarfileFails(@TempDir final File tempDir) throws IOException {
        final var notAJarFile = new File(tempDir, "not-a-jar.jar");
        try (var fileOut = new FileOutputStream(notAJarFile)) {
            fileOut.write("this is not a jarfile".getBytes(StandardCharsets.UTF_8));
        }

        try (var archiveReader = new ArchiveReader()) {
            assertThatThrownBy(() -> archiveReader.open(notAJarFile.getPath())).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> archiveReader.open(new File(tempDir, "missing.jar").getPath()))
                    .isInstanceOf(IOException.class);
        }
    }

    /** A string that is not a URL scheme is rejected, rather than being stored where it can never match. */
    @Test
    public void aStringThatIsNotAURLSchemeIsRejected() throws IOException {
        try (var archiveReader = new ArchiveReader()) {
            assertThat(archiveReader.enableURLScheme("https")).isSameAs(archiveReader);
            // A one-character scheme cannot be told apart from a Windows drive letter
            assertThatThrownBy(() -> archiveReader.enableURLScheme("c"))
                    .isInstanceOf(IllegalArgumentException.class);
            // The commonest mistake: including the scheme's trailing ':'
            assertThatThrownBy(() -> archiveReader.enableURLScheme("https:"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** A negative RAM size is rejected. */
    @Test
    public void aNegativeMaxBufferedJarRAMSizeIsRejected() throws IOException {
        try (var archiveReader = new ArchiveReader()) {
            assertThat(archiveReader.maxBufferedJarRAMSize(1024)).isSameAs(archiveReader);
            assertThatThrownBy(() -> archiveReader.maxBufferedJarRAMSize(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** Null arguments are rejected. */
    @Test
    public void nullArgumentsAreRejected(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "widget.jar");
        writeJar(jarFile, "com/xyz/widget.txt");

        try (var archiveReader = new ArchiveReader()) {
            assertThatThrownBy(() -> archiveReader.open(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> archiveReader.enableURLScheme(null)).isInstanceOf(NullPointerException.class);
            final var archive = archiveReader.open(jarFile.getPath());
            assertThatThrownBy(() -> archive.getEntry(null)).isInstanceOf(NullPointerException.class);
        }
    }
}
