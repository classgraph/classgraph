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
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.utils.VersionFinder;
import io.github.classgraph.base.internal.utils.VersionFinder.OperatingSystem;

/**
 * A thread interrupted while opening something through a virtual filesystem gets an {@link IOException} that names
 * the interruption as its cause, and the interruption is recorded where the rest of the scan can see it, rather
 * than only on the thread that noticed it. A thread interrupted while reading a jarfile through its file channel
 * gets an {@link IOException} too, but the other threads reading the same file are not affected.
 */
public class VfsInterruptTest {
    /**
     * True where a jarfile is memory-mapped rather than read through its file channel, which is on Windows only. A
     * read of a mapped jarfile does not check whether the thread has been interrupted, so it is not stopped.
     */
    private static final boolean JARFILES_ARE_MAPPED = VersionFinder.OS == OperatingSystem.Windows;

    /**
     * Clear the interrupt status, so that an assertion failing part-way through a test cannot leak an interrupted
     * thread into the tests that run after it.
     */
    @AfterEach
    public void clearInterruptStatus() {
        Thread.interrupted();
    }

    /**
     * Write a jarfile holding a single entry.
     *
     * @return the bytes of the jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static byte[] jarBytes() throws IOException {
        final var bytesOut = new ByteArrayOutputStream();
        try (var zipOut = new ZipOutputStream(bytesOut)) {
            zipOut.putNextEntry(new ZipEntry("com/xyz/widget.txt"));
            zipOut.write("widget".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        return bytesOut.toByteArray();
    }

    /**
     * Write a jarfile holding two jarfiles, so that opening the second of them has to go back through the outer
     * jarfile that opening the first one already put in the cache.
     *
     * @param outerJar
     *            the jarfile to write.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJarContainingTwoJars(final Path outerJar) throws IOException {
        final var innerJarBytes = jarBytes();
        try (OutputStream fileOut = Files.newOutputStream(outerJar); var zipOut = new ZipOutputStream(fileOut)) {
            for (final var innerJarName : new String[] { "lib/first.jar", "lib/second.jar" }) {
                zipOut.putNextEntry(new ZipEntry(innerJarName));
                zipOut.write(innerJarBytes);
                zipOut.closeEntry();
            }
        }
    }

    /**
     * Interrupting a thread that is opening a nested jarfile whose outer jarfile is already cached reports the
     * interruption as the cause of the {@link IOException}, and trips the interruption checker that the other
     * threads working on the same virtual filesystem poll.
     *
     * @param tempDir
     *            the temporary directory to write the jarfile into.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    @Test
    public void interruptedOpenNamesItsCauseAndStopsTheOtherThreads(@TempDir final Path tempDir)
            throws IOException {
        final var outerJar = tempDir.resolve("outer.jar");
        writeJarContainingTwoJars(outerJar);
        final var outerJarPath = outerJar.toAbsolutePath().toString();
        try (var vfs = new Vfs()) {
            // Opening the first nested jarfile caches the outer jarfile that holds it
            assertThat(vfs.open(outerJarPath + "!/lib/first.jar")).isNotNull();

            // Opening the second nested jarfile has to wait on the cached outer jarfile, and waiting is where an
            // already-interrupted thread finds out that it has been interrupted
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> vfs.open(outerJarPath + "!/lib/second.jar")).isInstanceOf(IOException.class)
                    .hasMessageContaining("Interrupted while opening")
                    .hasCauseInstanceOf(InterruptedException.class);

            // The thread that was interrupted still knows it was interrupted (and reading the status clears it, so
            // that the shared interruption checker can be tested on its own below)
            assertThat(Thread.interrupted()).isTrue();

            // ... and so does any thread that checks the shared interruption checker, as a scan's workers do
            assertThat(vfs.interruptionChecker().checkAndReturn()).isTrue();

            // checkAndReturn() interrupts the calling thread when the shared flag is set, so clear it again before
            // closing the Vfs
            Thread.interrupted();
        }
    }

    /**
     * Write a jarfile holding two small deflated entries and one large stored entry, which is read through the file
     * a buffer at a time rather than all at once.
     *
     * @param jar
     *            the jarfile to write.
     * @param largeContent
     *            the content of the stored entry.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJar(final Path jar, final byte[] largeContent) throws IOException {
        try (OutputStream fileOut = Files.newOutputStream(jar); var zipOut = new ZipOutputStream(fileOut)) {
            for (final var name : new String[] { "first.txt", "second.txt" }) {
                zipOut.putNextEntry(new ZipEntry(name));
                zipOut.write(name.getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
            }
            final var storedEntry = new ZipEntry("large.bin");
            storedEntry.setMethod(ZipEntry.STORED);
            storedEntry.setSize(largeContent.length);
            final var crc = new CRC32();
            crc.update(largeContent);
            storedEntry.setCrc(crc.getValue());
            zipOut.putNextEntry(storedEntry);
            zipOut.write(largeContent);
            zipOut.closeEntry();
        }
    }

    /**
     * Load an entry of a root on a new thread, so that the load cannot be affected by the interrupt status of the
     * calling thread.
     *
     * @param root
     *            the root.
     * @param name
     *            the name of the entry.
     * @return the content of the entry, as a string.
     * @throws Exception
     *             if the entry could not be loaded.
     */
    private static String loadOnAnotherThread(final VfsRoot root, final String name) throws Exception {
        final var content = new CompletableFuture<String>();
        Thread.ofPlatform().start(() -> {
            try {
                content.complete(root.getEntry(name).loadAsString());
            } catch (final Throwable t) {
                content.completeExceptionally(t);
            }
        });
        try {
            return content.get(30, TimeUnit.SECONDS);
        } catch (final ExecutionException e) {
            throw e.getCause() instanceof final Exception cause ? cause : e;
        }
    }

