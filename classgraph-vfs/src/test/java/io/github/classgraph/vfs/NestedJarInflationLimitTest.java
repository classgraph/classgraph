package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A deflated nested jarfile is inflated before it is read, so a small jarfile could otherwise inflate to fill the
 * heap or the temporary directory. The jarfiles nested within a jarfile may inflate to at most 100 times its
 * length, or 256 MB if that is more, all together.
 */
public class NestedJarInflationLimitTest {
    /** The size of the chunks a jarfile entry is written in, so that no test holds a whole entry in memory. */
    private static final int CHUNK_SIZE = 64 * 1024;

    /** The number of bytes the jarfiles nested within a jarfile may inflate to, however short the jarfile is. */
    private static final long MIN_LIMIT = 256L * 1024 * 1024;

    /**
     * Write a jarfile holding other jarfiles as deflated entries, each of which holds one stored entry,
     * {@code zeros.bin}, of zeros, and so deflates to about a thousandth of its length. The nested jarfiles are
     * written straight into the entries, so that a test of a limit of hundreds of megabytes writes no file of that
     * size.
     *
     * @param jarFile
     *            the jarfile to write.
     * @param entryNames
     *            the names of the entries.
     * @param numZeros
     *            the length of {@code zeros.bin} in each nested jarfile, a multiple of {@link #CHUNK_SIZE}.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJarOfDeflatedJarsOfZeros(final Path jarFile, final List<String> entryNames,
            final long numZeros) throws IOException {
        final var chunk = new byte[CHUNK_SIZE];
        final var crc = new CRC32();
        for (long i = 0; i < numZeros; i += CHUNK_SIZE) {
            crc.update(chunk);
        }
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(jarFile))) {
            // Zeros still deflate to about a 200th of their length at the fastest level, which takes a third of
            // the time of the default level
            zipOut.setLevel(Deflater.BEST_SPEED);
            for (final var entryName : entryNames) {
                zipOut.putNextEntry(new ZipEntry(entryName));
                // Not closed, since closing it would close zipOut: finish() writes its central directory
                final var nestedZipOut = new ZipOutputStream(zipOut);
                final var entry = new ZipEntry("zeros.bin");
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(numZeros);
                entry.setCompressedSize(numZeros);
                entry.setCrc(crc.getValue());
                nestedZipOut.putNextEntry(entry);
                for (long i = 0; i < numZeros; i += CHUNK_SIZE) {
                    nestedZipOut.write(chunk);
                }
                nestedZipOut.closeEntry();
                nestedZipOut.finish();
                zipOut.closeEntry();
            }
        }
    }

    /**
     * Write a jarfile holding other jarfiles as deflated entries.
     *
     * @param jarFile
     *            the jarfile to write.
     * @param entryNames
     *            the names of the entries.
     * @param nestedJarFiles
     *            the jarfiles to put in the entries, in the same order.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJarOfDeflatedJars(final Path jarFile, final List<String> entryNames,
            final List<Path> nestedJarFiles) throws IOException {
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(jarFile))) {
            for (int i = 0; i < entryNames.size(); i++) {
                zipOut.putNextEntry(new ZipEntry(entryNames.get(i)));
                Files.copy(nestedJarFiles.get(i), zipOut);
                zipOut.closeEntry();
            }
        }
    }

    /**
     * List the temporary files a nested jarfile of a given name would be spilled to.
     *
     * @param nestedJarName
     *            the name of the nested jarfile.
     * @return the names of the temporary files.
     */
    private static Set<String> tempFilesFor(final String nestedJarName) {
        final var names = new HashSet<String>();
        final var tempFiles = new File(System.getProperty("java.io.tmpdir")).listFiles();
        if (tempFiles != null) {
            for (final var tempFile : tempFiles) {
                if (tempFile.getName().startsWith("ClassGraph-") && tempFile.getName().endsWith(nestedJarName)) {
                    names.add(tempFile.getName());
                }
            }
        }
        return names;
    }

    /**
     * Nested jarfiles that inflate to about a thousand times their compressed size open, however small the jarfile
     * they are nested within, as long as together they inflate to no more than the minimum limit. Such a jarfile
     * holds a large file that compresses very well, such as a sparse database used as a test fixture.
     */
    @Test
    public void nestedJarfilesWithinTheMinimumLimitOpen(@TempDir final Path tempDir) throws IOException {
        final var numZeros = 8L * 1024 * 1024;
        final var entryNames = List.of("lib/a.jar", "lib/b.jar");
        final var outerJar = tempDir.resolve("outer.jar");
        writeJarOfDeflatedJarsOfZeros(outerJar, entryNames, numZeros);
        assertThat(2 * numZeros).isGreaterThan(100L * Files.size(outerJar));

        try (var vfs = new Vfs()) {
            for (final var entryName : entryNames) {
                final var root = vfs.open(outerJar + "!/" + entryName);
                assertThat(root.getEntry("zeros.bin")).isNotNull();
            }
        }
    }

