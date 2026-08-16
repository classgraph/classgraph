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
package io.github.classgraph.issues.issue128;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

public class Issue128Test {
    /** The site. */
    private static final String SITE = "https://raw.githubusercontent.com/classgraph";

    // The commit is pinned, rather than naming a branch, so that renaming a branch or moving this resource
    // within the repo cannot silently stop this test from fetching anything
    /** The jar URL. */
    private static final String JAR_URL = SITE + //
            "/classgraph/329d5049fa573cf4d992be684e3eaf40409da134/src/test/resources/nested-jars-level1.zip";

    /** The nested jar URL. */
    private static final String NESTED_JAR_URL = //
            JAR_URL + "!level2.jar!level3.jar!classpath1/classpath2";

    /** How long to wait for the availability check to connect, and then to answer, in milliseconds. */
    private static final int TIMEOUT_MILLIS = 5000;

    /**
     * A jar nested inside a jar that is fetched over HTTP is scanned.
     *
     * @throws Exception
     *             the exception
     */
    @Test
    public void issue128Test() throws Exception {
        // Test a nested jar inside a jar fetched over HTTP
        final var jarURL = new URL(NESTED_JAR_URL);
        try (var scanResult = new ClassGraph().overrideClassLoaders(new URLClassLoader(new URL[] { jarURL }, null))
                .enableRemoteJarScanning().scan()) {
            final var filesInsideLevel3 = scanResult.getAllResources().getPaths();
            if (!filesInsideLevel3.isEmpty()) {
                assertThat(filesInsideLevel3).containsOnly("com/test/Test.java", "com/test/Test.class");
                return;
            }
        }
        // Nothing was found inside the jar. Either the jar could not be fetched, which says nothing about
        // ClassGraph and must not fail the build, or it was fetched and the scan is at fault. Ask the server
        // which it was. Only JAR_URL names something the server has: NESTED_JAR_URL addresses a path inside the
        // jar, so fetching that always gives a 404, whatever state the jar itself is in.
        int responseCode;
        try {
            final var connection = (HttpURLConnection) new URL(JAR_URL).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            try {
                responseCode = connection.getResponseCode();
            } finally {
                connection.disconnect();
            }
        } catch (final IOException | SecurityException e) {
            abort("The remote jar could not be reached, so the scan had nothing to find: " + e);
            return;
        }
        switch (responseCode) {
        case HttpURLConnection.HTTP_OK:
            fail("The remote jar can be fetched, but scanning it found no files inside " + NESTED_JAR_URL);
            break;
        case HttpURLConnection.HTTP_NOT_FOUND:
        case HttpURLConnection.HTTP_GONE:
            // The commit is pinned, so the jar can only have gone if the repository history was rewritten
            fail("The pinned jar has gone from " + JAR_URL + " (HTTP " + responseCode
                    + "), so this test needs a new one");
            break;
        default:
            // Anything else (a redirect, a rate limit, a server error) is the server having a bad day
            abort("The remote jar could not be fetched (HTTP " + responseCode
                    + "), so the scan had nothing to find");
            break;
        }
    }
}
