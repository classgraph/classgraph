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
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions
 * of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the ownership tree that the roots opened by a {@link Vfs} form: a jarfile nested within another one, and a
 * package root view of a jarfile, are both opened within the root of the enclosing jarfile, so closing that root
 * closes them too, and closing one of them prunes it from the tree without disturbing the root it was opened
 * within.
 */
class VfsRootTreeTest {
    /** The name of the entry holding the nested jarfile. */
    private static final String INNER_JAR_NAME = "lib/inner.jar";

    /** The directory within the outer jarfile that holds classes. */
    private static final String PACKAGE_ROOT = "BOOT-INF/classes";

    /** The path of the outer jarfile. */
    private String outerJarPath;

    /**
     * Write a jarfile holding a nested jarfile and a class under a package root.
     *
     * @param tempDir
     *            a temporary directory to build the jarfiles in
     * @throws IOException
     *             if the jarfiles could not be written
     */
    @BeforeEach
    void buildJars(@TempDir final Path tempDir) throws IOException {
        final var innerJar = tempDir.resolve("inner.jar");
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(innerJar))) {
            zipOut.putNextEntry(new ZipEntry("testpkg/Inner.class"));
            zipOut.write("inner".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        final var outerJar = tempDir.resolve("outer.jar");
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(outerJar))) {
            // Stored, not deflated, so that the nested jarfile can be read as a slice of the outer one
            final var innerJarBytes = Files.readAllBytes(innerJar);
            final var innerJarEntry = new ZipEntry(INNER_JAR_NAME);
            innerJarEntry.setMethod(ZipEntry.STORED);
            innerJarEntry.setSize(innerJarBytes.length);
            innerJarEntry.setCompressedSize(innerJarBytes.length);
            final var crc = new CRC32();
            crc.update(innerJarBytes);
            innerJarEntry.setCrc(crc.getValue());
            zipOut.putNextEntry(innerJarEntry);
            zipOut.write(innerJarBytes);
            zipOut.closeEntry();

            zipOut.putNextEntry(new ZipEntry(PACKAGE_ROOT + "/testpkg/Outer.class"));
            zipOut.write("outer".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        outerJarPath = outerJar.toString();
    }

    /** Closing the root of the enclosing jarfile closes the roots that were opened within it. */
    @Test
    void closingAJarfileClosesTheRootsOpenedWithinIt() throws Exception {
        try (var vfs = new Vfs()) {
            final var innerRoot = vfs.open(outerJarPath + "!/" + INNER_JAR_NAME);
            final var packageRootView = vfs.open(outerJarPath + "!/" + PACKAGE_ROOT);
            final var outerRoot = vfs.open(outerJarPath);

            outerRoot.close();

            assertThatThrownBy(innerRoot::getEntries).isInstanceOf(IOException.class)
                    .hasMessageContaining("after the root has been closed");
            assertThatThrownBy(packageRootView::getEntries).isInstanceOf(IOException.class)
                    .hasMessageContaining("after the root has been closed");
            // Every closed root is out of the cache, and the Vfs itself is untouched by the close
            assertThat(vfs).isEmpty();
            assertThat(vfs.open(outerJarPath + "!/" + INNER_JAR_NAME).getEntries())
                    .extracting(VfsEntry::getPathFromRoot).containsExactly("testpkg/Inner.class");
        }
    }

    /**
     * Closing a root that was opened within another one prunes it from the tree: the root it was opened within goes
     * on working, and opening the same path again builds a fresh root within it.
     */
    @Test
    void closingANestedJarfileLeavesTheEnclosingOneWorking() throws Exception {
        try (var vfs = new Vfs()) {
            final var outerRoot = vfs.open(outerJarPath);
            final var innerRoot = vfs.open(outerJarPath + "!/" + INNER_JAR_NAME);

            innerRoot.close();

            assertThat(outerRoot.getEntries()).extracting(VfsEntry::getPathFromRoot).contains(INNER_JAR_NAME);
            assertThat(vfs).containsExactly(outerRoot);
            final var reopened = vfs.open(outerJarPath + "!/" + INNER_JAR_NAME);
            assertThat(reopened).isNotSameAs(innerRoot);
            assertThat(reopened.getEntries()).extracting(VfsEntry::getPathFromRoot)
                    .containsExactly("testpkg/Inner.class");
            // The enclosing jarfile was not reopened to build the new root within it
            assertThat(reopened.getContainerRoot()).isSameAs(reopened);
            assertThat(vfs.open(outerJarPath)).isSameAs(outerRoot);
        }
    }

    /**
     * Opening a path within a jarfile opens the enclosing jarfile as a root in its own right, cached under its own
     * path, so it is reported by the {@link Vfs} and is only read once however many roots are opened within it.
     */
    @Test
    void theEnclosingJarfileIsCachedUnderItsOwnPath() throws Exception {
        try (var vfs = new Vfs()) {
            final var innerRoot = vfs.open(outerJarPath + "!/" + INNER_JAR_NAME);
            assertThat(vfs).hasSize(2);

            final var outerRoot = vfs.open(outerJarPath);
            assertThat(vfs).hasSize(2);
            // The nested jarfile's root is the root of the whole nested jarfile, so it is its own container root;
            // the enclosing jarfile is the root it was opened within, which its path names
            assertThat(innerRoot.getContainerRoot()).isSameAs(innerRoot);
            assertThat(innerRoot.getPath()).isEqualTo(outerRoot.getPath() + "!/" + INNER_JAR_NAME);

            // The package root view of the same jarfile is the same jarfile, read through the same root
            final var packageRootView = vfs.open(outerJarPath + "!/" + PACKAGE_ROOT);
            assertThat(packageRootView.getContainerRoot()).isSameAs(outerRoot);
            assertThat(vfs).hasSize(3);
        }
    }
}
