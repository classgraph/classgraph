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
package io.github.classgraph.issues.issue245;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Issue245Test.
 */
public class Issue245Test {
    /**
     * Test custom package root.
     */
    @Test
    public void testCustomPackageRoot() {
        final ClassLoader classLoader = Issue245Test.class.getClassLoader();
        final String jarName = "spring-boot-fully-executable-jar.jar";
        final URL jarURL = classLoader.getResource(jarName);

        try (ScanResult scanResult = new ClassGraph() //
                .overrideClasspath(jarURL.toString() + "!/META-INF/maven") //
                .whitelistPaths("org.springframework/gs-spring-boot") //
                .disableNestedJarScanning() //
                .scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsOnly(
                    "org.springframework/gs-spring-boot/pom.xml",
                    "org.springframework/gs-spring-boot/pom.properties");
        }
    }
}
