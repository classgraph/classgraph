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

/** A class storing accept or reject criteria. */
public abstract class AcceptReject {
    /** Accepted items (whole-string match). */
    protected Set<String> accept;
    /** Rejected items (whole-string match). */
    protected Set<String> reject;
    /** Accepted items (prefix match), as a set. */
    protected Set<String> acceptPrefixesSet;
    /** Accepted items (prefix match), as a sorted list. */
    protected List<String> acceptPrefixes;
    /** Rejected items (prefix match). */
    protected List<String> rejectPrefixes;
    /** Accept glob strings. (Serialized to JSON, for logging purposes.) */
    protected Set<String> acceptGlobs;
    /** Reject glob strings. (Serialized to JSON, for logging purposes.) */
    protected Set<String> rejectGlobs;
    /** Accept regexp patterns. (Not serialized to JSON.) */
    protected transient List<Pattern> acceptPatterns;
    /** Reject regexp patterns. (Not serialized to JSON.) */
    protected transient List<Pattern> rejectPatterns;
    /** The separator character. */
    protected char separatorChar;

    /** Deserialization constructor. */
    public AcceptReject() {
    }

    /**
     * Constructor for deserialization.
     *
     * @param separatorChar
     *            the separator char
     */
    public AcceptReject(final char separatorChar) {
        this.separatorChar = separatorChar;
    }

    /** Accept/reject for prefix strings. */
    public static class AcceptRejectPrefix extends AcceptReject {
        /** Deserialization constructor. */
        public AcceptRejectPrefix() {
            super();
        }

        /**
         * Instantiate a new accept/reject for prefix strings.
         *
         * @param separatorChar
         *            the separator char
         */
        public AcceptRejectPrefix(final char separatorChar) {
            super(separatorChar);
        }

        /**
         * Add to the accept.
         *
         * @param str
         *            the string to accept
         */
        @Override
        public void addToAccept(final String str) {
            if (str.contains("*")) {
                throw new IllegalArgumentException("Cannot use a glob wildcard here: " + str);
            }
            if (this.acceptPrefixesSet == null) {
                this.acceptPrefixesSet = new HashSet<>();
            }
            this.acceptPrefixesSet.add(str);
        }

        /**
         * Add to the reject.
         *
         * @param str
         *            the string to reject
         */
        @Override
        public void addToReject(final String str) {
            if (str.contains("*")) {
                throw new IllegalArgumentException("Cannot use a glob wildcard here: " + str);
            }
            if (this.rejectPrefixes == null) {
                this.rejectPrefixes = new ArrayList<>();
            }
            this.rejectPrefixes.add(str);
        }

