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
package io.github.classgraph.classpath;

import static io.github.classgraph.classpath.Locations.location;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that a classloader and the classloaders it delegates to are placed in the classpath in the order that the
 * classloader resolves classes in, since that is the order in which a class defined in more than one classpath
 * element masks the copies after it.
 */
public class ClassLoaderDelegationOrderTest {
    /**
     * A classloader that delegates to its parent first, which is the standard delegation order, contributes its
     * parent's classpath elements before its own.
     *
     * @param tempDir
     *            a temporary directory to create the classpath elements in
     * @throws IOException
     *             if the classpath elements could not be created
     */
    @Test
    public void aParentFirstClassLoaderContributesItsParentsClasspathFirst(@TempDir final Path tempDir)
            throws IOException {
        final var parentDir = Files.createDirectory(tempDir.resolve("parent"));
        final var childDir = Files.createDirectory(tempDir.resolve("child"));
        // URLClassLoader delegates to its parent first, so the parent's directory masks the child's
        try (var parent = new URLClassLoader(new URL[] { parentDir.toUri().toURL() }, /* parent = */ null);
                var child = new URLClassLoader(new URL[] { childDir.toUri().toURL() }, parent);
                var classpath = new ClasspathFinder().enableClassLoaders(child).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(parentDir), location(childDir));
        }
    }

    /**
     * The application classloader's own classpath entries must be placed where the application classloader sits in
     * the delegation order, not appended after every other classloader's entries.
     *
     * <p>
     * No public API exposes those entries: the application classloader is not a {@link URLClassLoader}, and its
     * {@code jdk.internal.loader.URLClassPath ucp} field can only be read with {@code --add-opens} or Narcissus, so
     * the {@code java.class.path} system property normally stands in for it. That property lists the same entries,
     * but it is a property rather than a classloader, so it is easy to read it at the wrong point.
     *
     * @param tempDir
     *            a temporary directory to create the classpath element in
     * @throws IOException
     *             if the classpath element could not be created
     */
    @Test
    public void theApplicationClassLoadersEntriesArePlacedAtItsPositionInTheDelegationOrder(
            @TempDir final Path tempDir) throws IOException {
        final var childDir = Files.createDirectory(tempDir.resolve("child"));
        // A child of the application classloader delegates to it first, so every one of the application
        // classloader's entries must precede the child's
        try (var child = new URLClassLoader(new URL[] { childDir.toUri().toURL() },
                ClassLoader.getSystemClassLoader());
                var classpath = new ClasspathFinder().enableClassLoaders(child).find()) {
            assertThat(classpath.getLocations()).endsWith(location(childDir));
        }
    }

    /**
     * {@link ClasspathFinder#ignoreParentClassLoaders()} leaves out only the classpath entries that a <i>parent</i>
     * classloader declares, so the application classloader's own entries are still searched when it is one of the
     * classloaders being searched rather than a parent of one -- which is the usual case, since the context
     * classloader is normally the application classloader.
     *
     * @throws IOException
     *             if the classpath could not be read
     */
    @Test
    public void ignoringParentClassLoadersKeepsTheApplicationClassLoadersOwnEntries() throws IOException {
        try (var classpath = new ClasspathFinder().enableClasspath().ignoreParentClassLoaders().find()) {
            // This test class is loaded from a java.class.path entry, so that entry must have been searched
            assertThat(classpath.getLocations()).isNotEmpty();
            assertThat(String.join(File.pathSeparator, classpath.getLocations())).contains("classgraph-classpath");
        }
    }

    /**
     * A classloader that declares itself equal to another classloader still contributes its own classpath entries.
     * Two classloaders that are equal are still two classloaders, and TomEE really does make an instance of one
     * classloader class equal to the instance of another that it delegates to (#515).
     */
    static class EqualToEveryOtherClassLoader extends URLClassLoader {
        EqualToEveryOtherClassLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof EqualToEveryOtherClassLoader;
        }

        @Override
        public int hashCode() {
            return 0;
        }
    }

    /**
     * A classloader that is equal to another classloader is not the same classloader, so both are searched, and the
     * classpath entries of both are found.
     *
     * @param tempDir
     *            a temporary directory to create the classpath elements in
     * @throws IOException
     *             if the classpath elements could not be created
     */
    @Test
    public void aClassLoaderThatIsEqualToAnotherIsStillSearched(@TempDir final Path tempDir) throws IOException {
        final var parentDir = Files.createDirectory(tempDir.resolve("parent"));
        final var childDir = Files.createDirectory(tempDir.resolve("child"));
        try (var parent = new EqualToEveryOtherClassLoader(new URL[] { parentDir.toUri().toURL() },
                /* parent = */ null);
                var child = new EqualToEveryOtherClassLoader(new URL[] { childDir.toUri().toURL() }, parent);
                var classpath = new ClasspathFinder().enableClassLoaders(child).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(parentDir), location(childDir));
        }
    }
}
