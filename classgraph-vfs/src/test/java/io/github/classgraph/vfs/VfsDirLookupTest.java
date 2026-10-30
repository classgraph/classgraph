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
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.vfs.internal.slice.PathSlice;

/**
 * Looks files up in a directory root without walking it first. On Windows a lookup answers from the listing of the
 * file's directory, which holds the metadata of each file, so these check that the listing gives the same answers
 * as reading the metadata of each file would.
 */
public class VfsDirLookupTest {
    /**
     * A file is found, with its length, and a directory or a missing name is not.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void aFileIsFoundAndADirectoryIsNot(@TempDir final Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("pkg/sub"));
        Files.writeString(tempDir.resolve("pkg/A.txt"), "AAA");
        try (var vfs = new Vfs()) {
            final var root = vfs.open(tempDir.toString());
            final var entry = Objects.requireNonNull(root.getEntry("pkg/A.txt"));
            assertThat(entry.getLength()).isEqualTo(3L);
            assertThat(entry.loadAsString()).isEqualTo("AAA");
            assertThat(root.getEntry("pkg/sub")).isNull();
            assertThat(root.getEntry("pkg")).isNull();
            assertThat(root.getEntry("pkg/B.txt")).isNull();
            assertThat(root.getEntry("pkg/sub/A.txt")).isNull();
        }
    }

    /**
     * A file created after its directory was listed to look up another file is still found.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void aFileCreatedAfterItsDirectoryWasListedIsFound(@TempDir final Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("pkg"));
        Files.writeString(tempDir.resolve("pkg/A.txt"), "A");
        try (var vfs = new Vfs()) {
            final var root = vfs.open(tempDir.toString());
            assertThat(root.getEntry("pkg/A.txt")).isNotNull();
            assertThat(root.getEntry("pkg/B.txt")).isNull();
            Files.writeString(tempDir.resolve("pkg/B.txt"), "B");
            assertThat(Objects.requireNonNull(root.getEntry("pkg/B.txt")).loadAsString()).isEqualTo("B");
        }
    }

    /**
     * A file deleted after its directory was listed to look up another file may still be found, and then fails when
     * it is read.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void aFileDeletedAfterItsDirectoryWasListedFailsWhenRead(@TempDir final Path tempDir)
            throws IOException {
        Files.createDirectories(tempDir.resolve("pkg"));
        Files.writeString(tempDir.resolve("pkg/A.txt"), "A");
        Files.writeString(tempDir.resolve("pkg/B.txt"), "B");
        try (var vfs = new Vfs()) {
            final var root = vfs.open(tempDir.toString());
            assertThat(root.getEntry("pkg/A.txt")).isNotNull();
            Files.delete(tempDir.resolve("pkg/B.txt"));
            final var entry = root.getEntry("pkg/B.txt");
            if (entry != null) {
                assertThatThrownBy(entry::load).isInstanceOf(IOException.class);
            }
        }
    }

    /**
     * A symbolic link to a file is found as a file, with the length of its target, and a symbolic link to a
     * directory is not.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void aSymbolicLinkToAFileIsFound(@TempDir final Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("pkg/sub"));
        Files.writeString(tempDir.resolve("pkg/A.txt"), "AAA");
        try {
            Files.createSymbolicLink(tempDir.resolve("pkg/link.txt"), Path.of("A.txt"));
            Files.createSymbolicLink(tempDir.resolve("pkg/dirlink"), Path.of("sub"));
        } catch (IOException | UnsupportedOperationException e) {
            abort("Symlinks cannot be created: " + e);
        }
        try (var vfs = new Vfs()) {
            final var root = vfs.open(tempDir.toString());
            final var entry = Objects.requireNonNull(root.getEntry("pkg/link.txt"));
            assertThat(entry.getLength()).isEqualTo(3L);
            assertThat(entry.loadAsString()).isEqualTo("AAA");
            assertThat(root.getEntry("pkg/dirlink")).isNull();
        }
    }

    /**
     * A file that grows or shrinks after it was looked up is read whole, at the length it has when it is read.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void aFileThatChangesLengthAfterItWasLookedUpIsReadWhole(@TempDir final Path tempDir)
            throws IOException {
        Files.createDirectories(tempDir.resolve("pkg"));
        final var file = tempDir.resolve("pkg/A.txt");
        Files.writeString(file, "");
        try (var vfs = new Vfs()) {
            final var root = vfs.open(tempDir.toString());
            final var entry = Objects.requireNonNull(root.getEntry("pkg/A.txt"));
            assertThat(entry.load()).isEmpty();
            Files.writeString(file, "AAA");
            assertThat(entry.loadAsString()).isEqualTo("AAA");
            try (var buffer = entry.read()) {
                assertThat(Objects.requireNonNull(buffer.getByteBuffer()).remaining()).isEqualTo(3);
            }
            Files.writeString(file, "AAAAA");
            assertThat(Objects.requireNonNull(root.getEntry("pkg/A.txt")).loadAsString()).isEqualTo("AAAAA");
            Files.writeString(file, "A");
            assertThat(Objects.requireNonNull(root.getEntry("pkg/A.txt")).loadAsString()).isEqualTo("A");
        }
    }

    /**
     * A file larger than one read into an array (see {@link PathSlice#MAX_ARRAY_READ_SIZE}) is read whole, and is
     * read at the length it has when it is read after it grows or shrinks by more than one read.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void aFileOfSeveralReadsIsReadWholeAfterItChangesLength(@TempDir final Path tempDir) throws IOException {
        final var file = tempDir.resolve("A.bin");
        final var content = new byte[3 * PathSlice.MAX_ARRAY_READ_SIZE + 5];
        new Random(5).nextBytes(content);
        Files.write(file, content);
        try (var vfs = new Vfs()) {
            final var entry = Objects.requireNonNull(vfs.open(tempDir.toString()).getEntry("A.bin"));
            assertThat(entry.load()).isEqualTo(content);
            final var longer = Arrays.copyOf(content, content.length + PathSlice.MAX_ARRAY_READ_SIZE + 1);
            Files.write(file, longer);
            assertThat(entry.load()).isEqualTo(longer);
            final var shorter = Arrays.copyOf(content, PathSlice.MAX_ARRAY_READ_SIZE + 1);
            Files.write(file, shorter);
            assertThat(entry.load()).isEqualTo(shorter);
        }
    }

    /**
     * A file is read from the directory that is at its path when it is read, even when the directory was deleted
     * and made again after the file was looked up.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void aFileInADirectoryMadeAgainIsReadFromTheNewDirectory(@TempDir final Path tempDir)
            throws IOException {
        final var dir = tempDir.resolve("pkg");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("A.txt"), "old");
        try (var vfs = new Vfs()) {
            final var root = vfs.open(tempDir.toString());
            assertThat(Objects.requireNonNull(root.getEntry("pkg/A.txt")).loadAsString()).isEqualTo("old");
            Files.delete(dir.resolve("A.txt"));
            Files.delete(dir);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("A.txt"), "newer");
            assertThat(Objects.requireNonNull(root.getEntry("pkg/A.txt")).loadAsString()).isEqualTo("newer");
            Files.writeString(dir.resolve("B.txt"), "B");
            assertThat(Objects.requireNonNull(root.getEntry("pkg/B.txt")).loadAsString()).isEqualTo("B");
        }
    }

    /**
     * The directory roots of the process together hold no more than 256 directory handles open. Once they hold that
     * many, the handle opened first is closed to open a new one, in whichever root it is, and every file is still
     * read, including those of a directory whose handle was closed. Closing the roots closes their handles.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void directoryRootsTogetherHoldAtMost256DirectoryHandles(@TempDir final Path tempDir)
            throws IOException {
        final var numDirsPerRoot = 160;
        final var rootNames = new String[] { "r1", "r2" };
        for (final var rootName : rootNames) {
            for (var i = 0; i < numDirsPerRoot; i++) {
                final var dir = tempDir.resolve(rootName + "/d" + i);
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("A.txt"), rootName + i);
            }
        }
        // The JDK has no directory handles on Windows, so no root holds one there
        final var handlesAreUsed = File.separatorChar == '/';
        final var numOpenBefore = DirRoot.numOpenDirHandles();
        try (var vfs = new Vfs()) {
            final var roots = new DirRoot[rootNames.length];
            for (var r = 0; r < rootNames.length; r++) {
                roots[r] = (DirRoot) vfs.open(tempDir.resolve(rootNames[r]).toString());
                for (var i = 0; i < numDirsPerRoot; i++) {
                    assertThat(Objects.requireNonNull(roots[r].getEntry("d" + i + "/A.txt")).loadAsString())
                            .isEqualTo(rootNames[r] + i);
                }
            }
            assertThat(DirRoot.numOpenDirHandles()).isEqualTo(handlesAreUsed ? 256 : numOpenBefore);
            // The second root closed the handles that the first opened first, to open its own
            assertThat(roots[1].numDirHandles()).isEqualTo(handlesAreUsed ? numDirsPerRoot : 0);
            assertThat(roots[0].numDirHandles()).isEqualTo(handlesAreUsed ? 256 - numDirsPerRoot : 0);
            // A directory whose handle was closed is read through a new one
            assertThat(Objects.requireNonNull(roots[0].getEntry("d0/A.txt")).loadAsString()).isEqualTo("r10");
            assertThat(DirRoot.numOpenDirHandles()).isEqualTo(handlesAreUsed ? 256 : numOpenBefore);
        }
        assertThat(DirRoot.numOpenDirHandles()).isEqualTo(numOpenBefore);
    }

    /**
     * Threads that read the files of more directories than there are directory handles, at once, so that handles
     * other threads are reading through are closed under them, read every file, and leave no handle open once the
     * roots are closed.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws Exception
     *             if the directory could not be built or read.
     */
    @Test
    public void threadsReadingMoreDirectoriesThanThereAreHandlesReadEveryFile(@TempDir final Path tempDir)
            throws Exception {
        final var numDirs = 600;
        for (var i = 0; i < numDirs; i++) {
            final var dir = tempDir.resolve("d" + i);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("A.txt"), "A" + i);
        }
        final var numOpenBefore = DirRoot.numOpenDirHandles();
        final var numThreads = 8;
        try (var vfs = new Vfs()) {
            final var root = vfs.open(tempDir.toString());
            final var executor = Executors.newFixedThreadPool(numThreads);
            try {
                final var futures = new ArrayList<Future<?>>();
                for (var t = 0; t < numThreads; t++) {
                    final var start = t * 75;
                    futures.add(executor.submit(() -> {
                        for (var round = 0; round < 5; round++) {
                            for (var j = 0; j < numDirs; j++) {
                                final var i = (start + j) % numDirs;
                                assertThat(Objects.requireNonNull(root.getEntry("d" + i + "/A.txt")).loadAsString())
                                        .isEqualTo("A" + i);
                            }
                        }
                        return null;
                    }));
                }
                for (final var future : futures) {
                    future.get();
                }
            } finally {
                executor.shutdownNow();
            }
            assertThat(DirRoot.numOpenDirHandles()).isLessThanOrEqualTo(256);
        }
        assertThat(DirRoot.numOpenDirHandles()).isEqualTo(numOpenBefore);
    }
}
