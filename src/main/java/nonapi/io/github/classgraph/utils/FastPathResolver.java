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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nonapi.io.github.classgraph.utils.VersionFinder.OperatingSystem;

/**
 * Resolve relative paths and URLs/URIs against a base path.
 */
public final class FastPathResolver {
    /** Match %-encoded characters in URLs. */
    private static final Pattern percentMatcher = Pattern.compile("([%][0-9a-fA-F][0-9a-fA-F])+");

    /**
     * Match custom URL schemes of at least two characters (to avoid matching Windows drive letters, see
     * {@link JarUtils#URL_SCHEME_PATTERN}) followed by one or two slashes.
     */
    private static final Pattern schemeOneOrTwoSlashMatcher = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+\\-.]+:/{1,2}");

    /**
     * Custom separator used by Tomcat in {@code "war:"} URLs between the WAR file path and the inner path.
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
     * Unescape runs of percent encoding, e.g. "%20%2B%20" -&gt; " + "
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
     * Normalize a path: decode percent encoding, e.g. "%20" -&gt; " " (only if {@code percentDecode} is true),
     * convert every '\\' separator to '/', collapse runs of separators into one, and remove any final separator.
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
        if (!hasPercent && path.indexOf('\\') < 0 && path.indexOf("//") < 0 && !path.endsWith("/")) {
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
     * Convert a Spring Boot {@code "nested:"} URL into the equivalent {@code "jar:"} URL.
     *
     * @param path
     *            The path, which may or may not be a {@code "nested:"} URL.
     * @return The equivalent {@code "jar:"} URL if this is a {@code "nested:"} URL, otherwise the path, unchanged.
     */
    private static String nestedUrlToJarUrl(final String path) {
        // A "nested:" URL is almost always wrapped in a "jar:" URL, which is left in place
        final int schemeIdx = path.regionMatches(true, 0, "jar:", 0, 4) ? 4 : 0;
        if (!path.regionMatches(true, schemeIdx, "nested:", 0, 7)) {
            return path;
        }
        // The location of the nested entry runs up to the first "!/", and anything after that is the path of a
        // resource within the nested entry
        final int locStartIdx = schemeIdx + 7;
        int locEndIdx = path.indexOf("!/", locStartIdx);
        if (locEndIdx < 0) {
            locEndIdx = path.length();
        }
        final String location = path.substring(locStartIdx, locEndIdx);
        // Mirrors the split performed by org.springframework.boot.loader.net.protocol.nested.NestedLocation#parse
        final int sepIdx = location.lastIndexOf("/!");
        final String jarUrl = sepIdx < 0 ? location
                : location.substring(0, sepIdx) + "!/" + location.substring(sepIdx + 2);
        // What is left of a "nested:" URL is the path of the outer archive on disk
        return path.substring(0, schemeIdx) + "file:" + jarUrl + path.substring(locEndIdx);
    }

    /**
     * Strip any {@code "jar:"} prefix from a filename URI, convert it to a file path, and resolve relative paths
     * relative to {@code resolveBasePath}, collapsing {@code ".."} segments lexically. Use
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
     * Resolve a path that names a file on disk, leaving {@code ".."} segments in the outermost section for the
     * platform to resolve during canonicalization.
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

    /** What the prefix at the beginning of a path says about the rest of the path. */
    private static class ParsedPrefix {
        /** The prefix to put back in front of the resolved path, e.g. "https://". */
        String prefix = "";

        /** True if the path after the prefix is an absolute path, so no base path is resolved against it. */
        boolean isAbsolutePath;

        /** True if the remainder after the prefix is a filesystem path rather than a URL. */
        boolean remainderIsFilePath;

        /** The index of the first character after the prefix. */
        int startIdx;

        /** Constructor. */
        ParsedPrefix() {
        }
    }

    /**
     * Strip any number of nested URL scheme prefixes from the beginning of a path.
     *
     * @param path
     *            the path
     * @return what was stripped, and what it says about the rest of the path.
     */
    private static ParsedPrefix stripSchemePrefixes(final String path) {
        final ParsedPrefix parsed = new ParsedPrefix();
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
                final String relPath = parsed.startIdx == 0 ? path : path.substring(parsed.startIdx);
                final Matcher matcher = schemeOneOrTwoSlashMatcher.matcher(relPath);
                if (matcher.find()) {
                    matchedPrefix = true;
                    final String match = matcher.group();
                    parsed.startIdx += match.length();
                    // A scheme is case-insensitive, and its canonical form is lowercase (RFC 3986 section 3.1), so
                    // lowercase it, as the schemes that are recognized by name above are. The match is a scheme
                    // followed by ':' and one or two slashes, so this changes nothing but the scheme
                    parsed.prefix += match.toLowerCase(Locale.ROOT);
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
     * Return the authority at the beginning of a URL path (e.g. {@code "host:8080"} in {@code "http://host:8080/dir"})
     * or Windows UNC path, or the empty string if none.
     *
     * @param prefix
     *            the scheme prefix that was stripped from the front of the path
     * @param path
     *            what was left after the prefix was stripped
     * @return the authority, or the empty string if there is none.
     */
    private static String urlAuthority(final String prefix, final String path) {
        if (!prefix.endsWith("//")) {
            return "";
        }
        final int authorityEndIdx = path.indexOf('/');
        return authorityEndIdx < 0 ? path : path.substring(0, authorityEndIdx);
    }

    /**
     * Return the part of a path that follows its authority, dropping any duplicate leading slashes.
     *
     * @param path
     *            the path that the authority was read from
     * @param authority
     *            the authority, or the empty string if the path has none
     * @return the path after the authority.
     */
    private static String pathAfterAuthority(final String path, final String authority) {
        if (authority.isEmpty()) {
            return path;
        }
        int startIdx = authority.length();
        while (startIdx + 1 < path.length() && path.charAt(startIdx) == '/' && path.charAt(startIdx + 1) == '/') {
            startIdx++;
        }
        return path.substring(startIdx);
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

        // Convert Tomcat's "war:" URLs (#925) and Spring Boot's "nested:" URLs into the standard "jar:" form
        // before anything else, so that the rest of this method sees a path it understands
        final String relativePath = nestedUrlToJarUrl(warUrlToJarUrl(relativePathRaw));

        final ParsedPrefix parsed = stripSchemePrefixes(relativePath);
        String prefix = parsed.prefix;
        boolean isAbsolutePath = parsed.isAbsolutePath;
        boolean remainderIsFilePath = parsed.remainderIsFilePath;
        int startIdx = parsed.startIdx;

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

        // Handle Windows paths starting with a drive designation as an absolute path. A path that keeps its scheme
        // prefix is still a URL, not a file path, so none of this applies to it: dropping the slash before the
        // drive in "custom:///C:/a" would make "C:" the URL's authority
        if (VersionFinder.OS == OperatingSystem.Windows && prefix.isEmpty()) {
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
        final String pathResolved;
        if (isAbsolutePath || resolveBasePath == null || resolveBasePath.isEmpty()) {
            // There is no base path to resolve against, or path is an absolute path or http(s):// URL (ignore the
            // base path). A root path is the one kind of path whose final separator must not be removed, since the
            // separator is the whole of the directory's name
            final String authority = urlAuthority(prefix, pathStr);
            prefix += authority;
            final String path = pathAfterAuthority(pathStr, authority);
            pathResolved = "/".equals(path) || isDriveRoot ? path
                    : FileUtils.sanitizeEntryPath(path, /* removeInitialSlash = */ false,
                            /* removeFinalSlash = */ true,
                            /* collapseParentSegmentsInFirstSection = */ !leaveParentSegments);
        } else {
            // Path is a relative path -- resolve it relative to the base path. The base path may be a URL, e.g. the
            // dir of a jarfile that was fetched over http, so its scheme prefix is split off before the two are
            // joined and added back below: sanitizing the joined path would otherwise collapse the empty segment
            // between the scheme and the authority, and turn "http://host/dir" into "http:/host/dir"
            final ParsedPrefix parsedBasePath = stripSchemePrefixes(resolveBasePath);
            final String basePathRaw = parsedBasePath.startIdx == 0 ? resolveBasePath
                    : resolveBasePath.substring(parsedBasePath.startIdx);
            final String authority = urlAuthority(parsedBasePath.prefix, basePathRaw);
            prefix = parsedBasePath.prefix + authority;
            final String basePath = pathAfterAuthority(basePathRaw, authority);
            pathResolved = FileUtils.sanitizeEntryPath(basePath + (basePath.endsWith("/") ? "" : "/") + pathStr,
                    /* removeInitialSlash = */ false, /* removeFinalSlash = */ true,
                    /* collapseParentSegmentsInFirstSection = */ !leaveParentSegments);
        }

        // Add any prefix back, e.g. "https://". A prefix that already ends with a separator supplies the root
        // path's own separator, so joining the two must not double it ("C:/" must not become "C://").
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
