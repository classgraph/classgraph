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
package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * {@link ClassGraph#rejectClasspathElementsContainingResourcePath(String...)} must stop the whole classpath element
 * containing the matched resource from being scanned, not just the matched resource itself.
 */
public class RejectClasspathElementsContainingResourcePathTest {
    /** A directory classpath element containing res/indir.txt and res/alsoindir.txt. */
    private Path dir;

    /** A jarfile classpath element containing res/injar.txt. */
    private Path jar;

    /**
     * Build the two classpath elements.
     *
     * @param tempDir
     *            the temporary directory to build them in.
     * @throws IOException
     *             if the classpath elements could not be written.
     */
    @BeforeEach
    public void makeClasspathElements(@TempDir final Path tempDir) throws IOException {
        dir = tempDir.resolve("classpathElementDir");
        Files.createDirectories(dir.resolve("res"));
        Files.write(dir.resolve("res").resolve("indir.txt"), "indir".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("res").resolve("alsoindir.txt"), "alsoindir".getBytes(StandardCharsets.UTF_8));

        jar = tempDir.resolve("classpathElementJar.jar");
        try (OutputStream outputStream = Files.newOutputStream(jar);
                ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            zipOutputStream.putNextEntry(new ZipEntry("res/injar.txt"));
            zipOutputStream.write("injar".getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
    }

    /** Rejecting a resource path in the directory leaves the whole directory unscanned, but keeps the jar. */
    @Test
    public void aRejectedPathInADirRejectsTheWholeDir() {
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(dir.toString(), jar.toString())
                .rejectClasspathElementsContainingResourcePath("res/indir.txt").scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly("res/injar.txt");
            final List<URI> classpathURIs = scanResult.getClasspathURIs();
            assertThat(classpathURIs).hasSize(1);
            assertThat(classpathURIs.get(0).toString()).endsWith("classpathElementJar.jar");
        }
    }

    /** Rejecting a resource path in the jar leaves the whole jar unscanned, but keeps the directory. */
    @Test
    public void aRejectedPathInAJarRejectsTheWholeJar() {
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(dir.toString(), jar.toString())
                .rejectClasspathElementsContainingResourcePath("res/injar.txt").scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactlyInAnyOrder("res/indir.txt",
                    "res/alsoindir.txt");
            final List<URI> classpathURIs = scanResult.getClasspathURIs();
            assertThat(classpathURIs).hasSize(1);
            assertThat(classpathURIs.get(0).toString()).contains("classpathElementDir");
        }
    }

    /** A glob that matches a resource in both classpath elements leaves nothing to scan. */
    @Test
    public void aRejectedPathCanRejectEveryClasspathElement() {
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(dir.toString(), jar.toString())
                .rejectClasspathElementsContainingResourcePath("res/**").scan()) {
            assertThat(scanResult.getAllResources()).isEmpty();
            assertThat(scanResult.getClasspathURIs()).isEmpty();
        }
    }
}
