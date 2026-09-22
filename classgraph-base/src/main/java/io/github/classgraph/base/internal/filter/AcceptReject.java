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
package io.github.classgraph.base.internal.filter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.base.internal.utils.CollectionUtils;

/**
 * A set of accept and reject criteria. A criterion that contains a glob wildcard ({@code '*'} or {@code '?'}) is
 * compiled to a regexp; any other criterion is matched literally. A string is accepted if the accept criteria are
 * empty, or if it matches one of them, and is rejected if it matches one of the reject criteria.
 */
public abstract class AcceptReject {
    /** The package or path separator character. */
    protected final char separatorChar;

    /** The accept criteria that contain a wildcard, as they were given, for {@link #toString()}. */
    private final Set<String> acceptGlobs = new TreeSet<>();

    /** The accept criteria that contain a wildcard, compiled to regexps. */
    private final List<Pattern> acceptPatterns = new ArrayList<>();

    /** The reject criteria that contain a wildcard, as they were given, for {@link #toString()}. */
    private final Set<String> rejectGlobs = new TreeSet<>();

    /** The reject criteria that contain a wildcard, compiled to regexps. */
    private final List<Pattern> rejectPatterns = new ArrayList<>();

    /**
     * Create an empty set of accept and reject criteria.
     *
     * @param separatorChar
     *            The package or path separator character.
     */
    protected AcceptReject(final char separatorChar) {
        this.separatorChar = separatorChar;
    }

    /**
     * Convert a glob to a regexp {@link Pattern}, where {@code '*'} matches zero or more characters within a single
     * package or path segment, i.e. does not span {@link #separatorChar}, {@code "**"} matches zero or more whole
     * segments, and {@code '?'} matches exactly one character other than {@link #separatorChar}. Any number of
     * wildcards may be used in a single glob, and any other character is matched literally.
     *
     * <p>
     * As the final segment of an accept or reject criterion, {@code "**"} means "and everything below", which is
     * already the default for {@code ClassGraph#acceptPackages(String...)} and friends, so it is stripped by the
     * caller before the glob reaches this method. In any other position, {@code "**"} must form a complete segment,
     * and matches zero or more whole segments, e.g. {@code "com.**.impl"} matches {@code com.impl},
     * {@code com.a.impl} and {@code com.a.b.impl}.
     *
     * @param glob
     *            the glob
     * @param separatorChar
     *            the package or path separator character
     * @param prefixMatch
     *            if true, the pattern matches any string <i>starting with</i> a string matching the glob, rather
     *            than requiring a whole-string match
     * @return the pattern
     * @throws IllegalArgumentException
     *             if {@code "**"} is used without forming a complete package or path segment, e.g.
     *             {@code "com.a**b.impl"}
     */
    // #643, #870, #940
    public static Pattern globToPattern(final String glob, final char separatorChar, final boolean prefixMatch) {
        return globToPattern(glob, separatorChar, prefixMatch, /* ignoreCase = */ false);
    }

