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

import io.github.classgraph.base.internal.utils.VersionFinder;
import io.github.classgraph.base.internal.utils.VersionFinder.OperatingSystem;

/**
 * Utilities for parsing and sanitizing paths, nested jar separators, and path segments.
 */
public final class PathSyntax {
    /**
     * The separator between the random prefix of a ClassGraph temporary filename and the extracted file's leafname.
     */
    public static final String TEMP_FILENAME_LEAF_SEPARATOR = "---";

    /** The prefix for all ClassGraph temporary filenames. */
    public static final String TEMP_FILENAME_PREFIX = "ClassGraph--";

    /** Not instantiable. */
    private PathSyntax() {
        // Cannot be constructed
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Sanitize a host path against path traversal ("zip slip") by collapsing {@code "."}, empty segments
     * ({@code "//"}), and {@code ".."} segments within each {@code '!'} nested jar section without navigating above
     * the section root.
     *
     * @param prefix
     *            The scheme prefix and authority stripped from the front of the path, or the empty string for a
     *            filesystem path.
     * @param path
     *            The path to sanitize.
     * @param removeInitialSlash
     *            If {@code true}, remove any leading {@code '/'} characters.
     * @param removeFinalSlash
     *            If {@code true}, remove any trailing {@code '/'} characters.
     * @param collapseParentSegmentsInFirstSection
     *            If {@code true}, collapse {@code ".."} segments before the first nested jar separator; if
     *            {@code false}, preserve them for the filesystem to resolve.
     * @return The sanitized path.
     */
    public static String sanitizeEntryPath(final String prefix, final String path, final boolean removeInitialSlash,
            final boolean removeFinalSlash, final boolean collapseParentSegmentsInFirstSection) {
        if (path.isEmpty()) {
            return "";
        }

        // A '!' is only a nested jar separator if the path before it names an existing jarfile -- it is otherwise a
        // legal filename character, and must not be treated as a path hierarchy root (#903). A separator that falls
        // within the prefix is not in the path at all. If ".." is collapsed textually, the file that decides this
        // is the one the collapsed path names, not the one the filesystem finds by resolving "..", which on Linux
        // and macOS only exists if the directory that ".." removes exists too
        final var nestedJarSepIdx = Math.max(-1, indexOfNestedJarSeparator(prefix + path,
                /* collapseParentSegmentsFromIdx = */ collapseParentSegmentsInFirstSection ? prefix.length() : -1)
                - prefix.length());
        return sanitize(path, nestedJarSepIdx, removeInitialSlash, removeFinalSlash,
                collapseParentSegmentsInFirstSection);
    }

    /**
     * Sanitize a relative path within a directory or archive against path traversal ("zip slip"), collapsing
     * {@code "."}, empty segments, and {@code ".."} segments without navigating above the root, and stripping
     * leading {@code '/'} characters. Treats {@code '!'} as an ordinary character.
     *
     * @param relativePath
     *            The path to sanitize.
     * @param removeFinalSlash
     *            If {@code true}, remove any trailing {@code '/'} characters.
     * @return The sanitized path.
     */
    public static String sanitizeRelativePath(final String relativePath, final boolean removeFinalSlash) {
        if (relativePath.isEmpty()) {
            return "";
        }
        return sanitize(relativePath, /* nestedJarSepIdx = */ -1, /* removeInitialSlash = */ true, removeFinalSlash,
                /* collapseParentSegmentsInFirstSection = */ true);
    }

    /**
     * Sanitize a non-empty path given the index of its outermost nested jar separator.
     *
     * @param path
     *            The path to sanitize.
     * @param nestedJarSepIdx
     *            The index of the outermost nested jar separator {@code '!'} in the path, or -1 if none.
     * @param removeInitialSlash
     *            If {@code true}, remove any leading {@code '/'} characters.
     * @param removeFinalSlash
     *            If {@code true}, remove any trailing {@code '/'} characters.
     * @param collapseParentSegmentsInFirstSection
     *            If {@code false}, preserve {@code ".."} segments before the first nested jar separator.
     * @return The sanitized path.
     */
    private static String sanitize(final String path, final int nestedJarSepIdx, final boolean removeInitialSlash,
            final boolean removeFinalSlash, final boolean collapseParentSegmentsInFirstSection) {
        final var pathLen = path.length();
        final var pathHasInitialSlash = path.charAt(0) == '/';
        final var pathHasInitialSlashSlash = pathHasInitialSlash && pathLen > 1 && path.charAt(1) == '/';
        final StringBuilder pathSanitized = new StringBuilder(pathLen + 16);
        if (hasSegmentToSanitize(path, nestedJarSepIdx, collapseParentSegmentsInFirstSection)) {
            appendSanitizedSegments(path, nestedJarSepIdx, collapseParentSegmentsInFirstSection, pathSanitized);
            if (pathSanitized.isEmpty() && pathHasInitialSlash) {
                pathSanitized.append('/');
            }
        } else {
            pathSanitized.append(path);
        }

        // Intended to preserve the double slash at the start of UNC paths (#736). e.g. //server/file/path
        if (VersionFinder.OS == OperatingSystem.Windows && pathHasInitialSlashSlash) {
            pathSanitized.insert(0, '/');
        }

        // Strip the final slashes before the initial ones, so that for a path consisting only of slashes (which is
        // what "/.." and "/." normalize to), truncating the buffer cannot leave it shorter than startIdx
        if (removeFinalSlash) {
            while (!pathSanitized.isEmpty() && pathSanitized.charAt(pathSanitized.length() - 1) == '/') {
                pathSanitized.setLength(pathSanitized.length() - 1);
            }
        }
        var startIdx = 0;
        if (removeInitialSlash || !pathHasInitialSlash) {
            // Strip off leading "/" if it needs to be removed, or if it wasn't present in the original path (the
            // string-building code above prepends "/" to every segment). Note that "/" is always added after "!",
            // since "jar:" URLs expect this.
            while (startIdx < pathSanitized.length() && pathSanitized.charAt(startIdx) == '/') {
                startIdx++;
            }
        }

        return pathSanitized.substring(startIdx);
    }

    /**
     * Check whether a path contains any empty, {@code "."}, or collapsible {@code ".."} segment, or an empty
     * trailing nested jar section.
     *
     * @param path
     *            The path to check.
     * @param nestedJarSepIdx
     *            The index of the first nested jar separator {@code '!'} in the path, or -1 if none.
     * @param collapseParentSegmentsInFirstSection
     *            If {@code false}, preserve {@code ".."} segments before the first nested jar separator.
     * @return {@code true} if the path requires sanitization.
     */
    private static boolean hasSegmentToSanitize(final String path, final int nestedJarSepIdx,
            final boolean collapseParentSegmentsInFirstSection) {
        // Find all '/' and nested jar separator '!' character positions, which split a path into segments. This
        // scan reads the path via charAt() rather than copying it into a char[], since the common case is that
        // nothing needs sanitizing, and the copy would then be pure overhead.
        final var pathLen = path.length();
        var lastSepIdx = -1;
        var prevC = '\0';
        var inFirstSection = true;
        var sectionHasSegment = false;
        for (int i = 0, ii = pathLen + 1; i < ii; i++) {
            // The end of the path ends the last segment. It is detected by its index, not by the '\0' stand-in
            // character, since '\0' is an ordinary character within a path. At the end of the path, a segment of
            // length 0 means the previous character was a separator, so prevC is never '\0' there.
            final var atEnd = i == pathLen;
            final var c = atEnd ? '\0' : path.charAt(i);
            final var isSectionMarker = c == '!' && isNestedJarSeparatorAt(path, i, nestedJarSepIdx);
            if (c == '/' || isSectionMarker || atEnd) {
                final var segmentLength = i - (lastSepIdx + 1);
                if (
                // Found empty segment "//" or "!!"
                (segmentLength == 0 && prevC == c)
                        // Found empty segment "/!"
                        || (segmentLength == 0 && isSectionMarker && prevC == '/')
                        // Found segment "."
                        || (segmentLength == 1 && path.charAt(i - 1) == '.')
                        // Found segment ".." that has to be collapsed
                        || (segmentLength == 2 && path.charAt(i - 2) == '.' && path.charAt(i - 1) == '.'
                                && (collapseParentSegmentsInFirstSection || !inFirstSection))) {
                    return true;
                }
                if (segmentLength > 0) {
                    sectionHasSegment = true;
                }
                // Found a last section, after a nested jar separator, with no segments, as in a trailing "!" or
                // "!/". An earlier section with no segments, as in "!/!/", ends in an empty segment, found above
                if (atEnd && !inFirstSection && !sectionHasSegment) {
                    return true;
                }
                if (isSectionMarker) {
                    inFirstSection = false;
                    sectionHasSegment = false;
                }
                lastSepIdx = i;
            }
            prevC = c;
        }
        return false;
    }

    /**
     * Append sanitized path sections and segments to {@code pathSanitized}.
     *
     * @param path
     *            The path to sanitize.
     * @param nestedJarSepIdx
     *            The index of the first nested jar separator {@code '!'} in the path, or -1 if none.
     * @param collapseParentSegmentsInFirstSection
     *            If {@code false}, preserve {@code ".."} segments before the first nested jar separator.
     * @param pathSanitized
     *            The buffer to append the sanitized path to.
     */
    private static void appendSanitizedSegments(final String path, final int nestedJarSepIdx,
            final boolean collapseParentSegmentsInFirstSection, final StringBuilder pathSanitized) {
        // Sanitize between "!" section markers separately (".." should not apply past preceding "!")
        final var pathLen = path.length();
        final List<List<CharSequence>> allSectionSegments = new ArrayList<>();
        List<CharSequence> currSectionSegments = new ArrayList<>();
        allSectionSegments.add(currSectionSegments);
        var lastSepIdx = -1;
        var inFirstSection = true;
        for (var i = 0; i < pathLen + 1; i++) {
            // The end of the path ends the last segment ('\0' is an ordinary character within a path)
            final var atEnd = i == pathLen;
            final var c = atEnd ? '\0' : path.charAt(i);
            final var isSectionMarker = c == '!' && isNestedJarSeparatorAt(path, i, nestedJarSepIdx);
            if (c == '/' || isSectionMarker || atEnd) {
                final var segmentStartIdx = lastSepIdx + 1;
                final var segmentLen = i - segmentStartIdx;
                if (segmentLen == 0 || (segmentLen == 1 && path.charAt(segmentStartIdx) == '.')) {
                    // Ignore empty segment "//" or idempotent segment "/./"
                } else if (segmentLen == 2 && path.charAt(segmentStartIdx) == '.'
                        && path.charAt(segmentStartIdx + 1) == '.'
                        && (collapseParentSegmentsInFirstSection || !inFirstSection)) {
                    // Remove one segment if ".." encountered, but do not allow ".." above top of hierarchy
                    if (!currSectionSegments.isEmpty()) {
                        currSectionSegments.remove(currSectionSegments.size() - 1);
                    }
                } else {
                    // Encountered normal path segment
                    currSectionSegments.add(path.subSequence(segmentStartIdx, segmentStartIdx + segmentLen));
                }
                if (isSectionMarker) {
                    inFirstSection = false;
                    if (!currSectionSegments.isEmpty()) {
                        // Begin new section
                        currSectionSegments = new ArrayList<>();
                        allSectionSegments.add(currSectionSegments);
                    }
                }
                lastSepIdx = i;
            }
        }
        // Turn sections and segments back into path string
        for (final List<CharSequence> sectionSegments : allSectionSegments) {
            if (!sectionSegments.isEmpty()) {
                // Separate sections with "!"
                if (!pathSanitized.isEmpty()) {
                    pathSanitized.append('!');
                }
                for (final CharSequence sectionSegment : sectionSegments) {
                    pathSanitized.append('/');
                    pathSanitized.append(sectionSegment);
                }
            }
        }
        // Keep a final '/', as a path that needs no sanitizing keeps it, unless the last section has no segments,
        // in which case the '/' is dropped with that section and the separator before it
        if (path.charAt(pathLen - 1) == '/' && !currSectionSegments.isEmpty()) {
            pathSanitized.append('/');
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Determine whether the {@code '!'} at {@code plingIdx} is a nested jar separator. If the separator at
     * {@code outermostSepIdx} is followed by {@code '/'}, only later {@code '!'} characters followed by {@code '/'}
     * (or at the end of the path) are treated as separators; otherwise every {@code '!'} from
     * {@code outermostSepIdx} onwards is treated as a separator.
     *
     * @param path
     *            the path.
     * @param plingIdx
     *            the index of a {@code '!'} character within the path.
     * @param outermostSepIdx
     *            the index of the outermost separator from {@link #indexOfNestedJarSeparator(String)}, or -1 if
     *            none.
     * @return {@code true} if the {@code '!'} at {@code plingIdx} is a nested jar separator.
     */
    // #903
    public static boolean isNestedJarSeparatorAt(final String path, final int plingIdx, final int outermostSepIdx) {
        if (outermostSepIdx < 0 || plingIdx < outermostSepIdx) {
            return false;
        }
        return !endsSectionOfPath(path, outermostSepIdx) || endsSectionOfPath(path, plingIdx);
    }

    /**
     * Determine whether the character at {@code idx} is followed by {@code '/'} or is at the end of the path.
     *
     * @param path
     *            the path.
     * @param idx
     *            the index of a character within the path.
     * @return {@code true} if the character at {@code idx} ends a path section.
     */
    private static boolean endsSectionOfPath(final String path, final int idx) {
        return idx == path.length() - 1 || path.charAt(idx + 1) == '/';
    }

    /**
     * Find the index of the outermost nested jar separator ({@code '!'}) in a path, or -1 if none. Distinguishes a
     * separator from a literal {@code '!'} in a filename by checking whether the preceding path names an existing
     * regular file (or, for non-local URLs, by finding the first {@code '!'} after the URL authority).
     *
     * @param path
     *            the path, with any {@code "jar:"} and {@code "file:"} scheme prefixes already stripped.
     * @return the index of the outermost nested jar separator, or -1 if none.
     */
    // #903
    public static int indexOfNestedJarSeparator(final String path) {
        return indexOfNestedJarSeparator(path, /* collapseParentSegmentsFromIdx = */ -1);
    }

    /**
     * Find the index of the outermost nested jar separator ({@code '!'}) in a path, optionally collapsing
     * {@code ".."} segments before testing the filesystem.
     *
     * @param path
     *            the path, with any {@code "jar:"} and {@code "file:"} scheme prefixes already stripped.
     * @param collapseParentSegmentsFromIdx
     *            if non-negative, the index from which {@code ".."} segments are collapsed textually before testing
     *            the filesystem; if -1, the path is tested as written.
     * @return the index of the outermost nested jar separator, or -1 if none.
     */
    private static int indexOfNestedJarSeparator(final String path, final int collapseParentSegmentsFromIdx) {
        final var firstPlingIdx = path.indexOf('!');
        if (firstPlingIdx < 0) {
            return -1;
        }
        var plingIdx = firstPlingIdx;
        while (plingIdx >= 0) {
            // The outermost jarfile has to exist as a regular file for the classpath element to be scannable, so if
            // the path before the '!' names one, this '!' is the outermost separator.
            // N.B. the path is not resolved via FastPathResolver here, since that calls #sanitizeEntryPath, which
            // calls this method -- a relative path is resolved by java.io.File against the current directory, which
            // is the same base path anyway
            if (FileUtils.isFile(fileToTest(path, plingIdx, collapseParentSegmentsFromIdx))) {
                return plingIdx;
            }
            plingIdx = path.indexOf('!', plingIdx + 1);
        }
        // No prefix of the path names a local file, so either the path has no separator, or its jarfile is remote
        // and could not be stat-ed however the path is spelled. Test the whole path to tell the two apart: if it
        // exists locally, it is a path whose every '!' is a literal filename character. Only if nothing local
        // answers to it is it read as a URL, and the syntactic rule used to find the separator.
        // (A path that names nothing at all cannot be told from a URL, but it fails to open either way.)
        if (!URLPaths.startsWithURLScheme(path)
                || FileUtils.exists(fileToTest(path, path.length(), collapseParentSegmentsFromIdx))) {
            return -1;
        }
        // A '!' is a legal character in the authority of a URL, in its userinfo or a registered host name, and is
        // never a separator there, since the authority does not name a file. Taking it as the separator would put
        // the separator outside the path, so that a ".." after the jarfile's own separator could climb out of it.
        // A URL scheme cannot contain a ':', so the first ':' ends the scheme
        var pathStartIdx = path.indexOf(':') + 1;
        if (path.startsWith("//", pathStartIdx)) {
            pathStartIdx = path.indexOf('/', pathStartIdx + 2);
            if (pathStartIdx < 0) {
                // The URL is all authority, with no path
                return -1;
            }
        }
        return path.indexOf('!', pathStartIdx);
    }

    /**
     * Return the {@link File} for testing whether a path prefix names an existing file.
     *
     * @param path
     *            the path.
     * @param endIdx
     *            the end index of the prefix of the path to test.
     * @param collapseParentSegmentsFromIdx
     *            if non-negative, the index from which {@code ".."} segments are collapsed textually; if -1, the
     *            prefix is tested as written.
     * @return the file to test.
     */
    private static File fileToTest(final String path, final int endIdx, final int collapseParentSegmentsFromIdx) {
        if (collapseParentSegmentsFromIdx < 0 || collapseParentSegmentsFromIdx >= endIdx) {
            return new File(path.substring(0, endIdx));
        }
        return new File(path.substring(0, collapseParentSegmentsFromIdx)
                + sanitize(path.substring(collapseParentSegmentsFromIdx, endIdx), /* nestedJarSepIdx = */ -1,
                        /* removeInitialSlash = */ false, /* removeFinalSlash = */ false,
                        /* collapseParentSegmentsInFirstSection = */ true));
    }

    /**
     * Find the index of the innermost nested jar separator ({@code '!'}) in a path, or -1 if none.
     *
     * @param path
     *            the path, with any {@code "jar:"} and {@code "file:"} scheme prefixes already stripped.
     * @return the index of the innermost nested jar separator, or -1 if none.
     */
    // #903
    public static int lastIndexOfNestedJarSeparator(final String path) {
        final var outermostSepIdx = indexOfNestedJarSeparator(path);
        if (outermostSepIdx < 0) {
            return -1;
        }
        // Only the last element of a '!'-delimited path may be a non-jar path, so each separator after the
        // outermost one nests a level deeper, and the last of them is the innermost separator
        for (var plingIdx = path.lastIndexOf('!'); plingIdx > outermostSepIdx; plingIdx = path.lastIndexOf('!',
                plingIdx - 1)) {
            if (isNestedJarSeparatorAt(path, plingIdx, outermostSepIdx)) {
                return plingIdx;
            }
        }
        return outermostSepIdx;
    }

    /**
     * Ensure every nested jar separator in {@code path} is followed by {@code '/'}, leaving literal {@code '!'}
     * characters in filenames unchanged.
     *
     * @param path
     *            the path, with any {@code "jar:"} and {@code "file:"} scheme prefixes already stripped.
     * @return the path with {@code '/'} added after each bare nested jar separator.
     */
    // #903
    public static String toJarUrlSeparators(final String path) {
        final var outermostSepIdx = indexOfNestedJarSeparator(path);
        if (outermostSepIdx < 0) {
            return path;
        }
        final var pathLen = path.length();
        final var pathRewritten = new StringBuilder(pathLen + 8);
        pathRewritten.append(path, 0, outermostSepIdx);
        for (var i = outermostSepIdx; i < pathLen; i++) {
            final var c = path.charAt(i);
            pathRewritten.append(c);
            if (c == '!' && (i == pathLen - 1 || path.charAt(i + 1) != '/')
                    && isNestedJarSeparatorAt(path, i, outermostSepIdx)) {
                pathRewritten.append('/');
            }
        }
        return pathRewritten.toString();
    }

    /**
     * Determine whether a path begins with a URL scheme and does not name an existing local file or directory.
     *
     * @param path
     *            the path, with any {@code "jar:"} and {@code "file:"} scheme prefixes already stripped.
     * @return {@code true} if the path is a URL rather than a local filesystem path.
     */
    public static boolean hasURLScheme(final String path) {
        if (!URLPaths.startsWithURLScheme(path)) {
            // Nothing shaped like a scheme at the front, so the filesystem does not need to be consulted at all
            return false;
        }
        // Only the outermost element of the path could carry a scheme, so that is the part to test for existence
        final var sepIdx = indexOfNestedJarSeparator(path);
        return !FileUtils.exists(new File(sepIdx < 0 ? path : path.substring(0, sepIdx)));
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Return the leafname of a path after stripping any nested jar suffix from the outermost {@code '!'} separator
     * onwards and any ClassGraph temporary filename prefix.
     *
     * @param path
     *            A file path.
     * @return The leafname of the path.
     */
    public static String leafName(final String path) {
        // Only a '!' that separates a jarfile from a path within it ends the leafname -- a '!' is an ordinary
        // filename character otherwise. Ending the leafname at the first '!' regardless meant that the leafname of
        // a jar in a directory whose name contains a '!' was the directory name rather than the jar name, so the
        // jar matched no accept or reject criterion and was silently skipped
        // #903
        final var sepIdx = indexOfNestedJarSeparator(path);
        return segmentEndingAt(path, sepIdx >= 0 ? sepIdx : path.length());
    }

    /**
     * Return the last segment of a path (the innermost jar or entry name for a nested jar path), stripping any
     * ClassGraph temporary filename prefix.
     *
     * @param path
     *            A file path.
     * @return The last segment of the path.
     */
    public static String lastSegment(final String path) {
        return segmentEndingAt(path, path.length());
    }

    /**
     * Return the path segment ending at {@code endIdx}, with any ClassGraph temporary filename prefix stripped.
     *
     * @param path
     *            A file path.
     * @param endIdx
     *            The end index of the segment.
     * @return The segment.
     */
    private static String segmentEndingAt(final String path, final int endIdx) {
        var startIdx = 1 + (File.separatorChar == '/' ? path.lastIndexOf('/', endIdx)
                : Math.max(path.lastIndexOf('/', endIdx), path.lastIndexOf(File.separatorChar, endIdx)));
        startIdx = Math.min(startIdx, endIdx);
        // In case of temp files (for jars extracted from within jars), remove the temp filename prefix -- see
        // TempFile.create(). The prefix is only stripped from a segment that actually starts with the
        // temporary filename prefix, and only at the first separator after it: looking for the separator anywhere
        // truncated the name of a file genuinely named "a---b.jar" to "b.jar", and looking for it anywhere in the
        // whole path found a "---" in a directory name or in a path nested within the jar, which left the name
        // empty, so the jar matched no accept or reject criterion and was silently skipped
        if (path.startsWith(TEMP_FILENAME_PREFIX, startIdx)) {
            final var tempSepIdx = path.indexOf(TEMP_FILENAME_LEAF_SEPARATOR,
                    startIdx + TEMP_FILENAME_PREFIX.length());
            if (tempSepIdx >= 0 && tempSepIdx + TEMP_FILENAME_LEAF_SEPARATOR.length() <= endIdx) {
                startIdx = tempSepIdx + TEMP_FILENAME_LEAF_SEPARATOR.length();
            }
        }
        return path.substring(startIdx, endIdx);
    }

    /**
     * Return the substring of an entry name after its last {@code '/'}.
     *
     * @param name
     *            An entry name, relative to the root that contains it.
     * @return The simple name.
     */
    public static String simpleName(final String name) {
        return name.substring(name.lastIndexOf('/') + 1);
    }

    /**
     * Get the part of a path before its last separator, e.g. {@code "com/xyz"} for {@code "com/xyz/Widget.class"}
     * and for {@code "com/xyz/"}.
     *
     * @param path
     *            The path.
     * @param separator
     *            The path separator.
     * @return The path up to (but not including) the last separator, or the empty string if the path contains no
     *         separator, or its only separator is at the start.
     */
    public static String getParentDirPath(final String path, final char separator) {
        final var lastSlashIdx = path.lastIndexOf(separator);
        if (lastSlashIdx <= 0) {
            return "";
        }
        return path.substring(0, lastSlashIdx);
    }

    /**
     * Get the part of a '/'-separated path before its last '/', as {@link #getParentDirPath(String, char)} does.
     *
     * @param path
     *            The path.
     * @return The path up to (but not including) the last '/', or the empty string if the path contains no '/', or
     *         its only '/' is at the start.
     */
    public static String getParentDirPath(final String path) {
        return getParentDirPath(path, '/');
    }
}
