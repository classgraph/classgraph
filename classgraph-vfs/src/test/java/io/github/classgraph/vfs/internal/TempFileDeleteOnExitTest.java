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
package io.github.classgraph.vfs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsSpec;
import io.github.classgraph.vfs.internal.slice.Slice;

/**
 * Tests that a temporary file that could not be deleted is deleted when the JVM exits. Each test runs a child JVM
 * whose temporary directory is made unwritable while the file is deleted, so that the delete fails, and is made
 * writable again before the child JVM exits.
 */
class TempFileDeleteOnExitTest {
    /** The largest number of bytes of a stream that the child JVM buffers in RAM before spilling it to disk. */
    private static final int MAX_BUFFERED_SIZE = 16;

    /**
     * Run in a child JVM: spill a stream to a temporary file, then try to delete the file while the temporary
     * directory is unwritable, either by closing the slice over the file ({@code close}), or by failing the read of
     * the stream after the file has been created ({@code spill}). Print whether the file is still there, then make
     * the temporary directory writable again.
     */
    static final class DeleteInUnwritableTempDir {
        /** Not instantiable. */
        private DeleteInUnwritableTempDir() {
        }

        /**
         * Make the temporary directory writable or unwritable.
         *
         * @param writable
         *            whether the directory is to be writable.
         * @throws IOException
         *             if the permissions could not be changed.
         */
        private static void setTempDirWritable(final boolean writable) throws IOException {
            final var permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE);
            if (writable) {
                permissions.add(PosixFilePermission.OWNER_WRITE);
            }
            Files.setPosixFilePermissions(Path.of(System.getProperty("java.io.tmpdir")), permissions);
        }

        /**
         * Print whether the temporary directory still holds a file.
         *
         * @throws IOException
         *             if the directory could not be listed.
         */
        private static void printWhetherTempFileExists() throws IOException {
            try (var tempFiles = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
                System.out.println("tempFileExists=" + tempFiles.findAny().isPresent());
            }
        }

        /**
         * Entry point.
         *
         * @param args
         *            {@code close} or {@code spill}.
         * @throws IOException
         *             if the temporary file could not be created.
         */
        public static void main(final String[] args) throws IOException {
            try (var vfs = new Vfs(new VfsSpec().setMaxBufferedJarRAMSize(MAX_BUFFERED_SIZE),
                    new InterruptionChecker())) {
                if (args[0].equals("close")) {
                    final var slice = Slice.fromInputStream(
                            new ByteArrayInputStream(new byte[4 * MAX_BUFFERED_SIZE]), "lib.jar",
                            /* inputStreamLengthHint = */ -1L, vfs, /* log = */ null);
                    setTempDirWritable(false);
                    try {
                        slice.close();
                        printWhetherTempFileExists();
                    } finally {
                        setTempDirWritable(true);
                    }
                } else {
                    // A stream that fails once it has been spilled to disk, after making the temporary directory
                    // unwritable
                    final var failingStream = new InputStream() {
                        private int bytesRead;

                        @Override
                        public int read() throws IOException {
                            if (bytesRead < 4 * MAX_BUFFERED_SIZE) {
                                bytesRead++;
                                return 1;
                            }
                            setTempDirWritable(false);
                            throw new IOException("Simulated read failure");
                        }
                    };
                    try {
                        Slice.fromInputStream(failingStream, "lib.jar", /* inputStreamLengthHint = */ -1L, vfs,
                                /* log = */ null);
                        throw new IllegalStateException("The read did not fail");
                    } catch (final IOException e) {
                        if (!e.getMessage().equals("Simulated read failure")) {
                            throw e;
                        }
                        printWhetherTempFileExists();
                    } finally {
                        setTempDirWritable(true);
                    }
                }
            }
        }
    }

    /**
     * Run {@link DeleteInUnwritableTempDir} in a child JVM, and check that its temporary directory is empty after
     * it exits.
     *
     * @param tempDir
     *            the temporary directory of the child JVM.
     * @param mode
     *            the argument to pass to {@link DeleteInUnwritableTempDir}.
     * @throws Exception
     *             if the child JVM could not be run.
     */
    private static void checkTempFileIsDeletedOnExit(final Path tempDir, final String mode) throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "The default filesystem does not support POSIX file permissions");
        final var output = TempFilenameSanitizationTest
                .runChildJvm(new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Djava.io.tmpdir=" + tempDir, "-cp", System.getProperty("java.class.path"),
                        DeleteInUnwritableTempDir.class.getName(), mode).redirectErrorStream(true));
        assertThat(output).as("Child JVM output:%n%s", output).containsPattern("tempFileExists=(true|false)");
        // A user who can write to any directory, such as root, can delete the file whatever its permissions are
        assumeTrue(output.contains("tempFileExists=true"), "Deleting the temporary file did not fail");
        try (var tempFiles = Files.list(tempDir)) {
            assertThat(tempFiles).as("Child JVM output:%n%s", output).isEmpty();
        }
    }

    /**
     * A temporary file that could not be deleted when the slice over it was closed is deleted when the JVM exits.
     *
     * @param tempDir
     *            the temporary directory of the child JVM.
     * @throws Exception
     *             if the child JVM could not be run.
     */
    @Test
    void aTempFileThatCouldNotBeDeletedOnCloseIsDeletedOnExit(@TempDir final Path tempDir) throws Exception {
        checkTempFileIsDeletedOnExit(tempDir, "close");
    }

    /**
     * A temporary file that could not be deleted after writing to it failed is deleted when the JVM exits.
     *
     * @param tempDir
     *            the temporary directory of the child JVM.
     * @throws Exception
     *             if the child JVM could not be run.
     */
    @Test
    void aTempFileThatCouldNotBeDeletedAfterAFailedSpillIsDeletedOnExit(@TempDir final Path tempDir)
            throws Exception {
        checkTempFileIsDeletedOnExit(tempDir, "spill");
    }
}