        /**
         * Check if the requested string has an accepted/non-rejected prefix.
         *
         * @param str
         *            the string to test
         * @return true if string is accepted and not rejected
         */
        @Override
        public boolean isAcceptedAndNotRejected(final String str) {
            boolean isAccepted = acceptPrefixes == null;
            if (!isAccepted) {
                for (final String prefix : acceptPrefixes) {
                    if (str.startsWith(prefix)) {
                        isAccepted = true;
                        break;
                    }
                }
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
            return true;
        }

        /**
         * Check if the requested string has an accepted prefix.
         *
         * @param str
         *            the string to test
         * @return true if string is accepted
         */
        @Override
        public boolean isAccepted(final String str) {
            boolean isAccepted = acceptPrefixes == null;
            if (!isAccepted) {
                for (final String prefix : acceptPrefixes) {
                    if (str.startsWith(prefix)) {
                        isAccepted = true;
                        break;
                    }
                }
            }
            return isAccepted;
        }

        /**
         * Prefix-of-prefix is invalid -- throws {@link IllegalArgumentException}.
         *
         * @param str
         *            the string to test
         * @return (does not return, throws exception)
         * @throws IllegalArgumentException
         *             always
         */
        @Override
        public boolean acceptHasPrefix(final String str) {
            throw new IllegalArgumentException("Can only find prefixes of whole strings");
        }

        /**
         * Check if the requested string has a rejected prefix.
         *
         * @param str
         *            the string to test
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
            return false;
        }
    }

    /** Accept/reject for whole-strings matches. */
    public static class AcceptRejectWholeString extends AcceptReject {
        /** Deserialization constructor. */
        public AcceptRejectWholeString() {
            super();
        }

        /**
         * Instantiate a new accept/reject for whole-string matches.
         *
         * @param separatorChar
         *            the separator char
         */
        public AcceptRejectWholeString(final char separatorChar) {
            super(separatorChar);
        }

        /**
         * Add to the accept.
         *
         * @param str
         *            the string to accept
         */
        @Override
        public void addToAccept(final String str) {
            if (str.contains("*")) {
                if (this.acceptGlobs == null) {
                    this.acceptGlobs = new HashSet<>();
                    this.acceptPatterns = new ArrayList<>();
                }
                this.acceptGlobs.add(str);
                this.acceptPatterns.add(globToPattern(str, /* simpleGlob = */ true));
            } else {
                if (this.accept == null) {
                    this.accept = new HashSet<>();
                }
                this.accept.add(str);
            }

            // For AcceptRejectWholeString, which doesn't perform prefix matches like AcceptRejectPrefix,
            // use acceptPrefixes to store all parent prefixes of an accepted path, so that
            // acceptHasPrefix() can operate efficiently on very large accepts (#338),
            // in particular where the size of the accept is much larger than the maximum path depth.
            if (this.acceptPrefixesSet == null) {
                this.acceptPrefixesSet = new HashSet<>();
                acceptPrefixesSet.add("");
                acceptPrefixesSet.add("/");
            }
            final String separator = Character.toString(separatorChar);
            String prefix = str;
            if (prefix.contains("*")) {
                // Stop performing prefix search at first '*' -- this means prefix matching will
                // break if there is more than one '*' in the path
                prefix = prefix.substring(0, prefix.indexOf('*'));
                // /path/to/wildcard*.jar -> /path/to
                // /path/to/*.jar -> /path/to
                final int sepIdx = prefix.lastIndexOf(separatorChar);
                if (sepIdx < 0) {
                    prefix = "";
                } else {
                    prefix = prefix.substring(0, prefix.lastIndexOf(separatorChar));
                }
            }
            // Strip off any final separator
            while (prefix.endsWith(separator)) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            // Add str itself as a prefix (this will only match a parent dir for 
            for (; !prefix.isEmpty(); prefix = FileUtils.getParentDirPath(prefix, separatorChar)) {
                acceptPrefixesSet.add(prefix + separatorChar);
            }
        }

        /**
         * Add to the reject.
         *
         * @param str
         *            the string to reject
         */
        @Override
        public void addToReject(final String str) {
            if (str.contains("*")) {
                if (this.rejectGlobs == null) {
                    this.rejectGlobs = new HashSet<>();
                    this.rejectPatterns = new ArrayList<>();
                }
                this.rejectGlobs.add(str);
                this.rejectPatterns.add(globToPattern(str, /* simpleGlob = */ true));
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
         * @param str
         *            the string to test
         * @return true if the string is accepted and not rejected
         */
        @Override
        public boolean isAcceptedAndNotRejected(final String str) {
            return isAccepted(str) && !isRejected(str);
        }

        /**
         * Check if the requested string is accepted.
         *
         * @param str
         *            the string to test
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
         * @param str
         *            the string to test
         * @return true if the string is a prefix of an accepted string
         */
        @Override
        public boolean acceptHasPrefix(final String str) {
            if (acceptPrefixesSet == null) {
                return false;
            }
            return acceptPrefixesSet.contains(str);
        }

        /**
         * Check if the requested string is rejected.
         *
         * @param str
         *            the string to test
         * @return true if the string is rejected
         */
        @Override
        public boolean isRejected(final String str) {
            return (reject != null && reject.contains(str)) || matchesPatternList(str, rejectPatterns);
        }
    }

    /** Accept/reject for leaf matches. */
    public static class AcceptRejectLeafname extends AcceptRejectWholeString {
        /** Deserialization constructor. */
        public AcceptRejectLeafname() {
            super();
        }

        /**
         * Instantiates a new accept/reject for leaf matches.
         *
         * @param separatorChar
         *            the separator char
         */
        public AcceptRejectLeafname(final char separatorChar) {
            super(separatorChar);
        }

        /**
         * Add to the accept.
         *
         * @param str
         *            the string to accept
         */
        @Override
        public void addToAccept(final String str) {
            super.addToAccept(JarUtils.leafName(str));
        }

        /**
         * Add to the reject.
         *
         * @param str
         *            the string to reject
         */
        @Override
        public void addToReject(final String str) {
            super.addToReject(JarUtils.leafName(str));
        }

        /**
         * Check if the requested string is accepted and not rejected.
         *
         * @param str
         *            the string to test
         * @return true if the string is accepted and not rejected
         */
        @Override
        public boolean isAcceptedAndNotRejected(final String str) {
            return super.isAcceptedAndNotRejected(JarUtils.leafName(str));
        }

        /**
         * Check if the requested string is accepted.
         *
         * @param str
         *            the string to test
         * @return true if the string is accepted
         */
        @Override
        public boolean isAccepted(final String str) {
            return super.isAccepted(JarUtils.leafName(str));
        }

        /**
         * Prefix tests are invalid for jar leafnames -- throws {@link IllegalArgumentException}.
         *
         * @param str
         *            the string to test
         * @return (does not return, throws exception)
         * @throws IllegalArgumentException
         *             always
         */
        @Override
        public boolean acceptHasPrefix(final String str) {
            throw new IllegalArgumentException("Can only find prefixes of whole strings");
        }

        /**
         * Check if the requested string is rejected.
         *
         * @param str
         *            the string to test
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
     * @param str
     *            The string to accept.
     */
    public abstract void addToAccept(final String str);

    /**
     * Add to the reject.
     *
     * @param str
     *            The string to reject.
     */
    public abstract void addToReject(final String str);

    /**
     * Check if a string is accepted and not rejected.
     *
     * @param str
     *            The string to test.
     * @return true if the string is accepted and not rejected.
     */
    public abstract boolean isAcceptedAndNotRejected(final String str);

    /**
     * Check if a string is accepted.
     *
     * @param str
     *            The string to test.
     * @return true if the string is accepted.
     */
    public abstract boolean isAccepted(final String str);

    /**
     * Check if a string is a prefix of an accepted string.
     *
     * @param str
     *            The string to test.
     * @return true if the string is a prefix of an accepted string.
     */
    public abstract boolean acceptHasPrefix(final String str);

    /**
     * Check if a string is rejected.
     *
     * @param str
     *            The string to test.
     * @return true if the string is rejected.
     */
    public abstract boolean isRejected(final String str);

    /**
     * Remove initial and final '/' characters, if any.
     * 
     * @param path
     *            The path to normalize.
     * @return The normalized path.
     */
    public static String normalizePath(final String path) {
        String pathResolved = FastPathResolver.resolve(path);
        while (pathResolved.startsWith("/")) {
            pathResolved = pathResolved.substring(1);
        }
        return pathResolved;
    }

    /**
     * Remove initial and final '.' characters, if any.
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
     * Convert a package name to a path.
     * 
     * @param packageName
     *            The package name.
     * @return The path.
     */
    public static String packageNameToPath(final String packageName) {
        return packageName.replace('.', '/');
    }

    /**
     * Convert a class name to a classfile path.
     * 
     * @param className
     *            The class name.
     * @return The classfile path (including a ".class" suffix).
     */
    public static String classNameToClassfilePath(final String className) {
        return JarUtils.classNameToClassfilePath(className);
    }

    /**
     * Convert a spec with a '*' glob character into a regular expression.
     * 
     * @param glob
     *            The glob string.
     * @param simpleGlob
     *            if true, handles simple globs: "*" matches zero or more characters (replaces "." with "\\.", "*"
     *            with ".*", then compiles a regular expression). If false, handles filesystem-style globs: "**"
     *            matches zero or more characters, "*" matches zero or more characters other than "/", "?" matches
     *            one character (replaces "." with "\\.", "**" with ".*", "*" with "[^/]*", and "?" with ".", then
     *            compiles a regular expression).
     * @return The Pattern created from the glob string.
     */
    public static Pattern globToPattern(final String glob, final boolean simpleGlob) {
        // TODO: when API is next changed, make all glob behavior consistent between accept/reject criteria
        // and resource filtering (i.e. enforce simpleGlob == false, at least for accept/reject criteria for
        // paths, although packages/classes would need different handling because ** should work across
        // packages of any depth, rather than paths of any number of segments)
        return Pattern.compile("^" //
                + (simpleGlob //
                        ? glob.replace(".", "\\.") //
                                .replace("*", ".*") //
                        : glob.replace(".", "\\.") //
                                .replace("*", "[^/]*") //
                                .replace("[^/]*[^/]*", ".*") //
                                .replace('?', '.') //
                ) //
                + "$");
    }

    /**
     * Check if a string matches one of the patterns in the provided list.
     *
     * @param str
     *            the string to test
     * @param patterns
     *            the patterns
     * @return true, if successful
     */
    private static boolean matchesPatternList(final String str, final List<Pattern> patterns) {
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
        return accept == null && acceptPrefixes == null && acceptGlobs == null;
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
     * @param str
     *            The string to test.
     * @return true if the requested string is <i>specifically</i> accepted and not rejected, i.e. will not return
     *         true if the accept is empty, or if the string is rejected.
     */
    public boolean isSpecificallyAcceptedAndNotRejected(final String str) {
        return !acceptIsEmpty() && isAcceptedAndNotRejected(str);
    }

    /**
     * Check if a string is specifically accepted.
     *
     * @param str
     *            The string to test.
     * @return true if the requested string is <i>specifically</i> accepted, i.e. will not return true if the accept
     *         is empty.
     */
    public boolean isSpecificallyAccepted(final String str) {
        return !acceptIsEmpty() && isAccepted(str);
    }

    /** Need to sort prefixes to ensure correct accept/reject evaluation (see Issue #167). */
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
     * @param coll
     *            the coll
     * @param buf
     *            the buf
     */
    private static void quoteList(final Collection<String> coll, final StringBuilder buf) {
        buf.append('[');
        boolean first = true;
        for (final String item : coll) {
            if (first) {
                first = false;
            } else {
                buf.append(", ");
            }
            buf.append('"');
            for (int i = 0; i < item.length(); i++) {
                final char c = item.charAt(i);
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

    /* (non-Javadoc)
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
            if (buf.length() > 0) {
                buf.append("; ");
            }
            buf.append("acceptPrefixes: ");
            quoteList(acceptPrefixes, buf);
        }
        if (acceptGlobs != null) {
            if (buf.length() > 0) {
                buf.append("; ");
            }
            buf.append("acceptGlobs: ");
            quoteList(acceptGlobs, buf);
        }
        if (reject != null) {
            if (buf.length() > 0) {
                buf.append("; ");
            }
            buf.append("reject: ");
            quoteList(reject, buf);
        }
        if (rejectPrefixes != null) {
            if (buf.length() > 0) {
                buf.append("; ");
            }
            buf.append("rejectPrefixes: ");
            quoteList(rejectPrefixes, buf);
        }
        if (rejectGlobs != null) {
            if (buf.length() > 0) {
                buf.append("; ");
            }
            buf.append("rejectGlobs: ");
            quoteList(rejectGlobs, buf);
        }
        return buf.toString();
    }
}