package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A small deflated entry is inflated in one go, into an array of the uncompressed size that the central directory
 * declares, rather than through a stream. The declared size is only a hint, so an entry whose deflated data
 * inflates to more or less than it declares must still be read in full, and an entry whose deflated data is cut
 * short must not be read at all, whether the jarfile is read from a file or from memory (a nested jarfile).
 */
public class WholeEntryInflateTest {
    /** The signature of a central directory file header. */
    private static final int CEN_SIGNATURE = 0x02014b50;

    /** The name of the entry. */
    private static final String ENTRY_NAME = "text.txt";

    /** Content that compresses well, and is long enough that the declared sizes can be cut well short of it. */
    private static final byte[] CONTENT = "The quick brown fox jumps over the lazy dog. ".repeat(200)
            .getBytes(StandardCharsets.UTF_8);

    /**
     * Write a jarfile holding one deflated entry, {@link #ENTRY_NAME}, with the content {@link #CONTENT}, and
     * overwrite the sizes that the central directory declares for it. The entry is deflated, so its local header
     * defers its sizes to a data descriptor, and the central directory is the only place a reader takes them from.
     *
     * @param jarFile
     *            the jarfile to write.
     * @param compressedSizeDivisor
     *            the number to divide the compressed size by.
     * @param uncompressedSize
     *            the uncompressed size to declare.
     * @return the jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static Path writeJar(final Path jarFile, final int compressedSizeDivisor, final int uncompressedSize)
            throws IOException {
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(jarFile))) {
            zipOut.putNextEntry(new ZipEntry(ENTRY_NAME));
            zipOut.write(CONTENT);
            zipOut.closeEntry();
        }
        final var bytes = Files.readAllBytes(jarFile);
        final var buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (var i = bytes.length - 46; i >= 0; i--) {
            if (buf.getInt(i) == CEN_SIGNATURE) {
                buf.putInt(i + 20, buf.getInt(i + 20) / compressedSizeDivisor);
                buf.putInt(i + 24, uncompressedSize);
                Files.write(jarFile, bytes);
                return jarFile;
            }
        }
        throw new AssertionError("No central directory entry");
    }

    /**
     * Write a jarfile that holds {@code innerJar} as the deflated entry {@code lib/inner.jar}, which is small
     * enough to be inflated into memory when it is opened.
     *
     * @param outerJar
     *            the jarfile to write.
     * @param innerJar
     *            the jarfile to nest.
     * @return the path of the nested jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static String nest(final Path outerJar, final Path innerJar) throws IOException {
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(outerJar))) {
            zipOut.putNextEntry(new ZipEntry("lib/inner.jar"));
            Files.copy(innerJar, zipOut);
            zipOut.closeEntry();
        }
        return outerJar + "!/lib/inner.jar";
    }

    /** An entry that inflates to more than the uncompressed size it declares is read in full. */
    @Test
    public void anEntryThatInflatesToMoreThanItsDeclaredSizeIsReadInFull(@TempDir final Path tempDir)
            throws IOException {
        final var jarFile = writeJar(tempDir.resolve("understated.jar"), 1, CONTENT.length / 10);
        try (var vfs = new Vfs()) {
            final var entry = vfs.open(jarFile.toString()).getEntry(ENTRY_NAME);
            assertThat(entry.getLength()).isEqualTo(CONTENT.length / 10);
            assertThat(entry.load()).isEqualTo(CONTENT);
        }
    }

    /**
     * An entry of a nested jarfile that inflates to more than the uncompressed size it declares is read in full.
     */
    @Test
    public void aNestedEntryThatInflatesToMoreThanItsDeclaredSizeIsReadInFull(@TempDir final Path tempDir)
            throws IOException {
        final var nestedPath = nest(tempDir.resolve("outer.jar"),
                writeJar(tempDir.resolve("understated.jar"), 1, CONTENT.length / 10));
        try (var vfs = new Vfs()) {
            assertThat(vfs.open(nestedPath).getEntry(ENTRY_NAME).load()).isEqualTo(CONTENT);
        }
    }

    /** An entry that inflates to less than the uncompressed size it declares is read in full. */
    @Test
    public void anEntryThatInflatesToLessThanItsDeclaredSizeIsReadInFull(@TempDir final Path tempDir)
            throws IOException {
        final var jarFile = writeJar(tempDir.resolve("overstated.jar"), 1, CONTENT.length * 2);
        try (var vfs = new Vfs()) {
            final var entry = vfs.open(jarFile.toString()).getEntry(ENTRY_NAME);
            assertThat(entry.getLength()).isEqualTo(CONTENT.length * 2);
            assertThat(entry.load()).isEqualTo(CONTENT);
        }
    }

    /** An entry whose deflated data is cut short by the compressed size it declares cannot be read. */
    @Test
    public void anEntryWhoseDeflatedDataIsCutShortCannotBeRead(@TempDir final Path tempDir) throws IOException {
        final var jarFile = writeJar(tempDir.resolve("truncated.jar"), 2, CONTENT.length);
        try (var vfs = new Vfs()) {
            final var entry = vfs.open(jarFile.toString()).getEntry(ENTRY_NAME);
            assertThatThrownBy(entry::load).isInstanceOf(IOException.class);
            try (var in = entry.open()) {
                assertThatThrownBy(in::readAllBytes).isInstanceOf(IOException.class);
            }
        }
    }

    /** An entry of a nested jarfile whose deflated data is cut short cannot be read. */
    @Test
    public void aNestedEntryWhoseDeflatedDataIsCutShortCannotBeRead(@TempDir final Path tempDir)
            throws IOException {
        final var nestedPath = nest(tempDir.resolve("outer.jar"),
                writeJar(tempDir.resolve("truncated.jar"), 2, CONTENT.length));
        try (var vfs = new Vfs()) {
            final var entry = vfs.open(nestedPath).getEntry(ENTRY_NAME);
            assertThatThrownBy(entry::load).isInstanceOf(IOException.class);
        }
    }
}
