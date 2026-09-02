/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2026 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions
 * of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;

/**
 * Tests the teardown of a {@link Vfs}: that closing it deletes the temporary files that its roots extracted nested
 * jarfiles to, that closing one root deletes only that root's temporary file, that reading through it once it has
 * been closed is refused, and that an interruption raised while it was closing reaches the shared interruption
 * checker. A temporary file left behind would sit on disk for the rest of the life of the JVM.
 */
class VfsCloseTest {
    /**
     * Write a jarfile holding a single entry.
     *
     * @return the bytes of the jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static byte[] innerJarBytes() throws IOException {
        final var bytesOut = new ByteArrayOutputStream();
        try (var zipOut = new ZipOutputStream(bytesOut)) {
            zipOut.putNextEntry(new ZipEntry("com/xyz/widget.txt"));
            zipOut.write("widget".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        return bytesOut.toByteArray();
    }

    /**
     * Write a jarfile holding a deflated jarfile, which therefore has to be inflated before it can be read.
     *
     * @param outerJarFile
     *            the outer jarfile to write.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJarContainingDeflatedJar(final File outerJarFile) throws IOException {
        try (var fileOut = new FileOutputStream(outerJarFile); var zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry("lib/inner.jar"));
            zipOut.write(innerJarBytes());
            zipOut.closeEntry();
        }
    }

    /**
     * A {@link Vfs} that spills every nested jarfile it inflates straight to disk, rather than buffering it in RAM.
     *
     * @return the {@link Vfs}.
     */
    private static Vfs vfsThatSpillsToDisk() {
        return new Vfs(new VfsSpec().setMaxBufferedJarRAMSize(0));
    }

    /**
     * A {@link Vfs} that spills every nested jarfile it inflates straight to disk, rather than buffering it in RAM.
     *
     * @param memoryMapFiles
     *            the value to override {@code VfsSpec#memoryMapFiles} with. Files are memory-mapped on Windows
     *            only, so a test of the memory mapping path has to override the platform's own choice.
     * @return the {@link Vfs}.
     */
    private static Vfs vfsThatSpillsToDisk(final boolean memoryMapFiles) {
        return new Vfs(new VfsSpec().setMaxBufferedJarRAMSize(0).setMemoryMappingFiles(memoryMapFiles));
    }

    /**
     * Closing a root closes a buffer the caller read from it and has not closed, so that the buffer stops referring
     * to the content and the temporary file the root extracted is deleted as the root closes, rather than whenever
     * the caller gets round to closing the buffer.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into.
     * @throws Exception
     *             if the jarfile could not be written or read.
     */
    // #939
    @Test
    void closingTheRootClosesABufferTheCallerStillHolds(@TempDir final Path tempDir) throws Exception {
        final var outerJarFile = tempDir.resolve("outer.jar").toFile();
        writeJarContainingDeflatedJar(outerJarFile);

        final File extractedTempFile;
        try (var vfs = vfsThatSpillsToDisk(/* memoryMapFiles = */ true)) {
            final var innerRoot = vfs.open(outerJarFile.getPath() + "!/lib/inner.jar");
            extractedTempFile = innerRoot.getFile();
            assertThat(extractedTempFile).isNotNull().exists();

            try (var buffer = innerRoot.getEntry("com/xyz/widget.txt").read()) {
                assertThat(buffer.getByteBuffer()).isNotNull();
                innerRoot.close();
                // The root closed the buffer it handed out, so it no longer refers to the content, and the
                // temporary file is already gone rather than waiting for this try-with-resources block to end
                assertThat(buffer.getByteBuffer()).isNull();
                assertThat(extractedTempFile).doesNotExist();
            }
        }

        assertThat(extractedTempFile).doesNotExist();
    }

