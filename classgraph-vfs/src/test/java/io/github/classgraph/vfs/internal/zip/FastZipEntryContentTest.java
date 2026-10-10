package io.github.classgraph.vfs.internal.zip;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsSpec;
import io.github.classgraph.vfs.internal.slice.Slice;

/**
 * The content of a zip entry is read through the entry, which inflates it if it is deflated, while the slice of a
 * zip entry is the byte range that the entry occupies in its zipfile, which reads the same bytes whichever way it
 * is read.
 */
public class FastZipEntryContentTest {
    /** The name of the deflated entry. */
    private static final String DEFLATED_ENTRY_NAME = "deflated.txt";

    /** The name of the stored entry. */
    private static final String STORED_ENTRY_NAME = "stored.txt";

    /** The content of both entries: long and repetitive enough that deflating it makes it much shorter. */
    private static final byte[] CONTENT = "The quick brown fox jumps over the lazy dog. ".repeat(100)
            .getBytes(StandardCharsets.UTF_8);

    /**
     * Write a jarfile holding one deflated entry and one stored entry, both holding {@link #CONTENT}.
     *
     * @param jarFile
     *            the jarfile to write
     * @throws Exception
     *             if the jarfile could not be written
     */
    private static void writeJar(final File jarFile) throws Exception {
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry(DEFLATED_ENTRY_NAME));
            zipOut.write(CONTENT);
            zipOut.closeEntry();

            final var storedEntry = new ZipEntry(STORED_ENTRY_NAME);
            storedEntry.setMethod(ZipEntry.STORED);
            storedEntry.setSize(CONTENT.length);
            final var crc = new CRC32();
            crc.update(CONTENT);
            storedEntry.setCrc(crc.getValue());
            zipOut.putNextEntry(storedEntry);
            zipOut.write(CONTENT);
            zipOut.closeEntry();
        }
    }

    /** Assertions to run against an entry of an opened zipfile. */
    private interface EntryAssertions {
        /**
         * Run the assertions.
         *
         * @param zipEntry
         *            the entry
         * @throws Exception
         *             if an assertion could not be run
         */
        void run(FastZipEntry zipEntry) throws Exception;
    }

    /**
     * Open the jarfile written by {@link #writeJar}, and run the given assertions against one of its entries.
     *
     * @param tempDir
     *            the directory to write the jarfile to
     * @param entryName
     *            the name of the entry
     * @param assertions
     *            the assertions to run against the entry
     * @throws Exception
     *             if the jarfile could not be written or opened
     */
    private static void withEntry(final File tempDir, final String entryName, final EntryAssertions assertions)
            throws Exception {
        final var jarFile = new File(tempDir, "entry-content.jar");
        writeJar(jarFile);
        final var vfs = new Vfs(new VfsSpec(), new InterruptionChecker());
        try {
            final var zipEntry = JarOpener.openJarFile(jarFile, vfs, /* log = */ null).zipFile().getEntries()
                    .stream().filter(e -> e.entryName.equals(entryName)).findFirst().orElseThrow();
            assertions.run(zipEntry);
        } finally {
            // The jarfile must not be left open, otherwise the temporary directory cannot be deleted on Windows
            vfs.close(/* log = */ null);
        }
    }

    /**
     * Read the whole of a slice through its random access reader.
     *
     * @param slice
     *            the slice
     * @return the bytes of the slice
     * @throws Exception
     *             if the slice could not be read
     */
    private static byte[] readThroughReader(final Slice slice) throws Exception {
        final var bytes = new byte[(int) slice.sliceLength];
        assertThat(slice.randomAccessReader().read(0, bytes, 0, bytes.length)).isEqualTo(bytes.length);
        return bytes;
    }

    /**
     * Inflate the raw bytes of a deflated zip entry.
     *
     * @param deflated
     *            the raw bytes of the entry
     * @return the inflated bytes
     * @throws Exception
     *             if the bytes could not be inflated
     */
    private static byte[] inflate(final byte[] deflated) throws Exception {
        final var inflater = new Inflater(/* nowrap = */ true);
        try {
            inflater.setInput(deflated);
            final var inflated = new byte[CONTENT.length];
            assertThat(inflater.inflate(inflated)).isEqualTo(CONTENT.length);
            return inflated;
        } finally {
            inflater.end();
        }
    }

    /**
     * Assert that every way of reading the content of an entry gives {@link #CONTENT}.
     *
     * @param zipEntry
     *            the entry
     * @throws Exception
     *             if the entry could not be read
     */
    private static void assertContentIsReadEveryWay(final FastZipEntry zipEntry) throws Exception {
        assertThat(zipEntry.load()).isEqualTo(CONTENT);
        try (var inputStream = zipEntry.open()) {
            assertThat(inputStream.readAllBytes()).isEqualTo(CONTENT);
        }
        final var buffer = zipEntry.read();
        assertThat(buffer.isReadOnly()).isTrue();
        final var bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        assertThat(bytes).isEqualTo(CONTENT);
    }

    /**
     * The slice of a deflated entry gives the entry's compressed bytes whichever way it is read: loading it or
     * streaming it gives the same bytes as its random access reader, rather than inflating them.
     *
     * @param tempDir
     *            a temporary directory
     * @throws Exception
     *             if the jarfile could not be written or read
     */
    @Test
    public void theSliceOfADeflatedEntryReadsTheSameBytesEveryWay(@TempDir final File tempDir) throws Exception {
        withEntry(tempDir, DEFLATED_ENTRY_NAME, zipEntry -> {
            assertThat(zipEntry.isDeflated).isTrue();
            final var slice = zipEntry.getRawSlice();
            final var raw = readThroughReader(slice);
            assertThat(raw.length).isLessThan(CONTENT.length);
            assertThat(inflate(raw)).isEqualTo(CONTENT);
            assertThat(slice.load()).isEqualTo(raw);
            try (var inputStream = slice.open()) {
                assertThat(inputStream.readAllBytes()).isEqualTo(raw);
            }
        });
    }

    /**
     * The entry inflates the content of a deflated entry, whichever way it is read.
     *
     * @param tempDir
     *            a temporary directory
     * @throws Exception
     *             if the jarfile could not be written or read
     */
    @Test
    public void aDeflatedEntryIsInflatedEveryWayItIsRead(@TempDir final File tempDir) throws Exception {
        withEntry(tempDir, DEFLATED_ENTRY_NAME, FastZipEntryContentTest::assertContentIsReadEveryWay);
    }

    /**
     * The content of a stored entry is the slice that holds it, whichever way it is read.
     *
     * @param tempDir
     *            a temporary directory
     * @throws Exception
     *             if the jarfile could not be written or read
     */
    @Test
    public void aStoredEntryIsReadAsItIsStored(@TempDir final File tempDir) throws Exception {
        withEntry(tempDir, STORED_ENTRY_NAME, zipEntry -> {
            assertThat(zipEntry.isDeflated).isFalse();
            assertThat(readThroughReader(zipEntry.getRawSlice())).isEqualTo(CONTENT);
            assertContentIsReadEveryWay(zipEntry);
        });
    }
}
