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

    /** Match custom URLs that are followed by one or two slashes. */
    private static final Pattern schemeOneOrTwoSlashMatcher = Pattern.compile("^[a-zA-Z+\\-.]+:/{1,2}");

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
     * @param isFileOrJarURL
     *            True if this is a "file:" or "jar:" URL.
     * @return The normalized path.
     */
    public static String normalizePath(final String path, final boolean isFileOrJarURL) {
        final boolean hasPercent = path.indexOf('%') >= 0;
        if (!hasPercent && path.indexOf('\\') < 0 && !path.endsWith("/")) {
            return path;
        } else {
            final int len = path.length();
            final StringBuilder buf = new StringBuilder();
            // Only "file:" and "jar:" URLs are %-decoded (issue 255)
            if (hasPercent && isFileOrJarURL) {
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
                // Fast path -- no '%', or "http(s)://" or "jrt:" URL, or non-"file:" or non-"jar:" URL
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
     * @param resolveBasePath
     *            The base path.
     * @param relativePath
     *            The path to resolve relative to the base path.
     * @return The resolved path.
     */
    public static String resolve(final String resolveBasePath, final String relativePathRaw) {
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
        boolean isFileOrJarURL = false;
        int startIdx = 0;
        boolean matchedPrefix;
        do {
            matchedPrefix = false;
            if (relativePath.regionMatches(true, startIdx, "jar:", 0, 4)) {
                // "jar:" prefix can be stripped
                matchedPrefix = true;
                startIdx += 4;
                isFileOrJarURL = true;
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
            } else if (relativePath.regionMatches(true, startIdx, "https://", 0, 8)) {
                // Detect https://
                matchedPrefix = true;
                startIdx += 8;
                prefix += "https://";
                isAbsolutePath = true;
            } else if (relativePath.regionMatches(true, startIdx, "jrt:", 0, 4)) {
                // Detect jrt:
                matchedPrefix = true;
                startIdx += 4;
                prefix += "jrt:";
                isAbsolutePath = true;
            } else if (relativePath.regionMatches(true, startIdx, "file:", 0, 5)) {
                // Strip off "file:" prefix from relative path
                matchedPrefix = true;
                startIdx += 5;
                isFileOrJarURL = true;
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
                }
            }
        } while (matchedPrefix);

        // Handle Windows paths starting with a drive designation as an absolute path
        if (VersionFinder.OS == OperatingSystem.Windows) {
            if (relativePath.startsWith("//", startIdx) || relativePath.startsWith("\\\\", startIdx)) {
                // Windows UNC path
                startIdx += 2;
                prefix += "//";
                isAbsolutePath = true;
            } else if (relativePath.length() - startIdx > 2 && Character.isLetter(relativePath.charAt(startIdx))
                    && relativePath.charAt(startIdx + 1) == ':') {
                // Path like "C:/xyz"
                isAbsolutePath = true;
            } else if (relativePath.length() - startIdx > 3
                    && (relativePath.charAt(startIdx) == '/' || relativePath.charAt(startIdx) == '\\')
                    && Character.isLetter(relativePath.charAt(startIdx + 1))
                    && relativePath.charAt(startIdx + 2) == ':') {
                // Path like "/C:/xyz"
                isAbsolutePath = true;
                startIdx++;
            }
        }
        // Catch-all for paths starting with separator
        if (relativePath.length() - startIdx > 1
                && (relativePath.charAt(startIdx) == '/' || relativePath.charAt(startIdx) == '\\')) {
            isAbsolutePath = true;
        }

        // Normalize the path, then add any UNC or URL prefix
        String pathStr = normalizePath(startIdx == 0 ? relativePath : relativePath.substring(startIdx),
                isFileOrJarURL);
        if (!pathStr.equals("/")) {
            // Remove any "!/" on end of URL
            if (pathStr.endsWith("/")) {
                pathStr = pathStr.substring(0, pathStr.length() - 1);
            }
            // Only strip a trailing '!' if it is really a nested jar separator, i.e. if it marks the whole of
            // the jarfile before it -- a trailing '!' is otherwise part of a directory name (#903)
            if (pathStr.endsWith("!") && JarUtils.indexOfNestedJarSeparator(pathStr) == pathStr.length() - 1) {
                pathStr = pathStr.substring(0, pathStr.length() - 1);
            }
            if (pathStr.endsWith("/")) {
                pathStr = pathStr.substring(0, pathStr.length() - 1);
            }
            if (pathStr.isEmpty()) {
                pathStr = "/";
            }
        }

        // Sanitize path (resolve ".." sections, collapse "//" double separators, etc.)
        String pathResolved;
        if (isAbsolutePath || resolveBasePath == null || resolveBasePath.isEmpty()) {
            // There is no base path to resolve against, or path is an absolute path or http(s):// URL
            // (ignore the base path)
            pathResolved = FileUtils.sanitizeEntryPath(pathStr, /* removeInitialSlash = */ false,
                    /* removeFinalSlash = */ true);
        } else {
            // Path is a relative path -- resolve it relative to the base path
            pathResolved = FileUtils.sanitizeEntryPath(
                    resolveBasePath + (resolveBasePath.endsWith("/") ? "" : "/") + pathStr,
                    /* removeInitialSlash = */ false, /* removeFinalSlash = */ true);
        }

        // Add any prefix back, e.g. "https://"
        return prefix.isEmpty() ? pathResolved : prefix + pathResolved;
    }

    /**
     * Strip away any "jar:" prefix from a filename URI, and convert it to a file path, handling possibly-broken
     * mixes of filesystem and URI conventions. Returns null if relativePathStr is an "http(s):" path.
     * 
     * @param pathStr
     *            The path to resolve.
     * @return The resolved path.
     */
    public static String resolve(final String pathStr) {
        return resolve(null, pathStr);
    }
}
