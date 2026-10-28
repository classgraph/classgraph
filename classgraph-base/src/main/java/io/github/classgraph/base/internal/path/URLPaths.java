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

import java.io.IOError;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

import io.github.classgraph.base.internal.utils.VersionFinder;
import io.github.classgraph.base.internal.utils.VersionFinder.OperatingSystem;

/**
 * Utilities for validating URL schemes and percent-encoding, normalizing, and converting paths and URLs to
 * {@link URI} instances.
 */
public final class URLPaths {
    /**
     * Pattern matching a URL scheme (at least two characters) and its trailing {@code ':'} at the start of a path.
     */
    private static final Pattern URL_SCHEME_PREFIX_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+\\-.]+:");

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

    /** Not instantiable. */
    private URLPaths() {
        // Cannot be constructed
    }

    /**
     * Validate a URL scheme name and convert it to lowercase.
     *
     * @param scheme
     *            the scheme name (e.g. {@code "http"}), without a trailing {@code ':'}.
     * @return the lowercased scheme name.
     * @throws IllegalArgumentException
     *             if the scheme is shorter than two characters or is not a valid URL scheme.
     */
    public static String normalizeURLScheme(final String scheme) {
        // The scheme is validated, rather than simply lowercased, because a scheme given in a form that can never
        // match a URL, e.g. with a trailing ':', would otherwise be accepted and silently have no effect
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
     * Check whether a path syntactically begins with a URL scheme (at least two characters) followed by
     * {@code ':'}.
     *
     * @param path
     *            the path.
     * @return {@code true} if the path begins with a URL scheme followed by {@code ':'}.
     */
    public static boolean startsWithURLScheme(final String path) {
        return URL_SCHEME_PREFIX_PATTERN.matcher(path).find();
    }

    /**
     * Percent-encode unsafe characters in a URL path, preserving {@code '/'} and valid scheme or drive colons.
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
     * Test whether a character is a hexadecimal digit.
     *
     * @param c
     *            the character.
     * @return {@code true} if the character is a hexadecimal digit.
     */
    private static boolean isHexDigit(final char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * Percent-encode characters that are not permitted in a {@link URI}, preserving existing percent-escape
     * sequences and URL syntax characters.
     *
     * @param url
     *            the URL.
     * @return the URL with disallowed URI characters percent-encoded.
     */
    public static String encodeURL(final String url) {
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
     * For a Windows UNC {@code "file:"} URI with a non-empty authority (such as {@code file://server/share/x}),
     * move the authority into the path ({@code file:////server/share/x}) so that {@link java.net.URL} preserves the
     * UNC server name.
     *
     * @param uri
     *            the URI
     * @return the URI with any UNC server moved from the authority into the path
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
     * Normalize and percent-encode a file path or URL string so that it can be parsed by {@link URI#URI(String)}.
     *
     * @param urlPath
     *            the URL path
     * @return the normalized URL string
     */
    public static String normalizeURLPath(final String urlPath) {
        if (startsWithURLScheme(urlPath) && !urlPath.startsWith("file:") && !urlPath.startsWith("jar:")) {
            // A scheme other than "file:" does not name a file -- "jrt:", "http:" and "https:" do not, and nor does
            // a custom scheme, which names something only its URL handler can reach -- so there is no file path to
            // normalize, and what is left is still a URL: FastPathResolver keeps the percent encoding of a URL
            // rather than decoding it, so encoding it again here would name a resource on a host that does not
            // exist. Only what a URI cannot hold is escaped
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
        final var nestedJarSepIdx = PathSyntax.indexOfNestedJarSeparator(urlPathNormalized);
        if (nestedJarSepIdx < 0) {
            return encodePath(toFileURL(urlPathNormalized));
        }
        // The path really does name something nested inside a jarfile, so it is a "jar:" URL. The scheme ends the
        // path of the outermost jarfile at its first "!/", so a "!/" before the outermost separator, as at the end
        // of a directory named "dir!", is percent-encoded, as ArchiveRoot#resolveURI encodes it. That is done after
        // encodePath, which would encode the '%' a second time. (Everything before the outermost separator is
        // unchanged by toJarUrlSeparators.)
        return encodePath("jar:" + toFileURL(urlPathNormalized.substring(0, nestedJarSepIdx))).replace("!/", "%21/")
                + encodePath(PathSyntax.toJarUrlSeparators(urlPathNormalized).substring(nestedJarSepIdx));
    }

    /**
     * Convert a path or URL string into a normalized {@link URI}.
     *
     * @param path
     *            the path
     * @return the {@link URI} of the path
     * @throws IllegalStateException
     *             if the path could not be converted into a {@link URI}
     */
    public static URI toURI(final String path) {
        final var normalized = normalizeURLPath(path);
        try {
            return new URI(normalized);
        } catch (final URISyntaxException e) {
            throw new IllegalStateException("Could not form URI for " + path + " : " + e, e);
        }
    }

    /**
     * Convert a filesystem {@link Path} into a {@link URI}, preserving Windows UNC server names.
     *
     * @param path
     *            the path
     * @return the {@link URI} of the path
     * @throws IllegalStateException
     *             if the path could not be converted into a {@link URI}
     */
    public static URI toURI(final Path path) {
        try {
            // On Windows, Path#toUri() puts the server of a UNC path in the URI authority, where java.net.URL does
            // not find it again
            return moveUNCServerIntoPath(path.toUri());
        } catch (final IOError | SecurityException e) {
            throw new IllegalStateException("Could not form URI for " + path + " : " + e, e);
        }
    }

    /**
     * Strip leading {@code "jar:"} and {@code "file:"} scheme prefixes from a URL path.
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
     * Convert a plain file path into a {@code "file:"} URL string.
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