    /**
     * Convert a glob to a regexp {@link Pattern}, as {@link #globToPattern(String, char, boolean)} does, optionally
     * ignoring case.
     *
     * @param glob
     *            the glob
     * @param separatorChar
     *            the package or path separator character
     * @param prefixMatch
     *            if true, the pattern matches any string <i>starting with</i> a string matching the glob, rather
     *            than requiring a whole-string match
     * @param ignoreCase
     *            if true, the pattern matches ignoring case
     * @return the pattern
     * @throws IllegalArgumentException
     *             if {@code "**"} is used without forming a complete package or path segment
     */
    protected static Pattern globToPattern(final String glob, final char separatorChar, final boolean prefixMatch,
            final boolean ignoreCase) {
        final var segmentRegex = "[^" + separatorChar + "]+";
        final var separatorRegex = ("\\^$.|?*+()[]{}".indexOf(separatorChar) >= 0 ? "\\" : "") + separatorChar;
        final StringBuilder buf = new StringBuilder("^");
        for (var i = 0; i < glob.length(); i++) {
            final var c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    // "**" matches zero or more whole segments, so it must itself form a complete segment. One
                    // adjacent separator is absorbed into the repeating group, so that matching zero segments also
                    // consumes the separator, e.g. "com.**.impl" matches "com.impl" as well as "com.a.impl" and
                    // "com.a.b.impl". (#940)
                    if (!(i == 0 || glob.charAt(i - 1) == separatorChar)
                            || !(i + 2 == glob.length() || glob.charAt(i + 2) == separatorChar)) {
                        throw new IllegalArgumentException(
                                "\"**\" may only be used as a complete segment of a glob: " + glob);
                    }
                    if (i + 2 < glob.length()) {
                        // "**" is followed by a separator -- absorb the separator into the group, and skip the
                        // second '*' and the separator
                        buf.append("(?:").append(segmentRegex).append(separatorRegex).append(")*");
                        i += 2;
                    } else if (i > 0) {
                        // "**" is the final segment -- absorb the preceding separator into the group (normally
                        // unreachable, since callers strip a redundant trailing "**")
                        buf.setLength(buf.length() - separatorRegex.length());
                        buf.append("(?:").append(separatorRegex).append(segmentRegex).append(")*");
                        i++;
                    } else {
                        // The whole glob is just "**", which matches anything
                        buf.append(".*");
                        i++;
                    }
                } else {
                    buf.append("[^").append(separatorChar).append("]*");
                }
            } else if (c == '?') {
                buf.append("[^").append(separatorChar).append("]");
            } else if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
                buf.append('\\').append(c);
            } else {
                buf.append(c);
            }
        }
        if (prefixMatch) {
            buf.append(".*");
        }
        return Pattern.compile(buf.append('$').toString(),
                ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
    }

    /**
     * Strip a trailing {@code "**"} segment from a normalized package name or path, if present. A trailing
     * {@code "**"} means "and everything below", which is what the recursive accept/reject methods already do, so
     * it can simply be removed. {@code "**"} in any other position matches zero or more whole segments (see
     * {@link #globToPattern(String, char, boolean)}).
     *
     * @param packageOrPath
     *            the normalized package name or path
     * @param separatorChar
     *            the package or path separator character
     * @return the package name or path with any trailing {@code "**"} segment removed
     */
    public static String stripTrailingDoubleGlob(final String packageOrPath, final char separatorChar) {
        if ("**".equals(packageOrPath)) {
            return "";
        }
        if (packageOrPath.endsWith(separatorChar + "**")) {
            return packageOrPath.substring(0, packageOrPath.length() - 3);
        }
        return packageOrPath;
    }

    /**
     * Find the index of the first glob wildcard character in a string.
     *
     * @param str
     *            the string
     * @return the index of the first {@code '*'} or {@code '?'} in the string, or -1 if the string contains no
     *         wildcards, and so is matched literally
     */
    private static int indexOfWildcard(final String str) {
        for (var i = 0; i < str.length(); i++) {
            final var c = str.charAt(i);
            if (c == '*' || c == '?') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Check whether a string contains a glob wildcard, and so must be compiled to a {@link Pattern} rather than
     * matched literally.
     *
     * @param str
     *            the string
     * @return true if the string contains {@code '*'} or {@code '?'}
     */
    public static boolean containsWildcard(final String str) {
        return indexOfWildcard(str) >= 0;
    }

    /**
     * Add a criterion that contains a wildcard to the accept criteria. A criterion that was already added is
     * ignored.
     *
     * @param glob
     *            The criterion, as it was given.
     * @param pattern
     *            The criterion, compiled to a regexp.
     */
    protected void addAcceptGlob(final String glob, final Pattern pattern) {
        if (acceptGlobs.add(glob)) {
            acceptPatterns.add(pattern);
        }
    }

    /**
     * Add a criterion that contains a wildcard to the reject criteria. A criterion that was already added is
     * ignored.
     *
     * @param glob
     *            The criterion, as it was given.
     * @param pattern
     *            The criterion, compiled to a regexp.
     */
    protected void addRejectGlob(final String glob, final Pattern pattern) {
        if (rejectGlobs.add(glob)) {
            rejectPatterns.add(pattern);
        }
    }

    /**
     * Check whether a string matches one of the accept criteria that contain a wildcard.
     *
     * @param str
     *            The string to test.
     * @return true if the string matches one of them.
     */
    protected boolean matchesAcceptGlob(final String str) {
        return matchesPatternList(str, acceptPatterns);
    }

    /**
     * Check whether a string matches one of the reject criteria that contain a wildcard.
     *
     * @param str
     *            The string to test.
     * @return true if the string matches one of them.
     */
    protected boolean matchesRejectGlob(final String str) {
        return matchesPatternList(str, rejectPatterns);
    }

    /**
     * Check whether a string matches one of a list of patterns.
     *
     * @param str
     *            The string to test.
     * @param patterns
     *            The patterns.
     * @return true if the string matches one of the patterns.
     */
    protected static boolean matchesPatternList(final String str, final List<Pattern> patterns) {
        for (final Pattern pattern : patterns) {
            if (pattern.matcher(str).matches()) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Accept and reject criteria that match any string starting with one of them. */
    public static class AcceptRejectPrefix extends AcceptReject {
        /** The accept criteria that contain no wildcard. */
        private final Set<String> acceptPrefixes = new TreeSet<>();

        /** The reject criteria that contain no wildcard. */
        private final Set<String> rejectPrefixes = new TreeSet<>();

        /**
         * Create an empty set of prefix accept and reject criteria.
         *
         * @param separatorChar
         *            The package or path separator character.
         */
        public AcceptRejectPrefix(final char separatorChar) {
            super(separatorChar);
        }

        @Override
        public void addToAccept(final String str) {
            if (containsWildcard(str)) {
                // A glob prefix, e.g. "eu.*.domain." -- matched as a regexp rather than by String#startsWith, so
                // that glob accepts are recursive into sub-packages, just like literal accepts (#870)
                addAcceptGlob(str, globToPattern(str, separatorChar, /* prefixMatch = */ true));
            } else {
                acceptPrefixes.add(str);
            }
        }

        @Override
        public void addToReject(final String str) {
            if (containsWildcard(str)) {
                // A glob reject is matched as a regexp that also matches everything below it, so that e.g.
                // rejectPackages("javax.swing.*") rejects javax.swing.plaf.basic as well as javax.swing.plaf (#884)
                addRejectGlob(str, globToPattern(str, separatorChar, /* prefixMatch = */ true));
            } else {
                rejectPrefixes.add(str);
            }
        }

        /**
         * Check whether a string starts with one of a set of prefixes.
         *
         * @param str
         *            The string to test.
         * @param prefixes
         *            The prefixes.
         * @return true if the string starts with one of the prefixes.
         */
        private static boolean startsWithAny(final String str, final Set<String> prefixes) {
            for (final String prefix : prefixes) {
                if (str.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isAccepted(final String str) {
            return acceptIsEmpty() || startsWithAny(str, acceptPrefixes) || matchesAcceptGlob(str);
        }

        /**
         * Not supported for prefix criteria, since every string that starts with a prefix is accepted.
         *
         * @param str
         *            The string to test.
         * @return (does not return)
         * @throws UnsupportedOperationException
         *             always.
         */
        @Override
        public boolean acceptHasPrefix(final String str) {
            throw new UnsupportedOperationException("Can only find prefixes of whole strings");
        }

        @Override
        public boolean isRejected(final String str) {
            return startsWithAny(str, rejectPrefixes) || matchesRejectGlob(str);
        }

        @Override
        public boolean acceptIsEmpty() {
            return acceptPrefixes.isEmpty() && super.acceptIsEmpty();
        }

        @Override
        public boolean rejectIsEmpty() {
            return rejectPrefixes.isEmpty() && super.rejectIsEmpty();
        }

        @Override
        public String toString() {
            return toString("acceptPrefixes", acceptPrefixes, "rejectPrefixes", rejectPrefixes);
        }
    }

    /** Accept and reject criteria that match a whole string. */
    public static class AcceptRejectWholeString extends AcceptReject {
        /** If true, criteria are matched ignoring case. */
        private final boolean ignoreCase;

        /** The accept criteria that contain no wildcard. */
        private final Set<String> accept;

        /** The reject criteria that contain no wildcard. */
        private final Set<String> reject;

        /**
         * Each accepted string that contains no wildcard, and each of its ancestors, with a final separator, and
         * the part of each accepted glob before the separator that precedes its first wildcard, and each of its
         * ancestors. The empty string and the separator on its own, which both name the root, are added along with
         * the first accept criterion. This is so that {@link #acceptHasPrefix(String)} can answer with one lookup,
         * rather than by testing every accept criterion, since there may be a very large number of them (#338).
         */
        private final Set<String> acceptAncestors;

        /**
         * Patterns matching each ancestor of an accepted glob that itself contains a wildcard, which cannot be
         * listed in {@link #acceptAncestors}. (#870, #643)
         */
        private final List<Pattern> acceptAncestorPatterns = new ArrayList<>();

        /**
         * Create an empty set of whole-string accept and reject criteria, matched case-sensitively.
         *
         * @param separatorChar
         *            The package or path separator character.
         */
        public AcceptRejectWholeString(final char separatorChar) {
            this(separatorChar, /* ignoreCase = */ false);
        }

        /**
         * Create an empty set of whole-string accept and reject criteria.
         *
         * @param separatorChar
         *            The package or path separator character.
         * @param ignoreCase
         *            If true, criteria are matched ignoring case.
         */
        protected AcceptRejectWholeString(final char separatorChar, final boolean ignoreCase) {
            super(separatorChar);
            this.ignoreCase = ignoreCase;
            this.accept = newLiteralSet();
            this.reject = newLiteralSet();
            this.acceptAncestors = newLiteralSet();
        }

        /**
         * Create a set to store literal (non-glob) criteria in, which ignores case if this criterion does. The
         * criteria are stored with the spelling they were given, so that {@link #toString()} reports back what the
         * caller asked for.
         *
         * @return a new empty set.
         */
        private Set<String> newLiteralSet() {
            return ignoreCase ? new TreeSet<>(String.CASE_INSENSITIVE_ORDER) : new HashSet<>();
        }

        @Override
        public void addToAccept(final String str) {
            final var firstWildcardIdx = indexOfWildcard(str);
            if (firstWildcardIdx >= 0) {
                addAcceptGlob(str, globToPattern(str, separatorChar, /* prefixMatch = */ false, ignoreCase));
            } else {
                accept.add(str);
            }

            // Record the ancestors of the accepted string, so that acceptHasPrefix() can tell whether a directory
            // may still lead to an accepted path
            if (acceptAncestors.isEmpty()) {
                acceptAncestors.add("");
                acceptAncestors.add(Character.toString(separatorChar));
            }
            var prefix = str;
            if (firstWildcardIdx >= 0) {
                // Only the part before the segment that holds the first wildcard can be listed literally, e.g.
                // "/path/to" for "/path/to/*.jar"
                prefix = prefix.substring(0, firstWildcardIdx);
                final var sepIdx = prefix.lastIndexOf(separatorChar);
                prefix = sepIdx < 0 ? "" : prefix.substring(0, sepIdx);
            }
            // Strip off any final separator
            while (!prefix.isEmpty() && prefix.charAt(prefix.length() - 1) == separatorChar) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            for (; !prefix.isEmpty(); prefix = PathSyntax.getParentDirPath(prefix, separatorChar)) {
                acceptAncestors.add(prefix + separatorChar);
            }

            // For a glob with a wildcard before its final segment, e.g. "eu/*/domain/", the only ancestor listed
            // above is "eu/". Recursive directory scanning would then stop at "eu/core/", since that is neither
            // an accepted path nor a listed ancestor of one, and the accepted path "eu/core/domain/" would never be
            // reached. So record a pattern for each ancestor of the glob that contains a wildcard ("eu/*/" here),
            // however many wildcards the glob has. (#870, #643)
            if (firstWildcardIdx >= 0) {
                for (var sepIdx = str.indexOf(separatorChar, firstWildcardIdx); sepIdx >= 0; //
                        sepIdx = str.indexOf(separatorChar, sepIdx + 1)) {
                    acceptAncestorPatterns.add(globToPattern(str.substring(0, sepIdx + 1), separatorChar,
                            /* prefixMatch = */ false, ignoreCase));
                }
            }
        }

        @Override
        public void addToReject(final String str) {
            if (containsWildcard(str)) {
                addRejectGlob(str, globToPattern(str, separatorChar, /* prefixMatch = */ false, ignoreCase));
            } else {
                reject.add(str);
            }
        }

        @Override
        public boolean isAccepted(final String str) {
            return acceptIsEmpty() || accept.contains(str) || matchesAcceptGlob(str);
        }

        @Override
        public boolean acceptHasPrefix(final String str) {
            return acceptAncestors.contains(str) || matchesPatternList(str, acceptAncestorPatterns);
        }

        @Override
        public boolean isRejected(final String str) {
            return reject.contains(str) || matchesRejectGlob(str);
        }

        @Override
        public boolean acceptIsEmpty() {
            return accept.isEmpty() && super.acceptIsEmpty();
        }

        @Override
        public boolean rejectIsEmpty() {
            return reject.isEmpty() && super.rejectIsEmpty();
        }

        @Override
        public String toString() {
            return toString("accept", accept, "reject", reject);
        }
    }

    /**
     * Accept and reject criteria that match the leafname of a path, i.e. a filename. Criteria are matched ignoring
     * case, since two filenames differing only in case name the same file on a filesystem that ignores case, and a
     * criterion should not mean something different depending on the filesystem the classpath happens to be stored
     * on.
     */
    public static class AcceptRejectLeafname extends AcceptRejectWholeString {
        /**
         * Create an empty set of leafname accept and reject criteria.
         *
         * @param separatorChar
         *            The path separator character.
         */
        public AcceptRejectLeafname(final char separatorChar) {
            super(separatorChar, /* ignoreCase = */ true);
        }

        @Override
        public void addToAccept(final String str) {
            super.addToAccept(PathSyntax.leafName(str));
        }

        @Override
        public void addToReject(final String str) {
            super.addToReject(PathSyntax.leafName(str));
        }

        @Override
        public boolean isAccepted(final String str) {
            return super.isAccepted(PathSyntax.leafName(str));
        }

        /**
         * Not supported for leafname criteria, since a leafname has no ancestors.
         *
         * @param str
         *            The string to test.
         * @return (does not return)
         * @throws UnsupportedOperationException
         *             always.
         */
        @Override
        public boolean acceptHasPrefix(final String str) {
            throw new UnsupportedOperationException("Can only find prefixes of whole strings");
        }

        @Override
        public boolean isRejected(final String str) {
            return super.isRejected(PathSyntax.leafName(str));
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add an accept criterion.
     *
     * @param str
     *            The criterion.
     */
    public abstract void addToAccept(final String str);

    /**
     * Add a reject criterion.
     *
     * @param str
     *            The criterion.
     */
    public abstract void addToReject(final String str);

    /**
     * Check whether a string is accepted, i.e. whether there are no accept criteria, or the string matches one of
     * them.
     *
     * @param str
     *            The string to test.
     * @return true if the string is accepted.
     */
    public abstract boolean isAccepted(final String str);

    /**
     * Check whether a string is an ancestor of an accepted string, i.e. whether a directory scan that has reached
     * the string may still lead to an accepted string.
     *
     * @param str
     *            The string to test, ending in the separator character.
     * @return true if the string is an ancestor of an accepted string.
     */
    public abstract boolean acceptHasPrefix(final String str);

    /**
     * Check whether a string matches one of the reject criteria.
     *
     * @param str
     *            The string to test.
     * @return true if the string is rejected.
     */
    public abstract boolean isRejected(final String str);

    /**
     * Check whether a string is accepted and not rejected.
     *
     * @param str
     *            The string to test.
     * @return true if the string is accepted and not rejected.
     */
    public boolean isAcceptedAndNotRejected(final String str) {
        return isAccepted(str) && !isRejected(str);
    }

    /**
     * Check whether there are no accept criteria.
     *
     * @return true if no accept criteria were added.
     */
    public boolean acceptIsEmpty() {
        return acceptGlobs.isEmpty();
    }

    /**
     * Check whether there are no reject criteria.
     *
     * @return true if no reject criteria were added.
     */
    public boolean rejectIsEmpty() {
        return rejectGlobs.isEmpty();
    }

    /**
     * Check whether there are no accept or reject criteria.
     *
     * @return true if no accept or reject criteria were added.
     */
    public boolean acceptAndRejectAreEmpty() {
        return acceptIsEmpty() && rejectIsEmpty();
    }

    /**
     * Check whether a string is specifically accepted and not rejected.
     *
     * @param str
     *            The string to test.
     * @return true if the string matches one of the accept criteria and none of the reject criteria. Unlike
     *         {@link #isAcceptedAndNotRejected(String)}, this returns false if there are no accept criteria.
     */
    public boolean isSpecificallyAcceptedAndNotRejected(final String str) {
        return !acceptIsEmpty() && isAcceptedAndNotRejected(str);
    }

    /**
     * Check whether a string is specifically accepted.
     *
     * @param str
     *            The string to test.
     * @return true if the string matches one of the accept criteria. Unlike {@link #isAccepted(String)}, this
     *         returns false if there are no accept criteria.
     */
    public boolean isSpecificallyAccepted(final String str) {
        return !acceptIsEmpty() && isAccepted(str);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Normalize a path that was given as an accept or reject criterion. The path is resolved with
     * {@link FastPathResolver#resolve(String)}, which converts backslashes to '/', collapses runs of separators,
     * resolves {@code "."} and {@code ".."} segments, and removes any final separator; then any initial '/'
     * characters are removed.
     *
     * @param path
     *            The path to normalize.
     * @return The normalized path.
     */
    public static String normalizePath(final String path) {
        var pathResolved = FastPathResolver.resolve(path);
        while (pathResolved.startsWith("/")) {
            pathResolved = pathResolved.substring(1);
        }
        return pathResolved;
    }

    /**
     * Normalize a package or class name that was given as an accept or reject criterion, in the same way that
     * {@link #normalizePath(String)} normalizes a path, with '.' as the separator. For example,
     * {@code ".com..xyz."} becomes {@code "com.xyz"}.
     *
     * @param packageOrClassName
     *            The package or class name.
     * @return The normalized package or class name.
     */
    public static String normalizePackageOrClassName(final String packageOrClassName) {
        return normalizePath(packageOrClassName.replace('.', '/')).replace('/', '.');
    }

    /**
     * Convert a path to a package name.
     *
     * @param path
     *            The path.
     * @return The package name.
     */
    public static String pathToPackageName(final String path) {
        return path.replace('/', '.');
    }

    /**
     * Append a list of strings to a buffer, each one quoted, with any {@code '"'} or {@code '\\'} in it escaped.
     *
     * @param coll
     *            The strings, which are appended in sorted order.
     * @param buf
     *            The buffer to append to.
     */
    private static void quoteList(final Collection<String> coll, final StringBuilder buf) {
        buf.append('[');
        var first = true;
        for (final String item : CollectionUtils.sortCopy(coll)) {
            if (first) {
                first = false;
            } else {
                buf.append(", ");
            }
            buf.append('"');
            for (var i = 0; i < item.length(); i++) {
                final var c = item.charAt(i);
                if (c == '"' || c == '\\') {
                    buf.append('\\');
                }
                buf.append(c);
            }
            buf.append('"');
        }
        buf.append(']');
    }

    /**
     * Append one named list of criteria to a buffer, if the list is not empty.
     *
     * @param name
     *            The name of the list.
     * @param coll
     *            The criteria.
     * @param buf
     *            The buffer to append to.
     */
    private static void appendCriteria(final String name, final Collection<String> coll, final StringBuilder buf) {
        if (!coll.isEmpty()) {
            if (!buf.isEmpty()) {
                buf.append("; ");
            }
            buf.append(name).append(": ");
            quoteList(coll, buf);
        }
    }

    /**
     * Render the criteria as a string, for logging.
     *
     * @param acceptName
     *            The name of the accept criteria that contain no wildcard.
     * @param accept
     *            The accept criteria that contain no wildcard.
     * @param rejectName
     *            The name of the reject criteria that contain no wildcard.
     * @param reject
     *            The reject criteria that contain no wildcard.
     * @return The criteria, or the empty string if there are none.
     */
    protected String toString(final String acceptName, final Collection<String> accept, final String rejectName,
            final Collection<String> reject) {
        final StringBuilder buf = new StringBuilder();
        appendCriteria(acceptName, accept, buf);
        appendCriteria("acceptGlobs", acceptGlobs, buf);
        appendCriteria(rejectName, reject, buf);
        appendCriteria("rejectGlobs", rejectGlobs, buf);
        return buf.toString();
    }
}
