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

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nonapi.io.github.classgraph.utils.VersionFinder.OperatingSystem;

/**
 * Resolve relative paths and URLs/URIs against a base path in a way that is faster than Java's URL/URI parser (and
 * much faster than Path), while aiming for cross-platform compatibility, and hopefully in particular being robust
 * to the many forms of Windows path weirdness.
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
     * {@code org.apache.tomcat.util.buf.UriUtil#warToJar}. (#925)
     */
    private static final String customWarSeparator = VersionFinder
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
     *            the buf
     */
    private static void translateSeparator(final String path, final int startIdx, final int endIdx,
            final boolean stripFinalSeparator, final StringBuilder buf) {
        for (int i = startIdx; i < endIdx; i++) {
            final char c = path.charAt(i);
            if (c == '\\' || c == '/') {
                // Strip trailing separator, if necessary
                if (i < endIdx - 1 || !stripFinalSeparator) {
                    // Remove duplicate separators
                    final char prevChar = buf.length() == 0 ? '\0' : buf.charAt(buf.length() - 1);
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
        return (c >= '0' && c <= '9') ? (c - '0') //
                : (c >= 'a' && c <= 'f') ? (c - 'a' + 10) //
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
     *            the buf
     */
    private static void unescapePercentEncoding(final String path, final int startIdx, final int endIdx,
            final StringBuilder buf) {
        if (endIdx - startIdx == 3 && path.charAt(startIdx + 1) == '2' && path.charAt(startIdx + 2) == '0') {
            // Fast path for "%20"
            buf.append(' ');
        } else {
            final byte[] bytes = new byte[(endIdx - startIdx) / 3];
            for (int i = startIdx, j = 0; i < endIdx; i += 3, j++) {
                final char c1 = path.charAt(i + 1);
                final char c2 = path.charAt(i + 2);
                final int digit1 = hexCharToInt(c1);
                final int digit2 = hexCharToInt(c2);
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
        final boolean hasPercent = path.indexOf('%') >= 0;
        if (!hasPercent && path.indexOf('\\') < 0 && !path.endsWith("/")) {
            return path;
        } else {
            final int len = path.length();
            final StringBuilder buf = new StringBuilder();
            // Decode percent encoding only for a path, never for something that is still a URL (#255)
            if (hasPercent && percentDecode) {
                // Perform '%'-decoding of path segment
                int prevEndMatchIdx = 0;
                final Matcher matcher = percentMatcher.matcher(path);
                while (matcher.find()) {
                    final int startMatchIdx = matcher.start();
                    final int endMatchIdx = matcher.end();
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
     * (#925)
     *
     * @param path
     *            The path, which may or may not be a {@code "war:"} URL.
     * @return The equivalent {@code "jar:"} URL if this is a {@code "war:"} URL, otherwise the path, unchanged.
     */
    private static String warUrlToJarUrl(final String path) {
        if (!path.regionMatches(true, 0, "war:", 0, 4)) {
            return path;
        }
        // Strip the "war:" prefix, leaving a "file:" URL that the rest of the resolver understands
        final String jarUrl = path.substring(4);
        // Mirrors the separators tried by org.apache.tomcat.util.buf.UriUtil#warToJar
        int sepIdx = jarUrl.indexOf("*/");
        if (sepIdx < 0) {
            sepIdx = jarUrl.indexOf("^/");
        }
        int sepLen = 2;
        if (sepIdx < 0 && customWarSeparator != null && !customWarSeparator.isEmpty()) {
            sepIdx = jarUrl.indexOf(customWarSeparator + "/");
            sepLen = customWarSeparator.length() + 1;
        }
        return sepIdx < 0 ? jarUrl : jarUrl.substring(0, sepIdx) + "!/" + jarUrl.substring(sepIdx + sepLen);
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
     *            The base path.
     * @param relativePathRaw
     *            The path to resolve relative to the base path.
     * @return The resolved path.
     */
    public static String resolve(final String resolveBasePath, final String relativePathRaw) {
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
     *            The base path.
     * @param relativePathRaw
     *            The path to resolve relative to the base path.
     * @return The resolved path.
     */
    public static String resolveFilePath(final String resolveBasePath, final String relativePathRaw) {
        return resolve(resolveBasePath, relativePathRaw, /* namesFileOnDisk = */ true);
    }

    /**
     * Strip away any "jar:" prefix from a filename URI, and convert it to a file path, handling possibly-broken
     * mixes of filesystem and URI conventions; resolve relative paths relative to resolveBasePath.
     *
     * @param resolveBasePath
     *            The base path.
     * @param relativePathRaw
     *            The path to resolve relative to the base path.
     * @param namesFileOnDisk
     *            True if the path names a file on disk, so that a ".." segment in the outermost section is left for
     *            the filesystem to resolve rather than being collapsed textually.
     * @return The resolved path.
     */
    private static String resolve(final String resolveBasePath, final String relativePathRaw,
            final boolean namesFileOnDisk) {
        // See: http://stackoverflow.com/a/17870390/3950982
        // https://weblogs.java.net/blog/kohsuke/archive/2007/04/how_to_convert.html

        if (relativePathRaw == null || relativePathRaw.isEmpty()) {
            return resolveBasePath == null ? "" : resolveBasePath;
        }

        // Convert Tomcat's "war:" URLs into the standard "jar:" form before anything else, so that the rest of
        // this method sees a path it understands (#925)
        final String relativePath = warUrlToJarUrl(relativePathRaw);

        String prefix = "";
        boolean isAbsolutePath = false;
        // Percent encoding is only decoded when what is left after the scheme prefixes have been stripped is a
        // filesystem path. A path that is still a URL has to keep its encoding, or it can no longer be fetched: a
        // space decoded into "jar:http://host/a%20b.jar!/x" gives a URL that will not even parse as a URI. So each
        // scheme sets this according to what it leaves behind, and the innermost scheme is the one that decides
        boolean remainderIsFilePath = false;
        int startIdx = 0;
        boolean matchedPrefix;
        do {
            matchedPrefix = false;
            if (relativePath.regionMatches(true, startIdx, "jar:", 0, 4)) {
                // "jar:" prefix can be stripped. A "jar:" URL wraps an inner URL, so whether the result is a path
                // is decided by the inner scheme, if there is one -- but a "jar:" prefix on a bare path, as in
                // "jar:/dir/x.jar!/y", leaves a filesystem path behind
                matchedPrefix = true;
                startIdx += 4;
                remainderIsFilePath = true;
            } else if (relativePath.regionMatches(true, startIdx, "http://", 0, 7)) {
                // Detect http://
                matchedPrefix = true;
                startIdx += 7;
                // Force protocol name to lowercase
                prefix += "http://";
                // Treat the part after the protocol as an absolute path, so the domain is not treated as a directory
                // relative to the current directory.
                isAbsolutePath = true;
                // Don't un-escape percent encoding etc.
                remainderIsFilePath = false;
            } else if (relativePath.regionMatches(true, startIdx, "https://", 0, 8)) {
                // Detect https://
                matchedPrefix = true;
                startIdx += 8;
                prefix += "https://";
                isAbsolutePath = true;
                remainderIsFilePath = false;
            } else if (relativePath.regionMatches(true, startIdx, "jrt:", 0, 4)) {
                // Detect jrt:
                matchedPrefix = true;
                startIdx += 4;
                prefix += "jrt:";
                isAbsolutePath = true;
                remainderIsFilePath = false;
            } else if (relativePath.regionMatches(true, startIdx, "file:", 0, 5)) {
                // Strip off "file:" prefix from relative path
                matchedPrefix = true;
                startIdx += 5;
                remainderIsFilePath = true;
            } else {
                // Preserve the number of slashes on custom URL schemes (#420)
                final String relPath = startIdx == 0 ? relativePath : relativePath.substring(startIdx);
                final Matcher matcher = schemeOneOrTwoSlashMatcher.matcher(relPath);
                if (matcher.find()) {
                    matchedPrefix = true;
                    final String match = matcher.group();
                    startIdx += match.length();
                    prefix += match;
                    // Treat the part after the protocol as an absolute path, so the rest of the URL is not treated
                    // as a directory relative to the current directory.
                    isAbsolutePath = true;
                    // The scheme is kept, so the result is still a URL
                    remainderIsFilePath = false;
                }
            }
        } while (matchedPrefix);

        // A "file:" URL with an empty authority ("file:///path", which is the spelling that Path#toUri()
        // produces) has two slashes that are not part of the path. Drop them, so that the path itself is what the
        // checks below see -- otherwise on Windows the empty authority is read as the start of a UNC path, and
        // "file:///C:/xyz" resolves to "///C:/xyz", which names neither a drive nor a network share
        if (remainderIsFilePath && relativePath.startsWith("///", startIdx)) {
            startIdx += 2;
        }

        // A "file:" URL names the local machine either with an empty authority ("file:///path") or with the
        // authority "localhost" ("file://localhost/path"), and the two mean the same local path (RFC 8089 section
        // 2). Outside Windows there is no UNC concept, so the authority would otherwise be folded into the path
        // and "file://localhost/tmp/a" would resolve to "/localhost/tmp/a", which names a different file. On
        // Windows the authority is left alone, so that it becomes the UNC path "//localhost/tmp/a" -- that is both
        // what Path#of(URI) produces there and what RFC 8089 appendix B.3 specifies
        if (remainderIsFilePath && VersionFinder.OS != OperatingSystem.Windows
                && relativePath.regionMatches(true, startIdx, "//localhost/", 0, 12)) {
            startIdx += "//localhost".length();
        }

        // Handle Windows paths starting with a drive designation as an absolute path
        if (VersionFinder.OS == OperatingSystem.Windows) {
            if (relativePath.startsWith("//", startIdx) || relativePath.startsWith("\\\\", startIdx)) {
                // Windows UNC path
                startIdx += 2;
                prefix += "//";
                isAbsolutePath = true;
            } else if (relativePath.length() - startIdx >= 2 && Character.isLetter(relativePath.charAt(startIdx))
                    && relativePath.charAt(startIdx + 1) == ':') {
                // Path like "C:/xyz", or the bare drive designation "C:"
                isAbsolutePath = true;
            } else if (relativePath.length() - startIdx >= 3
                    && (relativePath.charAt(startIdx) == '/' || relativePath.charAt(startIdx) == '\\')
                    && Character.isLetter(relativePath.charAt(startIdx + 1))
                    && relativePath.charAt(startIdx + 2) == ':') {
                // Path like "/C:/xyz", or the bare drive designation "/C:"
                isAbsolutePath = true;
                startIdx++;
            }
        }
        // Catch-all for paths starting with separator. A path consisting of nothing but a separator is the root
        // path, which is absolute too, so one character is enough here
        if (relativePath.length() - startIdx >= 1
                && (relativePath.charAt(startIdx) == '/' || relativePath.charAt(startIdx) == '\\')) {
            isAbsolutePath = true;
        }

        // Normalize the path, then add any UNC or URL prefix
        final String pathRaw = startIdx == 0 ? relativePath : relativePath.substring(startIdx);
        String pathStr = normalizePath(pathRaw, remainderIsFilePath);
        if (!pathStr.equals("/")) {
            // Remove any "!/" on end of URL
            if (pathStr.endsWith("/")) {
                pathStr = pathStr.substring(0, pathStr.length() - 1);
            }
            // Only strip a trailing '!' if it is really a nested jar separator, i.e. if it marks the whole of
            // the jarfile before it -- a trailing '!' is otherwise part of a directory name (#903).
            // Use lastIndexOfNestedJarSeparator, not indexOfNestedJarSeparator, so that the trailing '!' of a
            // doubly-nested path such as "/a/b.war!/WEB-INF/lib/c.jar!" is stripped too: the innermost
            // separator is the relevant one, and this is the rule NestedJarHandler applies when splitting the
            // resulting path back apart.
            if (pathStr.endsWith("!") && JarUtils.lastIndexOfNestedJarSeparator(pathStr) == pathStr.length() - 1) {
                pathStr = pathStr.substring(0, pathStr.length() - 1);
            }
            if (pathStr.endsWith("/")) {
                pathStr = pathStr.substring(0, pathStr.length() - 1);
            }
            if (pathStr.isEmpty()) {
                pathStr = "/";
            }
        }

        // On Windows, the root directory of a drive is "C:/", and it is a root path in the same sense that "/" is:
        // its final separator is the whole of its name, and dropping it leaves the drive designation "C:", which
        // names the current directory on drive C instead. The separator was stripped along with every other
        // trailing separator above, so put it back. A bare "C:", written without a separator, is left as written
        final boolean isDriveRoot = VersionFinder.OS == OperatingSystem.Windows && isDriveDesignation(pathStr)
                && (pathRaw.endsWith("/") || pathRaw.endsWith("\\"));
        if (isDriveRoot) {
            pathStr += "/";
        }

        // Sanitize path (resolve ".." sections, collapse "//" double separators, etc.). A ".." in the outermost
        // section is only left for the filesystem to resolve if the path is known to name a file on disk, and the
        // path still has to be a path rather than a URL, since a URL has no filesystem to ask
        final boolean leaveParentSegments = namesFileOnDisk && prefix.isEmpty();
        String pathResolved;
        if (isAbsolutePath || resolveBasePath == null || resolveBasePath.isEmpty()) {
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
        if (prefix.isEmpty()) {
            return pathResolved;
        }
        return prefix.endsWith("/") && pathResolved.startsWith("/") ? prefix + pathResolved.substring(1)
                : prefix + pathResolved;
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
