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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
