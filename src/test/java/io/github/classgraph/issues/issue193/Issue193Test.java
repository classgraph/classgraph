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
 * Copyright (c) 2019 Luke Hutchison
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
package io.github.classgraph.issues.issue193;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.ops4j.pax.url.mvn.MavenResolvers;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Issue193Test.
 */
public class Issue193Test {
    /**
     * Issue 193 test.
     *
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    @Test
    public void issue193Test() throws IOException {
        // Resolve and download scala-library
        final File resolvedFile = MavenResolvers.createMavenResolver(null, null).resolve("org.scala-lang",
                "scala-library", null, null, "2.12.1");
        assertThat(resolvedFile).isFile();

        // Create a new custom class loader
        final ClassLoader classLoader = new URLClassLoader(new URL[] { resolvedFile.toURI().toURL() }, null);

        // Scan the classpath -- used to throw an exception for Stack, since companion object inherits
        // from different class
        try (ScanResult scanResult = new ClassGraph() //
                .whitelistPackages("scala.collection.immutable") //
                .overrideClassLoaders(classLoader) //
                .scan()) {
            final List<String> classes = scanResult //
                    .getAllClasses() //
                    .filter(ci -> ci.getName().endsWith("$")) //
                    .getNames();
            assertThat(classes).contains("scala.collection.immutable.Stack$");
        }
    }
}
