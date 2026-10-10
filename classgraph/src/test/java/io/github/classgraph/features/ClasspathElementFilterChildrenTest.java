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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;
import org.jspecify.annotations.Nullable;

/**
 * The classpath element filters apply to the classpath elements that another classpath element declares -- the
 * entries of its manifest's {@code Class-Path} attribute and the jarfiles in its lib dirs -- as well as to the ones
 * named directly, and a classpath element that a filter rejects is not opened, so the classpath elements that it
 * declares are not scanned either.
 */
public class ClasspathElementFilterChildrenTest {
    /**
     * Write a jarfile with one text entry and, optionally, a {@code Class-Path} manifest attribute.
     *
     * @param jarFile
     *            the jarfile to write.
     * @param entryPath
     *            the path of the text entry.
     * @param classPath
     *            the value of the {@code Class-Path} manifest attribute, or null for none.
     * @return the jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static Path writeJar(final Path jarFile, final String entryPath, final @Nullable String classPath)
            throws IOException {
        Files.createDirectories(jarFile.getParent());
        final var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (classPath != null) {
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
        }
        try (var jarOut = new JarOutputStream(Files.newOutputStream(jarFile), manifest)) {
            jarOut.putNextEntry(new ZipEntry(entryPath));
            jarOut.write(entryPath.getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
        return jarFile;
    }

    /**
     * Get the paths of the text resources a scan found, leaving out the manifests.
     *
     * @param classGraph
     *            the configured scanner.
     * @return the resource paths, sorted.
     */
    private static List<String> scannedTextResources(final ClassGraph classGraph) {
        try (var scanResult = classGraph.scan()) {
            return scanResult.getResourcesWithExtension("txt").stream().map(Resource::getPath).sorted().toList();
        }
    }

    /**
     * A path filter rejects a jarfile named by a {@code Class-Path} manifest entry, and the jarfile that the
     * rejected one names in turn is not scanned either.
     */
    @Test
    public void aPathFilterRejectsAClassPathManifestEntry(@TempDir final Path tempDir) throws IOException {
        final var a = writeJar(tempDir.resolve("a.jar"), "a.txt", "b.jar");
        writeJar(tempDir.resolve("b.jar"), "b.txt", "c.jar");
        writeJar(tempDir.resolve("c.jar"), "c.txt", null);
        assertThat(scannedTextResources(new ClassGraph().enableClasspathEntries(a.toFile())
                .filterClasspathElements(path -> !path.endsWith("b.jar")))).containsExactly("a.txt");
    }

    /** A URL filter rejects a jarfile named by a {@code Class-Path} manifest entry. */
    @Test
    public void aURLFilterRejectsAClassPathManifestEntry(@TempDir final Path tempDir) throws IOException {
        final var a = writeJar(tempDir.resolve("a.jar"), "a.txt", "b.jar");
        writeJar(tempDir.resolve("b.jar"), "b.txt", null);
        assertThat(scannedTextResources(new ClassGraph().enableClasspathEntries(a.toFile())
                .filterClasspathElementsByURL(url -> !url.getPath().endsWith("b.jar")))).containsExactly("a.txt");
    }

    /**
     * A URL filter is given a {@code Class-Path} manifest entry that is a URL as that URL, not as a file path, so a
     * filter can reject it before anything connects to it.
     */
    @Test
    public void aURLFilterIsGivenAClassPathManifestEntryURLAsItIs(@TempDir final Path tempDir) throws IOException {
        final var a = writeJar(tempDir.resolve("a.jar"), "a.txt", "http://example.invalid/remote.jar");
        final List<String> seen = new ArrayList<>();
        assertThat(scannedTextResources(
                new ClassGraph().enableClasspathEntries(a.toFile()).filterClasspathElementsByURL(url -> {
                    seen.add(url.toString());
                    return !"example.invalid".equals(url.getHost());
                }))).containsExactly("a.txt");
        assertThat(seen).contains("http://example.invalid/remote.jar");
    }

