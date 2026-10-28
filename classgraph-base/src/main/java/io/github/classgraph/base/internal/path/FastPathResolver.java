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

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.internal.utils.VersionFinder;
import io.github.classgraph.base.internal.utils.VersionFinder.OperatingSystem;

/**
 * Resolves relative paths and URLs/URIs against a base path and normalizes path syntax across platforms.
 */
public final class FastPathResolver {
    /** Match %-encoded characters in URLs. */
    private static final Pattern PERCENT_ESCAPES = Pattern.compile("([%][0-9a-fA-F][0-9a-fA-F])+");

    /** Matches a custom URL scheme (at least two characters) followed by a colon and zero to two slashes. */
    private static final Pattern SCHEME_AND_SLASHES = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+\\-.]+:/{0,2}");

    /** Custom separator between a WAR file path and in-WAR path in Tomcat {@code "war:"} URLs. */
    // #925
    private static final @Nullable String customWarSeparator = VersionFinder
            .getProperty("org.apache.tomcat.util.buf.UriUtil.WAR_SEPARATOR");

    /** Not instantiable. */
    private FastPathResolver() {
        // Cannot be constructed
    }

    /**
     * Translate backslashes to forward slashes, collapsing duplicate separators and optionally stripping a trailing
     * separator.
     *
     * @param path
     *            the path
     * @param startIdx
     *            the start index
     * @param endIdx
     *            the end index
     * @param stripFinalSeparator
     *            if true, strip the final separator
     * @param buf
     *            the buffer to append to
     */
    private static void translateSeparator(final String path, final int startIdx, final int endIdx,
            final boolean stripFinalSeparator, final StringBuilder buf) {
        for (var i = startIdx; i < endIdx; i++) {
            final var c = path.charAt(i);
            if (c == '\\' || c == '/') {
                // Strip trailing separator, if necessary
                if (i < endIdx - 1 || !stripFinalSeparator) {
                    // Remove duplicate separators
                    final var prevChar = buf.isEmpty() ? '\0' : buf.charAt(buf.length() - 1);
                    if (prevChar != '/') {
                        buf.append('/');
                    }
                }
            } else {
                buf.append(c);
            }
        }
    }

    /**
     * Test whether a path is a bare Windows drive designation, such as {@code "C:"}.
     *
     * @param path
     *            the path
     * @return true if the path is a letter followed by a colon
     */
    private static boolean isDriveDesignation(final String path) {
        return path.length() == 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':';
    }

    /**
     * Convert a hexadecimal digit character to its integer value (0-15).
     *
     * @param c
     *            A hexadecimal digit, in either case.
     * @return The value of the digit, from 0 to 15.
     */
    private static int hexCharToInt(final char c) {
        return c >= '0' && c <= '9' ? (c - '0') //
                : c >= 'a' && c <= 'f' ? (c - 'a' + 10) //
                        : (c - 'A' + 10);
    }

    /**
     * Unescape a run of percent-encoded bytes as UTF-8, leaving {@code "%2F"} and {@code "%5C"} encoded.
     *
     * @param path
     *            the path
     * @param startIdx
     *            the start index
     * @param endIdx
     *            the end index
     * @param buf
     *            the buffer to append to
     */
    private static void unescapePercentEncoding(final String path, final int startIdx, final int endIdx,
            final StringBuilder buf) {
        if (endIdx - startIdx == 3 && path.charAt(startIdx + 1) == '2' && path.charAt(startIdx + 2) == '0') {
            // Fast path for "%20"
            buf.append(' ');
        } else {
            final var bytes = new byte[(endIdx - startIdx) / 3];
            for (int i = startIdx, j = 0; i < endIdx; i += 3, j++) {
                final var c1 = path.charAt(i + 1);
                final var c2 = path.charAt(i + 2);
                final var digit1 = hexCharToInt(c1);
                final var digit2 = hexCharToInt(c2);
                bytes[j] = (byte) ((digit1 << 4) | digit2);
            }
            // Decode UTF-8 bytes
            String str = new String(bytes, StandardCharsets.UTF_8);
            // Turn forward slash / backslash back into %-encoding
            str = str.replace("/", "%2F").replace("\\", "%5C");
            buf.append(str);
        }
    }

    /**
     * Decode percent-encoded sequences (such as {@code "%20"} to {@code " "}) as UTF-8, leaving encoded path
     * separators ({@code "%2F"} and {@code "%5C"}) intact.
     *
     * @param path
     *            The path to decode.
     * @return The decoded path.
     */
    public static String decodePercentEncoding(final String path) {
        if (path.indexOf('%') < 0) {
            return path;
        }
        final StringBuilder buf = new StringBuilder();
        var prevEndMatchIdx = 0;
        final var matcher = PERCENT_ESCAPES.matcher(path);
        while (matcher.find()) {
            buf.append(path, prevEndMatchIdx, matcher.start());
            unescapePercentEncoding(path, matcher.start(), matcher.end(), buf);
            prevEndMatchIdx = matcher.end();
        }
        buf.append(path, prevEndMatchIdx, path.length());
        return buf.toString();
    }

    /**
     * Test whether {@link #resolve(String)} will decode percent-encoding in the given path (which it does only for
     * filesystem paths, not URLs).
     *
     * @param path
     *            The path.
     * @return {@code true} if {@link #resolve(String)} will decode percent-encoding in {@code path}.
     */
    public static boolean resolveDecodesPercentEncoding(final String path) {
        return stripSchemePrefixes(nestedUrlToJarUrl(warUrlToJarUrl(path))).remainderIsFilePath;
    }

    /**
     * Normalize a path by converting backslashes to forward slashes, collapsing duplicate slashes, stripping any
     * trailing slash, and optionally decoding percent-encoding.
     *
     * @param path
     *            The path to normalize.
     * @param percentDecode
     *            If {@code true}, decode percent-encoded sequences in the path.
     * @return The normalized path.
     */
    public static String normalizePath(final String path, final boolean percentDecode) {
        final var hasPercent = path.indexOf('%') >= 0;
        if (!hasPercent && path.indexOf('\\') < 0 && path.indexOf("//") < 0 && !path.endsWith("/")) {
            return path;
        } else {
            final var len = path.length();
            final StringBuilder buf = new StringBuilder();
            // Decode percent encoding only for a path, never for something that is still a URL (#255)
            if (hasPercent && percentDecode) {
                // Perform '%'-decoding of path segment
                var prevEndMatchIdx = 0;
                final var matcher = PERCENT_ESCAPES.matcher(path);
                while (matcher.find()) {
                    final var startMatchIdx = matcher.start();
                    final var endMatchIdx = matcher.end();
                    translateSeparator(path, prevEndMatchIdx, startMatchIdx, /* stripFinalSeparator = */ false,
                            buf);
                    unescapePercentEncoding(path, startMatchIdx, endMatchIdx, buf);
                    prevEndMatchIdx = endMatchIdx;
                }
                translateSeparator(path, prevEndMatchIdx, len, /* stripFinalSeparator = */ true, buf);
            } else {
                // Fast path -- no '%', or a path that stays a URL and so keeps its percent encoding
                translateSeparator(path, 0, len, /* stripFinalSeparator = */ true, buf);
            }
            return buf.toString();
        }
    }

    /**
     * Convert a Tomcat {@code "war:"} URL into the equivalent {@code "jar:"} URL.
     *
     * @param path
     *            The path, which may or may not be a {@code "war:"} URL.
     * @return The equivalent {@code "jar:"} URL if this is a {@code "war:"} URL, otherwise the path unchanged.
     */
    // #925
    private static String warUrlToJarUrl(final String path) {
        if (!path.regionMatches(true, 0, "war:", 0, 4)) {
            return path;
        }
        // Strip the "war:" prefix, leaving a "file:" URL that the rest of the resolver understands
        final var jarUrl = path.substring(4);
        // Mirrors the separators tried by org.apache.tomcat.util.buf.UriUtil#warToJar
        var sepIdx = jarUrl.indexOf("*/");
        if (sepIdx < 0) {
            sepIdx = jarUrl.indexOf("^/");
        }
        var sepLen = 2;
        if (sepIdx < 0 && customWarSeparator != null && !customWarSeparator.isEmpty()) {
            sepIdx = jarUrl.indexOf(customWarSeparator + "/");
            sepLen = customWarSeparator.length() + 1;
        }
        return sepIdx < 0 ? jarUrl : jarUrl.substring(0, sepIdx) + "!/" + jarUrl.substring(sepIdx + sepLen);
    }

    /**
     * Convert a Spring Boot {@code "nested:"} URL into the equivalent {@code "jar:"} URL.
     *
     * @param path
     *            The path, which may or may not be a {@code "nested:"} URL.
     * @return The equivalent {@code "jar:"} URL if this is a {@code "nested:"} URL, otherwise the path unchanged.
     */
    private static String nestedUrlToJarUrl(final String path) {
        // A "nested:" URL is almost always wrapped in a "jar:" URL, which is left in place
        final var schemeIdx = path.regionMatches(true, 0, "jar:", 0, 4) ? 4 : 0;
        if (!path.regionMatches(true, schemeIdx, "nested:", 0, 7)) {
            return path;
        }
        // The location of the nested entry runs up to the first "!/", and anything after that is the path of a
        // resource within the nested entry
        final var locStartIdx = schemeIdx + 7;
        var locEndIdx = path.indexOf("!/", locStartIdx);
        if (locEndIdx < 0) {
            locEndIdx = path.length();
        }
        final var location = path.substring(locStartIdx, locEndIdx);
        // Mirrors the split performed by org.springframework.boot.loader.net.protocol.nested.NestedLocation#parse
        final var sepIdx = location.lastIndexOf("/!");
        final var jarUrl = sepIdx < 0 ? location
                : location.substring(0, sepIdx) + "!/" + location.substring(sepIdx + 2);
        // What is left of a "nested:" URL is the path of the outer archive on disk
        return path.substring(0, schemeIdx) + "file:" + jarUrl + path.substring(locEndIdx);
    }

    /** Parsed scheme or path prefix information. */
    private static final class ParsedPrefix {
        /** The scheme or UNC prefix to prepend to the resolved path. */
        String prefix = "";

        /** True if the remainder of the path is absolute. */
        boolean isAbsolutePath;

        /** True if the remainder of the path is a filesystem path rather than a URL. */
        boolean remainderIsFilePath;

        /** The index of the first character after the stripped prefix. */
        int startIdx;

        /** Constructor. */
        private ParsedPrefix() {
            // Empty
        }
    }

    /**
     * Strip outer {@code "jar:"} and {@code "file:"} prefixes and extract any remaining URL scheme prefix.
     *
     * @param path
     *            the path
     * @return the parsed prefix metadata.
     */
    private static ParsedPrefix stripSchemePrefixes(final String path) {
        final var parsed = new ParsedPrefix();
        boolean matchedPrefix;
        do {
            matchedPrefix = false;
            if (path.regionMatches(true, parsed.startIdx, "jar:", 0, 4)) {
                // "jar:" prefix can be stripped. A "jar:" URL wraps an inner URL, so whether the result is a path
                // is decided by the inner scheme, if there is one -- but a "jar:" prefix on a bare path, as in
                // "jar:/dir/x.jar!/y", leaves a filesystem path behind
                matchedPrefix = true;
                parsed.startIdx += 4;
                parsed.remainderIsFilePath = true;
            } else if (path.regionMatches(true, parsed.startIdx, "http://", 0, 7)) {
                // Detect http://
                matchedPrefix = true;
                parsed.startIdx += 7;
                // Force protocol name to lowercase
                parsed.prefix = "http://";
                // Treat the part after the protocol as an absolute path, so the domain is not treated as a
                // directory relative to the current directory.
                parsed.isAbsolutePath = true;
                // Don't un-escape percent encoding etc.
                parsed.remainderIsFilePath = false;
            } else if (path.regionMatches(true, parsed.startIdx, "https://", 0, 8)) {
                // Detect https://
                matchedPrefix = true;
                parsed.startIdx += 8;
                parsed.prefix = "https://";
                parsed.isAbsolutePath = true;
                parsed.remainderIsFilePath = false;
            } else if (path.regionMatches(true, parsed.startIdx, "jrt:", 0, 4)) {
                // Detect jrt:
                matchedPrefix = true;
                parsed.startIdx += 4;
                parsed.prefix = "jrt:";
                parsed.isAbsolutePath = true;
                parsed.remainderIsFilePath = false;
            } else if (path.regionMatches(true, parsed.startIdx, "file:", 0, 5)) {
                // Strip off "file:" prefix from relative path
                matchedPrefix = true;
                parsed.startIdx += 5;
                parsed.remainderIsFilePath = true;
            } else {
                // Preserve the number of slashes on custom URL schemes (#420)
                final var relPath = parsed.startIdx == 0 ? path : path.substring(parsed.startIdx);
                final var matcher = SCHEME_AND_SLASHES.matcher(relPath);
                if (matcher.find()) {
                    matchedPrefix = true;
                    final var match = matcher.group();
                    parsed.startIdx += match.length();
                    // A scheme is case-insensitive, and its canonical form is lowercase (RFC 3986 section 3.1), so
                    // lowercase it, as the schemes that are recognized by name above are. The match is a scheme
                    // followed by ':' and at most two slashes, so this changes nothing but the scheme
                    parsed.prefix = match.toLowerCase(Locale.ROOT);
                    // Treat the part after the protocol as an absolute path, so the rest of the URL is not treated
                    // as a directory relative to the current directory.
                    parsed.isAbsolutePath = true;
                    // The scheme is kept, so the result is still a URL
                    parsed.remainderIsFilePath = false;
                }
            }
            // Only "jar:" and "file:" are stripped and wrap another URL. Once a scheme has been kept, the rest of
            // the path belongs to that URL, so in "http://jar:8080/x.jar" the host "jar" is not another scheme
        } while (matchedPrefix && parsed.prefix.isEmpty());
        return parsed;
    }

    /**
     * Extract the URL authority (or Windows UNC server name) at the start of {@code path} when {@code prefix} ends
     * with {@code "//"}.
     *
     * @param prefix
     *            the scheme prefix stripped from the front of the path
     * @param path
     *            the remainder of the path after the prefix
     * @return the authority, or the empty string if none.
     */
    private static String urlAuthority(final String prefix, final String path) {
        if (!prefix.endsWith("//")) {
            return "";
        }
        final var authorityEndIdx = path.indexOf('/');
        return authorityEndIdx < 0 ? path : path.substring(0, authorityEndIdx);
    }

    /**
     * Return the portion of {@code path} following {@code authority}, collapsing leading duplicate slashes.
     *
     * @param path
     *            the path from which the authority was read
     * @param authority
     *            the authority, or the empty string if none
     * @return the path after the authority.
     */
    private static String pathAfterAuthority(final String path, final String authority) {
        if (authority.isEmpty()) {
            return path;
        }
        var startIdx = authority.length();
        while (startIdx + 1 < path.length() && path.charAt(startIdx) == '/' && path.charAt(startIdx + 1) == '/') {
            startIdx++;
        }
        return path.substring(startIdx);
    }

    /**
     * Strip an empty or {@code "localhost"} authority from a {@code "file:"} URL.
     *
     * @param path
     *            the path
     * @param parsed
     *            the prefix parsed so far, updated in place
     */
    private static void stripFileUrlAuthority(final String path, final ParsedPrefix parsed) {
        if (!parsed.remainderIsFilePath) {
            return;
        }

        // A "file:" URL with an empty authority ("file:///path", which is the spelling that Path#toUri() produces)
        // has two slashes that are not part of the path. Drop them, so that the path itself is what the checks
        // below see -- otherwise on Windows the empty authority is read as the start of a UNC path, and
        // "file:///C:/xyz" resolves to "///C:/xyz", which names neither a drive nor a network share
        if (path.startsWith("///", parsed.startIdx)) {
            parsed.startIdx += 2;
        }

        // A "file:" URL names the local machine either with an empty authority ("file:///path") or with the
        // authority "localhost" ("file://localhost/path"), and the two mean the same local path (RFC 8089 section
        // 2). Outside Windows there is no UNC concept, so the authority would otherwise be folded into the path
        // and "file://localhost/tmp/a" would resolve to "/localhost/tmp/a", which names a different file. On
        // Windows the authority is left alone, so that it becomes the UNC path "//localhost/tmp/a" -- that is both
        // what Path#of(URI) produces there and what RFC 8089 appendix B.3 specifies
        if (VersionFinder.OS != OperatingSystem.Windows
                && path.regionMatches(true, parsed.startIdx, "//localhost/", 0, 12)) {
            parsed.startIdx += "//localhost".length();
        }
    }

    /**
     * Check whether the remainder of a path is absolute, stripping any Windows UNC prefix or leading slash before a
     * drive letter.
     *
     * @param path
     *            the path
     * @param parsed
     *            the prefix parsed so far, updated in place
     */
    private static void stripAbsolutePathPrefix(final String path, final ParsedPrefix parsed) {
        // Handle Windows paths starting with a drive designation as an absolute path. A path that keeps its scheme
        // prefix is still a URL, not a file path, so none of this applies to it: dropping the slash before the
        // drive in "custom:///C:/a" would make "C:" the URL's authority
        if (VersionFinder.OS == OperatingSystem.Windows && parsed.prefix.isEmpty()) {
            if (path.startsWith("//", parsed.startIdx) || path.startsWith("\\\\", parsed.startIdx)) {
                // Windows UNC path
                parsed.startIdx += 2;
                parsed.prefix = "//";
                parsed.isAbsolutePath = true;
            } else if (path.length() - parsed.startIdx >= 2 && Character.isLetter(path.charAt(parsed.startIdx))
                    && path.charAt(parsed.startIdx + 1) == ':') {
                // Path like "C:/xyz", or the bare drive designation "C:"
                parsed.isAbsolutePath = true;
            } else if (path.length() - parsed.startIdx >= 3
                    && (path.charAt(parsed.startIdx) == '/' || path.charAt(parsed.startIdx) == '\\')
                    && Character.isLetter(path.charAt(parsed.startIdx + 1))
                    && path.charAt(parsed.startIdx + 2) == ':') {
                // Path like "/C:/xyz", or the bare drive designation "/C:"
                parsed.isAbsolutePath = true;
                parsed.startIdx++;
            }
        }
        // Catch-all for paths starting with separator. A path consisting of nothing but a separator is the root
        // path, which is absolute too, so one character is enough here
        if (path.length() - parsed.startIdx >= 1
                && (path.charAt(parsed.startIdx) == '/' || path.charAt(parsed.startIdx) == '\\')) {
            parsed.isAbsolutePath = true;
        }
    }

    /**
     * Strip trailing path and nested jar separators from a normalized path.
     *
     * @param path
     *            the normalized path, with its scheme prefix removed
     * @param prefix
     *            the scheme prefix removed from the path, or the empty string for a filesystem path.
     * @return the path without trailing separators.
     */
    private static String stripTrailingSeparators(final String path, final String prefix) {
        if ("/".equals(path)) {
            // The root path is the one path whose final separator is the whole of its name
            return path;
        }
        var pathStr = path;
        // Remove any "!/" on end of URL
        if (pathStr.endsWith("/")) {
            pathStr = pathStr.substring(0, pathStr.length() - 1);
        }
        // Only strip a trailing '!' if it is really a nested jar separator, i.e. if it marks the whole of the
        // jarfile before it -- a trailing '!' is otherwise part of a directory name (#903). Use
        // lastIndexOfNestedJarSeparator, not indexOfNestedJarSeparator, so that the trailing '!' of a doubly-nested
        // path such as "/a/b.war!/WEB-INF/lib/c.jar!" is stripped too: the innermost separator is the relevant one,
        // and this is the rule the Vfs applies when splitting the resulting path back apart. The Vfs sees the path
        // with its scheme prefix, and without the prefix a remote URL such as "http://host/x.jar!" would be tested
        // as a local relative path, whose '!' is not a separator unless a local file precedes it.
        if (pathStr.endsWith("!") && PathSyntax.lastIndexOfNestedJarSeparator(prefix + pathStr) == prefix.length()
                + pathStr.length() - 1) {
            pathStr = pathStr.substring(0, pathStr.length() - 1);
        }
        if (pathStr.endsWith("/")) {
            pathStr = pathStr.substring(0, pathStr.length() - 1);
        }
        return pathStr.isEmpty() ? "/" : pathStr;
    }

    /**
     * Resolve a relative path or URI against {@code resolveBasePath}, stripping {@code "jar:"} and {@code "file:"}
     * prefixes, normalizing separators, and collapsing {@code ".."} segments textually. Use
     * {@link #resolveFilePath(String, String)} instead for a path that names a file on disk.
     *
     * @param resolveBasePath
     *            The base path, or {@code null} to resolve against no base path.
     * @param relativePathRaw
     *            The path to resolve relative to the base path.
     * @return The resolved path.
     */
    public static String resolve(final @Nullable String resolveBasePath, final String relativePathRaw) {
        return resolve(resolveBasePath, relativePathRaw, /* namesFileOnDisk = */ false);
    }

    /**
     * Resolve a filesystem path against {@code resolveBasePath} as in {@link #resolve(String, String)}, but
     * preserve {@code ".."} segments in the outermost path section so the platform filesystem can resolve them
     * relative to symlinks.
     *
     * @param resolveBasePath
     *            The base path, or {@code null} to resolve against no base path.
     * @param relativePathRaw
     *            The path to resolve relative to the base path.
     * @return The resolved path.
     */
    public static String resolveFilePath(final @Nullable String resolveBasePath, final String relativePathRaw) {
        return resolve(resolveBasePath, relativePathRaw, /* namesFileOnDisk = */ true);
    }

    /**
     * Resolve a path or URI against {@code resolveBasePath}.
     *
     * @param resolveBasePath
     *            The base path, or {@code null} to resolve against no base path.
     * @param relativePathRaw
     *            The path to resolve relative to the base path.
     * @param namesFileOnDisk
     *            If {@code true}, preserve {@code ".."} segments in the outermost path section for the filesystem
     *            to resolve.
     * @return The resolved path.
     */
    private static String resolve(final @Nullable String resolveBasePath, final String relativePathRaw,
            final boolean namesFileOnDisk) {
        // See: http://stackoverflow.com/a/17870390/3950982

        if (relativePathRaw.isEmpty()) {
            return resolveBasePath == null ? "" : resolveBasePath;
        }

        // Convert Tomcat's "war:" URLs (#925) and Spring Boot's "nested:" URLs into the standard "jar:" form before
        // anything else, so that the rest of this method sees a path it understands
        final var relativePath = nestedUrlToJarUrl(warUrlToJarUrl(relativePathRaw));

        final var parsed = stripSchemePrefixes(relativePath);
        stripFileUrlAuthority(relativePath, parsed);
        stripAbsolutePathPrefix(relativePath, parsed);

        // Normalize the path, then add any UNC or URL prefix
        final var pathRaw = parsed.startIdx == 0 ? relativePath : relativePath.substring(parsed.startIdx);
        var pathStr = stripTrailingSeparators(normalizePath(pathRaw, parsed.remainderIsFilePath), parsed.prefix);

        // On Windows, the root directory of a drive is "C:/", and it is a root path in the same sense that "/" is:
        // its final separator is the whole of its name, and dropping it leaves the drive designation "C:", which
        // names the current directory on drive C instead. The separator was stripped along with every other
        // trailing separator above, so put it back. A bare "C:", written without a separator, is left as written
        final var isDriveRoot = VersionFinder.OS == OperatingSystem.Windows && isDriveDesignation(pathStr)
                && (pathRaw.endsWith("/") || pathRaw.endsWith("\\"));
        if (isDriveRoot) {
            pathStr += "/";
        }

        // Sanitize path (resolve ".." sections, collapse "//" double separators, etc.). A ".." in the outermost
        // section is only left for the filesystem to resolve if the path is known to name a file on disk, and the
        // path still has to be a path rather than a URL, since a URL has no filesystem to ask
        final var leaveParentSegments = namesFileOnDisk && parsed.prefix.isEmpty();
        final String pathResolved;
        var prefix = parsed.prefix;
        if (parsed.isAbsolutePath || resolveBasePath == null || resolveBasePath.isEmpty()) {
            // There is no base path to resolve against, or path is an absolute path or http(s):// URL (ignore the
            // base path). A root path is the one kind of path whose final separator must not be removed, since the
            // separator is the whole of the directory's name
            final var authority = urlAuthority(prefix, pathStr);
            prefix += authority;
            final var path = pathAfterAuthority(pathStr, authority);
            pathResolved = "/".equals(path) || isDriveRoot ? path
                    : PathSyntax.sanitizeEntryPath(prefix, path, /* removeInitialSlash = */ false,
                            /* removeFinalSlash = */ true,
                            /* collapseParentSegmentsInFirstSection = */ !leaveParentSegments);
        } else {
            // Path is a relative path -- resolve it relative to the base path. The base path may be a URL, e.g. the
            // dir of a jarfile that was fetched over http, so its scheme prefix is split off before the two are
            // joined and added back below: sanitizing the joined path would otherwise collapse the empty segment
            // between the scheme and the authority, and turn "http://host/dir" into "http:/host/dir"
            final var parsedBasePath = stripSchemePrefixes(resolveBasePath);
            final var basePathRaw = parsedBasePath.startIdx == 0 ? resolveBasePath
                    : resolveBasePath.substring(parsedBasePath.startIdx);
            final var authority = urlAuthority(parsedBasePath.prefix, basePathRaw);
            prefix = parsedBasePath.prefix + authority;
            final var basePath = pathAfterAuthority(basePathRaw, authority);
            pathResolved = PathSyntax.sanitizeEntryPath(prefix,
                    basePath + (basePath.endsWith("/") ? "" : "/") + pathStr, /* removeInitialSlash = */ false,
                    /* removeFinalSlash = */ true,
                    /* collapseParentSegmentsInFirstSection = */ !leaveParentSegments);
        }

        // Add any prefix back, e.g. "https://". A prefix that already ends with a separator supplies the root
        // path's own separator, so joining the two must not double it ("custom:/" must not become "custom://").
        // The exception is a prefix that ends with an empty authority, as in "custom:///a/b": its "//" introduces
        // the authority, and the path keeps its own separator, which would otherwise turn "a" into the authority
        if (prefix.isEmpty()) {
            return pathResolved;
        }
        return prefix.endsWith("/") && !prefix.endsWith("://") && pathResolved.startsWith("/")
                ? prefix + pathResolved.substring(1)
                : prefix + pathResolved;
    }

    /**
     * Normalize a path or URI as in {@link #resolve(String, String)} with no base path.
     *
     * @param pathStr
     *            The path to resolve.
     * @return The resolved path.
     */
    public static String resolve(final String pathStr) {
        return resolve(null, pathStr);
    }
}