    /**
     * A nested jarfile that inflates to about a thousand times its compressed size, and to more than the minimum
     * limit, is refused, whether it would be inflated into RAM or spilled to a temporary file, and no temporary
     * file is left behind. (It is spilled either way, since it is longer than the default maximum it would be
     * inflated into RAM up to.)
     */
    @Test
    public void aNestedJarfileThatInflatesTooFarIsRefused(@TempDir final Path tempDir) throws IOException {
        final var outerJar = tempDir.resolve("outer.jar");
        writeJarOfDeflatedJarsOfZeros(outerJar, List.of("lib/inflation-limit-bomb-inner.jar"),
                MIN_LIMIT + CHUNK_SIZE);
        assertThat(100L * Files.size(outerJar)).isLessThan(MIN_LIMIT);

        final var tempFilesBefore = tempFilesFor("inflation-limit-bomb-inner.jar");
        for (final var vfsSpec : List.of(new VfsSpec(), new VfsSpec().setMaxBufferedJarRAMSize(0))) {
            try (var vfs = new Vfs(vfsSpec)) {
                assertThatThrownBy(() -> vfs.open(outerJar + "!/lib/inflation-limit-bomb-inner.jar"))
                        .isInstanceOf(IOException.class).rootCause()
                        .hasMessageContaining("lib/inflation-limit-bomb-inner.jar")
                        .hasMessageContaining("100 times");
                assertThat(tempFilesFor("inflation-limit-bomb-inner.jar")).isEqualTo(tempFilesBefore);
            }
        }
    }

    /**
     * Many nested jarfiles that each inflate too far are refused once together they would exceed the limit, so that
     * splitting the content of a nested jarfile among many entries does not get round the limit.
     */
    @Test
    public void manyNestedJarfilesShareOneLimit(@TempDir final Path tempDir) throws IOException {
        final var numZeros = 16L * 1024 * 1024;
        final var entryNames = new ArrayList<String>();
        for (int i = 0; i < 20; i++) {
            entryNames.add("lib/inner" + i + ".jar");
        }
        final var outerJar = tempDir.resolve("outer.jar");
        writeJarOfDeflatedJarsOfZeros(outerJar, entryNames, numZeros);

        try (var vfs = new Vfs()) {
            long totalInflated = 0;
            int numRefused = 0;
            for (final var entryName : entryNames) {
                try {
                    vfs.open(outerJar + "!/" + entryName);
                    totalInflated += numZeros;
                } catch (final IOException _) {
                    numRefused++;
                }
            }
            assertThat(numRefused).isPositive();
            assertThat(totalInflated).isLessThanOrEqualTo(MIN_LIMIT);
        }
    }

    /**
     * Closing the root of a nested jarfile gives back what it took of the limit, so that a long-lived {@link Vfs}
     * can open it again.
     */
    @Test
    public void closingANestedJarfileGivesBackItsShareOfTheLimit(@TempDir final Path tempDir) throws IOException {
        // The nested jarfile inflates to more than half of what the jarfiles nested within the outer jarfile may
        // inflate to
        final var numZeros = MIN_LIMIT * 9 / 16;
        final var outerJar = tempDir.resolve("outer.jar");
        writeJarOfDeflatedJarsOfZeros(outerJar, List.of("lib/inner.jar"), numZeros);
        assertThat(100L * Files.size(outerJar)).isLessThan(MIN_LIMIT / 2);

        try (var vfs = new Vfs()) {
            for (int i = 0; i < 3; i++) {
                final var root = vfs.open(outerJar + "!/lib/inner.jar");
                assertThat(root.getEntry("zeros.bin")).isNotNull();
                root.close();
            }
        }
    }

    /** A deflated nested jarfile of ordinary content, and one nested in turn within it, still open. */
    @Test
    public void anOrdinaryDeflatedNestedJarfileOpens(@TempDir final Path tempDir) throws IOException {
        final var randomBytes = new byte[1024 * 1024];
        new Random(2).nextBytes(randomBytes);
        final var innermostJar = tempDir.resolve("innermost.jar");
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(innermostJar))) {
            zipOut.putNextEntry(new ZipEntry("random.bin"));
            zipOut.write(randomBytes);
            zipOut.closeEntry();
        }
        final var innerJar = tempDir.resolve("inner.jar");
        writeJarOfDeflatedJars(innerJar, List.of("lib/innermost.jar"), List.of(innermostJar));
        final var outerJar = tempDir.resolve("outer.jar");
        writeJarOfDeflatedJars(outerJar, List.of("lib/inner.jar"), List.of(innerJar));

        for (final var vfsSpec : List.of(new VfsSpec(), new VfsSpec().setMaxBufferedJarRAMSize(0))) {
            try (var vfs = new Vfs(vfsSpec)) {
                final var root = vfs.open(outerJar + "!/lib/inner.jar!/lib/innermost.jar");
                assertThat(root.getEntry("random.bin").load()).isEqualTo(randomBytes);
            }
        }
    }
}
