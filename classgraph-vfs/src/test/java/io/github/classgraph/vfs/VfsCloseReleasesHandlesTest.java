package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.sun.management.UnixOperatingSystemMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Closing a virtual filesystem releases every file handle it opened. The caches of opened roots and of zipfiles
 * hold no handles of their own -- every handle belongs to the session -- so dropping the caches has to be enough to
 * release them all.
 */
public class VfsCloseReleasesHandlesTest {
    /** The number of times the virtual filesystem is opened and closed while the file handles are counted. */
    private static final int NUM_CYCLES = 20;

    /**
     * The number of open file handles, or -1 if the running JVM cannot count them (which is the case on Windows).
     *
     * @return the number of open file handles.
     */
    private static long numOpenFileHandles() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof final UnixOperatingSystemMXBean unixOsBean
                ? unixOsBean.getOpenFileDescriptorCount()
                : -1L;
    }

    /**
     * Write a jarfile holding a single entry.
     *
     * @param jarFile
     *            the jarfile to write.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJar(final File jarFile) throws IOException {
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry("com/xyz/widget.txt"));
            zipOut.write("widget".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
    }

    /**
     * Write a jarfile holding another jarfile, either stored, so that the inner jarfile is read in place through a
     * slice of the outer one, or deflated, so that it has to be inflated first.
     *
     * @param outerJarFile
     *            the outer jarfile to write.
     * @param innerJarBytes
     *            the content of the inner jarfile.
     * @param stored
     *            true to store the inner jarfile, false to deflate it.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJarContainingJar(final File outerJarFile, final byte[] innerJarBytes,
            final boolean stored) throws IOException {
        try (var fileOut = new FileOutputStream(outerJarFile); var zipOut = new ZipOutputStream(fileOut)) {
            final var entry = new ZipEntry("lib/inner.jar");
            if (stored) {
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(innerJarBytes.length);
                entry.setCompressedSize(innerJarBytes.length);
                final var crc = new CRC32();
                crc.update(innerJarBytes);
                entry.setCrc(crc.getValue());
            }
            zipOut.putNextEntry(entry);
            zipOut.write(innerJarBytes);
            zipOut.closeEntry();
        }
    }

    /**
     * Open a virtual filesystem over the given jarfiles and read through all of them, so that every cache of the
     * nested jar handler is filled, then close it.
     *
     * @param storedOuterJarFile
     *            an outer jarfile holding a stored inner jarfile.
     * @param deflatedOuterJarFile
     *            an outer jarfile holding a deflated inner jarfile.
     * @param dir
     *            a directory to open as a root.
     * @throws IOException
     *             if a jarfile could not be read.
     */
    private static void openAndCloseAVfs(final File storedOuterJarFile, final File deflatedOuterJarFile,
            final File dir) throws IOException {
        // No RAM is allowed to hold the inflated inner jarfile, so reading it spills to a temporary file, which is
        // another file handle that the session has to release
        try (var vfs = new Vfs(new VfsSpec().setMaxBufferedJarRAMSize(0))) {
            for (final var outerJarFile : new File[] { storedOuterJarFile, deflatedOuterJarFile }) {
                assertThat(vfs.open(outerJarFile).getEntries()).isNotEmpty();
                final var innerRoot = vfs.open(outerJarFile.getPath() + "!/lib/inner.jar");
                try (var byteBuffer = innerRoot.getEntry("com/xyz/widget.txt").read()) {
                    assertThat(byteBuffer.getByteBuffer()).isNotNull();
                }
            }
            assertThat(vfs.open(dir).getEntries()).isNotEmpty();
        }
    }

    /**
     * Every file handle opened while reading a directory, a jarfile, a jarfile nested inside a jarfile in place,
     * and a jarfile that had to be inflated to a temporary file first, is released when the virtual filesystem is
     * closed. Opening and closing one repeatedly therefore does not run the JVM out of file handles.
     *
     * @param tempDir
     *            a temporary directory to write the jarfiles to.
     * @throws IOException
     *             if a jarfile could not be written or read.
     */
    @Test
    public void closingAVfsReleasesEveryFileHandleItOpened(@TempDir final File tempDir) throws IOException {
        assumeTrue(numOpenFileHandles() >= 0L, "The running JVM cannot count open file handles");

        final var innerJarFile = new File(tempDir, "inner.jar");
        writeJar(innerJarFile);
        final var innerJarBytes = Files.readAllBytes(innerJarFile.toPath());
        final var storedOuterJarFile = new File(tempDir, "stored-outer.jar");
        writeJarContainingJar(storedOuterJarFile, innerJarBytes, /* stored = */ true);
        final var deflatedOuterJarFile = new File(tempDir, "deflated-outer.jar");
        writeJarContainingJar(deflatedOuterJarFile, innerJarBytes, /* stored = */ false);

        // The first cycle loads the classes that reading a jarfile needs, and whatever they open in turn, so the
        // baseline is taken after it rather than before it
        openAndCloseAVfs(storedOuterJarFile, deflatedOuterJarFile, tempDir);
        final var numOpenAtStart = numOpenFileHandles();

        for (var i = 0; i < NUM_CYCLES; i++) {
            openAndCloseAVfs(storedOuterJarFile, deflatedOuterJarFile, tempDir);
        }

        // Each cycle opens at least four files, so a leak of even one handle per cycle would show up well above the
        // slack allowed here for whatever else the JVM opened in the background while the cycles ran
        assertThat(numOpenFileHandles()).isLessThanOrEqualTo(numOpenAtStart + 4L);
    }
}
