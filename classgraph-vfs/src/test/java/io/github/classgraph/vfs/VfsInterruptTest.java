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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A thread interrupted while opening something through a virtual filesystem gets an {@link IOException} that names
 * the interruption as its cause, and the interruption is recorded where the rest of the scan can see it, rather
 * than only on the thread that noticed it.
 */
public class VfsInterruptTest {
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

            // ... and so does every other thread reading through this Vfs
            assertThat(vfs.interruptionChecker().checkAndReturn()).isTrue();

            // checkAndReturn() interrupts the calling thread when the shared flag is set, so clear it again before
            // closing the Vfs
            Thread.interrupted();
        }
    }
}
