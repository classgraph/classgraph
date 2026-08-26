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

import io.github.classgraph.base.internal.utils.CollectionUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The syntax of a list of paths delimited by {@link File#pathSeparatorChar}, as {@code java.class.path} and the
 * module path are written in, and as a {@code Class-Path:} manifest entry is written in with a different separator.
 *
 * <p>
 * A path element may itself be a URL, whose scheme ends in a {@code ':'} that is not a separator, so splitting a
 * path list is not simply splitting on the separator character.
 */
public final class PathList {
    /** The Constant DOUBLE_BACKSLASH_WITH_COLON. */
    private static final Pattern DOUBLE_BACKSLASH_WITH_COLON = Pattern.compile("\\\\:");

    /**
     * On everything but Windows, where the path separator is ':', need to treat the colon in these substrings as
     * non-separators, when at the beginning of the string or following a ':'.
     */
    private static final String[] UNIX_NON_PATH_SEPARATORS = { //
            "jar:", "file:", "http://", "https://", //
            // Tomcat serves a non-exploded WAR file through its own "war:" URL protocol (#925)
            "war:", //
            // Spring Boot addresses entries within an executable jar through its own "nested:" URL protocol
            "nested:" //
    };

    /**
     * The position of the colon characters in the corresponding UNIX_NON_PATH_SEPARATORS array entry.
     */
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

    /**
     * Constructor.
     */
    private PathList() {
        // Cannot be constructed
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Split a path on File.pathSeparator (':' on Linux, ';' on Windows), but also allow for the use of URLs with
     * protocol specifiers, e.g. "http://domain/jar1.jar:http://domain/jar2.jar".
     *
     * @param pathStr
     *            The path to split, or null.
     * @param allowedURLSchemes
     *            the URL schemes that may appear in path elements, or null if none have been registered
     * @return The path element substrings.
     */
    public static String[] split(final @Nullable String pathStr, final @Nullable Set<String> allowedURLSchemes) {
        return split(pathStr, File.pathSeparatorChar, allowedURLSchemes);
    }

    /**
     * Split a path on the given separator char. If the separator char is ':', also allow for the use of URLs with
     * protocol specifiers, e.g. "http://domain/jar1.jar:http://domain/jar2.jar".
     *
     * @param pathStr
     *            The path to split, or null.
     * @param separatorChar
     *            The separator char to use.
     * @param allowedURLSchemes
     *            the URL schemes that may appear in path elements, or null if none have been registered
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
     * Split a path on a separator char other than ':', which needs no special handling, since no URL scheme ends in
     * it. This is the path taken on Windows, where the separator is ';'.
     *
     * @param pathStr
     *            The path to split.
     * @param separatorChar
     *            The separator char to use.
     * @return The non-empty path element substrings, trimmed.
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
     * Split a path on ':', skipping the colons that end a URL scheme, so that HTTP(S) jars can be given in
     * {@code java.class.path}. (The JRE may not even support them, but we may as well do so.)
     *
     * @param pathStr
     *            The path to split.
     * @param allowedURLSchemes
     *            the URL schemes that may appear in path elements, or null if none have been registered
     * @return The non-empty path element substrings, trimmed and unescaped.
     */
    private static String[] splitOnColon(final String pathStr, final @Nullable Set<String> allowedURLSchemes) {
        // Find the ':' characters that really are path separators. The position before the start of the string and
        // the position after its end are both split points, so that the first and last parts are included.
        final Set<Integer> splitPoints = new HashSet<>();
        for (var i = -1;;) {
            if (!isSchemeOrEscapedColon(pathStr, i, allowedURLSchemes)) {
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
        final List<Integer> splitPointsSorted = new ArrayList<>(splitPoints);
        CollectionUtils.sortIfNotEmpty(splitPointsSorted);
        final List<String> parts = new ArrayList<>();
        for (var i = 1; i < splitPointsSorted.size(); i++) {
            final int idx0 = splitPointsSorted.get(i - 1);
            final int idx1 = splitPointsSorted.get(i);
            // Trim, and unescape "\\:"
            var part = pathStr.substring(idx0 + 1, idx1).trim();
            part = DOUBLE_BACKSLASH_WITH_COLON.matcher(part).replaceAll(":");
            // Remove empty path components
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts.toArray(String[]::new);
    }

    /**
     * Test whether a ':' in a path is part of a path element rather than a separator between two path elements,
     * either because it ends a URL scheme such as {@code "http:"}, or because it was escaped as {@code "\:"}.
     *
     * @param pathStr
     *            The path being split.
     * @param colonIdx
     *            The index of the ':' character, or -1 for the position before the start of the path (which is
     *            never a scheme colon, since a scheme needs at least one character before its colon).
     * @param allowedURLSchemes
     *            the URL schemes that may appear in path elements, or null if none have been registered
     * @return true if the ':' is part of a path element, so the path must not be split there.
     */
    private static boolean isSchemeOrEscapedColon(final String pathStr, final int colonIdx,
            final @Nullable Set<String> allowedURLSchemes) {
        // A ':' escaped as "\:" is part of a path element, not a separator (this is the escaping applied by
        // appendPathElt, and undone by the DOUBLE_BACKSLASH_WITH_COLON unescape in splitOnColon). Escaping is a
        // ClassGraph extension -- the JDK splits java.class.path on File.pathSeparator with no escape syntax at
        // all -- but it is safe, because File.separatorChar is '/' on every platform whose File.pathSeparatorChar
        // is ':', so a backslash before a colon is never part of the path syntax there. The cost is that a
        // classpath entry that genuinely ends in a backslash (a legal, if bizarre, filename character on Unix) is
        // joined to the entry that follows it instead of being split from it.
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
        // If custom URL schemes have been registered, allow those to be used as delimiters too
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
     * Test whether an index is at the start of a path element, i.e. whether everything between it and the previous
     * separator (or the start of the path) is whitespace. Whitespace is skipped because the path elements are
     * trimmed, so {@code "x.jar: http://domain/y.jar"} has to be read the same way as
     * {@code "x.jar:http://domain/y.jar"} -- otherwise the space would hide the URL scheme, and the path would be
     * split at the scheme's colon.
     *
     * @param pathStr
     *            The path being split.
     * @param startIdx
     *            The index to test.
     * @return true if a path element starts at the given index.
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
     * Append a path element to a buffer.
     *
     * @param pathElt
     *            the path element
     * @param buf
     *            the buffer to append to
     */
    private static void appendPathElt(final Object pathElt, final StringBuilder buf) {
        if (!buf.isEmpty()) {
            buf.append(File.pathSeparatorChar);
        }
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
     * Get a set of path elements as a string, from an array of objects (e.g. of String, File or URL type, whose
     * toString() method will be called to get the path component), and return the path as a single string
     * delineated with the standard path separator character.
     *
     * @param pathElts
     *            The path elements.
     * @return The delimited path formed out of the path elements.
     */
    public static String join(final Object... pathElts) {
        final StringBuilder buf = new StringBuilder();
        for (final Object pathElt : pathElts) {
            appendPathElt(pathElt, buf);
        }
        return buf.toString();
    }

    /**
     * Get a set of path elements as a string, from an array of objects (e.g. of String, File or URL type, whose
     * toString() method will be called to get the path component), and return the path as a single string
     * delineated with the standard path separator character.
     *
     * @param pathElts
     *            The path elements.
     * @return The delimited path formed out of the path elements, after calling each of their toString() methods.
     */
    public static String join(final Iterable<?> pathElts) {
        final StringBuilder buf = new StringBuilder();
        for (final Object pathElt : pathElts) {
            appendPathElt(pathElt, buf);
        }
        return buf.toString();
    }
}
