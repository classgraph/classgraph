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
package io.github.classgraph.base.internal.utils;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import io.github.classgraph.base.internal.utils.VersionFinder.OperatingSystem;
import org.jspecify.annotations.Nullable;

/**
 * Resolve relative paths and URLs/URIs against a base path, faster than Java's URL/URI parser and much faster than
 * {@link java.nio.file.Path}. Handles the path forms of every supported platform, including the several ways a
 * Windows path can be written (drive letters, UNC paths, backslash separators, and the {@code "file:"} URL
 * spellings of each).
 */
public final class FastPathResolver {
    /** Match %-encoded characters in URLs. */
    private static final Pattern percentMatcher = Pattern.compile("([%][0-9a-fA-F][0-9a-fA-F])+");

    /**
     * Match custom URLs that are followed by one or two slashes. The scheme grammar is the one in RFC 3986: a
     * letter, then any number of letters, digits, {@code '+'}, {@code '-'} and {@code '.'} -- digits included, so
     * that a scheme such as {@code "s3:"} is recognized. At least two characters are required, so that a Windows
     * drive designation such as {@code "C:/dir"} is read as a drive and not as a scheme, matching
     * {@link JarUtils#URL_SCHEME_PATTERN}. A single-letter scheme is unusable in practice for exactly that reason,
     * so nothing is given up by not recognizing one: off Windows, {@code "C:/dir"} is then resolved as an ordinary
     * relative path, which does not exist, so the classpath element is logged and skipped during scanning.
     */
    private static final Pattern schemeOneOrTwoSlashMatcher = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+\\-.]+:/{1,2}");

    /**
     * The separator that Tomcat uses in a {@code "war:"} URL between the path of the WAR file and the path within
     * the WAR file. Tomcat uses {@code "*&#47;"} by default, or {@code "^&#47;"}, or a custom separator set through
     * this system property, since a WAR file's path may itself contain {@code '*'} or {@code '^'}. See
     * {@code org.apache.tomcat.util.buf.UriUtil#warToJar}.
     */
    // #925
    private static final @Nullable String customWarSeparator = VersionFinder
            .getProperty("org.apache.tomcat.util.buf.UriUtil.WAR_SEPARATOR");

    /**
     * Constructor.
     */
    private FastPathResolver() {
        // Cannot be constructed
    }

    /**
     * Translate backslashes to forward slashes, optionally removing trailing separator.
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
     * @return true if the path is a letter followed by a colon, and nothing else
     */
    private static boolean isDriveDesignation(final String path) {
        return path.length() == 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':';
    }

    /**
     * Hex char to int.
     *
     * @param c
     *            the character
     * @return the integer value of the character
     */
    private static int hexCharToInt(final char c) {
        return c >= '0' && c <= '9' ? (c - '0') //
                : c >= 'a' && c <= 'f' ? (c - 'a' + 10) //
                        : (c - 'A' + 10);
    }

    /**
     * Unescape runs of percent encoding, e.g. "%20%43%20" -> " + "
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
     * Parse percent encoding, e.g. "%20" -&gt; " "; convert '/' or '\\' to SEP; remove trailing separator char if
     * present.
     *
     * @param path
     *            The path to normalize.
     * @param percentDecode
     *            True if percent encoding in the path should be decoded, which is the case only when the path
     *            resolves to a filesystem path rather than remaining a URL.
     * @return The normalized path.
     */
    public static String normalizePath(final String path, final boolean percentDecode) {
        final var hasPercent = path.indexOf('%') >= 0;
        if (!hasPercent && path.indexOf('\\') < 0 && !path.endsWith("/")) {
            return path;
        } else {
            final var len = path.length();
            final StringBuilder buf = new StringBuilder();
            // Decode percent encoding only for a path, never for something that is still a URL (#255)
            if (hasPercent && percentDecode) {
                // Perform '%'-decoding of path segment
                var prevEndMatchIdx = 0;
                final var matcher = percentMatcher.matcher(path);
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
                return buf.toString();
            }
            return buf.toString();
        }
    }

    /**
     * Convert a Tomcat {@code "war:"} URL into the equivalent {@code "jar:"} URL.
     *
     * <p>
     * Tomcat serves a non-exploded WAR file (i.e. a webapp deployed with {@code unpackWARs="false"}) through its
     * own {@code "war:"} URL protocol, which separates the path of the WAR file from the path within the WAR file
     * using {@code "*&#47;"} rather than the standard {@code "!&#47;"}, e.g.
     * {@code "war:file:/path/to/app.war*&#47;WEB-INF/classes/"}. Without this conversion, the {@code '*'} was read
     * as a wildcard, and the whole classpath element was rejected, so nothing in a non-exploded WAR was scanned.
     *
     * @param path
     *            The path, which may or may not be a {@code "war:"} URL.
     * @return The equivalent {@code "jar:"} URL if this is a {@code "war:"} URL, otherwise the path, unchanged.
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

    /** What the prefix at the beginning of a path says about the rest of the path. */
    private static final class ParsedPrefix {
        /** The prefix to put back in front of the resolved path, e.g. {@code "https://"}. */
        String prefix = "";

        /** True if the path after the prefix is an absolute path, so no base path is resolved against it. */
        boolean isAbsolutePath;

        /**
         * True if what is left after the prefix is a filesystem path rather than a URL. Percent encoding is only
         * decoded for a filesystem path: a path that is still a URL has to keep its encoding, or it can no longer
         * be fetched, since a space decoded into {@code "jar:http://host/a%20b.jar!/x"} gives a URL that will not
         * even parse as a URI. Each scheme sets this according to what it leaves behind, so the innermost scheme is
         * the one that decides.
         */
        boolean remainderIsFilePath;

        /** The index of the first character after the prefix. */
        int startIdx;
    }

    /**
     * Strip any number of nested URL scheme prefixes from the beginning of a path.
     *
     * @param path
     *            the path
     * @return what was stripped, and what it says about the rest of the path.
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
                parsed.prefix += "http://";
                // Treat the part after the protocol as an absolute path, so the domain is not treated as a
                // directory relative to the current directory.
                parsed.isAbsolutePath = true;
                // Don't un-escape percent encoding etc.
                parsed.remainderIsFilePath = false;
            } else if (path.regionMatches(true, parsed.startIdx, "https://", 0, 8)) {
                // Detect https://
                matchedPrefix = true;
                parsed.startIdx += 8;
                parsed.prefix += "https://";
                parsed.isAbsolutePath = true;
                parsed.remainderIsFilePath = false;
            } else if (path.regionMatches(true, parsed.startIdx, "jrt:", 0, 4)) {
                // Detect jrt:
                matchedPrefix = true;
                parsed.startIdx += 4;
                parsed.prefix += "jrt:";
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
                final var matcher = schemeOneOrTwoSlashMatcher.matcher(relPath);
                if (matcher.find()) {
                    matchedPrefix = true;
                    final var match = matcher.group();
                    parsed.startIdx += match.length();
                    parsed.prefix += match;
                    // Treat the part after the protocol as an absolute path, so the rest of the URL is not treated
                    // as a directory relative to the current directory.
                    parsed.isAbsolutePath = true;
                    // The scheme is kept, so the result is still a URL
                    parsed.remainderIsFilePath = false;
                }
            }
        } while (matchedPrefix);
        return parsed;
    }

    /**
     * Strip the authority of a {@code "file:"} URL, which names the local machine and is not part of the path.
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
     * Determine whether what is left of a path after its scheme prefixes is an absolute path, stripping the Windows
     * UNC prefix or the slash before a drive designation if either is present.
     *
     * @param path
     *            the path
     * @param parsed
     *            the prefix parsed so far, updated in place
     */
    private static void stripAbsolutePathPrefix(final String path, final ParsedPrefix parsed) {
        // Handle Windows paths starting with a drive designation as an absolute path
        if (VersionFinder.OS == OperatingSystem.Windows) {
            if (path.startsWith("//", parsed.startIdx) || path.startsWith("\\\\", parsed.startIdx)) {
                // Windows UNC path
                parsed.startIdx += 2;
                parsed.prefix += "//";
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
     * Strip the trailing separator, and any trailing nested jar separator, from a normalized path.
     *
     * @param path
     *            the normalized path
     * @return the path, without any trailing separator.
     */
    private static String stripTrailingSeparators(final String path) {
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
        // and this is the rule NestedJarHandler applies when splitting the resulting path back apart.
        if (pathStr.endsWith("!") && JarUtils.lastIndexOfNestedJarSeparator(pathStr) == pathStr.length() - 1) {
            pathStr = pathStr.substring(0, pathStr.length() - 1);
        }
        if (pathStr.endsWith("/")) {
            pathStr = pathStr.substring(0, pathStr.length() - 1);
        }
        return pathStr.isEmpty() ? "/" : pathStr;
    }

    /**
     * Strip away any "jar:" prefix from a filename URI, and convert it to a file path, handling possibly-broken
     * mixes of filesystem and URI conventions; resolve relative paths relative to resolveBasePath.
     *
     * <p>
     * Any {@code ".."} segment is resolved textually, without consulting the filesystem, and cannot climb above the
     * root of the path or above the nearest enclosing nested jar separator. Use
     * {@link #resolveFilePath(String, String)} instead for a path that names a file on disk.
     *
     * @param resolveBasePath
     *            The base path, or null to resolve against nothing.
     * @param relativePathRaw
     *            The path to resolve relative to the base path.
     * @return The resolved path.
     */
    public static String resolve(final @Nullable String resolveBasePath, final String relativePathRaw) {
        return resolve(resolveBasePath, relativePathRaw, /* namesFileOnDisk = */ false);
    }

    /**
     * Resolve a path that names a file on disk, such as a classpath entry, in the same way that the JVM's own
     * classloader resolves it.
     *
     * <p>
     * This differs from {@link #resolve(String, String)} in that a {@code ".."} segment in the outermost section of
     * the path is left in the resolved path, for the filesystem to resolve when the path is canonicalized. Only the
     * filesystem knows what such a segment means: after a symlinked directory, {@code ".."} names the parent of the
     * directory the symlink points at, not the parent of the symlink, so collapsing it textually would name a
     * different file than the one the JVM reaches through the same path. Everything after a nested jar separator is
     * an entry name within an archive, which has no symlinks and no filesystem to ask, so a {@code ".."} there is
     * still collapsed, and still cannot climb out of the archive it is in.
     *
     * @param resolveBasePath
     *            The base path, or null to resolve against nothing.
     * @param relativePathRaw
     *            The path to resolve relative to the base path.
     * @return The resolved path.
     */
    public static String resolveFilePath(final @Nullable String resolveBasePath, final String relativePathRaw) {
        return resolve(resolveBasePath, relativePathRaw, /* namesFileOnDisk = */ true);
    }

    /**
     * Strip away any "jar:" prefix from a filename URI, and convert it to a file path, handling possibly-broken
     * mixes of filesystem and URI conventions; resolve relative paths relative to resolveBasePath.
     *
     * @param resolveBasePath
     *            The base path, or null to resolve against nothing.
     * @param relativePathRaw
     *            The path to resolve relative to the base path.
     * @param namesFileOnDisk
     *            True if the path names a file on disk, so that a {@code ".."} segment in the outermost section is
     *            left for the filesystem to resolve rather than being collapsed textually.
     * @return The resolved path.
     */
    private static String resolve(final @Nullable String resolveBasePath, final String relativePathRaw,
            final boolean namesFileOnDisk) {
        // See: http://stackoverflow.com/a/17870390/3950982
        // https://weblogs.java.net/blog/kohsuke/archive/2007/04/how_to_convert.html

        if (relativePathRaw == null || relativePathRaw.isEmpty()) {
            return resolveBasePath == null ? "" : resolveBasePath;
        }

        // Convert Tomcat's "war:" URLs into the standard "jar:" form before anything else, so that the rest of this
        // method sees a path it understands (#925)
        final var relativePath = warUrlToJarUrl(relativePathRaw);

        final var parsed = stripSchemePrefixes(relativePath);
        stripFileUrlAuthority(relativePath, parsed);
        stripAbsolutePathPrefix(relativePath, parsed);

        // Normalize the path, then add any UNC or URL prefix
        final var pathRaw = parsed.startIdx == 0 ? relativePath : relativePath.substring(parsed.startIdx);
        var pathStr = stripTrailingSeparators(normalizePath(pathRaw, parsed.remainderIsFilePath));

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
        if (parsed.isAbsolutePath || resolveBasePath == null || resolveBasePath.isEmpty()) {
            // There is no base path to resolve against, or path is an absolute path or http(s):// URL (ignore the
            // base path). A root path is the one kind of path whose final separator must not be removed, since the
            // separator is the whole of the directory's name
            pathResolved = "/".equals(pathStr) || isDriveRoot ? pathStr
                    : FileUtils.sanitizeEntryPath(pathStr, /* removeInitialSlash = */ false,
                            /* removeFinalSlash = */ true,
                            /* collapseParentSegmentsInFirstSection = */ !leaveParentSegments);
        } else {
            // Path is a relative path -- resolve it relative to the base path
            pathResolved = FileUtils.sanitizeEntryPath(
                    resolveBasePath + (resolveBasePath.endsWith("/") ? "" : "/") + pathStr,
                    /* removeInitialSlash = */ false, /* removeFinalSlash = */ true,
                    /* collapseParentSegmentsInFirstSection = */ !leaveParentSegments);
        }

        // Add any prefix back, e.g. "https://". A prefix that already ends with a separator supplies the root
        // path's own separator, so joining the two must not double it ("C:/" must not become "C://")
        if (parsed.prefix.isEmpty()) {
            return pathResolved;
        }
        return parsed.prefix.endsWith("/") && pathResolved.startsWith("/")
                ? parsed.prefix + pathResolved.substring(1)
                : parsed.prefix + pathResolved;
    }

    /**
     * Strip away any "jar:" prefix from a filename URI, and convert it to a file path, handling possibly-broken
     * mixes of filesystem and URI conventions. An "http(s):" path is returned with its scheme prefix intact.
     *
     * @param pathStr
     *            The path to resolve.
     * @return The resolved path.
     */
    public static String resolve(final String pathStr) {
        return resolve(null, pathStr);
    }
}
