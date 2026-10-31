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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
     * Reading a file by name gives the same result as looking up its entry and reading that, both for the first
     * name read in a directory and for the names read after it, which are opened without being looked up first.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void readingByNameMatchesLookingUpAndReading(@TempDir final Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("pkg/sub"));
        Files.writeString(tempDir.resolve("pkg/A.txt"), "AAA");
        Files.writeString(tempDir.resolve("pkg/B.txt"), "");
        Files.writeString(tempDir.resolve("C.txt"), "C");
        final var names = new String[] { "pkg/A.txt", "pkg/B.txt", "pkg/sub", "pkg", "pkg/D.txt", "pkg/sub/A.txt",
                "nodir/A.txt", "pkg/A.txt/x", "C.txt", "", "/C.txt", "pkg//A.txt", "pkg/./A.txt", "pkg/../C.txt",
                "pkg/a.txt" };
        try (var vfs = new Vfs(); var vfs2 = new Vfs()) {
            final var root = vfs.open(tempDir.toString());
            final var lookupRoot = vfs2.open(tempDir.toString());
            // Twice, since the first read in a directory looks the file up, and later reads open the file directly
            for (var pass = 0; pass < 2; pass++) {
                for (final var name : names) {
                    final var entry = lookupRoot.getEntry(name);
                    assertThat(root.load(name)).as(name).isEqualTo(entry == null ? null : entry.load());
                }
            }
            assertThat(root.load("pkg/A.txt")).isEqualTo("AAA".getBytes(StandardCharsets.UTF_8));
            assertThat(root.load("pkg/sub")).isNull();
        }
    }

    /**
     * A file is found by its root-relative path, and not by a name that reaches it by some other route: one with an
     * empty, {@code "."} or {@code ".."} segment, or one written with the platform separator on Windows.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void aFileIsFoundOnlyByItsRootRelativePath(@TempDir final Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("pkg/sub"));
        Files.writeString(tempDir.resolve("pkg/sub/A.txt"), "A");
        Files.writeString(tempDir.resolve("C.txt"), "C");
        try (var vfs = new Vfs()) {
            final var root = vfs.open(tempDir.toString());
            for (final var name : new String[] { "pkg/sub/A.txt", "C.txt" }) {
                assertThat(root.getEntry(name)).as(name).isNotNull();
                assertThat(root.load(name)).as(name).isNotNull();
            }
            for (final var name : new String[] { "pkg//sub/A.txt", "pkg/./sub/A.txt", "pkg/sub/../sub/A.txt",
                    "./C.txt", "pkg/../C.txt", "pkg\\sub\\A.txt", "pkg/sub\\A.txt", "/C.txt" }) {
                assertThat(root.getEntry(name)).as(name).isNull();
                assertThat(root.load(name)).as(name).isNull();
            }
        }
    }

    /**
     * A file read by name is read whole at the length it has when it is read, including a file larger than one read
     * into an array (see {@link PathSlice#MAX_ARRAY_READ_SIZE}).
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void aFileReadByNameIsReadWholeAfterItChangesLength(@TempDir final Path tempDir) throws IOException {
        final var file = tempDir.resolve("A.bin");
        final var content = new byte[3 * PathSlice.MAX_ARRAY_READ_SIZE + 5];
        new Random(5).nextBytes(content);
        Files.write(file, content);
        try (var vfs = new Vfs()) {
            final var root = vfs.open(tempDir.toString());
            assertThat(root.load("A.bin")).isEqualTo(content);
            final var longer = Arrays.copyOf(content, content.length + PathSlice.MAX_ARRAY_READ_SIZE + 1);
            Files.write(file, longer);
            assertThat(root.load("A.bin")).isEqualTo(longer);
            final var shorter = Arrays.copyOf(content, 7);
            Files.write(file, shorter);
            assertThat(root.load("A.bin")).isEqualTo(shorter);
            Files.delete(file);
            assertThat(root.load("A.bin")).isNull();
        }
    }

    /**
     * A file read by name is read from the directory that is at its path when it is read, even when the directory
     * was deleted and made again after a file in it was read, and a file missing from the new directory is not
     * found.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void aFileReadByNameInADirectoryMadeAgainIsReadFromTheNewDirectory(@TempDir final Path tempDir)
            throws IOException {
        final var dir = tempDir.resolve("pkg");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("A.txt"), "old");
        Files.writeString(dir.resolve("B.txt"), "B");
        try (var vfs = new Vfs()) {
            final var root = vfs.open(tempDir.toString());
            assertThat(root.load("pkg/A.txt")).isEqualTo("old".getBytes(StandardCharsets.UTF_8));
            assertThat(root.load("pkg/B.txt")).isEqualTo("B".getBytes(StandardCharsets.UTF_8));
            Files.delete(dir.resolve("A.txt"));
            Files.delete(dir.resolve("B.txt"));
            Files.delete(dir);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("A.txt"), "newer");
            assertThat(root.load("pkg/A.txt")).isEqualTo("newer".getBytes(StandardCharsets.UTF_8));
            assertThat(root.load("pkg/B.txt")).isNull();
            Files.writeString(dir.resolve("C.txt"), "C");
            assertThat(root.load("pkg/C.txt")).isEqualTo("C".getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Reading a file by name from a closed root, or from a root of a closed {@link Vfs}, throws.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void readingByNameFromAClosedRootThrows(@TempDir final Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("A.txt"), "A");
        final VfsRoot root;
        try (var vfs = new Vfs()) {
            root = vfs.open(tempDir.toString());
            assertThat(root.load("A.txt")).isEqualTo("A".getBytes(StandardCharsets.UTF_8));
        }
        assertThatThrownBy(() -> root.load("A.txt")).isInstanceOf(IOException.class);
    }

    /**
     * Reading an entry of a jarfile by name gives its content, or null if there is no such file entry.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the jarfile could not be built or read.
     */
    @Test
    public void anEntryOfAJarfileIsReadByName(@TempDir final Path tempDir) throws IOException {
        final var jar = tempDir.resolve("a.jar");
        try (var out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("pkg/"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("pkg/A.txt"));
            out.write("AAA".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        try (var vfs = new Vfs()) {
            final var root = vfs.open(jar.toString());
            assertThat(root.load("pkg/A.txt")).isEqualTo("AAA".getBytes(StandardCharsets.UTF_8));
            assertThat(root.load("pkg")).isNull();
            assertThat(root.load("pkg/B.txt")).isNull();
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
     * A file is read from the directory that is at the root's path when it is read, even when the root's directory
     * was deleted and made again after the root read from it, so that the handle the root holds on it is a handle
     * on the deleted directory.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void aFileInARootDirectoryMadeAgainIsReadFromTheNewDirectory(@TempDir final Path tempDir)
            throws IOException {
        final var dir = tempDir.resolve("root");
        Files.createDirectories(dir.resolve("pkg"));
        Files.writeString(dir.resolve("pkg/A.txt"), "old");
        try (var vfs = new Vfs()) {
            final var root = vfs.open(dir.toString());
            assertThat(root.load("pkg/A.txt")).isEqualTo("old".getBytes(StandardCharsets.UTF_8));
            assertThat(Objects.requireNonNull(root.getEntry("pkg/A.txt")).loadAsString()).isEqualTo("old");
            // The first read after the directory is made again is a load
            makeAgain(dir, "A.txt", "newer");
            assertThat(root.load("pkg/A.txt")).isEqualTo("newer".getBytes(StandardCharsets.UTF_8));
            assertThat(Objects.requireNonNull(root.getEntry("pkg/A.txt")).loadAsString()).isEqualTo("newer");
            // The first read after the directory is made again is a lookup of an entry
            makeAgain(dir, "B.txt", "B");
            assertThat(Objects.requireNonNull(root.getEntry("pkg/B.txt")).loadAsString()).isEqualTo("B");
            assertThat(root.load("pkg/B.txt")).isEqualTo("B".getBytes(StandardCharsets.UTF_8));
            assertThat(root.load("pkg/A.txt")).isNull();
        }
    }

    /**
     * Delete a directory holding one file, in {@code pkg}, and make it again holding one other file.
     *
     * @param dir
     *            the directory.
     * @param fileName
     *            the name of the file to write in {@code pkg} of the new directory.
     * @param content
     *            the content of the file.
     * @throws IOException
     *             if the directory could not be deleted or made.
     */
    private static void makeAgain(final Path dir, final String fileName, final String content) throws IOException {
        try (var files = Files.list(dir.resolve("pkg"))) {
            for (final var file : files.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(dir.resolve("pkg"));
        Files.delete(dir);
        Files.createDirectories(dir.resolve("pkg"));
        Files.writeString(dir.resolve("pkg").resolve(fileName), content);
    }

    /**
     * The directory roots of the process together hold no more than 256 directory handles open, one each. Once they
     * hold that many, the handle opened first is closed to open a new one, in whichever root it is, and every file
     * is still read, including those of a root whose handle was closed. Closing the roots closes their handles.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void directoryRootsTogetherHoldAtMost256DirectoryHandles(@TempDir final Path tempDir)
            throws IOException {
        final var numRoots = 300;
        for (var r = 0; r < numRoots; r++) {
            final var dir = tempDir.resolve("r" + r + "/pkg");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("A.txt"), "A" + r);
        }
        // A root holds a directory handle only where the JDK opens a directory as a SecureDirectoryStream, which it
        // does not on Windows, nor on macOS below JDK 24
        final boolean handlesAreUsed;
        try (var stream = Files.newDirectoryStream(tempDir)) {
            handlesAreUsed = stream instanceof SecureDirectoryStream;
        }
        final var numOpenBefore = DirRoot.numOpenDirHandles();
        try (var vfs = new Vfs()) {
            final var roots = new DirRoot[numRoots];
            for (var r = 0; r < numRoots; r++) {
                roots[r] = (DirRoot) vfs.open(tempDir.resolve("r" + r).toString());
                assertThat(Objects.requireNonNull(roots[r].getEntry("pkg/A.txt")).loadAsString())
                        .isEqualTo("A" + r);
            }
            assertThat(DirRoot.numOpenDirHandles()).isEqualTo(handlesAreUsed ? 256 : numOpenBefore);
            // The later roots closed the handles of the roots that opened theirs first, to open their own
            for (var r = 0; r < numRoots; r++) {
                assertThat(roots[r].hasDirHandle()).isEqualTo(handlesAreUsed && r >= numRoots - 256);
            }
            // A root whose handle was closed is read through a new one
            assertThat(Objects.requireNonNull(roots[0].getEntry("pkg/A.txt")).loadAsString()).isEqualTo("A0");
            assertThat(roots[0].load("pkg/A.txt")).isEqualTo("A0".getBytes(StandardCharsets.UTF_8));
            assertThat(roots[0].hasDirHandle()).isEqualTo(handlesAreUsed);
            assertThat(DirRoot.numOpenDirHandles()).isEqualTo(handlesAreUsed ? 256 : numOpenBefore);
        }
        assertThat(DirRoot.numOpenDirHandles()).isEqualTo(numOpenBefore);
    }

    /**
     * Threads that read the files of more roots than there are directory handles, at once, so that handles other
     * threads are reading through are closed under them, read every file, and leave no handle open once the roots
     * are closed.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws Exception
     *             if the directory could not be built or read.
     */
    @Test
    public void threadsReadingMoreRootsThanThereAreHandlesReadEveryFile(@TempDir final Path tempDir)
            throws Exception {
        final var numRoots = 600;
        for (var i = 0; i < numRoots; i++) {
            final var dir = tempDir.resolve("d" + i + "/pkg");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("A.txt"), "A" + i);
        }
        final var numOpenBefore = DirRoot.numOpenDirHandles();
        final var numThreads = 8;
        try (var vfs = new Vfs()) {
            final var roots = new VfsRoot[numRoots];
            for (var i = 0; i < numRoots; i++) {
                roots[i] = vfs.open(tempDir.resolve("d" + i).toString());
            }
            final var executor = Executors.newFixedThreadPool(numThreads);
            try {
                final var futures = new ArrayList<Future<?>>();
                for (var t = 0; t < numThreads; t++) {
                    final var start = t * 75;
                    final var useLoad = t % 2 == 0;
                    futures.add(executor.submit(() -> {
                        for (var round = 0; round < 5; round++) {
                            for (var j = 0; j < numRoots; j++) {
                                final var i = (start + j) % numRoots;
                                if (useLoad) {
                                    assertThat(roots[i].load("pkg/A.txt"))
                                            .isEqualTo(("A" + i).getBytes(StandardCharsets.UTF_8));
                                } else {
                                    assertThat(
                                            Objects.requireNonNull(roots[i].getEntry("pkg/A.txt")).loadAsString())
                                            .isEqualTo("A" + i);
                                }
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