    /**
     * Closing a root closes a channel the caller opened on it and has not closed, for the same reason: a channel
     * reads the memory mapping directly where the entry is stored uncompressed, so one that is left open would keep
     * the file mapped and stop the temporary file being deleted as the root closes.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into.
     * @throws Exception
     *             if the jarfile could not be written or read.
     */
    // #939
    @Test
    void closingTheRootClosesAChannelTheCallerStillHolds(@TempDir final Path tempDir) throws Exception {
        final var outerJarFile = tempDir.resolve("outer.jar").toFile();
        writeJarContainingDeflatedJar(outerJarFile);

        final File extractedTempFile;
        try (var vfs = vfsThatSpillsToDisk(/* memoryMapFiles = */ true)) {
            final var innerRoot = vfs.open(outerJarFile.getPath() + "!/lib/inner.jar");
            extractedTempFile = innerRoot.getFile();
            assertThat(extractedTempFile).isNotNull().exists();

            try (var channel = Files.newByteChannel(innerRoot.getEntry("com/xyz/widget.txt").asPath())) {
                assertThat(channel.isOpen()).isTrue();
                innerRoot.close();
                // The root closed the channel it handed out, so it no longer reads the content, and the temporary
                // file is already gone rather than waiting for this try-with-resources block to end
                assertThat(channel.isOpen()).isFalse();
                assertThatThrownBy(() -> channel.read(ByteBuffer.allocate(1)))
                        .isInstanceOf(ClosedChannelException.class);
                assertThat(extractedTempFile).doesNotExist();
            }
        }

        assertThat(extractedTempFile).doesNotExist();
    }

    /** Closing a {@link Vfs} deletes the temporary file that a nested jarfile was extracted to. */
    @Test
    void theCloseDeletesTheTempFileANestedJarWasExtractedTo(@TempDir final Path tempDir) throws Exception {
        final var outerJarFile = tempDir.resolve("outer.jar").toFile();
        writeJarContainingDeflatedJar(outerJarFile);
        final File extractedTempFile;
        try (var vfs = vfsThatSpillsToDisk()) {
            final var innerRoot = vfs.open(outerJarFile.getPath() + "!/lib/inner.jar");
            assertThat(innerRoot.getEntries()).isNotEmpty();
            // The inner jarfile was deflated, so it was inflated to a temporary file, which the root now owns
            extractedTempFile = innerRoot.getFile();
            assertThat(extractedTempFile).isNotNull().exists();
        }

        assertThat(extractedTempFile).doesNotExist();
    }

    /**
     * Closing one root deletes the temporary file that root extracted, and leaves the temporary file of another
     * root of the same {@link Vfs} where it is: a root owns what was opened to read it, and nothing more.
     */
    @Test
    void closingOneRootDeletesOnlyItsOwnTempFile(@TempDir final Path tempDir) throws Exception {
        final var firstOuterJarFile = tempDir.resolve("first.jar").toFile();
        final var secondOuterJarFile = tempDir.resolve("second.jar").toFile();
        writeJarContainingDeflatedJar(firstOuterJarFile);
        writeJarContainingDeflatedJar(secondOuterJarFile);

        try (var vfs = vfsThatSpillsToDisk()) {
            final var firstInnerRoot = vfs.open(firstOuterJarFile.getPath() + "!/lib/inner.jar");
            final var secondInnerRoot = vfs.open(secondOuterJarFile.getPath() + "!/lib/inner.jar");
            final var firstTempFile = firstInnerRoot.getFile();
            final var secondTempFile = secondInnerRoot.getFile();
            assertThat(firstTempFile).isNotNull().exists();
            assertThat(secondTempFile).isNotNull().exists();

            firstInnerRoot.close();

            assertThat(firstTempFile).doesNotExist();
            assertThat(secondTempFile).exists();
            assertThat(secondInnerRoot.getEntry("com/xyz/widget.txt")).isNotNull();
        }
    }

    /**
     * Closing the root of the jarfile that encloses a nested jarfile deletes the temporary file the nested jarfile
     * was extracted to, since closing a root closes the roots that were opened within it.
     */
    @Test
    void closingTheContainerRootDeletesTheNestedJarTempFile(@TempDir final Path tempDir) throws Exception {
        final var outerJarFile = tempDir.resolve("outer.jar").toFile();
        writeJarContainingDeflatedJar(outerJarFile);

        try (var vfs = vfsThatSpillsToDisk()) {
            final var innerRoot = vfs.open(outerJarFile.getPath() + "!/lib/inner.jar");
            final var tempFile = innerRoot.getFile();
            assertThat(tempFile).isNotNull().exists();

            vfs.open(outerJarFile).close();

            assertThat(innerRoot.isClosed()).isTrue();
            assertThat(tempFile).doesNotExist();
        }
    }

