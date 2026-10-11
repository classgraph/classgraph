package nonapi.io.github.classgraph.fastzipfilereader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * A deflated nested jarfile is inflated before it is read, so a small jarfile could otherwise inflate to fill the
 * heap or the temporary directory. The jarfiles nested within a jarfile may inflate to at most 100 times its
 * length, or 256 MB if that is more, all together.
 */
public class NestedJarInflationLimitTest {
    /** The size of the chunks a jarfile entry is written in, so that no test holds a whole entry in memory. */
    private static final int CHUNK_SIZE = 64 * 1024;

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
    private static void writeJarOfDeflatedJarsOfZeros(final File jarFile, final List<String> entryNames,
            final long numZeros) throws IOException {
        final byte[] chunk = new byte[CHUNK_SIZE];
        final CRC32 crc = new CRC32();
        for (long i = 0; i < numZeros; i += CHUNK_SIZE) {
            crc.update(chunk);
        }
        try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(jarFile))) {
            // Zeros still deflate to about a 200th of their length at the fastest level, which takes a third of
            // the time of the default level
            zipOut.setLevel(Deflater.BEST_SPEED);
            for (final String entryName : entryNames) {
                zipOut.putNextEntry(new ZipEntry(entryName));
                // Not closed, since closing it would close zipOut: finish() writes its central directory
                final ZipOutputStream nestedZipOut = new ZipOutputStream(zipOut);
                final ZipEntry entry = new ZipEntry("zeros.bin");
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
    private static void writeJarOfDeflatedJars(final File jarFile, final List<String> entryNames,
            final List<File> nestedJarFiles) throws IOException {
        try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(jarFile))) {
            for (int i = 0; i < entryNames.size(); i++) {
                zipOut.putNextEntry(new ZipEntry(entryNames.get(i)));
                Files.copy(nestedJarFiles.get(i).toPath(), zipOut);
                zipOut.closeEntry();
            }
        }
    }

    /**
     * Open a jarfile, which may be nested, through a {@link NestedJarHandler}.
     *
     * @param nestedJarHandler
     *            the nested jar handler.
     * @param nestedJarPath
     *            the path of the jarfile, with a "!/" before each nested jarfile.
     * @return the jarfile.
     * @throws Exception
     *             if the jarfile could not be opened.
     */
    private static LogicalZipFile open(final NestedJarHandler nestedJarHandler, final String nestedJarPath)
            throws Exception {
        final Entry<LogicalZipFile, String> logicalZipFileAndPackageRoot = //
                nestedJarHandler.nestedPathToLogicalZipFileAndPackageRootMap.get(nestedJarPath, /* log = */ null);
        return logicalZipFileAndPackageRoot.getKey();
    }

    /**
     * Create a {@link NestedJarHandler}.
     *
     * @param maxBufferedJarRAMSize
     *            the size above which a nested jarfile is inflated to a temporary file rather than to RAM.
     * @return the nested jar handler.
     */
    private static NestedJarHandler newNestedJarHandler(final int maxBufferedJarRAMSize) {
        final ScanSpec scanSpec = new ScanSpec();
        scanSpec.maxBufferedJarRAMSize = maxBufferedJarRAMSize;
        return new NestedJarHandler(scanSpec, new InterruptionChecker(), new ReflectionUtils());
    }

    /**
     * Find the root cause of an exception.
     *
     * @param t
     *            the exception.
     * @return the root cause.
     */
    private static Throwable rootCause(final Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * Nested jarfiles that inflate to about a thousand times their compressed size open, however small the jarfile
     * they are nested within, as long as together they inflate to no more than the minimum limit. Such a jarfile
     * holds a large file that compresses very well, such as a sparse database used as a test fixture.
     */
    @Test
    public void nestedJarfilesWithinTheMinimumLimitOpen(@TempDir final File tempDir) throws Exception {
        final long numZeros = 8L * 1024 * 1024;
        final List<String> entryNames = Arrays.asList("lib/a.jar", "lib/b.jar");
        final File outerJar = new File(tempDir, "outer.jar");
        writeJarOfDeflatedJarsOfZeros(outerJar, entryNames, numZeros);
        assertThat(2 * numZeros).isGreaterThan(100L * outerJar.length());

        final NestedJarHandler nestedJarHandler = newNestedJarHandler(new ScanSpec().maxBufferedJarRAMSize);
        try {
            for (final String entryName : entryNames) {
                final LogicalZipFile nestedJar = open(nestedJarHandler, outerJar.getPath() + "!/" + entryName);
                assertThat(nestedJar.entries).hasSize(1);
                assertThat(nestedJar.entries.get(0).uncompressedSize).isEqualTo(numZeros);
            }
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
    }

    /**
     * A nested jarfile that inflates to about a thousand times its compressed size, and to more than the minimum
     * limit, is refused, whether it would be inflated into RAM or spilled to a temporary file. (It is spilled
     * either way, since it is longer than the default maximum it would be inflated into RAM up to.)
     */
    @Test
    public void aNestedJarfileThatInflatesTooFarIsRefused(@TempDir final File tempDir) throws Exception {
        final File outerJar = new File(tempDir, "outer.jar");
        writeJarOfDeflatedJarsOfZeros(outerJar, Collections.singletonList("lib/inflation-limit-bomb-inner.jar"),
                NestedJarInflationAllowance.MIN_MAX_BYTES + CHUNK_SIZE);
        assertThat(100L * outerJar.length()).isLessThan(NestedJarInflationAllowance.MIN_MAX_BYTES);

        for (final int maxBufferedJarRAMSize : new int[] { new ScanSpec().maxBufferedJarRAMSize, 0 }) {
            final NestedJarHandler nestedJarHandler = newNestedJarHandler(maxBufferedJarRAMSize);
            try {
                Throwable thrown = null;
                try {
                    open(nestedJarHandler, outerJar.getPath() + "!/lib/inflation-limit-bomb-inner.jar");
                } catch (final Exception e) {
                    thrown = e;
                }
                assertThat(thrown).isNotNull();
                assertThat(rootCause(thrown)).isInstanceOf(IOException.class)
                        .hasMessageContaining("lib/inflation-limit-bomb-inner.jar")
                        .hasMessageContaining("100 times");
            } finally {
                nestedJarHandler.close(/* log = */ null);
            }
        }
    }

    /**
     * Many nested jarfiles that each inflate too far are refused once together they would exceed the limit, so that
     * splitting the content of a nested jarfile among many entries does not get round the limit.
     */
    @Test
    public void manyNestedJarfilesShareOneLimit(@TempDir final File tempDir) throws Exception {
        final long numZeros = 16L * 1024 * 1024;
        final List<String> entryNames = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            entryNames.add("lib/inner" + i + ".jar");
        }
        final File outerJar = new File(tempDir, "outer.jar");
        writeJarOfDeflatedJarsOfZeros(outerJar, entryNames, numZeros);

        final NestedJarHandler nestedJarHandler = newNestedJarHandler(new ScanSpec().maxBufferedJarRAMSize);
        try {
            long totalInflated = 0;
            int numRefused = 0;
            for (final String entryName : entryNames) {
                try {
                    open(nestedJarHandler, outerJar.getPath() + "!/" + entryName);
                    totalInflated += numZeros;
                } catch (final Exception e) {
                    assertThat(rootCause(e)).hasMessageContaining("100 times");
                    numRefused++;
                }
            }
            assertThat(numRefused).isPositive();
            assertThat(totalInflated).isLessThanOrEqualTo(NestedJarInflationAllowance.MIN_MAX_BYTES);
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
    }

    /** A deflated nested jarfile of ordinary content, and one nested in turn within it, still open. */
    @Test
    public void anOrdinaryDeflatedNestedJarfileOpens(@TempDir final File tempDir) throws Exception {
        final byte[] randomBytes = new byte[1024 * 1024];
        new Random(2).nextBytes(randomBytes);
        final File innermostJar = new File(tempDir, "innermost.jar");
        try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(innermostJar))) {
            zipOut.putNextEntry(new ZipEntry("random.bin"));
            zipOut.write(randomBytes);
            zipOut.closeEntry();
        }
        final File innerJar = new File(tempDir, "inner.jar");
        writeJarOfDeflatedJars(innerJar, Collections.singletonList("lib/innermost.jar"),
                Collections.singletonList(innermostJar));
        final File outerJar = new File(tempDir, "outer.jar");
        writeJarOfDeflatedJars(outerJar, Collections.singletonList("lib/inner.jar"),
                Collections.singletonList(innerJar));

        for (final int maxBufferedJarRAMSize : new int[] { new ScanSpec().maxBufferedJarRAMSize, 0 }) {
            final NestedJarHandler nestedJarHandler = newNestedJarHandler(maxBufferedJarRAMSize);
            try {
                final LogicalZipFile innermost = open(nestedJarHandler,
                        outerJar.getPath() + "!/lib/inner.jar!/lib/innermost.jar");
                assertThat(innermost.entries).hasSize(1);
                assertThat(innermost.entries.get(0).entryName).isEqualTo("random.bin");
                assertThat(Arrays.equals(innermost.entries.get(0).getSlice().load(), randomBytes)).isTrue();
            } finally {
                nestedJarHandler.close(/* log = */ null);
            }
        }
    }
}
