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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Looks entries up by names whose case does not match the case of the names they are stored under.
 *
 * <p>
 * Every assertion in here holds on every operating system, whether or not its filesystem is case-insensitive: a
 * lookup by exact name is not answered by a differently-cased name anywhere, and a lookup that ignores case is
 * answered by one everywhere.
 */
public class VfsEntryLookupCaseTest {
    /** The name a file is stored under, in the case it is stored in. */
    private static final String STORED_NAME = "Com/Xyz/Widget.txt";

    /** The same name, spelled in a different case. */
    private static final String DIFFERENTLY_CASED_NAME = "com/xyz/widget.txt";

    /** The content of that file. */
    private static final String CONTENT = "widget";

    /**
     * A lookup by exact name is not answered by a file whose name differs only in case, on any operating system.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void aDirectoryEntryIsNotFoundUnderADifferentlyCasedName(@TempDir final Path tempDir)
            throws IOException {
        final var dir = makeDir(tempDir, STORED_NAME);
        try (var vfs = new Vfs()) {
            final var root = vfs.open(dir.toString());
            assertThat(root.getEntry(DIFFERENTLY_CASED_NAME)).isNull();
            assertThat(Objects.requireNonNull(root.getEntry(STORED_NAME)).getName()).isEqualTo(STORED_NAME);
        }
    }

    /**
     * The same holds for a jarfile, which carries its own namespace and so answers the same question the same way
     * wherever it is read.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the jarfile could not be built or read.
     */
    @Test
    public void aJarfileEntryIsNotFoundUnderADifferentlyCasedName(@TempDir final Path tempDir) throws IOException {
        final var jar = makeJar(tempDir, STORED_NAME);
        try (var vfs = new Vfs()) {
            final var root = vfs.open(jar.toString());
            assertThat(root.getEntry(DIFFERENTLY_CASED_NAME)).isNull();
            assertThat(Objects.requireNonNull(root.getEntry(STORED_NAME)).getName()).isEqualTo(STORED_NAME);
        }
    }

    /**
     * A lookup that ignores case finds the file, and the entry it returns is named the way the file is stored,
     * rather than the way it was asked for.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the classpath elements could not be built or read.
     */
    @Test
    public void aDifferentlyCasedNameIsFoundWhenCaseIsIgnored(@TempDir final Path tempDir) throws IOException {
        final var dir = makeDir(tempDir.resolve("dir"), STORED_NAME);
        final var jar = makeJar(tempDir, STORED_NAME);
        try (var vfs = new Vfs()) {
            for (final var rootPath : new String[] { dir.toString(), jar.toString() }) {
                final var root = vfs.open(rootPath);
                final var entry = Objects.requireNonNull(root.getEntryCaseInsensitive(DIFFERENTLY_CASED_NAME),
                        rootPath);
                assertThat(entry.getName()).as(rootPath).isEqualTo(STORED_NAME);
                assertThat(new String(entry.load(), StandardCharsets.UTF_8)).as(rootPath).isEqualTo(CONTENT);
                // The exact name still matches when case is ignored
                assertThat(Objects.requireNonNull(root.getEntryCaseInsensitive(STORED_NAME), rootPath).getName())
                        .as(rootPath).isEqualTo(STORED_NAME);
                assertThat(root.getEntryCaseInsensitive("com/xyz/nonexistent.txt")).as(rootPath).isNull();
            }
        }
    }

    /**
     * A zipfile can store two entries whose names differ only in case, and a lookup that ignores case reports both
     * of them, in the order they appear in the zipfile, while a lookup by exact name reports one entry each.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the jarfile could not be built or read.
     */
    @Test
    public void everyEntryThatMatchesWhenCaseIsIgnoredIsReported(@TempDir final Path tempDir) throws IOException {
        final var jar = makeJar(tempDir, STORED_NAME, DIFFERENTLY_CASED_NAME);
        try (var vfs = new Vfs()) {
            final var root = vfs.open(jar.toString());
            assertThat(root.getEntriesCaseInsensitive(DIFFERENTLY_CASED_NAME)).extracting(VfsEntry::getName)
                    .containsExactly(STORED_NAME, DIFFERENTLY_CASED_NAME);
            // The first entry in the zipfile is the one a single lookup finds, whichever case it was asked for in
            assertThat(Objects.requireNonNull(root.getEntryCaseInsensitive(DIFFERENTLY_CASED_NAME)).getName())
                    .isEqualTo(STORED_NAME);
            assertThat(Objects.requireNonNull(root.getEntry(STORED_NAME)).getName()).isEqualTo(STORED_NAME);
            assertThat(Objects.requireNonNull(root.getEntry(DIFFERENTLY_CASED_NAME)).getName())
                    .isEqualTo(DIFFERENTLY_CASED_NAME);
        }
    }

