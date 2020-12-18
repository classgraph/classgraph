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
package io.github.classgraph.issues.issue468;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileNotFoundException;
import java.net.URL;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ResourceList;
import io.github.classgraph.ScanResult;

/**
 * Issue468Test.
 */
public class Issue468Test {
    /** Scan */
    private static void scan(final ClassGraph classGraph) {
        try (ScanResult scanResult = classGraph.scan()) {
            final ResourceList resources = scanResult.getAllResources();
            assertThat(resources.size()).isEqualTo(1);
            assertThat(resources.getPaths()).containsExactly("innerfile");
        }
    }

    /**
     * Test '+' signs in URLs.
     *
     * @throws Exception
     *             the exception
     */
    @Test
    public void testPlusSigns() throws Exception {
        final URL url = Issue468Test.class.getClassLoader().getResource("issue468/x+y/z+w.jar");
        if (url == null) {
            throw new FileNotFoundException();
        }
        scan(new ClassGraph().acceptPackagesNonRecursive("").overrideClasspath(url));
    }

    /**
     * Test "file:" URIs as strings, with and without the scheme.
     *
     * @throws Exception
     *             the exception
     */
    @Test
    public void testFileURIs() throws Exception {
        final URL url = Issue468Test.class.getClassLoader().getResource("issue468/x+y/z+w.jar");
        if (url == null) {
            throw new FileNotFoundException();
        }
        final String urlStr = url.toString();
        scan(new ClassGraph().acceptPackagesNonRecursive("").overrideClasspath(urlStr));
        assertThat(urlStr).startsWith("file:");
        scan(new ClassGraph().acceptPackagesNonRecursive("").overrideClasspath(urlStr.substring(5)));
    }
}
