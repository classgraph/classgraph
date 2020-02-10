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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolve relative paths and URLs/URIs against a base path in a way that is faster than Java's URL/URI parser (and
 * much faster than Path), while aiming for cross-platform compatibility, and hopefully in particular being robust
 * to the many forms of Windows path weirdness.
 */
public final class FastPathResolver {
    /** Match %-encoded characters in URLs. */
    private static final Pattern percentMatcher = Pattern.compile("([%][0-9a-fA-F][0-9a-fA-F])+");

    /** True if we're running on Windows. */
    private static final boolean WINDOWS = File.separatorChar == '\\';

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
     * Strip away any "jar:" prefix from a filename URI, and convert it to a file path, handling possibly-broken
     * mixes of filesystem and URI conventions; resolve relative paths relative to resolveBasePath.
     * 
     * @param resolveBasePath
     *            The base path.
     * @param relativePath
     *            The path to resolve relative to the base path.
     * @return The resolved path.
     */
    public static String resolve(final String resolveBasePath, final String relativePath) {
        // See: http://stackoverflow.com/a/17870390/3950982
        // https://weblogs.java.net/blog/kohsuke/archive/2007/04/how_to_convert.html

        if (relativePath == null || relativePath.isEmpty()) {
            return resolveBasePath == null ? "" : resolveBasePath;
        }

        String prefix = "";
        boolean isAbsolutePath = false;
        boolean isFileOrJarURL = false;
        int startIdx = 0;
        if (relativePath.regionMatches(true, startIdx, "jar:", 0, 4)) {
            // "jar:" prefix can be stripped
            startIdx += 4;
            isFileOrJarURL = true;
        }
        if (relativePath.regionMatches(true, startIdx, "http://", 0, 7)) {
            // Detect http://
            startIdx += 7;
            // Force protocol name to lowercase
            prefix = "http://";
            // Treat the part after the protocol as an absolute path, so the domain is not treated as a directory
            // relative to the current directory.
            isAbsolutePath = true;
            // Don't un-escape percent encoding etc.
        } else if (relativePath.regionMatches(true, startIdx, "https://", 0, 8)) {
            // Detect https://
            startIdx += 8;
            prefix = "https://";
            isAbsolutePath = true;
        } else if (relativePath.regionMatches(true, startIdx, "jrt:", 0, 5)) {
            // Detect jrt:
            startIdx += 4;
            prefix = "jrt:";
            isAbsolutePath = true;
        } else if (relativePath.regionMatches(true, startIdx, "file:", 0, 5)) {
            // Strip off any "file:" prefix from relative path
            startIdx += 5;
            if (WINDOWS) {
                if (relativePath.startsWith("\\\\\\\\", startIdx) || relativePath.startsWith("////", startIdx)) {
                    // Windows UNC URL
                    startIdx += 4;
                    prefix = "//";
                    isAbsolutePath = true;
                } else {
                    if (relativePath.startsWith("\\\\", startIdx)) {
                        startIdx += 2;
                    }
                }
            }
            if (relativePath.startsWith("//", startIdx)) {
                startIdx += 2;
            }
            isFileOrJarURL = true;
        } else if (WINDOWS && (relativePath.startsWith("//") || relativePath.startsWith("\\\\"))) {
            // Windows UNC path
            startIdx += 2;
            prefix = "//";
            isAbsolutePath = true;
        }
        // Handle Windows paths starting with a drive designation as an absolute path
        if (WINDOWS) {
            if (relativePath.length() - startIdx > 2 && Character.isLetter(relativePath.charAt(startIdx))
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
            if (pathStr.endsWith("!")) {
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
            pathResolved = FileUtils.sanitizeEntryPath(pathStr, /* removeInitialSlash = */ false);
        } else {
            // Path is a relative path -- resolve it relative to the base path
            pathResolved = FileUtils.sanitizeEntryPath(
                    resolveBasePath + (resolveBasePath.endsWith("/") ? "" : "/") + pathStr,
                    /* removeInitialSlash = */ false);
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