    /**
     * A jarfile that a filter rejects stays out of the scan when it is named directly and also named by another
     * jarfile's {@code Class-Path} manifest entry.
     */
    @Test
    public void aRejectedJarIsNotScannedWhenAnotherJarNamesIt(@TempDir final Path tempDir) throws IOException {
        final var a = writeJar(tempDir.resolve("a.jar"), "a.txt", "b.jar");
        final var b = writeJar(tempDir.resolve("b.jar"), "b.txt", null);
        assertThat(scannedTextResources(new ClassGraph().enableClasspathEntries(a.toFile(), b.toFile())
                .filterClasspathElements(path -> !path.endsWith("b.jar")))).containsExactly("a.txt");
    }

    /** A {@link ClassLoaderHandler} that declares the lib dir {@code lib/} for the classloader it is given. */
    private static final class LibDirHandler implements ClassLoaderHandler {
        /** The classloader to handle. */
        private final ClassLoader handled;

        /** The classpath element of that classloader. */
        private final Path classpathElement;

        /**
         * Constructor.
         *
         * @param handled
         *            the classloader to handle.
         * @param classpathElement
         *            the classpath element of that classloader.
         */
        LibDirHandler(final ClassLoader handled, final Path classpathElement) {
            this.handled = handled;
            this.classpathElement = classpathElement;
        }

        @Override
        public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
            return classLoaderClass == handled.getClass();
        }

        @Override
        public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
                final @Nullable ClassGraphLog log) {
            classLoaderOrder.add(classLoader, log);
        }

        @Override
        public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
                final @Nullable ClassGraphLog log) {
            classpathOrder.addClasspathEntry(classpathElement, classLoader, log);
        }

        @Override
        public List<String> getLibDirPrefixes() {
            return List.of("lib/");
        }
    }

    /** A classloader that no built-in {@link ClassLoaderHandler} knows about. */
    private static final class UnknownClassLoader extends ClassLoader {
        /** Constructor. */
        UnknownClassLoader() {
            // Use the bootstrap classloader as the parent, so that the classpath of the classloader that loaded
            // this test does not end up in the scan
            super(null);
        }
    }

    /** A path filter rejects a jarfile in the lib dir of a directory classpath element. */
    @Test
    public void aPathFilterRejectsALibDirJarOfADirectory(@TempDir final Path tempDir) throws IOException {
        final var dir = tempDir.resolve("exploded");
        writeJar(dir.resolve("lib/kept.jar"), "kept.txt", null);
        writeJar(dir.resolve("lib/rejected.jar"), "rejected.txt", null);
        final var classLoader = new UnknownClassLoader();
        assertThat(scannedTextResources(new ClassGraph().enableClassLoaders(classLoader)
                .registerClassLoaderHandler(new LibDirHandler(classLoader, dir))
                .filterClasspathElements(path -> !path.endsWith("rejected.jar")))).containsExactly("kept.txt");
    }

    /**
     * A path filter rejects a jarfile in the lib dir of a jarfile classpath element, by the path of the nested
     * jarfile, in which the outer jarfile is separated from the path within it by {@code "!/"}.
     */
    @Test
    public void aPathFilterRejectsALibDirJarOfAJarfile(@TempDir final Path tempDir) throws IOException {
        final var kept = Files.readAllBytes(writeJar(tempDir.resolve("kept.jar"), "kept.txt", null));
        final var rejected = Files.readAllBytes(writeJar(tempDir.resolve("rejected.jar"), "rejected.txt", null));
        final var outer = tempDir.resolve("outer.jar");
        try (var jarOut = new JarOutputStream(Files.newOutputStream(outer))) {
            jarOut.putNextEntry(new ZipEntry("lib/kept.jar"));
            jarOut.write(kept);
            jarOut.closeEntry();
            jarOut.putNextEntry(new ZipEntry("lib/rejected.jar"));
            jarOut.write(rejected);
            jarOut.closeEntry();
        }
        final var classLoader = new UnknownClassLoader();
        assertThat(scannedTextResources(new ClassGraph().enableClassLoaders(classLoader)
                .registerClassLoaderHandler(new LibDirHandler(classLoader, outer))
                .filterClasspathElements(path -> !path.endsWith("outer.jar!/lib/rejected.jar"))))
                .containsExactly("kept.txt");
    }
}
