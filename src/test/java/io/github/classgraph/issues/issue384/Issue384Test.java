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
package io.github.classgraph.issues.issue384;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.AbstractMap.SimpleEntry;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Test.
 */
public class Issue384Test {

    private static final String SCHEME = "customscheme";

    private static Map<String, String> remappedURLs = new HashMap<>();

    static {
        URL.setURLStreamHandlerFactory(protocol -> SCHEME.equals(protocol) ? new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(final URL url) throws IOException {
                // Record that the URL was remapped, so we know this custom URLStreamHandler was called
                final String newURL = "file:" + url.getPath();
                remappedURLs.put(url.toString(), newURL);
                // Replace scheme with "file://"
                return new URL(newURL).openConnection();
            }
        } : null);
    }

    /**
     * Test.
     */
    @Test
    public void issue384Test() throws MalformedURLException {
        final String filePath = Issue384Test.class.getClassLoader().getResource("nested-jars-level1.zip").getPath();
        final String customSchemeURL = SCHEME + ":" + filePath;
        final URL url = new URL(customSchemeURL);
        try (ScanResult scanResult = new ClassGraph().enableRemoteJarScanning().overrideClasspath(url).scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly("level2.jar");
            assertThat(remappedURLs.entrySet().iterator().next())
                    .isEqualTo(new SimpleEntry<>(customSchemeURL, "file:" + filePath));
        }
    }
}
