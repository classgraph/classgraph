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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/** Tests that a classloader that is equal to another classloader is still searched in its own right. */
public class EqualClassLoadersTest {
    /**
     * A classloader that declares itself equal to every other classloader of its class. TomEE really does make an
     * instance of one classloader class equal to the instance of another that it delegates to (#515).
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
        final Path parentDir = Files.createDirectory(tempDir.resolve("parent"));
        final Path childDir = Files.createDirectory(tempDir.resolve("child"));
        try (URLClassLoader parent = new EqualToEveryOtherClassLoader(new URL[] { parentDir.toUri().toURL() },
                /* parent = */ null);
                URLClassLoader child = new EqualToEveryOtherClassLoader(new URL[] { childDir.toUri().toURL() },
                        parent);
                ScanResult scanResult = new ClassGraph().overrideClassLoaders(child).scan()) {
            // URLClassLoader delegates to its parent first, so the parent's directory comes first
            assertThat(scanResult.getClasspath()).isEqualTo(parentDir.toFile().getPath() + File.pathSeparator
                    + childDir.toFile().getPath());
        }
    }
}
