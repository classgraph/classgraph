package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Closing a {@link ScanResult} closes every stream that the caller opened on one of its resources and left open,
 * whichever method the resource was found with, and whether or not {@link ScanResult#getAllResources()} was ever
 * called. A stream on a file in a directory, or on a resource of a module that is a directory, holds a file handle
 * of its own, which would otherwise stay open until the garbage collector found the stream.
 */
public class ScanResultCloseReleasesStreamsTest {
    /** The Linux directory that holds one entry per file descriptor this process has open. */
    private static final File PROC_SELF_FD = new File("/proc/self/fd");

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
     * Write a file, creating its parent directories.
     *
     * @param file
     *            the file.
     * @throws IOException
     *             if the file could not be written.
     */
    private static void writeFile(final Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "widget");
    }

    /**
     * Open a stream on a resource, read one byte of it, and leave it open.
     *
     * @param resource
     *            the resource.
     * @return the open stream.
     * @throws IOException
     *             if the resource could not be read.
     */
    private static InputStream openAndReadOneByte(final Resource resource) throws IOException {
        final var inputStream = resource.open();
        assertThat(inputStream.read()).isEqualTo('w');
        return inputStream;
    }

    /**
     * A stream left open on an accepted resource of a directory, and one left open on a resource that is not
     * accepted, are both closed by closing the {@link ScanResult}, although {@link ScanResult#getAllResources()}
     * was never called, so the {@link ScanResult} holds neither resource in a list of its own.
     *
     * @param tempDir
     *            a temporary directory to write the classpath directory to.
     * @throws IOException
     *             if a file could not be written or read.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    public void closingTheScanResultClosesStreamsOnADirectory(@TempDir final Path tempDir) throws IOException {
        final var acceptedFile = tempDir.resolve("accepted/a.txt");
        writeFile(acceptedFile);
        final var notAcceptedFile = tempDir.resolve("other/b.txt");
        writeFile(notAcceptedFile);

        final InputStream acceptedInputStream;
        final InputStream notAcceptedInputStream;
        try (var scanResult = new ClassGraph().enableClasspathEntries(tempDir.toString()).acceptPaths("accepted")
                .scan()) {
            acceptedInputStream = openAndReadOneByte(scanResult.getResourcesWithPath("accepted/a.txt").get(0));
            notAcceptedInputStream = openAndReadOneByte(
                    scanResult.getResourcesWithPathIgnoringAccept("other/b.txt").get(0));
            assertThat(List.of(numFileHandlesOpenOn(acceptedFile), numFileHandlesOpenOn(notAcceptedFile)))
                    .as("handles open on the accepted file and the file that is not accepted")
                    .containsExactly(1L, 1L);
        }

        assertThat(List.of(numFileHandlesOpenOn(acceptedFile), numFileHandlesOpenOn(notAcceptedFile)))
                .as("handles open on the accepted file and the file that is not accepted").containsExactly(0L, 0L);
        assertThatIOException().isThrownBy(acceptedInputStream::read);
        assertThatIOException().isThrownBy(notAcceptedInputStream::read);
        // The caller closing a stream that closing the ScanResult already closed is not an error
        acceptedInputStream.close();
        notAcceptedInputStream.close();
    }

    /**
     * A stream left open on a resource of a module that is a directory is closed by closing the {@link ScanResult}.
     * The JDK's reader for such a module opens a file stream of its own for each resource, which closing the reader
     * does not close.
     *
     * @param tempDir
     *            a temporary directory to compile the module in.
     * @throws IOException
     *             if a file could not be written or read.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    public void closingTheScanResultClosesStreamsOnAModuleDirectory(@TempDir final Path tempDir)
            throws IOException {
        final var moduleName = "com.xyz.exploded";
        final var srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        final var moduleInfoSrc = srcDir.resolve("module-info.java");
        Files.writeString(moduleInfoSrc, "module " + moduleName + " {}\n");
        final var moduleDir = tempDir.resolve("module");
        final var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the tests must be run on a JDK, not a JRE").isNotNull();
        assertThat(compiler.run(/* in = */ null, /* out = */ null, /* err = */ null, "-d", moduleDir.toString(),
                moduleInfoSrc.toString())).as("javac exit code").isZero();
        final var moduleFile = moduleDir.resolve("modulescan/a.txt");
        writeFile(moduleFile);

        final var finder = ModuleFinder.of(moduleDir);
        final var bootLayer = ModuleLayer.boot();
        final var configuration = bootLayer.configuration().resolve(finder, ModuleFinder.of(), Set.of(moduleName));
        final var moduleLayer = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(bootLayer),
                ScanResultCloseReleasesStreamsTest.class.getClassLoader()).layer();

        final InputStream inputStream;
        try (var scanResult = new ClassGraph().enableNonSystemModules().enableModuleLayers(moduleLayer)
                .ignoreParentModuleLayers().acceptPaths("modulescan").scan()) {
            inputStream = openAndReadOneByte(scanResult.getResourcesWithPath("modulescan/a.txt").get(0));
            assertThat(numFileHandlesOpenOn(moduleFile)).isEqualTo(1L);
        }

        assertThat(numFileHandlesOpenOn(moduleFile)).isZero();
        assertThatIOException().isThrownBy(inputStream::read);
        inputStream.close();
    }
}