    /**
     * A jarfile that stores its manifest under a lower-cased name still has a manifest, the same way
     * {@link java.util.jar.JarFile#getManifest()} still finds one.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the jarfile could not be built or read.
     */
    @Test
    public void aManifestStoredUnderALowerCasedNameIsStillTheManifest(@TempDir final Path tempDir)
            throws IOException {
        final var jar = tempDir.resolve("widget.jar");
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(jar))) {
            zipOut.putNextEntry(new ZipEntry("meta-inf/manifest.mf"));
            zipOut.write(
                    "Manifest-Version: 1.0\r\nClass-Path: lib/dep.jar\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        try (var vfs = new Vfs()) {
            assertThat(vfs.open(jar.toString()).getManifestEntry("Class-Path")).isEqualTo("lib/dep.jar");
        }
    }

    /**
     * A jarfile that stores a manifest under both names is described by the one under the name a manifest is
     * supposed to be stored under.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the jarfile could not be built or read.
     */
    @Test
    public void theManifestUnderTheCanonicalNameIsThePreferredOne(@TempDir final Path tempDir) throws IOException {
        final var jar = tempDir.resolve("widget.jar");
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(jar))) {
            // The lower-cased name is written first, so that entry order cannot be what decides this
            zipOut.putNextEntry(new ZipEntry("meta-inf/manifest.mf"));
            zipOut.write("Manifest-Version: 1.0\r\nClass-Path: lower.jar\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
            zipOut.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zipOut.write("Manifest-Version: 1.0\r\nClass-Path: upper.jar\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        try (var vfs = new Vfs()) {
            assertThat(vfs.open(jar.toString()).getManifestEntry("Class-Path")).isEqualTo("upper.jar");
        }
    }

    /**
     * A file reached through a symbolic link is found by the name it was reached through: the name of the file the
     * link points at is nothing like the name of the link, rather than the same name in a different case.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the directory could not be built or read.
     */
    @Test
    public void aFileReachedThroughASymbolicLinkIsFoundByTheNameItWasReachedThrough(@TempDir final Path tempDir)
            throws IOException {
        final var dir = makeDir(tempDir.resolve("dir"), STORED_NAME);
        try {
            // A link to a directory, and a link to a file
            Files.createSymbolicLink(dir.resolve("Linked"), dir.resolve("Com"));
            Files.createSymbolicLink(dir.resolve("Alias.txt"), dir.resolve(STORED_NAME));
        } catch (final IOException | UnsupportedOperationException e) {
            assumeTrue(false, "Symbolic links are not supported here: " + e);
        }
        try (var vfs = new Vfs()) {
            final var root = vfs.open(dir.toString());
            final var throughDirLink = Objects.requireNonNull(root.getEntry("Linked/Xyz/Widget.txt"));
            assertThat(new String(throughDirLink.load(), StandardCharsets.UTF_8)).isEqualTo(CONTENT);
            final var throughFileLink = Objects.requireNonNull(root.getEntry("Alias.txt"));
            assertThat(throughFileLink.getName()).isEqualTo("Alias.txt");
            assertThat(new String(throughFileLink.load(), StandardCharsets.UTF_8)).isEqualTo(CONTENT);
        }
    }

    /**
     * Create a directory holding one file at each of the given names.
     *
     * @param dir
     *            the directory to create.
     * @param names
     *            the names to store the file under.
     * @return the directory.
     * @throws IOException
     *             if the directory could not be created.
     */
    private static Path makeDir(final Path dir, final String... names) throws IOException {
        for (final var name : names) {
            final var file = dir.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, CONTENT);
        }
        return dir;
    }

    /**
     * Create a jarfile holding one entry at each of the given names.
     *
     * @param tempDir
     *            the directory to create it in.
     * @param names
     *            the names to store the entry under.
     * @return the jarfile.
     * @throws IOException
     *             if the jarfile could not be created.
     */
    private static Path makeJar(final Path tempDir, final String... names) throws IOException {
        final var jar = tempDir.resolve("widget.jar");
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (final var name : names) {
                zipOut.putNextEntry(new ZipEntry(name));
                zipOut.write(CONTENT.getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
            }
        }
        return jar;
    }
}
