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

import static org.assertj.core.api.Assertions.assertThat;

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
                var classpath = new ClasspathFinder().overrideClassLoaders(child).find()) {
            assertThat(classpath.getLocations()).containsExactly(location(parentDir), location(childDir));
        }
    }

    /**
     * The location that a directory is reported as.
     *
     * @param dir
     *            the directory
     * @return the location
     */
    private static String location(final Path dir) {
        return dir.toFile().getPath().replace(java.io.File.separatorChar, '/');
    }
}
