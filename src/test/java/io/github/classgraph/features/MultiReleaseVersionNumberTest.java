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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ResourceList;
import io.github.classgraph.ScanResult;
import nonapi.io.github.classgraph.utils.VersionFinder;

/**
 * A versioned section of a multi-release jarfile is only selected if it is named by a version number as the JVM
 * writes it.
 */
class MultiReleaseVersionNumberTest {
    /** The path of an entry that is stored in the base of the jarfile and in two versioned sections. */
    private static final String OVERRIDDEN = "pkg/overridden.txt";

    /**
     * Write a jarfile entry.
     *
     * @param jarOutputStream
     *            the jarfile to write to.
     * @param name
     *            the name of the entry.
     * @param content
     *            the content of the entry.
     * @throws IOException
     *             if the entry could not be written.
     */
    private static void putEntry(final JarOutputStream jarOutputStream, final String name, final String content)
            throws IOException {
        jarOutputStream.putNextEntry(new JarEntry(name));
        jarOutputStream.write(content.getBytes(StandardCharsets.UTF_8));
        jarOutputStream.closeEntry();
    }

    /**
     * The JVM does not read a version number with a leading zero as a version number, since it looks a versioned
     * entry up by the version written without one. If {@code "09"} were read as 9, its entry would mask the entry
     * of the section for version 9, since {@code "09"} sorts before {@code "9"}.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the jarfile could not be built or read.
     */
    @Test
    void aVersionNumberWithALeadingZeroIsNotAVersion(@TempDir final Path tempDir) throws IOException {
        final Path jar = tempDir.resolve("multi-release.jar");
        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Multi-Release"), "true");
        try (OutputStream outputStream = Files.newOutputStream(jar);
                JarOutputStream jarOutputStream = new JarOutputStream(outputStream, manifest)) {
            putEntry(jarOutputStream, "META-INF/versions/09/" + OVERRIDDEN, "09");
            putEntry(jarOutputStream, "META-INF/versions/9/" + OVERRIDDEN, "9");
            putEntry(jarOutputStream, OVERRIDDEN, "base");
        }
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(jar.toString()).scan()) {
            final ResourceList resources = scanResult.getResourcesWithPath(OVERRIDDEN);
            assertThat(resources).hasSize(1);
            // A JVM older than 9 selects no versioned section at all
            assertThat(resources.get(0).getContentAsString())
                    .isEqualTo(VersionFinder.JAVA_MAJOR_VERSION >= 9 ? "9" : "base");
        }
    }
}
