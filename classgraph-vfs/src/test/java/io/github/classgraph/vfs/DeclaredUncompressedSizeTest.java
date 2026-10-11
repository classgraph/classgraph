package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.github.classgraph.vfs.reader.RandomAccessOrSequentialReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The uncompressed size that a zip entry declares is only a hint for the size of the buffer it is inflated into,
 * since a zipfile can declare any size. A deflated entry that declares far more than it holds must not make every
 * reader of it allocate a buffer of the declared size.
 *
 * <p>
 * The jarfiles these tests write are small, but {@code openingANestedJarfileDoesNotAllocateMoreThanItMayInflateTo}
 * opens a nested jarfile of 254 MB of zeros, which is longer than
 * {@link VfsSpec#DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE}, so it is inflated into a temporary file of about 254 MB in
 * {@code java.io.tmpdir}, which is deleted when the test closes its {@link Vfs}.
 */
public class DeclaredUncompressedSizeTest {
    /** The uncompressed size that the entries declare: 16 MB, the most a declared size could allocate up front. */
    private static final int DECLARED_SIZE = 16 * 1024 * 1024;

    /** The most that reading one small entry may allocate: far less than {@link #DECLARED_SIZE}. */
    private static final long MAX_ALLOCATED_BYTES = 4L * 1024 * 1024;

    /** The signature of a central directory file header. */
    private static final int CEN_SIGNATURE = 0x02014b50;

    /**
     * Write a jarfile.
     *
     * @param jarFile
     *            the jarfile to write.
     * @param deflatedName
     *            the name of a deflated entry.
     * @param deflatedContent
     *            the content of the deflated entry.
     * @param storedName
     *            the name of a stored entry, or null for none.
     * @param storedContent
     *            the content of the stored entry.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJar(final Path jarFile, final String deflatedName, final byte[] deflatedContent,
            final String storedName, final byte[] storedContent) throws IOException {
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(jarFile))) {
            zipOut.putNextEntry(new ZipEntry(deflatedName));
            zipOut.write(deflatedContent);
            zipOut.closeEntry();
            if (storedName != null) {
                final var entry = new ZipEntry(storedName);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(storedContent.length);
                final var crc = new CRC32();
                crc.update(storedContent);
                entry.setCrc(crc.getValue());
                zipOut.putNextEntry(entry);
                zipOut.write(storedContent);
                zipOut.closeEntry();
            }
        }
    }

    /**
     * Overwrite the uncompressed size that the central directory declares for an entry with {@link #DECLARED_SIZE}.
     * The entry is deflated, so its local header defers its sizes to a data descriptor, and the central directory
     * is the only place a reader takes them from.
     *
     * @param jarFile
     *            the jarfile.
     * @param entryName
     *            the name of the entry.
     * @param compressedSize
     *            if not zero, also overwrite the compressed size that the central directory declares with this
     *            size, read as an unsigned 32-bit value.
     * @throws IOException
     *             if the jarfile could not be read or written.
     */
    private static void overstateUncompressedSize(final Path jarFile, final String entryName,
            final int compressedSize) throws IOException {
        final var bytes = Files.readAllBytes(jarFile);
        final var buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        final var nameBytes = entryName.getBytes(StandardCharsets.UTF_8);
        for (var i = 0; i + 46 + nameBytes.length <= bytes.length; i++) {
            if (buf.getInt(i) == CEN_SIGNATURE && buf.getShort(i + 28) == nameBytes.length
                    && ByteBuffer.wrap(bytes, i + 46, nameBytes.length).equals(ByteBuffer.wrap(nameBytes))) {
                assertThat(buf.getInt(i + 24)).isLessThan(DECLARED_SIZE);
                buf.putInt(i + 24, DECLARED_SIZE);
                if (compressedSize != 0) {
                    buf.putInt(i + 20, compressedSize);
                }
                Files.write(jarFile, bytes);
                return;
            }
        }
        throw new AssertionError("No central directory entry for " + entryName);
    }

    /**
     * Random bytes, which do not compress.
     *
     * @param length
     *            the number of bytes.
     * @param seed
     *            the random seed.
     * @return the bytes.
     */
    private static byte[] randomBytes(final int length, final long seed) {
        final var bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    /**
     * Get the number of bytes the current thread has allocated so far, or skip the test if the JVM cannot tell.
     *
     * @return the number of bytes allocated.
     */
    private static long allocatedBytes() {
        final var threadMXBean = ManagementFactory.getThreadMXBean();
        assumeTrue(threadMXBean instanceof com.sun.management.ThreadMXBean);
        final var sunThreadMXBean = (com.sun.management.ThreadMXBean) threadMXBean;
        assumeTrue(sunThreadMXBean.isThreadAllocatedMemorySupported());
        sunThreadMXBean.setThreadAllocatedMemoryEnabled(true);
        return sunThreadMXBean.getCurrentThreadAllocatedBytes();
    }

    /**
     * A small deflated entry of a toplevel jarfile is loaded without allocating the uncompressed size it declares,
     * since its compressed bytes cannot inflate to anywhere near that size.
     */
    @Test
    public void loadingASmallEntryDoesNotAllocateItsDeclaredSize(@TempDir final Path tempDir) throws IOException {
        final var content = "small".getBytes(StandardCharsets.UTF_8);
        final var jarFile = tempDir.resolve("overstated.jar");
        writeJar(jarFile, "small.bin", content, /* storedName = */ null, /* storedContent = */ null);
        overstateUncompressedSize(jarFile, "small.bin", /* compressedSize = */ 0);

        try (var vfs = new Vfs()) {
            final var entry = vfs.open(jarFile.toString()).getEntry("small.bin");
            assertThat(entry.getLength()).isEqualTo(DECLARED_SIZE);
            final var allocatedBefore = allocatedBytes();
            assertThat(entry.load()).isEqualTo(content);
            assertThat(allocatedBytes() - allocatedBefore).isLessThan(MAX_ALLOCATED_BYTES);
        }
    }

    /**
     * A small deflated entry whose compressed size runs past the end of the jarfile is read to the end of its
     * deflate stream, and the compressed size it declares does not let it allocate the uncompressed size it
     * declares either, since only the bytes left in the jarfile can be inflated.
     */
    @Test
    public void loadingASmallEntryWhoseCompressedSizeRunsPastTheEndDoesNotAllocateItsDeclaredSize(
            @TempDir final Path tempDir) throws IOException {
        final var content = "small".getBytes(StandardCharsets.UTF_8);
        final var jarFile = tempDir.resolve("overstated-csize.jar");
        writeJar(jarFile, "small.bin", content, /* storedName = */ null, /* storedContent = */ null);
        overstateUncompressedSize(jarFile, "small.bin", /* compressedSize = */ 0xfffffff0);

        try (var vfs = new Vfs()) {
            final var entry = vfs.open(jarFile.toString()).getEntry("small.bin");
            // The compressed size the entry declares is cut short at the end of the jarfile
            assertThat(entry.getCompressedSize()).isLessThanOrEqualTo(Files.size(jarFile));
            final var allocatedBefore = allocatedBytes();
            assertThat(entry.load()).isEqualTo(content);
            assertThat(allocatedBytes() - allocatedBefore).isLessThan(MAX_ALLOCATED_BYTES);
        }
    }

    /**
     * A small deflated entry whose compressed size runs past the end of the jarfile, and whose declared compressed
     * size could inflate to its declared uncompressed size, does not allocate its declared uncompressed size when
     * it is read through a {@link RandomAccessOrSequentialReader}, since only the bytes left in the jarfile can be
     * inflated.
     */
    @Test
    public void readingASmallEntryWhoseCompressedSizeRunsPastTheEndDoesNotAllocateItsDeclaredSize(
            @TempDir final Path tempDir) throws IOException {
        final var content = "small".getBytes(StandardCharsets.UTF_8);
        final var jarFile = tempDir.resolve("overstated-csize.jar");
        writeJar(jarFile, "small.bin", content, /* storedName = */ null, /* storedContent = */ null);
        overstateUncompressedSize(jarFile, "small.bin", /* compressedSize = */ DECLARED_SIZE);

        try (var vfs = new Vfs()) {
            final var entry = vfs.open(jarFile.toString()).getEntry("small.bin");
            final var allocatedBefore = allocatedBytes();
            try (var reader = new RandomAccessOrSequentialReader(entry)) {
                assertThat(reader.length()).isEqualTo(content.length);
            }
            assertThat(allocatedBytes() - allocatedBefore).isLessThan(MAX_ALLOCATED_BYTES);
        }
    }

    /**
     * A small deflated nested jarfile within a large jarfile is opened without allocating the uncompressed size it
     * declares, since its compressed bytes cannot inflate to anywhere near that size, although the jarfiles nested
     * within the large jarfile may inflate to more than that size.
     */
    @Test
    public void openingASmallNestedJarfileDoesNotAllocateItsDeclaredSize(@TempDir final Path tempDir)
            throws IOException {
        final var innerJar = tempDir.resolve("inner.jar");
        writeJar(innerJar, "a.txt", "a".getBytes(StandardCharsets.UTF_8), /* storedName = */ null,
                /* storedContent = */ null);
        // Pad the outer jarfile so that the jarfiles nested within it may inflate to more than DECLARED_SIZE, even
        // without the minimum of 256 MB
        final var outerJar = tempDir.resolve("outer.jar");
        writeJar(outerJar, "lib/inner.jar", Files.readAllBytes(innerJar), "padding.bin",
                randomBytes(DECLARED_SIZE / 50, 1));
        overstateUncompressedSize(outerJar, "lib/inner.jar", /* compressedSize = */ 0);

        try (var vfs = new Vfs()) {
            vfs.open(outerJar.toString());
            final var allocatedBefore = allocatedBytes();
            final var root = vfs.open(outerJar + "!/lib/inner.jar");
            assertThat(allocatedBytes() - allocatedBefore).isLessThan(MAX_ALLOCATED_BYTES);
            assertThat(root.getEntry("a.txt").load()).isEqualTo("a".getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * A deflated nested jarfile that could inflate to the uncompressed size it declares is opened without
     * allocating that size, when what is left of what the jarfiles nested within the jarfile that holds it may
     * inflate to is less than that size.
     */
    @Test
    public void openingANestedJarfileDoesNotAllocateMoreThanItMayInflateTo(@TempDir final Path tempDir)
            throws IOException {
        // Random bytes, stored, so that the nested jarfile hardly compresses, and its compressed bytes could
        // inflate to more than DECLARED_SIZE
        final var randomContent = randomBytes(20 * 1024, 2);
        final var innerJar = tempDir.resolve("inner.jar");
        writeJar(innerJar, "unused.txt", new byte[0], "random.bin", randomContent);
        // The jarfiles nested within a short jarfile may inflate to 256 MB in all. Another nested jarfile, of
        // 254 MB of zeros, is opened first, so that less than MAX_ALLOCATED_BYTES of that is left
        final var numZeros = 254L * 1024 * 1024;
        final var chunk = new byte[64 * 1024];
        final var crc = new CRC32();
        for (long i = 0; i < numZeros; i += chunk.length) {
            crc.update(chunk);
        }
        final var outerJar = tempDir.resolve("outer.jar");
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(outerJar))) {
            zipOut.setLevel(Deflater.BEST_SPEED);
            zipOut.putNextEntry(new ZipEntry("lib/zeros.jar"));
            // Not closed, since closing it would close zipOut: finish() writes its central directory
            final var nestedZipOut = new ZipOutputStream(zipOut);
            final var entry = new ZipEntry("zeros.bin");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(numZeros);
            entry.setCompressedSize(numZeros);
            entry.setCrc(crc.getValue());
            nestedZipOut.putNextEntry(entry);
            for (long i = 0; i < numZeros; i += chunk.length) {
                nestedZipOut.write(chunk);
            }
            nestedZipOut.closeEntry();
            nestedZipOut.finish();
            zipOut.closeEntry();
            zipOut.putNextEntry(new ZipEntry("lib/inner.jar"));
            Files.copy(innerJar, zipOut);
            zipOut.closeEntry();
        }
        overstateUncompressedSize(outerJar, "lib/inner.jar", /* compressedSize = */ 0);
        assertThat(100L * Files.size(outerJar)).isLessThan(numZeros);

        try (var vfs = new Vfs()) {
            vfs.open(outerJar.toString());
            assertThat(vfs.open(outerJar + "!/lib/zeros.jar").getEntry("zeros.bin")).isNotNull();
            final var allocatedBefore = allocatedBytes();
            final var root = vfs.open(outerJar + "!/lib/inner.jar");
            assertThat(allocatedBytes() - allocatedBefore).isLessThan(MAX_ALLOCATED_BYTES);
            assertThat(root.getEntry("random.bin").load()).isEqualTo(randomContent);
        }
    }
}
