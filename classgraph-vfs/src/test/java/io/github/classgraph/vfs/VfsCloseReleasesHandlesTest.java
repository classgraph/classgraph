package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.tools.ToolProvider;

import com.sun.management.UnixOperatingSystemMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Closing a virtual filesystem releases every file handle it opened. The caches of opened roots and of zipfiles
 * hold no handles of their own -- every handle belongs to a root in the ownership tree -- so closing every root has
 * to be enough to release them all.
 */
public class VfsCloseReleasesHandlesTest {
    /** The number of times the virtual filesystem is opened and closed while the file handles are counted. */
    private static final int NUM_CYCLES = 20;

    /** The Linux directory that holds one entry per file descriptor this process has open. */
    private static final File PROC_SELF_FD = new File("/proc/self/fd");

    /**
     * The number of open file handles, or -1 if the running JVM cannot count them (which is the case on Windows).
     *
     * @return the number of open file handles.
     */
    private static long numOpenFileHandles() {
        // On Linux the descriptors are listed in /proc, which is both the most direct measure and immune to the
        // management API failing to start up inside a container
        final var fileDescriptorNames = PROC_SELF_FD.list();
        if (fileDescriptorNames != null) {
            return fileDescriptorNames.length;
        }
        try {
            return ManagementFactory
                    .getOperatingSystemMXBean() instanceof final UnixOperatingSystemMXBean unixOsBean
                            ? unixOsBean.getOpenFileDescriptorCount()
                            : -1L;
        } catch (final RuntimeException _) {
            // Some JVMs throw while starting up the management API, and one that cannot report the count is no
            // different here from one that does not implement the interface
            return -1L;
        }
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
        // another file handle that closing the virtual filesystem has to release
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

    /**
     * The number of file descriptors this process has open on the given file, counted from the links in
     * {@code /proc/self/fd}, which exist only on Linux.
     *
     * @param file
     *            the file.
     * @return the number of file descriptors open on the file.
     * @throws IOException
     *             if {@code /proc/self/fd} could not be listed.
     */
    private static long numFileHandlesOpenOn(final Path file) throws IOException {
        final var realFile = file.toRealPath();
        var numHandles = 0L;
        try (var fileDescriptors = Files.newDirectoryStream(PROC_SELF_FD.toPath())) {
            for (final var fileDescriptor : fileDescriptors) {
                try {
                    if (Files.readSymbolicLink(fileDescriptor).equals(realFile)) {
                        numHandles++;
                    }
                } catch (final IOException _) {
                    // The descriptor was closed after the directory was listed, or is the one listing it
                }
            }
        }
        return numHandles;
    }

    /**
     * Open a stream on an entry, read one byte of it, and leave it open.
     *
     * @param entry
     *            the entry.
     * @return the open stream.
     * @throws IOException
     *             if the entry could not be read.
     */
    private static InputStream openAndReadOneByte(final VfsEntry entry) throws IOException {
        final var inputStream = entry.open();
        assertThat(inputStream.read()).isEqualTo('w');
        return inputStream;
    }

    /**
     * A stream that the caller left open on a file in a directory, or on a resource of a module that is a
     * directory, holds a file handle of its own, which closing the virtual filesystem closes, so that the caller
     * leaving one open does not leak the handle until the garbage collector finds the stream.
     *
     * @param tempDir
     *            a temporary directory to write the directory and the module to.
     * @throws IOException
     *             if a file could not be written or read.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    public void closingAVfsClosesTheStreamsTheCallerLeftOpen(@TempDir final Path tempDir) throws IOException {
        final var dir = tempDir.resolve("dir");
        final var dirFile = dir.resolve("com/xyz/widget.txt");
        Files.createDirectories(dirFile.getParent());
        Files.writeString(dirFile, "widget");

        // A module that is a directory rather than a jarfile, which the JDK's ModuleReader reads each resource of
        // through a file stream of its own, so that closing the reader does not close the streams it opened
        final var srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        final var moduleInfoSrc = srcDir.resolve("module-info.java");
        Files.writeString(moduleInfoSrc, "module com.xyz.exploded {}\n");
        final var moduleDir = tempDir.resolve("module");
        final var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the tests must be run on a JDK, not a JRE").isNotNull();
        assertThat(compiler.run(/* in = */ null, /* out = */ null, /* err = */ null, "-d", moduleDir.toString(),
                moduleInfoSrc.toString())).as("javac exit code").isZero();
        final var moduleFile = moduleDir.resolve("com/xyz/widget.txt");
        Files.createDirectories(moduleFile.getParent());
        Files.writeString(moduleFile, "widget");
        final var moduleReference = ModuleFinder.of(moduleDir).find("com.xyz.exploded").orElseThrow();

        final InputStream dirInputStream;
        final InputStream moduleInputStream;
        try (var vfs = new Vfs()) {
            dirInputStream = openAndReadOneByte(vfs.open(dir).getEntry("com/xyz/widget.txt"));
            moduleInputStream = openAndReadOneByte(vfs.open(moduleReference).getEntry("com/xyz/widget.txt"));
            assertThat(List.of(numFileHandlesOpenOn(dirFile), numFileHandlesOpenOn(moduleFile)))
                    .as("handles open on the directory file and the module file").containsExactly(1L, 1L);
        }

        assertThat(List.of(numFileHandlesOpenOn(dirFile), numFileHandlesOpenOn(moduleFile)))
                .as("handles open on the directory file and the module file").containsExactly(0L, 0L);
        assertThatIOException().isThrownBy(dirInputStream::read);
        assertThatIOException().isThrownBy(moduleInputStream::read);
        // The caller closing a stream that the close of the virtual filesystem already closed is not an error
        dirInputStream.close();
        moduleInputStream.close();
    }
}
