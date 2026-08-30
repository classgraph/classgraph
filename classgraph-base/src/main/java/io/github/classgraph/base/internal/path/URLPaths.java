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
package io.github.classgraph.base.internal.path;

import io.github.classgraph.base.internal.utils.VersionFinder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

import io.github.classgraph.base.internal.utils.VersionFinder.OperatingSystem;

/**
 * The URL end of a path: percent-encoding and decoding the path part of a URL, normalizing it, and reading and
 * normalizing the scheme that a path may begin with.
 */
public final class URLPaths {
    /**
     * Check if a path has a URL scheme at the beginning. Require at least 2 chars in a URL scheme, so that Windows
     * drive designations don't get treated as URL schemes.
     */
    public static final Pattern URL_SCHEME_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9+\\-.]+[:].*");

    /** A URL scheme on its own, without the trailing {@code ':'}. */
    private static final Pattern URL_SCHEME_NAME_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9+\\-.]+");

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
    private URLPaths() {
        // Cannot be constructed
    }

    /**
     * Check that a string is a URL scheme name, and lowercase it, since URL schemes are case-insensitive but are
     * matched in lowercase.
     *
     * @param scheme
     *            the scheme, e.g. "http", without the trailing ':'.
     * @return the scheme, lowercased.
     * @throws IllegalArgumentException
     *             if the scheme is shorter than two characters, or is not a valid URL scheme.
     */
    public static String normalizeURLScheme(final String scheme) {
        // The scheme is validated, rather than simply lowercased, because a scheme registered in a form that can
        // never match, e.g. with a trailing ':', would otherwise silently fail to enable anything
        if (scheme.length() < 2) {
            // A one-character scheme cannot be told apart from a Windows drive letter
            throw new IllegalArgumentException("URL schemes must contain at least two characters");
        }
        if (!URL_SCHEME_NAME_PATTERN.matcher(scheme).matches()) {
            throw new IllegalArgumentException("Not a valid URL scheme: \"" + scheme + "\"");
        }
        return scheme.toLowerCase(Locale.ROOT);
    }

    /**
     * Unescape chars in a URL. URLDecoder.decode is broken: https://bugs.openjdk.java.net/browse/JDK-8179507
     *
     * @param str
     *            the string to unescape
     * @param isQuery
     *            true if the string is a query string, in which case {@code '+'} is decoded as a space
     * @param buf
     *            the buffer to write the decoded bytes to
     */
    private static void unescapeChars(final String str, final boolean isQuery, final ByteArrayOutputStream buf) {
        if (str.isEmpty()) {
            return;
        }
        for (int chrIdx = 0, len = str.length(); chrIdx < len; chrIdx++) {
            final var c = str.charAt(chrIdx);
            if (c == '%') {
                // Decode %-escaped char sequence, e.g. %5D
                if (chrIdx <= len - 3) {
                    final var c1 = str.charAt(++chrIdx);
                    final var digit1 = c1 >= '0' && c1 <= '9' ? (c1 - '0')
                            : c1 >= 'a' && c1 <= 'f' ? (c1 - 'a' + 10)
                                    : c1 >= 'A' && c1 <= 'F' ? (c1 - 'A' + 10) : -1;
                    final var c2 = str.charAt(++chrIdx);
                    final var digit2 = c2 >= '0' && c2 <= '9' ? (c2 - '0')
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
                final var codePoint = str.codePointAt(chrIdx);
                chrIdx += Character.charCount(codePoint) - 1;
                try {
                    buf.write(Character.toString(codePoint).getBytes(StandardCharsets.UTF_8));
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
     * @param path
     *            The path to encode.
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
     * Test whether a character is a hexadecimal digit, i.e. whether it can be one of the two digits of a percent
     * escape.
     *
     * @param c
     *            the character.
     * @return true if the character is a hexadecimal digit.
     */
    private static boolean isHexDigit(final char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * Percent-encode only the characters that a URI cannot hold, leaving the rest of a URL as it is written.
     *
     * <p>
     * This is what a path that is still a URL needs, as opposed to a file path: a URL is already percent-encoded,
     * so {@link #encodePath(String)} would encode it a second time and turn {@code "%20"} into {@code "%2520"},
     * naming a resource that does not exist. It would also escape the URL's own syntax -- the colon before a port
     * number becomes {@code "%3a"}, which makes the authority name a host that does not exist, and the {@code '?'}
     * of a query string becomes {@code "%3f"}.
     *
     * <p>
     * An escape that is already written is therefore kept as it is, and only a lone {@code '%'} that does not
     * introduce one is escaped. What is escaped besides that is what {@link URI} rejects: a space, a control
     * character, a character outside ASCII, and the handful of ASCII characters that no part of a URI may hold.
     *
     * @param url
     *            the URL.
     * @return the URL, with the characters a URI cannot hold percent-encoded.
     */
    private static String encodeURL(final String url) {
        final StringBuilder buf = new StringBuilder(url.length() + 16);
        for (var i = 0; i < url.length(); i++) {
            final var c = url.charAt(i);
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
                final var codePoint = url.codePointAt(i);
                i += Character.charCount(codePoint) - 1;
                for (final byte b : Character.toString(codePoint).getBytes(StandardCharsets.UTF_8)) {
                    buf.append('%').append(HEXADECIMAL[(b & 0xf0) >> 4]).append(HEXADECIMAL[b & 0x0f]);
                }
            }
        }
        return buf.toString();
    }

    /**
     * Move the server of a UNC path out of the authority of a {@code "file:"} URI and back into its path, so that
     * the URI names the same file after it is converted to a {@link java.net.URL} and opened.
     *
     * <p>
     * {@link java.nio.file.Path#toUri()} renders the UNC path {@code \\server\share\x} as
     * {@code file://server/share/x}, putting the server in the URI authority. {@link java.net.URL} reads that back
     * as the local path {@code \share\x}, dropping the server, so opening it fails or, worse, reads a different
     * file. {@link java.io.File#toURI()} renders the same path as {@code file:////server/share/x}, with an empty
     * authority and the server in the path, and that does read back as the UNC path it came from. Both spellings
     * are permitted (RFC 8089 appendix E.3.2); only the second one round-trips.
     *
     * <p>
     * A URI with no authority is returned unchanged, so this is a no-op for every path that is not a UNC path.
     *
     * @param uri
     *            the URI
     * @return the URI, with any UNC server moved from the authority into the path
     */
    public static URI moveUNCServerIntoPath(final URI uri) {
        final var authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty() || !"file".equals(uri.getScheme())) {
            return uri;
        }
        final var path = uri.getRawPath();
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
        if (urlPath.startsWith("jrt:") || urlPath.startsWith("http://") || urlPath.startsWith("https://")) {
            // These schemes do not name a file, so there is no file path to normalize, and what is left is still a
            // URL: FastPathResolver keeps the percent encoding of a URL rather than decoding it, so encoding it
            // again here would name a resource on a host that does not exist. Only what a URI cannot hold is
            // escaped
            final var url = encodeURL(urlPath);
            // A "!/" separates a jarfile from a path within it whatever the jarfile is fetched over, so a jarfile
            // nested inside a jarfile that was fetched over http is named by a "jar:" URL, exactly as a nested
            // jarfile on the local filesystem is
            return PathSyntax.indexOfNestedJarSeparator(url) < 0 ? url
                    : "jar:" + PathSyntax.toJarUrlSeparators(url);
        }
        var urlPathNormalized = stripJarAndFilePrefixes(urlPath);

        // A '!' is only a nested jar separator if it really separates a jarfile from a path within it -- it is an
        // ordinary filename character otherwise, and a path such as "/dir!bang/x.jar" must not be rewritten to
        // "/dir!/bang/x.jar", which names a different file and cannot be opened. Every separator that is one has to
        // be spelled "!/" here, since that is what the "jar:" URL scheme requires
        // #903
        final var hasNestedJarSeparator = PathSyntax.indexOfNestedJarSeparator(urlPathNormalized) >= 0;
        if (hasNestedJarSeparator) {
            urlPathNormalized = PathSyntax.toJarUrlSeparators(urlPathNormalized);
        }

        urlPathNormalized = toFileURL(urlPathNormalized);

        // Prepend "jar:" if the path really does name something nested inside a jarfile
        if (hasNestedJarSeparator) {
            urlPathNormalized = "jar:" + urlPathNormalized;
        }
        return encodePath(urlPathNormalized);
    }

    /**
     * Strip the {@code "jar:"} and {@code "file:"} scheme prefixes from a URL path, if present, leaving a plain
     * file path.
     *
     * @param urlPath
     *            the URL path.
     * @return the path with the scheme prefixes removed.
     */
    private static String stripJarAndFilePrefixes(final String urlPath) {
        var path = urlPath;
        if (path.startsWith("jar:")) {
            path = path.substring(4);
        }
        if (path.startsWith("file:")) {
            path = path.substring(5);
            // "file:" may be followed by an authority, which is not part of the path. Drop the two slashes that
            // introduce it, so that the "file://" prefix added by toFileURL() cannot produce a path with a run of
            // slashes in it, e.g. "file://///tmp/x.jar". Exactly two slashes are dropped, so that the remaining
            // "//" of a UNC path is kept: "file:////server/share/x" leaves "//server/share/x", which names the
            // share it came from, rather than the local path "/server/share/x", which does not
            if (path.startsWith("///")) {
                path = path.substring(2);
            } else if (path.startsWith("//")) {
                // Only two slashes, so what follows is either an authority naming the local machine or, in the
                // spelling some classloaders use, the path itself. Either way one slash is enough
                path = path.substring(1);
            }
        }
        return path;
    }

    /**
     * Turn a plain file path into a {@code "file:"} URL, prepending {@code "file:///"} to absolute paths and
     * {@code "file:"} to relative paths.
     *
     * @param path
     *            the file path.
     * @return the {@code "file:"} URL.
     */
    private static String toFileURL(final String path) {
        // On Windows, remove the drive prefix from the path, if present (otherwise the ':' after the drive letter
        // would be escaped as %3A)
        var windowsDrivePrefix = "";
        var pathWithoutDrive = path;
        if (VersionFinder.OS == OperatingSystem.Windows) {
            if (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':') {
                // Path of form "C:/xyz"
                windowsDrivePrefix = path.substring(0, 2);
                pathWithoutDrive = path.substring(2);
            } else if (path.length() >= 3 && path.charAt(0) == '/' && Character.isLetter(path.charAt(1))
                    && path.charAt(2) == ':') {
                // Path of form "/C:/xyz"
                windowsDrivePrefix = path.substring(1, 3);
                pathWithoutDrive = path.substring(3);
            }
        }
        if (!windowsDrivePrefix.isEmpty()) {
            // There is a Windows drive, so the path must be absolute
            return "file:///" + windowsDrivePrefix + pathWithoutDrive;
        }
        // Absolute path: file:///xyz -- relative path: file:xyz
        return pathWithoutDrive.startsWith("/") ? "file://" + pathWithoutDrive : "file:" + pathWithoutDrive;
    }
}
