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
package io.github.lukehutch.fastclasspathscanner.utils;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

public class ClasspathUtils {
    /**
     * Can be used to get the URL of a classpath resource whose classpath element and relative path has been passed
     * into a MatchProcessor.
     * 
     * @param classpathElt
     *            The classpath element (a directory or jarfile), as a File object.
     * @param relativePath
     *            The relative path within the classpath element, with '/' as the path delimiter character, and
     *            without an initial or final delimiter character.
     * @return The URL, in the form "file:/classpath/elt/path/followed/by/relative/path" or
     *         "jar:file:/classpath/elt/path.jar!/followed/by/relative/path".
     */
    public static URL getClasspathResourceURL(final File classpathElt, final String relativePath) {
        final boolean classpathEltIsJar = classpathElt.isFile();
        String classpathEltURL;
        try {
            classpathEltURL = classpathElt.toURI().toURL().toString();
            if (!classpathEltIsJar && !classpathEltURL.endsWith("/")) {
                // Ensure trailing slash for directory classpath entries
                classpathEltURL += "/";
            }
        } catch (final MalformedURLException e) {
            // Should not happen
            throw new RuntimeException(e);
        }
        final String relativePathEncoded = URLPathEncoder.encodePath(relativePath);
        final String url = classpathEltIsJar ? "jar:" + classpathEltURL + "!/" + relativePathEncoded
                : classpathEltURL + relativePathEncoded;
        try {
            return new URL(url);
        } catch (final MalformedURLException e) {
            // Should not happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Return true if a file exists and can be read.
     */
    public static boolean canRead(final File file) {
        try {
            return file.canRead();
        } catch (final SecurityException e) {
            return false;
        }
    }
}
