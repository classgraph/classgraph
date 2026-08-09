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
package nonapi.io.github.classgraph.scanspec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import nonapi.io.github.classgraph.utils.CollectionUtils;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import org.jspecify.annotations.Nullable;

/** A class storing accept or reject criteria. */
public abstract class AcceptReject {
    /** Accepted items (whole-string match). */
    protected @Nullable Set<String> accept;
    /** Rejected items (whole-string match). */
    protected @Nullable Set<String> reject;
    /** Accepted items (prefix match), as a set. */
    protected @Nullable Set<String> acceptPrefixesSet;
    /** Accepted items (prefix match), as a sorted list. */
    protected @Nullable List<String> acceptPrefixes;
    /** Rejected items (prefix match). */
    protected @Nullable List<String> rejectPrefixes;
    /** Accept glob strings. (Retained for logging purposes.) */
    protected @Nullable Set<String> acceptGlobs;
    /** Reject glob strings. (Retained for logging purposes.) */
    protected @Nullable Set<String> rejectGlobs;
    /** Accept regexp patterns. */
    protected @Nullable List<Pattern> acceptPatterns;
    /**
     * Regexp patterns matching the wildcard-containing path prefixes of accepted
     * globs, used by {@link #acceptHasPrefix(String)}.
     */
    // #870
    protected @Nullable List<Pattern> acceptPrefixPatterns;
    /** Reject regexp patterns. */
    protected @Nullable List<Pattern> rejectPatterns;
    /** The separator character. */
    protected char separatorChar;

    /**
     * Instantiate a new accept/reject criterion.
     *
     * @param separatorChar the separator char
     */
    protected AcceptReject(final char separatorChar) {
        this.separatorChar = separatorChar;
    }

