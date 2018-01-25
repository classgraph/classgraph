/*
 * This file is part of FastClasspathScanner.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.issues.issue128;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchContentsProcessor;

public class Issue128Test {

    private static final String SITE = "https://github.com/lukehutch";

    private static final String JAR_URL = SITE + //
            "/fast-classpath-scanner/blob/master/src/test/resources/nested-jars-level1.zip?raw=true";

    private static final String NESTED_JAR_URL = //
            JAR_URL + "!level2.jar!level3.jar!classpath1/classpath2";

    @Test
    public void issue128Test() throws IOException {
        // Test a nested jar inside a jar fetched over HTTP
        final List<String> filesInsideLevel3 = new ArrayList<>();
        final URL jarURL = new URL(NESTED_JAR_URL);
        new FastClasspathScanner() //
                .overrideClassLoaders(new URLClassLoader(new URL[] { jarURL }, null))
                .matchFilenamePattern(".*", new FileMatchContentsProcessor() {
                    @Override
                    public void processMatch(final String relativePath, final byte[] fileContents)
                            throws IOException {
                        filesInsideLevel3.add(relativePath);
                    }
                }).scan();
        if (filesInsideLevel3.isEmpty()) {
            // If there were no files inside jar, it is possible that remote jar could not be downloaded
            try (InputStream is = jarURL.openStream()) {
                throw new RuntimeException("Able to download remote jar, but could not find files within jar");
            } catch (final IOException e) {
                System.err.println(
                        "Could not download remote jar, skipping test " + Issue128Test.class.getName() + ": " + e);
            }
        } else {
            assertThat(filesInsideLevel3).containsOnly("com/test/Test.java", "com/test/Test.class");
        }
    }
}
