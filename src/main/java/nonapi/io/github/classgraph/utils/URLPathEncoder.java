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
package nonapi.io.github.classgraph.utils;

import java.nio.charset.StandardCharsets;

/** A simple URL path encoder. */
public final class URLPathEncoder {

    /** Whether an ASCII character is URL-safe. */
    private static boolean[] safe = new boolean[256];

    static {
        for (int i = 'a'; i <= 'z'; i++) {
            safe[i] = true;
        }
        for (int i = 'A'; i <= 'Z'; i++) {
            safe[i] = true;
        }
        for (int i = '0'; i <= '9'; i++) {
            safe[i] = true;
        }
        // "safe" rule
        safe['$'] = safe['-'] = safe['_'] = safe['.'] = safe['+'] = true;
        // "extra" rule
        safe['!'] = safe['*'] = safe['\''] = safe['('] = safe[')'] = safe[','] = true;
        // Only include "/" from "fsegment" and "hsegment" rules (exclude ':', '@', '&' and '=' for safety)
        safe['/'] = true;
    }

    /** Hexadecimal digits. */
    private static final char[] HEXADECIMAL = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c',
            'd', 'e', 'f' };

    /** Valid classpath URL scheme prefixes. */
    private static final String[] SCHEME_PREFIXES = { "jrt:", "file:", "jar:file:", "jar:", "http:", "https:" };

    /**
     * Constructor.
     */
    private URLPathEncoder() {
        // Cannot be constructed
    }

    /**
     * Encode a URL path using percent-encoding. '/' is not encoded.
     * 
     * @param path
     *            The path to encode.
     * @return The encoded path.
     */
    public static String encodePath(final String path) {
        // Accept ':' if it is part of a scheme prefix
        int validColonPrefixLen = 0;
        for (final String scheme : SCHEME_PREFIXES) {
            if (path.startsWith(scheme)) {
                validColonPrefixLen = scheme.length();
                break;
            }
        }
        final byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
        final StringBuilder encodedPath = new StringBuilder(pathBytes.length * 3);
        for (int i = 0; i < pathBytes.length; i++) {
            final byte pathByte = pathBytes[i];
            final int b = pathByte & 0xff;
            if (safe[b] || (b == ':' && i < validColonPrefixLen)) {
                encodedPath.append((char) b);
            } else {
                encodedPath.append('%');
                encodedPath.append(HEXADECIMAL[(b & 0xf0) >> 4]);
                encodedPath.append(HEXADECIMAL[b & 0x0f]);
            }
        }
        return encodedPath.toString();
    }

    /**
     * Normalize a URL path, so that it can be fed into the URL or URI constructor.
     *
     * @param urlPath
     *            the URL path
     * @return the URL string
     */
    public static String normalizeURLPath(final String urlPath) {
        String urlPathNormalized = urlPath;
        if (!urlPathNormalized.startsWith("jrt:") && !urlPathNormalized.startsWith("http://")
                && !urlPathNormalized.startsWith("https://")) {
            // Any URL with the "jar:" prefix must have "/" after any "!"
            urlPathNormalized = urlPathNormalized.replace("/!", "!").replace("!/", "!").replace("!", "!/");
            // Prepend "jar:file:"
            if (!urlPathNormalized.startsWith("file:") && !urlPathNormalized.startsWith("jar:")) {
                urlPathNormalized = "file:" + urlPathNormalized;
            }
            if (urlPathNormalized.contains("!") && !urlPathNormalized.startsWith("jar:")) {
                urlPathNormalized = "jar:" + urlPathNormalized;
            }
        }
        return encodePath(urlPathNormalized);
    }
}
