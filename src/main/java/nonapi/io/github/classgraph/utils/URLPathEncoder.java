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
package nonapi.io.github.classgraph.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import nonapi.io.github.classgraph.utils.VersionFinder.OperatingSystem;

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
        // Also allow  '+' characters (#468)
        //safe['+'] = true;
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
     * Unescape chars in a URL. URLDecoder.decode is broken: https://bugs.openjdk.java.net/browse/JDK-8179507
     *
     * @param str
     *            the string to unescape
     * @param isQuery
     *            true if the string comes from the query section of a URL, where '+' means space
     * @param buf
     *            the buffer to write the unescaped UTF-8 bytes to
     */
    private static void unescapeChars(final String str, final boolean isQuery, final ByteArrayOutputStream buf) {
        if (str.isEmpty()) {
            return;
        }
        for (int chrIdx = 0, len = str.length(); chrIdx < len; chrIdx++) {
            final char c = str.charAt(chrIdx);
            if (c == '%') {
                // Decode %-escaped char sequence, e.g. %5D
                if (chrIdx <= len - 3) {
                    final char c1 = str.charAt(++chrIdx);
                    final int digit1 = c1 >= '0' && c1 <= '9' ? (c1 - '0')
                            : c1 >= 'a' && c1 <= 'f' ? (c1 - 'a' + 10)
                                    : c1 >= 'A' && c1 <= 'F' ? (c1 - 'A' + 10) : -1;
                    final char c2 = str.charAt(++chrIdx);
                    final int digit2 = c2 >= '0' && c2 <= '9' ? (c2 - '0')
                            : c2 >= 'a' && c2 <= 'f' ? (c2 - 'a' + 10)
                                    : c2 >= 'A' && c2 <= 'F' ? (c2 - 'A' + 10) : -1;
                    if (digit1 < 0 || digit2 < 0) {
                        try {
                            buf.write(str.substring(chrIdx - 2, chrIdx + 1).getBytes(StandardCharsets.UTF_8));
                        } catch (final IOException e) {
                            // Ignore
                        }
                    } else {
                        buf.write((byte) ((digit1 << 4) | digit2));
                    }
                } else {
                    // A '%' too close to the end of the string to be followed by two hexadecimal digits does not
                    // introduce an escape sequence, so write it out as it is, the same as the branch above does for
                    // a '%' that is not followed by two hexadecimal digits. The following characters are then
                    // handled by the rest of this loop, so nothing is dropped
                    buf.write((byte) c);
                }
            } else if (isQuery && c == '+') {
                buf.write((byte) ' ');
            } else if (c <= 0x7f) {
                buf.write((byte) c);
            } else {
                // A character outside the Basic Multilingual Plane is stored as a surrogate pair, and the two
                // surrogates only encode as UTF-8 together -- encoding each of them on its own produces '?' for
                // both, silently renaming the path
                final int codePoint = str.codePointAt(chrIdx);
                chrIdx += Character.charCount(codePoint) - 1;
                try {
                    buf.write(new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8));
                } catch (final IOException e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Unescape a URL segment, and turn it from UTF-8 bytes into a Java string.
     *
     * @param str
     *            the str
     * @return the string
     */
    public static String decodePath(final String str) {
        final int queryIdx = str.indexOf('?');
        final String partBeforeQuery = queryIdx < 0 ? str : str.substring(0, queryIdx);
        final String partFromQuery = queryIdx < 0 ? "" : str.substring(queryIdx);
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        unescapeChars(partBeforeQuery, /* isQuery = */ false, buf);
        unescapeChars(partFromQuery, /* isQuery = */ true, buf);
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
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
        // Also accept ':' after a Windows drive letter
        if (VersionFinder.OS == OperatingSystem.Windows) {
            int i = validColonPrefixLen;
            if (i < path.length() && path.startsWith("///", i)) {
                i += "///".length();
            }
            if (i < path.length() - 1 && Character.isLetter(path.charAt(i)) && path.charAt(i + 1) == ':') {
                validColonPrefixLen = i + 2;
            }
        }

        // Apply URL encoding rules to rest of path
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
     * Test whether a character is a hexadecimal digit.
     *
     * @param c
     *            the character.
     * @return true if the character is a hexadecimal digit.
     */
    private static boolean isHexDigit(final char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * Percent-encode characters that a {@link URI} cannot hold, preserving existing percent-escape sequences.
     *
     * @param url
     *            the URL.
     * @return the URL, with the characters a URI cannot hold percent-encoded.
     */
    private static String encodeURL(final String url) {
        final StringBuilder buf = new StringBuilder(url.length() + 16);
        for (int i = 0; i < url.length(); i++) {
            final char c = url.charAt(i);
            if (c == '%' && i < url.length() - 2 && isHexDigit(url.charAt(i + 1))
                    && isHexDigit(url.charAt(i + 2))) {
                // An escape that is already written is part of the URL, and is not encoded a second time
                buf.append(url, i, i + 3);
                i += 2;
            } else if (c > ' ' && c < 0x7f && "\"<>\\^`{|}%".indexOf(c) < 0) {
                buf.append(c);
            } else {
                // Percent-encode the UTF-8 bytes of everything a URI cannot hold. A character outside the Basic
                // Multilingual Plane is stored as a surrogate pair, and the two surrogates only encode as UTF-8
                // together, so the whole code point is taken rather than one char
                final int codePoint = url.codePointAt(i);
                i += Character.charCount(codePoint) - 1;
                for (final byte b : new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8)) {
                    buf.append('%').append(HEXADECIMAL[(b & 0xf0) >> 4]).append(HEXADECIMAL[b & 0x0f]);
                }
            }
        }
        return buf.toString();
    }

    /**
     * Move the server of a Windows UNC {@code "file:"} URI from the authority component into the path component
     * ({@code file:////server/share/...}) so that it round-trips through {@link java.net.URL}.
     *
     * @param uri
     *            the URI
     * @return the URI, with any UNC server moved from the authority into the path
     */
    public static URI moveUNCServerIntoPath(final URI uri) {
        final String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty() || !"file".equals(uri.getScheme())) {
            return uri;
        }
        final String path = uri.getRawPath();
        return URI.create("file:////" + authority + (path == null ? "" : path));
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
        boolean hasNestedJarSeparator = false;
        if (JarUtils.URL_SCHEME_PATTERN.matcher(urlPathNormalized).matches()
                && !urlPathNormalized.startsWith("file:") && !urlPathNormalized.startsWith("jar:")) {
            // A scheme other than "file:" does not name a file -- "jrt:", "http:" and "https:" do not, and nor does
            // a custom scheme, which names something only its URL handler can reach -- so there is no file path to
            // normalize, and what is left is still a URL, which is already percent-encoded: encoding it again would
            // turn "%20" into "%2520" and the colon before a port number into "%3a", naming a resource on a host
            // that does not exist. Only what a URI cannot hold is escaped
            urlPathNormalized = encodeURL(urlPathNormalized);
        } else {

            // Strip "jar:" and/or "file:", if already present
            if (urlPathNormalized.startsWith("jar:")) {
                urlPathNormalized = urlPathNormalized.substring(4);
            }
            if (urlPathNormalized.startsWith("file:")) {
                urlPathNormalized = urlPathNormalized.substring(5);
                // "file:" may be followed by an authority, which is not part of the path. Drop the two slashes
                // that introduce it, so that the "file://" prefix added below cannot produce a path with a run of
                // slashes in it, e.g. "file://///tmp/x.jar". Exactly two slashes are dropped, so that the
                // remaining "//" of a UNC path is kept: "file:////server/share/x" leaves "//server/share/x",
                // which names the share it came from, rather than the local path "/server/share/x", which does not
                if (urlPathNormalized.startsWith("///")) {
                    urlPathNormalized = urlPathNormalized.substring(2);
                } else if (urlPathNormalized.startsWith("//")) {
                    // Only two slashes, so what follows is either an authority naming the local machine or, in the
                    // spelling some classloaders use, the path itself. Either way one slash is enough
                    urlPathNormalized = urlPathNormalized.substring(1);
                }
            }

            // A '!' is only a nested jar separator if it really separates a jarfile from a path within it -- it is
            // an ordinary filename character otherwise, and a path such as "/dir!bang/x.jar" must not be rewritten
            // to "/dir!/bang/x.jar", which names a different file and cannot be opened. Every separator that is
            // one has to be spelled "!/" here, since that is what the "jar:" URL scheme requires. This is done
            // before the Windows drive prefix is stripped below, since the separator is found by testing whether
            // the path before the '!' names a file, which needs the drive letter
            // #903
            hasNestedJarSeparator = JarUtils.indexOfNestedJarSeparator(urlPathNormalized) >= 0;
            if (hasNestedJarSeparator) {
                urlPathNormalized = JarUtils.toJarUrlSeparators(urlPathNormalized);
            }

            // On Windows, remove drive prefix from path, if present (otherwise the ':' after the drive
            // letter will be escaped as %3A)
            String windowsDrivePrefix = "";
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

            // Prepend "jar:" if the path really does name something nested inside a jarfile
            if (hasNestedJarSeparator && !urlPathNormalized.startsWith("jar:")) {
                urlPathNormalized = "jar:" + urlPathNormalized;
            }

            urlPathNormalized = encodePath(urlPathNormalized);
        }
        return urlPathNormalized;
    }
}
