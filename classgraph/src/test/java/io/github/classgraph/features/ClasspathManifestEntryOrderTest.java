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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;

/**
 * A class that is defined by more than one classpath element is read from the element that comes first in the
 * classpath order, and a classpath element named by another element's {@code Class-Path} manifest entry takes its
 * position directly after the element that named it, rather than being appended to the end of the classpath.
 *
 * <p>
 * Classpath elements are opened in parallel, so this checks that the classpath order is decided by where each
 * element sits in the classpath, not by the order in which the scanner finished opening them.
 */
class ClasspathManifestEntryOrderTest {
    /** The zipfile that defines {@code issue100.Test} with a field named {@code a}. */
    private static final String A_ZIP = "issue100-has-field-a.zip";

    /** The zipfile that defines {@code issue100.Test} with a field named {@code b}. */
    private static final String B_ZIP = "issue100-has-field-b.zip";

    /**
     * Copy a zipfile from the test resources into a directory, so that it can be named by a relative
     * {@code Class-Path} manifest entry.
     *
     * @param dir
     *            the directory to copy the zipfile into.
     * @param resourceName
     *            the name of the zipfile in the test resources.
     * @return the copied zipfile.
     * @throws IOException
     *             if the zipfile could not be copied.
     */
    private static File copyZip(final Path dir, final String resourceName) throws IOException {
        final var zipFile = dir.resolve(resourceName);
        try (var inputStream = ClasspathManifestEntryOrderTest.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            Files.copy(inputStream, zipFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return zipFile.toFile();
    }

    /**
     * Write a jarfile that contains nothing but a manifest whose {@code Class-Path} attribute names another
     * classpath element.
     *
     * @param dir
     *            the directory to write the jarfile into.
     * @param jarName
     *            the filename of the jarfile to write.
     * @param classpathManifestEntry
     *            the value of the {@code Class-Path} manifest attribute.
     * @return the jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static File writeJarNaming(final Path dir, final String jarName, final String classpathManifestEntry)
            throws IOException {
        final var manifest = new Manifest();
        final var mainAttributes = manifest.getMainAttributes();
        mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mainAttributes.put(Attributes.Name.CLASS_PATH, classpathManifestEntry);
        final var jarFile = dir.resolve(jarName);
        try (var outputStream = Files.newOutputStream(jarFile);
                var jarOutputStream = new JarOutputStream(outputStream, manifest)) {
            // No entries -- the manifest is the whole jar
        }
        return jarFile.toFile();
    }

    /**
     * Scan a classpath, and return the name of the only field of {@code issue100.Test}, which says which of the two
     * zipfiles the class was read from.
     *
     * @param classpathElements
     *            the classpath elements to scan, in classpath order.
     * @return the name of the field.
     */
    private static String fieldNameOfScannedClass(final Object... classpathElements) {
        try (var scanResult = new ClassGraph().overrideClasspath(classpathElements).acceptPackages("issue100")
                .enableFieldInfo().scan()) {
            return scanResult.getClassInfo("issue100.Test").getFieldInfo().get(0).getName();
        }
    }

    /** A class defined by two classpath elements is read from the element that comes first. */
    @Test
    void theEarlierOfTwoCopiesOfAClassIsTheOneThatIsRead(@TempDir final Path tempDir) throws IOException {
        final var aZip = copyZip(tempDir, A_ZIP);
        final var bZip = copyZip(tempDir, B_ZIP);
        assertThat(fieldNameOfScannedClass(aZip, bZip)).isEqualTo("a");
        assertThat(fieldNameOfScannedClass(bZip, aZip)).isEqualTo("b");
    }

    /**
     * A classpath element named by a {@code Class-Path} manifest entry is scanned as if it sat directly after the
     * jarfile that named it, so it masks the classes of the classpath elements that come after that jarfile, and is
     * masked by the ones that come before it.
     */
    @Test
    void aManifestClassPathEntryTakesThePositionOfTheJarThatNamedIt(@TempDir final Path tempDir)
            throws IOException {
        final var aZip = copyZip(tempDir, A_ZIP);
        copyZip(tempDir, B_ZIP);
        final var namesBZip = writeJarNaming(tempDir, "names-another-classpath-element.jar", B_ZIP);
        // The jar that names B comes first, so B is scanned before A, even though A is named directly
        assertThat(fieldNameOfScannedClass(namesBZip, aZip)).isEqualTo("b");
        // A comes first, so it masks the B that the following jar names
        assertThat(fieldNameOfScannedClass(aZip, namesBZip)).isEqualTo("a");
    }

    /**
     * The order of the entries in a {@code Class-Path} manifest attribute is a property of the jarfile that
     * declares them, so a classpath element that is also listed on the toplevel classpath still takes its declared
     * position within the {@code Class-Path} entry of the jar that names it, rather than being hoisted to the front
     * of that jar's entries because it is a toplevel classpath element in its own right.
     */
    // #810
    @Test
    void aChildElementThatIsAlsoOnTheToplevelClasspathKeepsItsPositionWithinItsParent(@TempDir final Path tempDir)
            throws IOException {
        copyZip(tempDir, A_ZIP);
        final var bZip = copyZip(tempDir, B_ZIP);
        final var namesAThenB = writeJarNaming(tempDir, "names-a-then-b.jar", A_ZIP + " " + B_ZIP);
        // The jar names A before B, so A masks B, even though B is also listed on the toplevel classpath
        assertThat(fieldNameOfScannedClass(namesAThenB, bZip)).isEqualTo("a");
    }

    /**
     * The order of the entries in a {@code Class-Path} manifest attribute is a property of the jarfile that
     * declares them, so a classpath element that is named by the {@code Class-Path} entries of two different
     * jarfiles takes its declared position within each of them, rather than taking the position it was given by
     * whichever jarfile named it earliest.
     */
    // #810
    @Test
    void aChildElementNamedByTwoParentsKeepsItsPositionWithinEachParent(@TempDir final Path tempDir)
            throws IOException {
        copyZip(tempDir, A_ZIP);
        copyZip(tempDir, B_ZIP);
        // A jar with no classes of its own, so that A is not the first entry of the Class-Path that names it
        final var emptyJar = writeJarNaming(tempDir, "no-classes.jar", "");
        final var namesEmptyThenAThenB = writeJarNaming(tempDir, "names-empty-then-a-then-b.jar",
                emptyJar.getName() + " " + A_ZIP + " " + B_ZIP);
        // A second jar names B alone, so B is the first Class-Path entry of that jar
        final var namesB = writeJarNaming(tempDir, "names-b.jar", B_ZIP);
        // The first jar names A before B, so A masks B, whichever position the second jar gives B
        assertThat(fieldNameOfScannedClass(namesEmptyThenAThenB, namesB)).isEqualTo("a");
    }
}
