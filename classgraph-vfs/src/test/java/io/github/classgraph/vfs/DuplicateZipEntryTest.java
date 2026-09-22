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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reads jarfiles that hold two entries with the same name. {@link ZipOutputStream} will not write such a jarfile,
 * but other tools will, and {@link JarFile}, and so every classloader that reads a jarfile, finds the last of the
 * two in the central directory. A root has to find the same one.
 */
public class DuplicateZipEntryTest {
    /**
     * Write a jarfile, then give each entry whose name is a key of {@code renames} the name it maps to. The two
     * names of each pair must be the same length, and must not occur anywhere else in the jarfile.
     *
     * @param jar
     *            the jarfile to write.
     * @param renames
     *            pairs of names: the name an entry is written under, then the name it is given afterward.
     * @param namesAndContents
     *            pairs of strings: the name of an entry, then its content.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJar(final Path jar, final String[] renames, final String... namesAndContents)
            throws IOException {
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var i = 0; i < namesAndContents.length; i += 2) {
                zipOut.putNextEntry(new ZipEntry(namesAndContents[i]));
                zipOut.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
            }
        }
        // Each name occurs twice, in the local file header and in the central directory, and nothing that is
        // checksummed covers either of them, so renaming them in place makes a valid jarfile
        var content = new String(Files.readAllBytes(jar), StandardCharsets.ISO_8859_1);
        for (var i = 0; i < renames.length; i += 2) {
            content = content.replace(renames[i], renames[i + 1]);
        }
        Files.write(jar, content.getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * Of two entries with the same name, the last one in the central directory is the one a root finds, and the
     * only one it lists, as it is the one a classloader reads.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the jarfile could not be built or read.
     */
    @Test
    public void theLastOfTwoEntriesWithTheSameNameIsTheOneFound(@TempDir final Path tempDir) throws IOException {
        final var jar = tempDir.resolve("dup.jar");
        writeJar(jar, new String[] { "dup-2.txt", "dup-1.txt" }, "dup-1.txt", "first", "dup-2.txt", "second");
        try (var jarFile = new JarFile(jar.toFile())) {
            assertThat(new String(jarFile.getInputStream(jarFile.getEntry("dup-1.txt")).readAllBytes(),
                    StandardCharsets.UTF_8)).isEqualTo("second");
        }
        try (var vfs = new Vfs()) {
            final var root = vfs.open(jar.toString());
            assertThat(
                    new String(Objects.requireNonNull(root.getEntry("dup-1.txt")).load(), StandardCharsets.UTF_8))
                    .isEqualTo("second");
            assertThat(root.getEntries()).singleElement().satisfies(
                    entry -> assertThat(new String(entry.load(), StandardCharsets.UTF_8)).isEqualTo("second"));
        }
    }

    /**
     * Of two versioned entries of a multi-release jarfile with the same name, the last one in the central directory
     * is the one a root finds, as it is the one a classloader reads.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the jarfile could not be built or read.
     */
    @Test
    public void theLastOfTwoVersionedEntriesWithTheSameNameIsTheOneFound(@TempDir final Path tempDir)
            throws IOException {
        final var jar = tempDir.resolve("dup.jar");
        writeJar(jar, new String[] { "dup-2.txt", "dup-1.txt" }, //
                JarFile.MANIFEST_NAME, "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n", //
                "dup-1.txt", "base", //
                "META-INF/versions/9/dup-1.txt", "first", //
                "META-INF/versions/9/dup-2.txt", "second");
        try (var vfs = new Vfs()) {
            final var root = vfs.open(jar.toString());
            assertThat(
                    new String(Objects.requireNonNull(root.getEntry("dup-1.txt")).load(), StandardCharsets.UTF_8))
                    .isEqualTo("second");
        }
    }

    /**
     * Of two manifests stored under the same name, the last one in the central directory is the manifest, as it is
     * the one {@link JarFile#getManifest()} reads.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the jarfile could not be built or read.
     */
    @Test
    public void theLastOfTwoManifestsWithTheSameNameIsTheManifest(@TempDir final Path tempDir) throws IOException {
        final var jar = tempDir.resolve("dup.jar");
        writeJar(jar, new String[] { "META-INF/MANIFEST.MX", JarFile.MANIFEST_NAME }, //
                JarFile.MANIFEST_NAME, "Manifest-Version: 1.0\r\nClass-Path: first.jar\r\n\r\n", //
                "META-INF/MANIFEST.MX", "Manifest-Version: 1.0\r\nClass-Path: second.jar\r\n\r\n");
        try (var jarFile = new JarFile(jar.toFile())) {
            assertThat(jarFile.getManifest().getMainAttributes().getValue("Class-Path")).isEqualTo("second.jar");
        }
        try (var vfs = new Vfs()) {
            assertThat(vfs.open(jar.toString()).getManifestEntry("Class-Path")).isEqualTo("second.jar");
        }
    }
}
