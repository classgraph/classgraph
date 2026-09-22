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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * The {@code Class-Path} and {@code Bundle-ClassPath} manifest attributes are read with the syntax their
 * specifications give them.
 */
class ManifestClassPathSyntaxTest {
    /** A zipfile that defines the class {@code issue100.Test}. */
    private static final String ZIP = "issue100-has-field-a.zip";

    /**
     * Copy the zipfile that defines {@code issue100.Test} to a file.
     *
     * @param file
     *            the file to copy it to.
     * @throws IOException
     *             if the zipfile could not be copied.
     */
    private static void copyZip(final Path file) throws IOException {
        try (InputStream inputStream = ManifestClassPathSyntaxTest.class.getClassLoader()
                .getResourceAsStream(ZIP)) {
            Files.copy(inputStream, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Write a jarfile with a manifest attribute, and optionally one entry that holds the zipfile that defines
     * {@code issue100.Test}.
     *
     * @param jarFile
     *            the jarfile to write.
     * @param attributeName
     *            the name of the manifest attribute.
     * @param attributeValue
     *            the value of the manifest attribute.
     * @param innerZipEntryName
     *            the name of the entry to store the zipfile under, or null for no entry.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJar(final Path jarFile, final Attributes.Name attributeName,
            final String attributeValue, final String innerZipEntryName) throws IOException {
        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(attributeName, attributeValue);
        try (OutputStream outputStream = Files.newOutputStream(jarFile);
                JarOutputStream jarOutputStream = new JarOutputStream(outputStream, manifest)) {
            if (innerZipEntryName != null) {
                jarOutputStream.putNextEntry(new JarEntry(innerZipEntryName));
                try (InputStream inputStream = ManifestClassPathSyntaxTest.class.getClassLoader()
                        .getResourceAsStream(ZIP)) {
                    final byte[] buf = new byte[8192];
                    for (int n; (n = inputStream.read(buf)) > 0;) {
                        jarOutputStream.write(buf, 0, n);
                    }
                }
                jarOutputStream.closeEntry();
            }
        }
    }

    /**
     * Scan a jarfile, and say whether {@code issue100.Test} was found.
     *
     * @param jarFile
     *            the jarfile.
     * @return true if the class was found.
     */
    private static boolean findsTheClass(final Path jarFile) {
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(jarFile.toFile())
                .acceptPackages("issue100").scan()) {
            return scanResult.getClassInfo("issue100.Test") != null;
        }
    }

    /**
     * A {@code Class-Path} entry is a relative URL, so its percent encoding is decoded, as the JVM's own
     * classloader decodes it. This is the only way to name a jarfile with a space in its name, since a space
     * separates the entries.
     */
    @Test
    void classPathEntriesArePercentDecoded(@TempDir final Path tempDir) throws IOException {
        copyZip(tempDir.resolve("my lib.jar"));
        final Path jar = tempDir.resolve("names-my-lib.jar");
        writeJar(jar, Attributes.Name.CLASS_PATH, "my%20lib.jar", null);
        assertThat(findsTheClass(jar)).isTrue();
    }

    /** The paths of a {@code Bundle-ClassPath} entry may be surrounded by spaces. */
    @Test
    void bundleClassPathPathsMayBeSurroundedBySpaces(@TempDir final Path tempDir) throws IOException {
        final Path jar = tempDir.resolve("bundle.jar");
        writeJar(jar, new Attributes.Name("Bundle-ClassPath"), ". , inner.jar", "inner.jar");
        assertThat(findsTheClass(jar)).isTrue();
    }

    /** A {@code Bundle-ClassPath} path may be quoted, and may be followed by parameters. */
    @Test
    void bundleClassPathPathsMayBeQuotedOrHaveParameters(@TempDir final Path tempDir) throws IOException {
        final Path quoted = tempDir.resolve("quoted.jar");
        writeJar(quoted, new Attributes.Name("Bundle-ClassPath"), ".,\"inner.jar\"", "inner.jar");
        assertThat(findsTheClass(quoted)).isTrue();
        final Path withParameter = tempDir.resolve("with-parameter.jar");
        writeJar(withParameter, new Attributes.Name("Bundle-ClassPath"),
                ".,inner.jar;selection-filter=\"(os.name=Linux)\"", "inner.jar");
        assertThat(findsTheClass(withParameter)).isTrue();
    }
}
