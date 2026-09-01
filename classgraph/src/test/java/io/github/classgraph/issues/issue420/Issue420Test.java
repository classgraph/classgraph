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
package io.github.classgraph.issues.issue420;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.google.common.jimfs.Jimfs;

import io.github.classgraph.ClassGraph;

public class Issue420Test {
    /**
     * Test accessing a jar over Jimfs.
     *
     * @throws IOException
     *             If an I/O exception occurred.
     * @throws URISyntaxException
     *             If a URI is bad.
     */
    @Test
    public void testScanningFileBackedByFileSystem() throws IOException, URISyntaxException {
        try (var memFs = Jimfs.newFileSystem()) {
            final var jarPath = Path.of(getClass().getClassLoader().getResource("multi-release-jar.jar").toURI());
            final var memFsPath = memFs.getPath("multi-release-jar.jar");
            final var memFsCopyOfJar = Files.copy(jarPath, memFsPath);
            final var memFsCopyOfJarURL = memFsCopyOfJar.toUri().toURL();

            try (var childClassLoader = new URLClassLoader(new URL[] { memFsCopyOfJarURL },
                    getClass().getClassLoader())) {
                final var classGraph = new ClassGraph().enableClassLoaders(childClassLoader)
                        .ignoreParentClassLoaders().acceptPackages("mrj").enableClassInfo().enableFieldInfo()
                        .enableMethodInfo().enableAnnotationInfo().enableStaticFinalFieldConstantInitializerValues()
                        .ignoreClassVisibility().ignoreFieldVisibility().ignoreMethodVisibility();
                try (var scanResult = classGraph.scan()) {
                    assertThat(scanResult.getClassInfo("mrj.Cls")).isNotNull();
                }
            }
        }
    }

    /**
     * Write a class into a package hierarchy in Jimfs, then scan a directory of that filesystem.
     *
     * @param packageRootPrefix
     *            The directory the package hierarchy is written beneath, relative to the root of the filesystem.
     * @param dirToScan
     *            The directory to hand to the classloader as a classpath element, relative to the root of the
     *            filesystem.
     * @return whether the class was found.
     * @throws IOException
     *             If an I/O exception occurred.
     * @throws URISyntaxException
     *             If a URI is bad.
     */
    private boolean classIsFound(final String packageRootPrefix, final String dirToScan)
            throws IOException, URISyntaxException {
        try (var memFs = Jimfs.newFileSystem()) {
            final var packageName = "io.github.classgraph.issues.issue146";
            final var className = "CompiledWithJDK8";
            final var packagePath = packageName.replace('.', '/');
            final var classFullyQualifiedName = packageName + ".CompiledWithJDK8";
            final var classFilePath = classFullyQualifiedName.replace('.', '/') + ".class";
            final var jarPath = Path.of(Issue420Test.class.getClassLoader().getResource(classFilePath).toURI());
            final var memFsDirPath = memFs.getPath(packageRootPrefix + packagePath);
            Files.createDirectories(memFsDirPath);
            final var memFsFilePath = memFs.getPath(memFsDirPath + "/" + className + ".class");
            final var memFsCopyOfClassFile = Files.copy(jarPath, memFsFilePath);
            assertThat(Files.exists(memFsCopyOfClassFile));
            final var memFsDirToScanURL = memFs.getPath(dirToScan).toUri().toURL();
            try (var childClassLoader = new URLClassLoader(new URL[] { memFsDirToScanURL },
                    getClass().getClassLoader())) {
                final var classGraph = new ClassGraph().enableClassLoaders(childClassLoader)
                        .ignoreParentClassLoaders().acceptPackages(packageName).enableClassInfo().enableFieldInfo()
                        .enableMethodInfo().enableAnnotationInfo().enableStaticFinalFieldConstantInitializerValues()
                        .ignoreClassVisibility().ignoreFieldVisibility().ignoreMethodVisibility();
                try (var scanResult = classGraph.scan()) {
                    return scanResult.getClassInfo(classFullyQualifiedName) != null;
                }
            }
        }
    }

    /**
     * Test accessing a package hierarchy rooted at the default dir of "work/" in Jimfs.
     *
     * @throws IOException
     *             If an I/O exception occurred.
     * @throws URISyntaxException
     *             If a URI is bad.
     */
    @Test
    public void testScanningDirBackedByFileSystem() throws IOException, URISyntaxException {
        assertThat(classIsFound("", "")).isTrue();
    }

    /**
     * Test accessing a package hierarchy rooted at "work/classes/" in Jimfs. A {@link URLClassLoader} loads classes
     * only from the directories it was given, so the package hierarchy is only scannable when the classloader is
     * given "work/classes/" itself -- a directory named "classes" that turns up inside a classpath element is a
     * package, not a package root.
     *
     * @throws IOException
     *             If an I/O exception occurred.
     * @throws URISyntaxException
     *             If a URI is bad.
     */
    @Test
    public void testScanningDirBackedByFileSystemWithPackageRoot() throws IOException, URISyntaxException {
        assertThat(classIsFound("classes/", "classes")).isTrue();
        assertThat(classIsFound("classes/", "")).isFalse();
    }
}
