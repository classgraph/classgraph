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

import io.github.classgraph.base.internal.utils.VersionFinder;
import io.github.classgraph.base.internal.utils.VersionFinder.OperatingSystem;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The syntax of a single path: its segments, its nested jar separators, and its leafname.
 *
 * <p>
 * These operations read the path as text, and do not ask the filesystem what it names. The one exception is
 * {@link #indexOfNestedJarSeparator(String)}, which has to test whether a path names a file, because a {@code '!'}
 * separator cannot be told from a {@code '!'} in a filename by syntax alone. Reading and resolving the file a path
 * names are the business of {@link FileUtils} and {@link FastPathResolver} respectively.
 */
public final class PathSyntax {
    /**
     * The separator between the random part of a ClassGraph temporary filename and the leafname of the file that
     * was extracted to it.
     */
    public static final String TEMP_FILENAME_LEAF_SEPARATOR = "---";

    /**
     * Constructor.
     */
    private PathSyntax() {
        // Cannot be constructed
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Sanitize relative paths against "zip slip" vulnerability, by removing path segments if ".." is found in the
     * URL, but without allowing navigation above the path hierarchy root. Treats each "!" character as a new path
     * hierarchy root. Also removes "." and empty path segments ("//").
     *
     * @param path
     *            The path to sanitize.
     * @param removeInitialSlash
     *            If true, remove any '/' character(s) from the beginning of the returned path.
     * @param removeFinalSlash
     *            If true, remove any '/' character(s) from the end of the returned path.
     * @return The sanitized path.
     */
    public static String sanitizeEntryPath(final String path, final boolean removeInitialSlash,
            final boolean removeFinalSlash) {
        return sanitizeEntryPath(path, removeInitialSlash, removeFinalSlash,
                /* collapseParentSegmentsInFirstSection = */ true);
    }

    /**
     * Sanitize relative paths against "zip slip" vulnerability, by removing path segments if ".." is found in the
     * URL, but without allowing navigation above the path hierarchy root. Treats each "!" character as a new path
     * hierarchy root. Also removes "." and empty path segments ("//").
     *
     * @param path
     *            The path to sanitize.
     * @param removeInitialSlash
     *            If true, remove any '/' character(s) from the beginning of the returned path.
     * @param removeFinalSlash
     *            If true, remove any '/' character(s) from the end of the returned path.
     * @param collapseParentSegmentsInFirstSection
     *            If true, a ".." segment before the first nested jar separator removes the segment before it, as it
     *            does in every later section. If false, a ".." segment there is left in the path for the platform
     *            to resolve. Pass false only for the first section of a path that names a file on disk, where the
     *            two can differ: on Linux and macOS, after a symlinked directory, ".." names the parent of the
     *            directory the symlink points at, not the parent of the symlink, so collapsing it here would name a
     *            different file than the one the path reaches (on Windows the path APIs collapse it lexically, so
     *            the two agree there). Every later section is a path within an archive, which has no symlinks and
     *            no filesystem to ask, so ".." there is always collapsed -- that is what stops a "zip slip" entry
     *            name from escaping the archive.
     * @return The sanitized path.
     */
    public static String sanitizeEntryPath(final String path, final boolean removeInitialSlash,
            final boolean removeFinalSlash, final boolean collapseParentSegmentsInFirstSection) {
        if (path.isEmpty()) {
            return "";
        }

        // A '!' is only a nested jar separator if the path before it names an existing jarfile -- it is otherwise a
        // legal filename character, and must not be treated as a path hierarchy root (#903)
        final var nestedJarSepIdx = indexOfNestedJarSeparator(path);

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
     * Check whether a path contains any segment that has to be removed, i.e. a {@code ".."} segment, a {@code "."}
     * segment, or an empty segment.
     *
     * @param path
     *            The path to check.
     * @param nestedJarSepIdx
     *            The index of the first nested jar separator {@code '!'} in the path, or -1 if there is none.
     * @param collapseParentSegmentsInFirstSection
     *            If false, a {@code ".."} segment before the first nested jar separator is left in the path.
     * @return true if the path has to be sanitized.
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
        for (int i = 0, ii = pathLen + 1; i < ii; i++) {
            final var c = i == pathLen ? '\0' : path.charAt(i);
            final var isSectionMarker = c == '!' && nestedJarSepIdx >= 0 && i >= nestedJarSepIdx;
            if (c == '/' || isSectionMarker || c == '\0') {
                final var segmentLength = i - (lastSepIdx + 1);
                if (
                // Found empty segment "//" or "!!"
                (segmentLength == 0 && prevC == c)
                        // Found segment "."
                        || (segmentLength == 1 && path.charAt(i - 1) == '.')
                        // Found segment ".." that has to be collapsed
                        || (segmentLength == 2 && path.charAt(i - 2) == '.' && path.charAt(i - 1) == '.'
                                && (collapseParentSegmentsInFirstSection || !inFirstSection))) {
                    return true;
                }
                if (isSectionMarker) {
                    inFirstSection = false;
                }
                lastSepIdx = i;
            }
            prevC = c;
        }
        return false;
    }

    /**
     * Append a path to a {@link StringBuilder}, dropping empty and {@code "."} segments, and removing the preceding
     * segment for each {@code ".."} segment. Each segment is preceded by {@code '/'}, and each nested jar separator
     * is written as {@code '!'} (so that {@code "jar:"} URL syntax is produced, since {@code '/'} always follows).
     *
     * @param path
     *            The path to sanitize.
     * @param nestedJarSepIdx
     *            The index of the first nested jar separator {@code '!'} in the path, or -1 if there is none.
     * @param collapseParentSegmentsInFirstSection
     *            If false, a {@code ".."} segment before the first nested jar separator is kept as an ordinary
     *            segment, for the filesystem to resolve.
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
            final var c = i == pathLen ? '\0' : path.charAt(i);
            final var isSectionMarker = c == '!' && nestedJarSepIdx >= 0 && i >= nestedJarSepIdx;
            if (c == '/' || isSectionMarker || c == '\0') {
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
                // Delineate sections with "!"
                if (!pathSanitized.isEmpty()) {
                    pathSanitized.append('!');
                }
                for (final CharSequence sectionSegment : sectionSegments) {
                    pathSanitized.append('/');
                    pathSanitized.append(sectionSegment);
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the index of the outermost nested jar separator ('!') in a path, i.e. the '!' that separates the
     * outermost jarfile from a path nested within it, or -1 if the path contains no nested jar separator.
     *
     * <p>
     * A '!' is not necessarily a separator -- it is a legal character in a file or directory name on every platform
     * ClassGraph supports, and users do put it in their directory names. The {@link java.net.JarURLConnection} spec
     * defines the separator as {@code "!/"}, and gives no way of escaping a literal '!' other than percent-encoding
     * it as {@code %21} within the inner URL, so the separator cannot be identified by syntax alone:
     * {@code /dir!/x.jar} is ambiguous between a jar {@code x.jar} in a directory named {@code dir!}, and an entry
     * {@code x.jar} within a jarfile named {@code dir}.
     *
     * <p>
     * That ambiguity is resolved here by testing the filesystem: the outermost '!' separator is the first '!' whose
     * preceding path names an existing regular file (which must be the outermost jarfile). Every subsequent '!' is
     * then a separator too, since only the last element of a '!'-delimited path may be a non-jar path. If no '!' is
     * preceded by an existing file, the path contains no separator, and any '!' in it is a literal filename
     * character.
     *
     * <p>
     * The filesystem cannot be consulted for non-{@code file:} URLs (e.g. {@code http:} jar URLs), so for those the
     * old syntactic rule is retained, and the first '!' is taken to be the separator.
     *
     * @param path
     *            the path, with any {@code "jar:"} and {@code "file:"} scheme prefixes already stripped.
     * @return the index of the outermost nested jar separator, or -1 if there is none.
     */
    // #903
    public static int indexOfNestedJarSeparator(final String path) {
        var plingIdx = path.indexOf('!');
        if (plingIdx < 0) {
            return -1;
        }
        if (URLPaths.URL_SCHEME_PATTERN.matcher(path).matches()) {
            // Cannot stat a remote URL -- fall back to the syntactic rule
            return plingIdx;
        }
        while (plingIdx >= 0) {
            // The outermost jarfile has to exist as a regular file for the classpath element to be scannable, so if
            // the path before the '!' names one, this '!' is the outermost separator.
            // N.B. the path is not resolved via FastPathResolver here, since that calls #sanitizeEntryPath, which
            // calls this method -- a relative path is resolved by java.io.File against the current directory, which
            // is the same base path anyway
            if (new File(path.substring(0, plingIdx)).isFile()) {
                return plingIdx;
            }
            plingIdx = path.indexOf('!', plingIdx + 1);
        }
        return -1;
    }

    /**
     * Find the index of the innermost nested jar separator ('!') in a path, or -1 if the path contains no nested
     * jar separator. See {@link #indexOfNestedJarSeparator(String)} for how separators are distinguished from
     * literal '!' characters in filenames.
     *
     * @param path
     *            the path, with any {@code "jar:"} and {@code "file:"} scheme prefixes already stripped.
     * @return the index of the innermost nested jar separator, or -1 if there is none.
     */
    // #903
    public static int lastIndexOfNestedJarSeparator(final String path) {
        // Every '!' after the outermost separator is also a separator, so if there is an outermost separator, the
        // last '!' in the path is the innermost separator
        return indexOfNestedJarSeparator(path) < 0 ? -1 : path.lastIndexOf('!');
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the leafname of a path, after first stripping off everything from the nested jar separator ('!')
     * onwards, if present, so that the leafname of a path within a jarfile is the name of the jarfile itself.
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
        final var endIdx = sepIdx >= 0 ? sepIdx : path.length();
        var leafStartIdx = 1 + (File.separatorChar == '/' ? path.lastIndexOf('/', endIdx)
                : Math.max(path.lastIndexOf('/', endIdx), path.lastIndexOf(File.separatorChar, endIdx)));
        // In case of temp files (for jars extracted from within jars), remove the temp filename prefix -- see
        // ScanResources.makeTempFile()
        var tempSepIdx = path.indexOf(TEMP_FILENAME_LEAF_SEPARATOR);
        if (tempSepIdx >= 0) {
            tempSepIdx += TEMP_FILENAME_LEAF_SEPARATOR.length();
        }
        leafStartIdx = Math.max(leafStartIdx, tempSepIdx);
        leafStartIdx = Math.min(leafStartIdx, endIdx);
        return path.substring(leafStartIdx, endIdx);
    }

    /**
     * Get the parent dir path.
     *
     * @param path
     *            the path
     * @param separator
     *            the separator
     * @return the parent dir path
     */
    public static String getParentDirPath(final String path, final char separator) {
        final var lastSlashIdx = path.lastIndexOf(separator);
        if (lastSlashIdx <= 0) {
            return "";
        }
        return path.substring(0, lastSlashIdx);
    }

    /**
     * Get the parent dir path.
     *
     * @param path
     *            the path
     * @return the parent dir path
     */
    public static String getParentDirPath(final String path) {
        return getParentDirPath(path, '/');
    }
}
