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
package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ScanResult#getResourcesWithPathIgnoringAccept(String)} finds a resource whether or not it is in an
 * accepted package, but never one that is rejected. A directory classpath element answers such a lookup out of the
 * filesystem, rather than out of the resources that the scan recorded, which is what lets it find a resource in a
 * directory that the recursive scan stopped short of -- so the rejected ones have to be filtered out of the lookup
 * itself.
 */
public class RejectedResourcesAreNotFoundByPathTest {
    /** A resource in a package that is neither accepted nor rejected. */
    private static final String NEITHER = "com/neither/Neither.txt";

    /** A resource in a rejected package. */
    private static final String REJECTED = "com/rejected/Rejected.txt";

    /**
     * Write the two resources into a directory.
     *
     * @param dir
     *            the directory to write into.
     * @throws IOException
     *             if the resources could not be written.
     */
    private static void writeDir(final Path dir) throws IOException {
        for (final String path : Arrays.asList(NEITHER, REJECTED)) {
            final Path file = dir.resolve(path);
            Files.createDirectories(file.getParent());
            Files.write(file, new byte[0]);
        }
    }

    /**
     * Write the two resources into a jarfile.
     *
     * @param jar
     *            the jarfile to write.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJar(final Path jar) throws IOException {
        try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            for (final String path : Arrays.asList(NEITHER, REJECTED)) {
                zipOut.putNextEntry(new ZipEntry(path));
                zipOut.closeEntry();
            }
        }
    }

    /**
     * Scan a single classpath element, rejecting {@code "com.rejected"} and accepting nothing in particular.
     *
     * @param classpathElement
     *            the classpath element to scan.
     * @return the scan result.
     */
    private static ScanResult scan(final Path classpathElement) {
        return new ClassGraph().overrideClasspath(Collections.singletonList(classpathElement)).rejectPackages("com.rejected")
                .scan();
    }

    /**
     * A rejected resource is not found by path, in a directory classpath element or in a jarfile alike, since the
     * two must answer the same question the same way.
     *
     * @param tempDir
     *            a temporary directory to build the classpath elements in.
     * @throws IOException
     *             if the classpath elements could not be written.
     */
    @Test
    public void aRejectedResourceIsNotFoundByPath(@TempDir final Path tempDir) throws IOException {
        final Path dir = tempDir.resolve("dir");
        writeDir(dir);
        try (ScanResult scanResult = scan(dir)) {
            assertThat(scanResult.getResourcesWithPathIgnoringAccept(REJECTED)).isEmpty();
        }

        final Path jar = tempDir.resolve("app.jar");
        writeJar(jar);
        try (ScanResult scanResult = scan(jar)) {
            assertThat(scanResult.getResourcesWithPathIgnoringAccept(REJECTED)).isEmpty();
        }
    }

    /**
     * A resource that is neither accepted nor rejected is still found by path in a directory classpath element,
     * which is the whole point of the method, so the reject filter must not turn it into a lookup of the accepted
     * resources alone.
     *
     * @param tempDir
     *            a temporary directory to build the classpath element in.
     * @throws IOException
     *             if the classpath element could not be written.
     */
    @Test
    public void aResourceThatIsMerelyNotAcceptedIsStillFoundByPath(@TempDir final Path tempDir) throws IOException {
        final Path dir = tempDir.resolve("dir");
        writeDir(dir);
        try (ScanResult scanResult = scan(dir)) {
            assertThat(scanResult.getResourcesWithPathIgnoringAccept(NEITHER).getPaths()).containsExactly(NEITHER);
        }
    }
}
