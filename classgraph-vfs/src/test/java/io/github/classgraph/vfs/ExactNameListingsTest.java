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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks names against the listings of their directories. These tests hold on every filesystem, since
 * {@link ExactNameListings} answers from the listings alone.
 */
public class ExactNameListingsTest {
    /**
     * A name is case-folded when any of its segments, a directory or the file, is not listed in that case.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built.
     */
    @Test
    public void aNameIsCaseFoldedWhenAnySegmentIsNotListedInThatCase(@TempDir final Path tempDir)
            throws IOException {
        Files.createDirectories(tempDir.resolve("Com/Xyz"));
        Files.writeString(tempDir.resolve("Com/Xyz/Widget.txt"), "widget");
        final var listings = new ExactNameListings(tempDir);
        assertThat(listings.isCaseFolded("Com/Xyz/Widget.txt")).isFalse();
        assertThat(listings.isCaseFolded("Com/Xyz/widget.txt")).isTrue();
        assertThat(listings.isCaseFolded("Com/xyz/Widget.txt")).isTrue();
        assertThat(listings.isCaseFolded("com/Xyz/Widget.txt")).isTrue();
        assertThat(listings.isCaseFolded("Com/Xyz")).isFalse();
    }

    /**
     * A file created after its directory was listed is listed when it is looked up.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built.
     */
    @Test
    public void aFileCreatedAfterItsDirectoryWasListedIsFound(@TempDir final Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("pkg"));
        Files.writeString(tempDir.resolve("pkg/A.txt"), "a");
        final var listings = new ExactNameListings(tempDir);
        assertThat(listings.isCaseFolded("pkg/A.txt")).isFalse();
        Files.writeString(tempDir.resolve("pkg/B.txt"), "b");
        Files.createDirectories(tempDir.resolve("other"));
        Files.writeString(tempDir.resolve("other/C.txt"), "c");
        assertThat(listings.isCaseFolded("pkg/B.txt")).isFalse();
        assertThat(listings.isCaseFolded("other/C.txt")).isFalse();
    }

    /**
     * A directory root finds a file created after an earlier lookup in the same directory.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void aDirectoryRootFindsAFileCreatedAfterAnEarlierLookup(@TempDir final Path tempDir)
            throws IOException {
        Files.createDirectories(tempDir.resolve("pkg"));
        Files.writeString(tempDir.resolve("pkg/A.txt"), "a");
        try (var vfs = new Vfs()) {
            final var root = vfs.open(tempDir.toString());
            assertThat(root.getEntry("pkg/A.txt")).isNotNull();
            Files.writeString(tempDir.resolve("pkg/B.txt"), "b");
            assertThat(Objects.requireNonNull(root.getEntry("pkg/B.txt")).getPathFromRoot()).isEqualTo("pkg/B.txt");
            assertThat(root.getEntry("pkg/b.txt")).isNull();
        }
    }
}
