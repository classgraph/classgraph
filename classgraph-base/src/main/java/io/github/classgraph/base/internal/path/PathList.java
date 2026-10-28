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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Utilities for splitting and joining path lists delimited by {@link File#pathSeparatorChar} or a custom separator
 * character, preserving URL schemes within elements.
 */
public final class PathList {
    /** URL scheme prefixes whose colons are not path separators when splitting on {@code ':'}. */
    private static final String[] UNIX_NON_PATH_SEPARATORS = { //
            "jar:", "file:", "http://", "https://", //
            // Tomcat serves a non-exploded WAR file through its own "war:" URL protocol (#925)
            "war:", //
            // Spring Boot addresses entries within an executable jar through its own "nested:" URL protocol
            "nested:" //
    };

    /** Index of the {@code ':'} character in each entry of {@link #UNIX_NON_PATH_SEPARATORS}. */
    private static final int[] UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS;

    static {
        UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS = new int[UNIX_NON_PATH_SEPARATORS.length];
        for (var i = 0; i < UNIX_NON_PATH_SEPARATORS.length; i++) {
            UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS[i] = UNIX_NON_PATH_SEPARATORS[i].indexOf(':');
            if (UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS[i] < 0) {
                throw new RuntimeException("Could not find ':' in \"" + UNIX_NON_PATH_SEPARATORS[i] + "\"");
            }
        }
    }

    /** Not instantiable. */
    private PathList() {
        // Cannot be constructed
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Split a path list on {@link File#pathSeparatorChar}, preserving URLs with protocol specifiers.
     *
     * @param pathStr
     *            The path list to split, or {@code null}.
     * @param allowedURLSchemes
     *            Additional allowed URL schemes, or {@code null}.
     * @return The path element substrings.
     */
    public static String[] split(final @Nullable String pathStr, final @Nullable Set<String> allowedURLSchemes) {
        return split(pathStr, File.pathSeparatorChar, allowedURLSchemes);
    }

    /**
     * Split a path list on {@code separatorChar}, preserving URLs with protocol specifiers when
     * {@code separatorChar} is {@code ':'}.
     *
     * @param pathStr
     *            The path list to split, or {@code null}.
     * @param separatorChar
     *            The separator character.
     * @param allowedURLSchemes
     *            Additional allowed URL schemes, or {@code null}.
     * @return The path element substrings.
     */
    public static String[] split(final @Nullable String pathStr, final char separatorChar,
            final @Nullable Set<String> allowedURLSchemes) {
        if (pathStr == null || pathStr.isEmpty()) {
            return new String[0];
        }
        return separatorChar == ':' ? splitOnColon(pathStr, allowedURLSchemes)
                : splitOnSeparator(pathStr, separatorChar);
    }

    /**
     * Split a path list on a separator character other than {@code ':'}, trimming elements and omitting empty
     * entries.
     *
     * @param pathStr
     *            The path list to split.
     * @param separatorChar
     *            The separator character.
     * @return The non-empty trimmed path elements.
     */
    private static String[] splitOnSeparator(final String pathStr, final char separatorChar) {
        // N.B. the separator is searched for literally, rather than with String#split(), whose argument is a
        // regular expression -- a separator that is a regex metacharacter would otherwise split on the wrong thing
        final List<String> parts = new ArrayList<>();
        for (var startIdx = 0; startIdx <= pathStr.length();) {
            var endIdx = pathStr.indexOf(separatorChar, startIdx);
            if (endIdx < 0) {
                endIdx = pathStr.length();
            }
            final var part = pathStr.substring(startIdx, endIdx).trim();
            // Remove empty path components
            if (!part.isEmpty()) {
                parts.add(part);
            }
            startIdx = endIdx + 1;
        }
        return parts.toArray(String[]::new);
    }

    /**
     * Split a path list on {@code ':'}, preserving colons that belong to URL schemes, URL authorities, or
     * {@code "\\:"} escape sequences.
     *
     * @param pathStr
     *            The path list to split.
     * @param allowedURLSchemes
     *            Additional allowed URL schemes, or {@code null}.
     * @return The non-empty trimmed and unescaped path elements.
     */
    private static String[] splitOnColon(final String pathStr, final @Nullable Set<String> allowedURLSchemes) {
        // Find the ':' characters that really are path separators. The position before the start of the string and
        // the position after its end are both split points, so that the first and last parts are included. The
        // split points are found in increasing order.
        final List<Integer> splitPoints = new ArrayList<>();
        // The index of the last '/' or whitespace character before the current ':', or -1 if there is none. It is
        // updated by scanning each character once, rather than by searching back from each ':', which would take
        // time quadratic in the length of a path made of many short names with no '/' in them.
        var slashOrSpaceIdx = -1;
        var scanIdx = 0;
        for (var i = -1;;) {
            for (; scanIdx < i; scanIdx++) {
                final var c = pathStr.charAt(scanIdx);
                if (c == '/' || c <= ' ') {
                    slashOrSpaceIdx = scanIdx;
                }
            }
            // A ':' in the authority of a URL (before a port, in "user:password@", or in an IPv6 address) is part
            // of the URL, whatever its scheme. The authority runs from the "://" to the next '/', and contains no
            // whitespace, so the ':' is in an authority if the last '/' or whitespace before it ends a "://".
            if (!isSchemeOrEscapedColon(pathStr, i, allowedURLSchemes)
                    && !endsURLSchemeSeparator(pathStr, slashOrSpaceIdx)) {
                splitPoints.add(i);
            }
            // Search for next ':' character
            i = pathStr.indexOf(':', i + 1);
            if (i < 0) {
                // Add end of string marker once last ':' has been found
                splitPoints.add(pathStr.length());
                break;
            }
        }
        final List<String> parts = new ArrayList<>();
        for (var i = 1; i < splitPoints.size(); i++) {
            final int idx0 = splitPoints.get(i - 1);
            final int idx1 = splitPoints.get(i);
            // Trim, and unescape "\\:"
            final var part = pathStr.substring(idx0 + 1, idx1).trim().replace("\\:", ":");
            // Remove empty path components
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts.toArray(String[]::new);
    }

    /**
     * Test whether the {@code ':'} at {@code colonIdx} is part of a URL scheme or escaped as {@code "\\:"} rather
     * than a path separator.
     *
     * @param pathStr
     *            The path list being split.
     * @param colonIdx
     *            The index of the {@code ':'} character, or -1 for the position before the start of the string.
     * @param allowedURLSchemes
     *            Additional allowed URL schemes, or {@code null}.
     * @return {@code true} if the {@code ':'} is part of a path element rather than a separator.
     */
    private static boolean isSchemeOrEscapedColon(final String pathStr, final int colonIdx,
            final @Nullable Set<String> allowedURLSchemes) {
        // A ':' escaped as "\:" is part of a path element, not a separator (this is the escaping applied by
        // appendPathElt, and undone in splitOnColon). Escaping is a ClassGraph extension -- the JDK splits
        // java.class.path on File.pathSeparator with no escape syntax at all -- but it is safe, because
        // File.separatorChar is '/' on every platform whose File.pathSeparatorChar is ':', so a backslash before a
        // colon is never part of the path syntax there. The cost is that a classpath entry that genuinely ends in
        // a backslash (a legal, if bizarre, filename character on Unix) is joined to the entry that follows it
        // instead of being split from it.
        if (colonIdx > 0 && pathStr.charAt(colonIdx - 1) == '\\') {
            return true;
        }
        for (var j = 0; j < UNIX_NON_PATH_SEPARATORS.length; j++) {
            // Skip ':' characters in the middle of non-path-separators such as "http://"
            final var startIdx = colonIdx - UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS[j];
            if (pathStr.regionMatches(true, startIdx, UNIX_NON_PATH_SEPARATORS[j], 0,
                    UNIX_NON_PATH_SEPARATORS[j].length())
                    // Don't treat the "jar:" in the middle of "x.jar:y.jar" as a URL scheme
                    && startsAPathElement(pathStr, startIdx)) {
                return true;
            }
        }
        if (allowedURLSchemes == null || allowedURLSchemes.isEmpty()) {
            return false;
        }
        // The ':' that ends a registered custom URL scheme is part of a path element too
        for (final String scheme : allowedURLSchemes) {
            // Skip schemes already handled by the faster matching code above
            if (!"http".equals(scheme) && !"https".equals(scheme) && !"jar".equals(scheme) && !"file".equals(scheme)
                    && !"war".equals(scheme) && !"nested".equals(scheme)) {
                final var startIdx = colonIdx - scheme.length();
                if (pathStr.regionMatches(true, startIdx, scheme, 0, scheme.length())
                        && startsAPathElement(pathStr, startIdx)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Test whether {@code slashIdx} is the second slash of a {@code "://"} URL scheme separator at the start of a
     * path element.
     *
     * @param pathStr
     *            The path list being split.
     * @param slashIdx
     *            The character index to check, or -1.
     * @return {@code true} if a {@code "://"} URL scheme separator ends at {@code slashIdx}.
     */
    private static boolean endsURLSchemeSeparator(final String pathStr, final int slashIdx) {
        if (slashIdx < 2 || pathStr.charAt(slashIdx) != '/' || pathStr.charAt(slashIdx - 1) != '/'
                || pathStr.charAt(slashIdx - 2) != ':') {
            return false;
        }
        // The scheme is a letter followed by letters, digits, '+', '-' and '.' (RFC 3986, section 3.1)
        final var schemeEnd = slashIdx - 2;
        var schemeStart = schemeEnd;
        while (schemeStart > 0 && isSchemeChar(pathStr.charAt(schemeStart - 1))) {
            schemeStart--;
        }
        return schemeStart < schemeEnd && isAsciiLetter(pathStr.charAt(schemeStart))
                && startsAPathElement(pathStr, schemeStart);
    }

    /**
     * Test whether a character may appear in a URL scheme.
     *
     * @param c
     *            The character.
     * @return true if the character is an ASCII letter or digit, '+', '-' or '.'.
     */
    private static boolean isSchemeChar(final char c) {
        return isAsciiLetter(c) || c >= '0' && c <= '9' || c == '+' || c == '-' || c == '.';
    }

    /**
     * Test whether a character is an ASCII letter.
     *
     * @param c
     *            The character.
     * @return true if the character is an ASCII letter.
     */
    private static boolean isAsciiLetter(final char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    /**
     * Test whether {@code startIdx} is at the start of a path element (preceded only by whitespace after the start
     * of the string or a {@code ':'} separator).
     *
     * @param pathStr
     *            The path list being split.
     * @param startIdx
     *            The index to test.
     * @return {@code true} if a path element starts at {@code startIdx}.
     */
    private static boolean startsAPathElement(final String pathStr, final int startIdx) {
        for (var i = startIdx - 1; i >= 0; i--) {
            final var c = pathStr.charAt(i);
            if (c == ':') {
                return true;
            }
            // Anything above ' ' is not trimmed off the path element, so it is part of the element
            if (c > ' ') {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Append a path element to {@code buf}, escaping any embedded path separator.
     *
     * @param pathElt
     *            the path element
     * @param buf
     *            the buffer to append to
     */
    private static void appendPathElt(final Object pathElt, final StringBuilder buf) {
        // Escape any rogue path separators, as long as file separator is not '\\' (on Windows, if there are any
        // extra ';' characters in a path element, there's really nothing we can do to escape them, since they can't
        // be escaped as "\\;") (Use String.replace() rather than String.replaceAll(), so that both arguments are
        // literal -- in a replaceAll() replacement string, a single backslash escapes the char after it, so the
        // intended escape sequence would be emitted as a bare path separator.)
        final var path = File.separatorChar == '\\' ? pathElt.toString()
                : pathElt.toString().replace(File.pathSeparator, "\\" + File.pathSeparator);
        buf.append(path);
    }

    /**
     * Join path elements into a single string separated by {@link File#pathSeparatorChar}, escaping embedded
     * {@code ':'} separators as {@code "\\:"} on non-Windows platforms.
     *
     * @param pathElts
     *            The path elements.
     * @return The joined path list.
     */
    public static String join(final Iterable<?> pathElts) {
        final StringBuilder buf = new StringBuilder();
        var first = true;
        for (final Object pathElt : pathElts) {
            // Test for the first element rather than for an empty buffer, so that an empty first element is not
            // lost: ["", "/a"] joins to ":/a", just as ["/a", ""] joins to "/a:"
            if (!first) {
                buf.append(File.pathSeparatorChar);
            }
            first = false;
            appendPathElt(pathElt, buf);
        }
        return buf.toString();
    }
}