    /**
     * A jarfile stored uncompressed within the jarfile that encloses it is read in place, as a byte range of the
     * enclosing jarfile, so no temporary file is created for it, and closing its root leaves the enclosing jarfile
     * readable: the root of the nested jarfile owns nothing.
     */
    @Test
    void aStoredNestedJarOwnsNoTempFile(@TempDir final Path tempDir) throws Exception {
        final var outerJarFile = tempDir.resolve("outer.jar").toFile();
        final var jarBytes = innerJarBytes();
        try (var fileOut = new FileOutputStream(outerJarFile); var zipOut = new ZipOutputStream(fileOut)) {
            final var entry = new ZipEntry("lib/inner.jar");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(jarBytes.length);
            entry.setCompressedSize(jarBytes.length);
            final var crc = new java.util.zip.CRC32();
            crc.update(jarBytes);
            entry.setCrc(crc.getValue());
            zipOut.putNextEntry(entry);
            zipOut.write(jarBytes);
            zipOut.closeEntry();
        }

        try (var vfs = vfsThatSpillsToDisk()) {
            final var outerRoot = vfs.open(outerJarFile);
            final var innerRoot = vfs.open(outerJarFile.getPath() + "!/lib/inner.jar");
            assertThat(innerRoot.getEntries()).isNotEmpty();
            // The nested jarfile is read as a byte range of the jarfile that encloses it, not extracted. The
            // comparison is against the canonical file, since a root reports the path it canonicalized the
            // jarfile to, and a temporary directory is reached through a symlink on macOS and through an 8.3
            // short name on Windows.
            assertThat(innerRoot.getFile()).isEqualTo(outerJarFile.getCanonicalFile());

            innerRoot.close();

            // The nested root released nothing that the enclosing root reads through
            assertThat(outerRoot.getEntry("lib/inner.jar").load()).isNotEmpty();
        }
    }

    /**
     * Reading a deflated entry through a closed {@link Vfs} is refused rather than handed an inflater from a
     * recycler that has already been force-closed.
     */
    @Test
    void aClosedVfsRefusesToReadADeflatedEntry(@TempDir final Path tempDir) throws Exception {
        final var jarFile = tempDir.resolve("jar.jar").toFile();
        try (var fileOut = new FileOutputStream(jarFile)) {
            fileOut.write(innerJarBytes());
        }

        final var vfs = new Vfs();
        final var entry = vfs.open(jarFile).getEntry("com/xyz/widget.txt");
        // The entry reads while the Vfs is open, so only the close can be what turns the second read away
        try (var entryInputStream = entry.open()) {
            assertThat(entryInputStream.readAllBytes()).isNotEmpty();
        }
        vfs.close();

        assertThatThrownBy(entry::open).hasMessageContaining("has been closed");
    }

    /**
     * An interruption that a step of the teardown restored on this thread rather than recorded reaches the shared
     * interruption checker, so that any other thread still reading through this {@link Vfs} stops too. Asking the
     * garbage collector to unmap files is one such step: it is a static utility with no checker to reach, so
     * restoring the status that the throw cleared is all it can do.
     */
    @Test
    void theCloseRoutesAnInterruptionOnThisThreadThroughTheSharedChecker() {
        final var interruptionChecker = new InterruptionChecker();
        final var vfs = new Vfs(new VfsSpec(), interruptionChecker);
        Thread.currentThread().interrupt();
        vfs.close(/* log = */ null);

        // Clear this thread's status, so that only the shared flag can make the check below report an interruption
        assertThat(Thread.interrupted()).isTrue();
        assertThat(interruptionChecker.checkAndReturn()).isTrue();

        // checkAndReturn() interrupts this thread again when the shared flag is set, so clear it once more
        Thread.interrupted();
    }
}
