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
package io.github.classgraph.issues.issue420;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import com.google.common.jimfs.Jimfs;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Issue193Test.
 */
public class Issue420Test {
    /**
     * Test.
     *
     * @throws IOException
     *             If an I/O exception occurred.
     * @throws URISyntaxException
     *             If a URI is bad.
     */
    @Test
    public void issue420Test() throws IOException, URISyntaxException {
        try (FileSystem memFs = Jimfs.newFileSystem()) {
            final Path jarPath = Paths
                    .get(getClass().getClassLoader().getResource("multi-release-jar.jar").toURI());
            final Path memFsPath = memFs.getPath("multi-release-jar.jar");
            final Path memFsCopyOfJar = Files.copy(jarPath, memFsPath);
            final URL memFsCopyOfJarURL = memFsCopyOfJar.toUri().toURL();

            try (URLClassLoader childClassLoader = new URLClassLoader(new URL[] { memFsCopyOfJarURL },
                    getClass().getClassLoader())) {
                final ClassGraph classGraph = new ClassGraph().enableURLScheme(memFsCopyOfJarURL.getProtocol())
                        .overrideClassLoaders(childClassLoader).ignoreParentClassLoaders().whitelistPackages("mrj")
                        .enableAllInfo();
                try (ScanResult scanResult = classGraph.scan()) {
                    assertThat(scanResult.getClassInfo("mrj.Cls")).isNotNull();
                }
            }
        }
    }
}