    /**
     * A thread that is interrupted while it reads a jarfile fails to read it, but does not stop any other thread
     * from reading the same jarfile, then or later. The jarfile is read through a file channel that every thread
     * shares, and a file channel is closed by a read on a thread that has been interrupted. (Where the jarfile is
     * memory-mapped instead, the interrupted thread reads it too.)
     *
     * @param tempDir
     *            the temporary directory to write the jarfile into.
     * @throws Exception
     *             if the jarfile could not be written or read.
     */
    @Test
    public void anInterruptedReadDoesNotStopOtherReadsOfTheJarfile(@TempDir final Path tempDir) throws Exception {
        final var jar = tempDir.resolve("test.jar");
        writeJar(jar, new byte[1]);
        try (var vfs = new Vfs()) {
            final var root = vfs.open(jar);
            assertThat(root.getEntry("first.txt").loadAsString()).isEqualTo("first.txt");

            Thread.currentThread().interrupt();
            if (JARFILES_ARE_MAPPED) {
                assertThat(root.getEntry("second.txt").loadAsString()).isEqualTo("second.txt");
            } else {
                assertThatThrownBy(() -> root.getEntry("second.txt").load()).isInstanceOf(IOException.class);
            }
            // The interruption is not swallowed
            assertThat(Thread.interrupted()).isTrue();

            assertThat(loadOnAnotherThread(root, "second.txt")).isEqualTo("second.txt");
            // The thread that was interrupted can read the jarfile again once its interrupt status is cleared
            assertThat(root.getEntry("first.txt").loadAsString()).isEqualTo("first.txt");
        }
    }

    /**
     * A thread that is part-way through reading an entry carries on reading it after another thread is interrupted
     * while reading the same jarfile.
     *
     * @param tempDir
     *            the temporary directory to write the jarfile into.
     * @throws Exception
     *             if the jarfile could not be written or read.
     */
    @Test
    public void aReadInProgressIsNotStoppedByAnotherThreadsInterruptedRead(@TempDir final Path tempDir)
            throws Exception {
        final var largeContent = new byte[100_000];
        for (var i = 0; i < largeContent.length; i++) {
            largeContent[i] = (byte) (i * 31);
        }
        final var jar = tempDir.resolve("test.jar");
        writeJar(jar, largeContent);
        try (var vfs = new Vfs()) {
            final var root = vfs.open(jar);
            try (var inputStream = root.getEntry("large.bin").open()) {
                final var contentRead = new byte[largeContent.length];
                assertThat(inputStream.readNBytes(contentRead, 0, 1000)).isEqualTo(1000);

                // Another thread is interrupted while reading the same jarfile
                final var otherThreadFailure = new CompletableFuture<Throwable>();
                final var otherThread = Thread.ofPlatform().unstarted(() -> {
                    Thread.currentThread().interrupt();
                    try {
                        root.getEntry("second.txt").load();
                        otherThreadFailure.complete(null);
                    } catch (final Throwable t) {
                        otherThreadFailure.complete(t);
                    }
                });
                otherThread.start();
                if (JARFILES_ARE_MAPPED) {
                    assertThat(otherThreadFailure.get(30, TimeUnit.SECONDS)).isNull();
                } else {
                    assertThat(otherThreadFailure.get(30, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
                }

                // This thread was not interrupted, so it reads the rest of the entry
                assertThat(inputStream.readNBytes(contentRead, 1000, largeContent.length - 1000))
                        .isEqualTo(largeContent.length - 1000);
                assertThat(contentRead).isEqualTo(largeContent);
            }
        }
    }
}
