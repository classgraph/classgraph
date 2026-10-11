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
import static org.assertj.core.api.Assertions.tuple;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;
import org.jspecify.annotations.Nullable;

/**
 * The package root prefixes that a registered {@link ClassLoaderHandler} declares are applied by the scan to the
 * classpath elements that handler found, whatever form the handler spells them in, and whatever the handler does
 * with the list after handing it over.
 */
public class RegisteredHandlerPrefixesTest {
    /** A classloader that no built-in {@link ClassLoaderHandler} knows about. */
    private static final class ListedClassLoader extends ClassLoader {
        /** The classpath entries this classloader loads from. */
        final List<Path> classpathEntries;

        /**
         * Constructor.
         *
         * @param classpathEntries
         *            the classpath entries this classloader loads from.
         */
        ListedClassLoader(final Path... classpathEntries) {
            // Use the bootstrap classloader as the parent, so that the classpath of the classloader that loaded
            // this test does not end up in the scan
            super(null);
            this.classpathEntries = List.of(classpathEntries);
        }
    }

    /**
     * A {@link ClassLoaderHandler} for {@link ListedClassLoader}, declaring the package root prefixes it is given.
     */
    private static final class ListedClassLoaderHandler implements ClassLoaderHandler {
        /** Gives the package root prefixes each time they are asked for. */
        private final Supplier<List<String>> packageRootPrefixes;

        /**
         * Constructor.
         *
         * @param packageRootPrefixes
         *            gives the package root prefixes each time they are asked for.
         */
        ListedClassLoaderHandler(final Supplier<List<String>> packageRootPrefixes) {
            this.packageRootPrefixes = packageRootPrefixes;
        }

        @Override
        public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
            return classLoaderClass == ListedClassLoader.class;
        }

        @Override
        public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
                final @Nullable ClassGraphLog log) {
            classLoaderOrder.add(classLoader, log);
        }

        @Override
        public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
                final @Nullable ClassGraphLog log) {
            for (final var classpathEntry : ((ListedClassLoader) classLoader).classpathEntries) {
                classpathOrder.addClasspathEntry(classpathEntry, classLoader, log);
            }
        }

        @Override
        public List<String> getPackageRootPrefixes() {
            return packageRootPrefixes.get();
        }
    }

    /**
     * Write a file, creating the directories above it.
     *
     * @param file
     *            the file to write.
     * @throws IOException
     *             if the file could not be written.
     */
    private static void writeFile(final Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
    }

    /**
     * A package root prefix that a handler spells without its trailing slash, with a leading slash or with
     * backslashes still names the same package root, in a directory and in a jarfile.
     *
     * @param prefix
     *            a spelling of the package root prefix {@code "classes/"}.
     */
    @ParameterizedTest
    @ValueSource(strings = { "classes/", "classes", "/classes/", "\\classes" })
    public void aPackageRootPrefixIsAppliedHoweverItIsSpelled(final String prefix, @TempDir final Path tempDir)
            throws IOException {
        final var dir = tempDir.resolve("d");
        writeFile(dir.resolve("classes/pkg/x.txt"));
        final var jar = tempDir.resolve("j.jar");
        try (var zipOut = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            zipOut.putNextEntry(new ZipEntry("classes/pkg/y.txt"));
            zipOut.write('y');
            zipOut.closeEntry();
        }

        try (var scanResult = new ClassGraph().enableClassLoaders(new ListedClassLoader(dir, jar))
                .registerClassLoaderHandler(new ListedClassLoaderHandler(() -> List.of(prefix))).scan()) {
            assertThat(scanResult.getAllResources())
                    .extracting(Resource::getPath, Resource::getPackageRootPrefix,
                            Resource::getPathRelativeToContainer)
                    .containsExactly(tuple("pkg/x.txt", "classes/", "classes/pkg/x.txt"),
                            tuple("pkg/y.txt", "classes/", "classes/pkg/y.txt"));
        }
    }

    /**
     * A handler that names the package root of a war as a {@link Path} in a zip filesystem, such as
     * {@code /WEB-INF/classes} of {@code app.war}, has that package root scanned, whether the war is a file of the
     * default filesystem or is itself inside another jarfile. A declared package root at the end of a classpath
     * element is stripped off, and what is left names the war, which is not a path in the zip filesystem the
     * handler's {@link Path} belongs to.
     *
     * @param nested
     *            whether the war is inside another jarfile.
     */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    public void aPackageRootNamedByAPathInAZipFilesystemIsScanned(final boolean nested, @TempDir final Path tempDir)
            throws IOException {
        final var outerPath = tempDir.resolve(nested ? "outer.jar" : "app.war");
        try (var outerFs = FileSystems.newFileSystem(outerPath, Map.of("create", "true"))) {
            if (nested) {
                try (var warFs = FileSystems.newFileSystem(outerFs.getPath("/app.war"), Map.of("create", "true"))) {
                    writeFile(warFs.getPath("/WEB-INF/classes/pkg/x.txt"));
                }
            } else {
                writeFile(outerFs.getPath("/WEB-INF/classes/pkg/x.txt"));
            }
        }

        try (var outerFs = FileSystems.newFileSystem(outerPath);
                var warFs = nested ? FileSystems.newFileSystem(outerFs.getPath("/app.war")) : outerFs;
                var scanResult = new ClassGraph()
                        .enableClassLoaders(new ListedClassLoader(warFs.getPath("/WEB-INF/classes")))
                        .registerClassLoaderHandler(new ListedClassLoaderHandler(() -> List.of("WEB-INF/classes/")))
                        .scan()) {
            assertThat(scanResult.getAllResources()).extracting(Resource::getPath, Resource::getPackageRootPrefix)
                    .containsExactly(tuple("pkg/x.txt", "WEB-INF/classes/"));
        }
    }

    /**
     * Each classpath element is scanned with the package root prefixes its handler declared when that element was
     * found, even when the handler hands over one list that it refills for each classloader.
     */
    @Test
    public void aRefilledPrefixListDoesNotChangeTheElementsAlreadyFound(@TempDir final Path tempDir)
            throws IOException {
        final var dirA = tempDir.resolve("a");
        writeFile(dirA.resolve("p0/a.txt"));
        final var dirB = tempDir.resolve("b");
        writeFile(dirB.resolve("p1/b.txt"));
        final List<String> prefixes = new ArrayList<>();
        final var calls = new int[1];
        final var handler = new ListedClassLoaderHandler(() -> {
            prefixes.clear();
            prefixes.add("p" + calls[0]++ + "/");
            return prefixes;
        });

        try (var scanResult = new ClassGraph()
                .enableClassLoaders(new ListedClassLoader(dirA), new ListedClassLoader(dirB))
                .registerClassLoaderHandler(handler).scan()) {
            assertThat(scanResult.getAllResources()).extracting(Resource::getPath, Resource::getPackageRootPrefix)
                    .containsExactly(tuple("a.txt", "p0/"), tuple("b.txt", "p1/"));
        }
    }
}
