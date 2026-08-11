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
package io.github.classgraph.issues.issue193;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;
import org.ops4j.pax.url.mvn.MavenResolvers;

import io.github.classgraph.ClassGraph;

public class Issue193Test {
    /**
     * Scala companion objects, whose superclass differs from that of the companion class, are scanned without
     * throwing.
     *
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    @Test
    public void issue193Test() throws IOException {
        // Resolve and download scala-library
        final var resolvedFile = MavenResolvers.createMavenResolver(null, null).resolve("org.scala-lang",
                "scala-library", null, null, "2.12.1");
        assertThat(resolvedFile).isFile();

        // Scan the classpath, using a new custom class loader -- used to throw an exception for Stack, since
        // companion object inherits from different class
        try (var classLoader = new URLClassLoader(new URL[] { resolvedFile.toURI().toURL() }, null);
                var scanResult = new ClassGraph() //
                        .acceptPackages("scala.collection.immutable") //
                        .overrideClassLoaders(classLoader) //
                        .scan()) {
            final var classes = scanResult //
                    .getAllClasses() //
                    .filter(ci -> ci.getName().endsWith("$")) //
                    .getNames();
            assertThat(classes).contains("scala.collection.immutable.Stack$");
        }
    }
}
