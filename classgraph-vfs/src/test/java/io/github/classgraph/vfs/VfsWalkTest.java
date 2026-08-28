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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests {@link VfsRoot#walk(VfsVisitor)}. */
public class VfsWalkTest {
    /** Records what a walk offered, so that a test can assert on the order and on what was skipped. */
    private static final class Recorder implements VfsVisitor {
        /** The name of every directory the walk offered, in the order it offered them. */
        final List<String> dirNames = new ArrayList<>();

        /** The name of every entry the walk visited, in the order it visited them. */
        final List<String> entryNames = new ArrayList<>();

        /** Decides which directories to enter. */
        private final Predicate<String> enterDir;

        /** Decides which entries to go on walking after. */
        private final Predicate<String> continueAfter;

        /**
         * Constructor.
         *
         * @param enterDir
         *            returns true for a directory whose entries are wanted.
         * @param continueAfter
         *            returns true for an entry the walk should go on past.
         */
        Recorder(final Predicate<String> enterDir, final Predicate<String> continueAfter) {
            this.enterDir = enterDir;
            this.continueAfter = continueAfter;
        }

        /** Constructor for a walk that skips nothing and stops at nothing. */
        Recorder() {
            this(dirName -> true, entryName -> true);
        }

        @Override
        public boolean enterDirectory(final String dirName) {
            dirNames.add(dirName);
            return enterDir.test(dirName);
        }

        @Override
        public boolean visitEntry(final VfsEntry entry) {
            entryNames.add(entry.getPathFromRoot());
            return continueAfter.test(entry.getPathFromRoot());
        }
    }

    /**
     * Write a jarfile whose central directory lists the given entries in the given order.
     *
     * @param jarFile
     *            the jarfile to write.
     * @param entryNames
     *            the names of the entries to write, in order.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJar(final File jarFile, final String... entryNames) throws IOException {
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            for (final var entryName : entryNames) {
                zipOut.putNextEntry(new ZipEntry(entryName));
                zipOut.write(entryName.getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
            }
        }
    }

    /**
     * Create a directory tree with a file at the root, a file two levels down, and a directory that contains only
     * another directory.
     *
     * @param tempDir
     *            the directory to create the tree in.
     * @return the root of the tree.
     * @throws IOException
     *             if the tree could not be created.
     */
    private static File writeDirTree(final File tempDir) throws IOException {
        final var dir = new File(tempDir, "classes");
        assertThat(new File(dir, "com/xyz").mkdirs()).isTrue();
        assertThat(new File(dir, "empty/deep").mkdirs()).isTrue();
        Files.writeString(new File(dir, "root.txt").toPath(), "root");
        Files.writeString(new File(dir, "com/xyz/widget.txt").toPath(), "widget");
        Files.writeString(new File(dir, "empty/deep/leaf.txt").toPath(), "leaf");
        return dir;
    }

    // ---------------------------------------------------------------------------------------------------------

    /** A directory tree is walked from the top down, each directory's own files before its subdirectories. */
    @Test
    public void aDirectoryTreeIsWalkedFilesBeforeSubdirectories(@TempDir final File tempDir) throws IOException {
        try (var vfs = new Vfs()) {
            final var recorder = new Recorder();
            vfs.open(writeDirTree(tempDir).getPath()).walk(recorder);

            // Every directory is offered, parents before children, including ones that contain no files at all
            assertThat(recorder.dirNames).containsExactly("/", "com/", "com/xyz/", "empty/", "empty/deep/");
            assertThat(recorder.entryNames).containsExactly("root.txt", "com/xyz/widget.txt",
                    "empty/deep/leaf.txt");
        }
    }

    /** A walk of a directory tree reports the same entries, in the same order, as {@link VfsRoot#getEntries()}. */
    @Test
    public void aDirectoryTreeWalkAgreesWithGetEntries(@TempDir final File tempDir) throws IOException {
        try (var vfs = new Vfs()) {
            final var root = vfs.open(writeDirTree(tempDir).getPath());
            final var recorder = new Recorder();
            root.walk(recorder);
            assertThat(recorder.entryNames)
                    .isEqualTo(root.getEntries().stream().map(VfsEntry::getPathFromRoot).toList());
        }
    }

    /** Skipping a directory of a directory tree skips the whole subtree below it. */
    @Test
    public void skippingADirectoryOfATreeSkipsTheSubtree(@TempDir final File tempDir) throws IOException {
        try (var vfs = new Vfs()) {
            final var recorder = new Recorder(dirName -> !dirName.equals("com/"), entryName -> true);
            vfs.open(writeDirTree(tempDir).getPath()).walk(recorder);

            // "com/xyz/" is never even offered, because "com/" was not listed
            assertThat(recorder.dirNames).containsExactly("/", "com/", "empty/", "empty/deep/");
            assertThat(recorder.entryNames).containsExactly("root.txt", "empty/deep/leaf.txt");
        }
    }

    /** Returning false from {@link VfsVisitor#visitEntry(VfsEntry)} stops a directory tree walk at once. */
    @Test
    public void aDirectoryTreeWalkStopsWhenTheVisitorSaysSo(@TempDir final File tempDir) throws IOException {
        try (var vfs = new Vfs()) {
            final var recorder = new Recorder(dirName -> true, entryName -> !entryName.equals("root.txt"));
            vfs.open(writeDirTree(tempDir).getPath()).walk(recorder);

            assertThat(recorder.dirNames).containsExactly("/");
            assertThat(recorder.entryNames).containsExactly("root.txt");
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    /** A jarfile is walked in central directory order, and its root entries are in the directory named "/". */
    @Test
    public void aJarfileIsWalkedInCentralDirectoryOrder(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "app.jar");
        writeJar(jarFile, "com/xyz/B.txt", "com/xyz/A.txt", "root.txt", "com/abc/C.txt");

        try (var vfs = new Vfs()) {
            final var recorder = new Recorder();
            vfs.open(jarFile.getPath()).walk(recorder);

            assertThat(recorder.dirNames).containsExactly("com/xyz/", "/", "com/abc/");
            assertThat(recorder.entryNames).containsExactly("com/xyz/B.txt", "com/xyz/A.txt", "root.txt",
                    "com/abc/C.txt");
        }
    }

    /**
     * Skipping a directory of a jarfile skips only that directory's entries -- the directories below it are still
     * offered, since a caller may be stripping a package root prefix, which makes a name below a directory
     * unrelated to the directory itself.
     */
    @Test
    public void skippingADirectoryOfAJarfileSkipsOnlyItsEntries(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "app.jar");
        writeJar(jarFile, "BOOT-INF/x.txt", "BOOT-INF/classes/com/xyz/A.txt");

        try (var vfs = new Vfs()) {
            final var recorder = new Recorder(dirName -> !dirName.equals("BOOT-INF/"), entryName -> true);
            vfs.open(jarFile.getPath()).walk(recorder);

            assertThat(recorder.dirNames).containsExactly("BOOT-INF/", "BOOT-INF/classes/com/xyz/");
            assertThat(recorder.entryNames).containsExactly("BOOT-INF/classes/com/xyz/A.txt");
        }
    }

    /** A jarfile directory whose entries are not contiguous is offered once per run of them. */
    @Test
    public void aJarfileDirectoryIsOfferedOncePerRunOfItsEntries(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "app.jar");
        writeJar(jarFile, "a/1.txt", "b/1.txt", "a/2.txt");

        try (var vfs = new Vfs()) {
            final var recorder = new Recorder();
            vfs.open(jarFile.getPath()).walk(recorder);

            assertThat(recorder.dirNames).containsExactly("a/", "b/", "a/");
            assertThat(recorder.entryNames).containsExactly("a/1.txt", "b/1.txt", "a/2.txt");
        }
    }

    /**
     * A package root is stripped from the names a walk reports, just as it is from
     * {@link VfsEntry#getPathFromRoot()}.
     */
    @Test
    public void aWalkOfAPackageRootReportsStrippedNames(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "app.jar");
        writeJar(jarFile, "BOOT-INF/x.txt", "BOOT-INF/classes/com/xyz/A.txt");

        try (var vfs = new Vfs()) {
            final var recorder = new Recorder();
            vfs.open(jarFile.getPath() + "!/BOOT-INF/classes").walk(recorder);

            assertThat(recorder.dirNames).containsExactly("com/xyz/");
            assertThat(recorder.entryNames).containsExactly("com/xyz/A.txt");
        }
    }

    /** Returning false from {@link VfsVisitor#visitEntry(VfsEntry)} stops a jarfile walk at once. */
    @Test
    public void aJarfileWalkStopsWhenTheVisitorSaysSo(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "app.jar");
        writeJar(jarFile, "a/1.txt", "a/2.txt", "b/1.txt");

        try (var vfs = new Vfs()) {
            final var recorder = new Recorder(dirName -> true, entryName -> !entryName.equals("a/1.txt"));
            vfs.open(jarFile.getPath()).walk(recorder);

            assertThat(recorder.dirNames).containsExactly("a/");
            assertThat(recorder.entryNames).containsExactly("a/1.txt");
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    /** A module is walked in the same order as {@link VfsRoot#getEntries()}, and its directories are offered. */
    @Test
    public void aModuleIsWalked() throws IOException {
        try (var vfs = new Vfs()) {
            final var root = vfs.open(ModuleFinder.ofSystem().find("java.logging").orElseThrow());
            final var recorder = new Recorder();
            root.walk(recorder);

            assertThat(recorder.entryNames)
                    .isEqualTo(root.getEntries().stream().map(VfsEntry::getPathFromRoot).toList());
            assertThat(recorder.entryNames).contains("java/util/logging/Logger.class");
            assertThat(recorder.dirNames).contains("java/util/logging/");
        }
    }

    /** Skipping a directory of a module skips its entries. */
    @Test
    public void skippingADirectoryOfAModuleSkipsItsEntries() throws IOException {
        try (var vfs = new Vfs()) {
            final var recorder = new Recorder(dirName -> !dirName.equals("java/util/logging/"), entryName -> true);
            vfs.open(ModuleFinder.ofSystem().find("java.logging").orElseThrow()).walk(recorder);

            assertThat(recorder.dirNames).contains("java/util/logging/");
            assertThat(recorder.entryNames).doesNotContain("java/util/logging/Logger.class");
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    /** A null visitor is rejected by every kind of root. */
    @Test
    public void aNullVisitorIsRejected(@TempDir final File tempDir) throws IOException {
        final var jarFile = new File(tempDir, "app.jar");
        writeJar(jarFile, "root.txt");

        try (var vfs = new Vfs()) {
            assertThatThrownBy(() -> vfs.open(jarFile.getPath()).walk(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> vfs.open(writeDirTree(tempDir).getPath()).walk(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(
                    () -> vfs.open(ModuleFinder.ofSystem().find("java.logging").orElseThrow()).walk(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
