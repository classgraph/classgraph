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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.test.accepted.Accepted;
import io.github.classgraph.test.rejected.RejectedSuperclass;

/**
 * A rejected class is left out of the results, but the class graph is still traversed through it, so that the
 * classes above it in the hierarchy are still reported. That traversal reads the rejected classfile by name, which
 * a jarfile classpath element and a directory classpath element have to be able to do alike.
 */
public class RejectedSuperclassIsTraversedTest {
    /** The package that the scan rejects. */
    private static final String REJECTED_PACKAGE = RejectedSuperclass.class.getPackage().getName();

    /** The classfile paths of the two classes. */
    private static final List<String> CLASSFILE_PATHS = List.of(
            RejectedSuperclass.class.getName().replace('.', '/') + ".class",
            Accepted.class.getName().replace('.', '/') + ".class");

    /**
     * Read a classfile from the test classpath.
     *
     * @param classfilePath
     *            the path of the classfile.
     * @return the content of the classfile.
     * @throws IOException
     *             if the classfile could not be read.
     */
    private static byte[] classfileContent(final String classfilePath) throws IOException {
        try (var in = RejectedSuperclassIsTraversedTest.class.getClassLoader().getResourceAsStream(classfilePath)) {
            assertThat(in).as(classfilePath).isNotNull();
            return in.readAllBytes();
        }
    }

    /**
     * Write the two classfiles into a directory.
     *
     * @param dir
     *            the directory to write into.
     * @throws IOException
     *             if the classfiles could not be written.
     */
    private static void writeDir(final Path dir) throws IOException {
        for (final String classfilePath : CLASSFILE_PATHS) {
            final var classfile = dir.resolve(classfilePath);
            Files.createDirectories(classfile.getParent());
            Files.write(classfile, classfileContent(classfilePath));
        }
    }

    /**
     * Write the two classfiles into a jarfile.
     *
     * @param jar
     *            the jarfile to write.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJar(final Path jar) throws IOException {
        try (var zipOut = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            for (final String classfilePath : CLASSFILE_PATHS) {
                zipOut.putNextEntry(new ZipEntry(classfilePath));
                zipOut.write(classfileContent(classfilePath));
                zipOut.closeEntry();
            }
        }
    }

    /**
     * Scan a single classpath element, rejecting the superclass.
     *
     * @param classpathElement
     *            the classpath element to scan.
     * @return the scan result.
     */
    private static ScanResult scan(final Path classpathElement) {
        return new ClassGraph().enableClasspathEntries(List.of(classpathElement)).rejectPackages(REJECTED_PACKAGE)
                .enableExternalClasses().scan();
    }

    /**
     * The rejected superclass is left out of the hierarchy, but {@link Object}, which is above it, is not, in a
     * jarfile classpath element and in a directory classpath element alike.
     *
     * @param tempDir
     *            a temporary directory to build the classpath elements in.
     * @throws IOException
     *             if the classpath elements could not be written.
     */
    @Test
    public void theHierarchyAboveARejectedClassIsStillReported(@TempDir final Path tempDir) throws IOException {
        final var dir = tempDir.resolve("dir");
        writeDir(dir);
        try (var scanResult = scan(dir)) {
            assertThat(scanResult.getAllSuperclasses(Accepted.class.getName()).getNames())
                    .containsExactly("java.lang.Object");
        }

        final var jar = tempDir.resolve("app.jar");
        writeJar(jar);
        try (var scanResult = scan(jar)) {
            assertThat(scanResult.getAllSuperclasses(Accepted.class.getName()).getNames())
                    .containsExactly("java.lang.Object");
        }
    }
}