    /**
     * Convert a glob to a regexp {@link Pattern}, where {@code '*'} matches zero or
     * more characters within a single package or path segment, i.e. does not span
     * {@link #separatorChar}, {@code "**"} matches zero or more whole segments, and
     * {@code '?'} matches exactly one character other than {@link #separatorChar}.
     * Any number of wildcards may be used in a single glob, and any other character
     * is matched literally.
     *
     * <p>
     * As the final segment of an accept or reject criterion, {@code "**"} means
     * "and everything below", which is already the default for
     * {@link io.github.classgraph.ClassGraph#acceptPackages(String...)} and
     * friends, so it is stripped by the caller before the glob reaches this method.
     * In any other position, {@code "**"} must form a complete segment, and matches
     * zero or more whole segments, e.g. {@code "com.**.impl"} matches
     * {@code com.impl}, {@code com.a.impl} and {@code com.a.b.impl}.
     *
     * @param glob          the glob
     * @param separatorChar the package or path separator character
     * @param prefixMatch   if true, the pattern matches any string <i>starting
     *                      with</i> a string matching the glob, rather than
     *                      requiring a whole-string match
     * @return the pattern
     * @throws IllegalArgumentException if {@code "**"} is used without forming a
     *                                  complete package or path segment, e.g.
     *                                  {@code "com.a**b.impl"}
     */
    // #643, #870, #940
    public static Pattern globToPattern(final String glob, final char separatorChar, final boolean prefixMatch) {
        final var segmentRegex = "[^" + separatorChar + "]+";
        final var separatorRegex = ("\\^$.|?*+()[]{}".indexOf(separatorChar) >= 0 ? "\\" : "") + separatorChar;
        final StringBuilder buf = new StringBuilder("^");
        for (var i = 0; i < glob.length(); i++) {
            final var c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    // "**" matches zero or more whole segments, so it must itself form a complete
                    // segment.
                    // One adjacent separator is absorbed into the repeating group, so that matching
                    // zero
                    // segments also consumes the separator, e.g. "com.**.impl" matches "com.impl"
                    // as well
                    // as "com.a.impl" and "com.a.b.impl". (#940)
                    if (!(i == 0 || glob.charAt(i - 1) == separatorChar)
                            || !(i + 2 == glob.length() || glob.charAt(i + 2) == separatorChar)) {
                        throw new IllegalArgumentException(
                                "\"**\" may only be used as a complete segment of a glob: " + glob);
                    }
                    if (i + 2 < glob.length()) {
                        // "**" is followed by a separator -- absorb the separator into the group, and
                        // skip the second '*' and the separator
                        buf.append("(?:").append(segmentRegex).append(separatorRegex).append(")*");
                        i += 2;
                    } else if (i > 0) {
                        // "**" is the final segment -- absorb the preceding separator into the group
                        // (normally unreachable, since callers strip a redundant trailing "**")
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
        return Pattern.compile(buf.append('$').toString());
    }

    /**
     * Strip a trailing {@code "**"} segment from a normalized package name or path,
     * if present. A trailing {@code "**"} means "and everything below", which is
     * what the recursive accept/reject methods already do, so it can simply be
     * removed. {@code "**"} in any other position matches zero or more whole
     * segments (see {@link #globToPattern(String, char, boolean)}).
     *
     * @param packageOrPath the normalized package name or path
     * @param separatorChar the package or path separator character
     * @return the package name or path with any trailing {@code "**"} segment
     *         removed
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
     * @param str the string
     * @return the index of the first {@code '*'} or {@code '?'} in the string, or
     *         -1 if the string contains no wildcards, and so is matched literally
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
     * Check whether a string contains a glob wildcard, and so must be compiled to a
     * {@link Pattern} rather than matched literally.
     *
     * @param str the string
     * @return true if the string contains {@code '*'} or {@code '?'}
     */
    public static boolean containsWildcard(final String str) {
        return indexOfWildcard(str) >= 0;
    }

    /** Accept/reject for prefix strings. */
    public static class AcceptRejectPrefix extends AcceptReject {
        /**
         * Instantiate a new accept/reject for prefix strings.
         *
         * @param separatorChar the separator char
         */
        public AcceptRejectPrefix(final char separatorChar) {
            super(separatorChar);
        }

        /**
         * Add to the accept.
         *
         * @param str the string to accept
         */
        @Override
        public void addToAccept(final String str) {
            if (containsWildcard(str)) {
                // A glob prefix, e.g. "eu.*.domain." -- matched as a regexp rather than by
                // String#startsWith,
                // so that glob accepts are recursive into sub-packages, just like literal
                // accepts (#870)
                if (this.acceptGlobs == null || this.acceptPatterns == null) {
                    this.acceptGlobs = new HashSet<>();
                    this.acceptPatterns = new ArrayList<>();
                }
                this.acceptGlobs.add(str);
                this.acceptPatterns.add(globToPattern(str, separatorChar, /* prefixMatch = */ true));
                return;
            }
            if (this.acceptPrefixesSet == null) {
                this.acceptPrefixesSet = new HashSet<>();
            }
            this.acceptPrefixesSet.add(str);
        }

        /**
         * Add to the reject.
         *
         * @param str the string to reject
         */
        @Override
        public void addToReject(final String str) {
            if (containsWildcard(str)) {
                if (this.rejectGlobs == null || this.rejectPatterns == null) {
                    this.rejectGlobs = new HashSet<>();
                    this.rejectPatterns = new ArrayList<>();
                }
                this.rejectGlobs.add(str);
                this.rejectPatterns.add(globToPattern(str, separatorChar, /* prefixMatch = */ true));
                return;
            }
            if (this.rejectPrefixes == null) {
                this.rejectPrefixes = new ArrayList<>();
            }
            this.rejectPrefixes.add(str);
        }

        /**
         * Check if the requested string has an accepted/non-rejected prefix.
         *
         * @param str the string to test
         * @return true if string is accepted and not rejected
         */
        @Override
        public boolean isAcceptedAndNotRejected(final String str) {
            var isAccepted = acceptPrefixes == null && acceptPatterns == null;
            if (!isAccepted && acceptPrefixes != null) {
                for (final String prefix : acceptPrefixes) {
                    if (str.startsWith(prefix)) {
                        isAccepted = true;
                        break;
                    }
                }
            }
            if (!isAccepted) {
                isAccepted = matchesPatternList(str, acceptPatterns);
            }
            if (!isAccepted) {
                return false;
            }
            if (rejectPrefixes != null) {
                for (final String prefix : rejectPrefixes) {
                    if (str.startsWith(prefix)) {
                        return false;
                    }
                }
            }
            return !matchesPatternList(str, rejectPatterns);
        }

        /**
         * Check if the requested string has an accepted prefix.
         *
         * @param str the string to test
         * @return true if string is accepted
         */
        @Override
        public boolean isAccepted(final String str) {
            var isAccepted = acceptPrefixes == null && acceptPatterns == null;
            if (!isAccepted && acceptPrefixes != null) {
                for (final String prefix : acceptPrefixes) {
                    if (str.startsWith(prefix)) {
                        isAccepted = true;
                        break;
                    }
                }
            }
            return isAccepted || matchesPatternList(str, acceptPatterns);
        }

        /**
         * Prefix-of-prefix is invalid -- throws {@link IllegalArgumentException}.
         *
         * @param str the string to test
         * @return (does not return, throws exception)
         * @throws IllegalArgumentException always
         */
        @Override
        public boolean acceptHasPrefix(final String str) {
            throw new IllegalArgumentException("Can only find prefixes of whole strings");
        }

        /**
         * Check if the requested string has a rejected prefix.
         *
         * @param str the string to test
         * @return true if the string has a rejected prefix
         */
        @Override
        public boolean isRejected(final String str) {
            if (rejectPrefixes != null) {
                for (final String prefix : rejectPrefixes) {
                    if (str.startsWith(prefix)) {
                        return true;
                    }
                }
            }
            // Also test any glob reject prefixes, which are matched as regexps rather than
            // by
            // String#startsWith. Without this, a reject criterion containing a wildcard was
            // not applied to
            // sub-packages or sub-directories of a matched package or directory, so e.g.
            // rejectPackages("javax.swing.*") rejected javax.swing.plaf but not
            // javax.swing.plaf.basic (#884)
            return matchesPatternList(str, rejectPatterns);
        }
    }

    /** Accept/reject for whole-strings matches. */
    public static class AcceptRejectWholeString extends AcceptReject {
        /**
         * Instantiate a new accept/reject for whole-string matches.
         *
         * @param separatorChar the separator char
         */
        public AcceptRejectWholeString(final char separatorChar) {
            super(separatorChar);
        }

        /**
         * Add to the accept.
         *
         * @param str the string to accept
         */
        @Override
        public void addToAccept(final String str) {
            if (containsWildcard(str)) {
                if (this.acceptGlobs == null || this.acceptPatterns == null) {
                    this.acceptGlobs = new HashSet<>();
                    this.acceptPatterns = new ArrayList<>();
                }
                this.acceptGlobs.add(str);
                this.acceptPatterns.add(globToPattern(str, separatorChar, /* prefixMatch = */ false));
            } else {
                if (this.accept == null) {
                    this.accept = new HashSet<>();
                }
                this.accept.add(str);
            }

            // For AcceptRejectWholeString, which doesn't perform prefix matches like
            // AcceptRejectPrefix,
            // use acceptPrefixes to store all parent prefixes of an accepted path, so that
            // acceptHasPrefix() can operate efficiently on very large accepts (#338),
            // in particular where the size of the accept is much larger than the maximum
            // path depth.
            if (this.acceptPrefixesSet == null) {
                this.acceptPrefixesSet = new HashSet<>();
                acceptPrefixesSet.add("");
                acceptPrefixesSet.add("/");
            }
            final var separator = Character.toString(separatorChar);
            var prefix = str;
            final var firstWildcardIdx = indexOfWildcard(prefix);
            if (firstWildcardIdx >= 0) {
                // Stop performing prefix search at the first wildcard -- this means prefix
                // matching will break if there is more than one wildcard in the path
                prefix = prefix.substring(0, firstWildcardIdx);
                // /path/to/wildcard*.jar -> /path/to
                // /path/to/*.jar -> /path/to
                final var sepIdx = prefix.lastIndexOf(separatorChar);
                prefix = sepIdx < 0 ? "" : prefix.substring(0, sepIdx);
            }
            // Strip off any final separator
            while (prefix.endsWith(separator)) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            // Record the accepted path itself and each of its parent directories as a
            // prefix, so that
            // acceptHasPrefix() can tell whether a directory may still lead to an accepted
            // path
            for (; !prefix.isEmpty(); prefix = FileUtils.getParentDirPath(prefix, separatorChar)) {
                acceptPrefixesSet.add(prefix + separatorChar);
            }

            // The literal prefix search above stops at the first wildcard, so for a glob
            // with a wildcard before its
            // final segment, e.g. "eu/*/domain/", the only recorded prefix is "eu/".
            // Recursive directory
            // scanning would then stop at "eu/core/", since that is neither an accepted
            // path nor a recorded
            // prefix of one, and the accepted path "eu/core/domain/" would never be
            // reached. Record a pattern
            // for each path prefix of the glob that contains a wildcard ("eu/*/" here), so
            // that
            // acceptHasPrefix() can report that "eu/core/" may still lead to an accepted
            // path. (#870, #643)
            if (firstWildcardIdx >= 0) {
                for (var sepIdx = str.indexOf(separatorChar); sepIdx >= 0; sepIdx = str.indexOf(separatorChar,
                        sepIdx + 1)) {
                    final var pathPrefix = str.substring(0, sepIdx + 1);
                    if (containsWildcard(pathPrefix)) {
                        if (this.acceptPrefixPatterns == null) {
                            this.acceptPrefixPatterns = new ArrayList<>();
                        }
                        this.acceptPrefixPatterns
                                .add(globToPattern(pathPrefix, separatorChar, /* prefixMatch = */ false));
                    }
                }
            }
        }

        /**
         * Add to the reject.
         *
         * @param str the string to reject
         */
        @Override
        public void addToReject(final String str) {
            if (containsWildcard(str)) {
                if (this.rejectGlobs == null || this.rejectPatterns == null) {
                    this.rejectGlobs = new HashSet<>();
                    this.rejectPatterns = new ArrayList<>();
                }
                this.rejectGlobs.add(str);
                this.rejectPatterns.add(globToPattern(str, separatorChar, /* prefixMatch = */ false));
            } else {
                if (this.reject == null) {
                    this.reject = new HashSet<>();
                }
                this.reject.add(str);
            }
        }

        /**
         * Check if the requested string is accepted and not rejected.
         *
         * @param str the string to test
         * @return true if the string is accepted and not rejected
         */
        @Override
        public boolean isAcceptedAndNotRejected(final String str) {
            return isAccepted(str) && !isRejected(str);
        }

        /**
         * Check if the requested string is accepted.
         *
         * @param str the string to test
         * @return true if the string is accepted
         */
        @Override
        public boolean isAccepted(final String str) {
            return (accept == null && acceptPatterns == null) || (accept != null && accept.contains(str))
                    || matchesPatternList(str, acceptPatterns);
        }

        /**
         * Check if the requested string is a prefix of an accepted string.
         *
         * @param str the string to test
         * @return true if the string is a prefix of an accepted string
         */
        @Override
        public boolean acceptHasPrefix(final String str) {
            if (acceptPrefixesSet == null) {
                return false;
            }
            // Also test the prefixes of any accepted glob that contain a wildcard, since
            // those cannot be
            // enumerated into acceptPrefixesSet. (#870, #643)
            return acceptPrefixesSet.contains(str) || matchesPatternList(str, acceptPrefixPatterns);
        }

        /**
         * Check if the requested string is rejected.
         *
         * @param str the string to test
         * @return true if the string is rejected
         */
        @Override
        public boolean isRejected(final String str) {
            return (reject != null && reject.contains(str)) || matchesPatternList(str, rejectPatterns);
        }
    }

    /** Accept/reject for leaf matches. */
    public static class AcceptRejectLeafname extends AcceptRejectWholeString {
        /**
         * Instantiates a new accept/reject for leaf matches.
         *
         * @param separatorChar the separator char
         */
        public AcceptRejectLeafname(final char separatorChar) {
            super(separatorChar);
        }

        /**
         * Add to the accept.
         *
         * @param str the string to accept
         */
        @Override
        public void addToAccept(final String str) {
            super.addToAccept(JarUtils.leafName(str));
        }

        /**
         * Add to the reject.
         *
         * @param str the string to reject
         */
        @Override
        public void addToReject(final String str) {
            super.addToReject(JarUtils.leafName(str));
        }

        /**
         * Check if the requested string is accepted and not rejected.
         *
         * @param str the string to test
         * @return true if the string is accepted and not rejected
         */
        @Override
        public boolean isAcceptedAndNotRejected(final String str) {
            return super.isAcceptedAndNotRejected(JarUtils.leafName(str));
        }

        /**
         * Check if the requested string is accepted.
         *
         * @param str the string to test
         * @return true if the string is accepted
         */
        @Override
        public boolean isAccepted(final String str) {
            return super.isAccepted(JarUtils.leafName(str));
        }

        /**
         * Prefix tests are invalid for jar leafnames -- throws
         * {@link IllegalArgumentException}.
         *
         * @param str the string to test
         * @return (does not return, throws exception)
         * @throws IllegalArgumentException always
         */
        @Override
        public boolean acceptHasPrefix(final String str) {
            throw new IllegalArgumentException("Can only find prefixes of whole strings");
        }

        /**
         * Check if the requested string is rejected.
         *
         * @param str the string to test
         * @return true if the string is rejected
         */
        @Override
        public boolean isRejected(final String str) {
            return super.isRejected(JarUtils.leafName(str));
        }
    }

    /**
     * Add to the accept.
     *
     * @param str The string to accept.
     */
    public abstract void addToAccept(final String str);

    /**
     * Add to the reject.
     *
     * @param str The string to reject.
     */
    public abstract void addToReject(final String str);

    /**
     * Check if a string is accepted and not rejected.
     *
     * @param str The string to test.
     * @return true if the string is accepted and not rejected.
     */
    public abstract boolean isAcceptedAndNotRejected(final String str);

    /**
     * Check if a string is accepted.
     *
     * @param str The string to test.
     * @return true if the string is accepted.
     */
    public abstract boolean isAccepted(final String str);

    /**
     * Check if a string is a prefix of an accepted string.
     *
     * @param str The string to test.
     * @return true if the string is a prefix of an accepted string.
     */
    public abstract boolean acceptHasPrefix(final String str);

    /**
     * Check if a string is rejected.
     *
     * @param str The string to test.
     * @return true if the string is rejected.
     */
    public abstract boolean isRejected(final String str);

    /**
     * Remove initial and final '/' characters, if any.
     * 
     * @param path The path to normalize.
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
     * Remove initial and final '.' characters, if any.
     * 
     * @param packageOrClassName The package or class name.
     * @return The normalized package or class name.
     */
    public static String normalizePackageOrClassName(final String packageOrClassName) {
        return normalizePath(packageOrClassName.replace('.', '/')).replace('/', '.');
    }

    /**
     * Convert a path to a package name.
     * 
     * @param path The path.
     * @return The package name.
     */
    public static String pathToPackageName(final String path) {
        return path.replace('/', '.');
    }

    /**
     * Convert a package name to a path.
     * 
     * @param packageName The package name.
     * @return The path.
     */
    public static String packageNameToPath(final String packageName) {
        return packageName.replace('.', '/');
    }

    /**
     * Convert a class name to a classfile path.
     * 
     * @param className The class name.
     * @return The classfile path (including a ".class" suffix).
     */
    public static String classNameToClassfilePath(final String className) {
        return JarUtils.classNameToClassfilePath(className);
    }

    /**
     * Check if a string matches one of the patterns in the provided list.
     *
     * @param str      the string to test
     * @param patterns the patterns
     * @return true, if successful
     */
    private static boolean matchesPatternList(final String str, final @Nullable List<Pattern> patterns) {
        if (patterns != null) {
            for (final Pattern pattern : patterns) {
                if (pattern.matcher(str).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check if the accept is empty.
     *
     * @return true if there were no accept criteria added.
     */
    public boolean acceptIsEmpty() {
        // (Also test acceptPrefixesSet, since acceptPrefixes is only populated from it
        // by sortPrefixes(),
        // so an AcceptRejectPrefix would otherwise look empty until sortPrefixes() had
        // been called)
        return accept == null && acceptPrefixes == null && acceptPrefixesSet == null && acceptGlobs == null;
    }

    /**
     * Check if the reject is empty.
     *
     * @return true if there were no reject criteria added.
     */
    public boolean rejectIsEmpty() {
        return reject == null && rejectPrefixes == null && rejectGlobs == null;
    }

    /**
     * Check if the accept and reject are empty.
     *
     * @return true if there were no accept or reject criteria added.
     */
    public boolean acceptAndRejectAreEmpty() {
        return acceptIsEmpty() && rejectIsEmpty();
    }

    /**
     * Check if a string is specifically accepted and not rejected.
     *
     * @param str The string to test.
     * @return true if the requested string is <i>specifically</i> accepted and not
     *         rejected, i.e. will not return true if the accept is empty, or if the
     *         string is rejected.
     */
    public boolean isSpecificallyAcceptedAndNotRejected(final String str) {
        return !acceptIsEmpty() && isAcceptedAndNotRejected(str);
    }

    /**
     * Check if a string is specifically accepted.
     *
     * @param str The string to test.
     * @return true if the requested string is <i>specifically</i> accepted, i.e.
     *         will not return true if the accept is empty.
     */
    public boolean isSpecificallyAccepted(final String str) {
        return !acceptIsEmpty() && isAccepted(str);
    }

    /** Need to sort prefixes to ensure correct accept/reject evaluation. */
    // #167
    void sortPrefixes() {
        if (acceptPrefixesSet != null) {
            acceptPrefixes = new ArrayList<>(acceptPrefixesSet);
        }
        if (acceptPrefixes != null) {
            CollectionUtils.sortIfNotEmpty(acceptPrefixes);
        }
        if (rejectPrefixes != null) {
            CollectionUtils.sortIfNotEmpty(rejectPrefixes);
        }
    }

    /**
     * Quote list.
     *
     * @param coll the coll
     * @param buf  the buffer to append to
     */
    private static void quoteList(final Collection<String> coll, final StringBuilder buf) {
        buf.append('[');
        var first = true;
        for (final String item : coll) {
            if (first) {
                first = false;
            } else {
                buf.append(", ");
            }
            buf.append('"');
            for (var i = 0; i < item.length(); i++) {
                final var c = item.charAt(i);
                if (c == '"') {
                    buf.append("\\\"");
                } else {
                    buf.append(c);
                }
            }
            buf.append('"');
        }
        buf.append(']');
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        if (accept != null) {
            buf.append("accept: ");
            quoteList(accept, buf);
        }
        if (acceptPrefixes != null) {
            if (!buf.isEmpty()) {
                buf.append("; ");
            }
            buf.append("acceptPrefixes: ");
            quoteList(acceptPrefixes, buf);
        }
        if (acceptGlobs != null) {
            if (!buf.isEmpty()) {
                buf.append("; ");
            }
            buf.append("acceptGlobs: ");
            quoteList(acceptGlobs, buf);
        }
        if (reject != null) {
            if (!buf.isEmpty()) {
                buf.append("; ");
            }
            buf.append("reject: ");
            quoteList(reject, buf);
        }
        if (rejectPrefixes != null) {
            if (!buf.isEmpty()) {
                buf.append("; ");
            }
            buf.append("rejectPrefixes: ");
            quoteList(rejectPrefixes, buf);
        }
        if (rejectGlobs != null) {
            if (!buf.isEmpty()) {
                buf.append("; ");
            }
            buf.append("rejectGlobs: ");
            quoteList(rejectGlobs, buf);
        }
        return buf.toString();
    }
}