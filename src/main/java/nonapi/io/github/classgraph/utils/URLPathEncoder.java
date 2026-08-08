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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import nonapi.io.github.classgraph.utils.VersionFinder.OperatingSystem;

/** A simple URL path encoder. */
public final class URLPathEncoder {

    /** Whether an ASCII character is URL-safe. */
    private static final boolean[] safe = new boolean[256];

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
        // Only include "/" from "fsegment" and "hsegment" rules (exclude ':', '@', '&'
        // and '=' for safety)
        safe['/'] = true;
        // Also allow '+' characters (#468)
        // safe['+'] = true;
    }

    /** Hexadecimal digits. */
    private static final char[] HEXADECIMAL = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd',
            'e', 'f' };

    /** Valid classpath URL scheme prefixes. */
    private static final String[] SCHEME_PREFIXES = { "jrt:", "file:", "jar:file:", "jar:", "http:", "https:" };

    /**
     * Constructor.
     */
    private URLPathEncoder() {
        // Cannot be constructed
    }

    /**
     * Unescape chars in a URL. URLDecoder.decode is broken:
     * https://bugs.openjdk.java.net/browse/JDK-8179507
     */
    private static void unescapeChars(final String str, final boolean isQuery, final ByteArrayOutputStream buf) {
        if (str.isEmpty()) {
            return;
        }
        for (int chrIdx = 0, len = str.length(); chrIdx < len; chrIdx++) {
            final var c = str.charAt(chrIdx);
            if (c == '%') {
                // Decode %-escaped char sequence, e.g. %5D, ignoring a truncated %-seq at
                // the end of the string
                if (chrIdx <= len - 3) {
                    final var c1 = str.charAt(++chrIdx);
                    final var digit1 = c1 >= '0' && c1 <= '9' ? (c1 - '0')
                            : c1 >= 'a' && c1 <= 'f' ? (c1 - 'a' + 10) : c1 >= 'A' && c1 <= 'F' ? (c1 - 'A' + 10) : -1;
                    final var c2 = str.charAt(++chrIdx);
                    final var digit2 = c2 >= '0' && c2 <= '9' ? (c2 - '0')
                            : c2 >= 'a' && c2 <= 'f' ? (c2 - 'a' + 10) : c2 >= 'A' && c2 <= 'F' ? (c2 - 'A' + 10) : -1;
                    if (digit1 < 0 || digit2 < 0) {
                        try {
                            buf.write(str.substring(chrIdx - 2, chrIdx + 1).getBytes(StandardCharsets.UTF_8));
                        } catch (final IOException e) {
                            // Ignore
                        }
                    } else {
                        buf.write((byte) ((digit1 << 4) | digit2));
                    }
                }
            } else if (isQuery && c == '+') {
                buf.write((byte) ' ');
            } else if (c <= 0x7f) {
                buf.write((byte) c);
            } else {
                try {
                    buf.write(Character.toString(c).getBytes(StandardCharsets.UTF_8));
                } catch (final IOException e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Unescape a URL segment, and turn it from UTF-8 bytes into a Java string.
     *
     * @param str the str
     * @return the string
     */
    public static String decodePath(final String str) {
        final var queryIdx = str.indexOf('?');
        final var partBeforeQuery = queryIdx < 0 ? str : str.substring(0, queryIdx);
        final var partFromQuery = queryIdx < 0 ? "" : str.substring(queryIdx);
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        unescapeChars(partBeforeQuery, /* isQuery = */ false, buf);
        unescapeChars(partFromQuery, /* isQuery = */ true, buf);
        return buf.toString(StandardCharsets.UTF_8);
    }

    /**
     * Encode a URL path using percent-encoding. '/' is not encoded.
     * 
     * @param path The path to encode.
     * @return The encoded path.
     */
    public static String encodePath(final String path) {
        // Accept ':' if it is part of a scheme prefix
        var validColonPrefixLen = 0;
        for (final String scheme : SCHEME_PREFIXES) {
            if (path.startsWith(scheme)) {
                validColonPrefixLen = scheme.length();
                break;
            }
        }
        // Also accept ':' after a Windows drive letter
        if (VersionFinder.OS == OperatingSystem.Windows) {
            var i = validColonPrefixLen;
            if (i < path.length() && path.startsWith("///", i)) {
                i += "///".length();
            }
            if (i < path.length() - 1 && Character.isLetter(path.charAt(i)) && path.charAt(i + 1) == ':') {
                validColonPrefixLen = i + 2;
            }
        }

        // Apply URL encoding rules to rest of path
        final var pathBytes = path.getBytes(StandardCharsets.UTF_8);
        final StringBuilder encodedPath = new StringBuilder(pathBytes.length * 3);
        for (var i = 0; i < pathBytes.length; i++) {
            final var pathByte = pathBytes[i];
            final var b = pathByte & 0xff;
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
     * @param urlPath the URL path
     * @return the URL string
     */
    public static String normalizeURLPath(final String urlPath) {
        var urlPathNormalized = urlPath;
        if (!urlPathNormalized.startsWith("jrt:") && !urlPathNormalized.startsWith("http://")
                && !urlPathNormalized.startsWith("https://")) {

            // Strip "jar:" and/or "file:", if already present
            if (urlPathNormalized.startsWith("jar:")) {
                urlPathNormalized = urlPathNormalized.substring(4);
            }
            if (urlPathNormalized.startsWith("file:")) {
                urlPathNormalized = urlPathNormalized.substring(5);
                // "file:" may be followed by an empty authority, i.e. "file://" or "file:///".
                // Collapse the run
                // of leading slashes down to one, so that the "file://" prefix added below
                // cannot produce a path
                // with a run of slashes in it, e.g. "file://///tmp/x.jar".
                var numLeadingSlashes = 0;
                while (numLeadingSlashes < urlPathNormalized.length()
                        && urlPathNormalized.charAt(numLeadingSlashes) == '/') {
                    numLeadingSlashes++;
                }
                if (numLeadingSlashes > 1) {
                    urlPathNormalized = urlPathNormalized.substring(numLeadingSlashes - 1);
                }
            }

            // On Windows, remove drive prefix from path, if present (otherwise the ':'
            // after the drive
            // letter will be escaped as %3A)
            var windowsDrivePrefix = "";
            if (VersionFinder.OS == OperatingSystem.Windows) {
                if (urlPathNormalized.length() >= 2 && Character.isLetter(urlPathNormalized.charAt(0))
                        && urlPathNormalized.charAt(1) == ':') {
                    // Path of form "C:/xyz"
                    windowsDrivePrefix = urlPathNormalized.substring(0, 2);
                    urlPathNormalized = urlPathNormalized.substring(2);
                } else if (urlPathNormalized.length() >= 3 && urlPathNormalized.charAt(0) == '/'
                        && Character.isLetter(urlPathNormalized.charAt(1)) && urlPathNormalized.charAt(2) == ':') {
                    // Path of form "/C:/xyz"
                    windowsDrivePrefix = urlPathNormalized.substring(1, 3);
                    urlPathNormalized = urlPathNormalized.substring(3);
                }
            }

            // Any URL containing "!" segments must have "/" after "!" for the "jar:" URL
            // scheme to work
            urlPathNormalized = urlPathNormalized.replace("/!", "!").replace("!/", "!").replace("!", "!/");

            // Prepend "file:///" to absolute paths and "file:" to relative paths
            if (windowsDrivePrefix.isEmpty()) {
                // There is no Windows drive
                if (urlPathNormalized.startsWith("/")) {
                    // Absolute path: file:///xyz
                    urlPathNormalized = "file://" + urlPathNormalized;
                } else {
                    // Relative path: file:xyz
                    urlPathNormalized = "file:" + urlPathNormalized;
                }
            } else {
                // There is a Windows drive, path must be absolute
                urlPathNormalized = "file:///" + windowsDrivePrefix + urlPathNormalized;
            }

            // Prepend "jar:" if path contains a "!" segment
            if (urlPathNormalized.contains("!") && !urlPathNormalized.startsWith("jar:")) {
                urlPathNormalized = "jar:" + urlPathNormalized;
            }
        }
        return encodePath(urlPathNormalized);
    }
}
