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
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;

/** A class storing whitelist or blacklist criteria. */
public abstract class WhiteBlackList {
    /** Whitelisted items (whole-string match). */
    protected Set<String> whitelist;
    /** Blacklisted items (whole-string match). */
    protected Set<String> blacklist;
    /** Whitelisted items (prefix match), as a set. */
    protected Set<String> whitelistPrefixesSet;
    /** Whitelisted items (prefix match), as a sorted list. */
    protected List<String> whitelistPrefixes;
    /** Blacklisted items (prefix match). */
    protected List<String> blacklistPrefixes;
    /** Whitelist glob strings. (Serialized to JSON, for logging purposes.) */
    protected Set<String> whitelistGlobs;
    /** Blacklist glob strings. (Serialized to JSON, for logging purposes.) */
    protected Set<String> blacklistGlobs;
    /** Whitelist regexp patterns. (Not serialized to JSON.) */
    protected transient List<Pattern> whitelistPatterns;
    /** Blacklist regexp patterns. (Not serialized to JSON.) */
    protected transient List<Pattern> blacklistPatterns;
    /** The separator character. */
    protected char separatorChar;

    /** Deserialization constructor. */
    public WhiteBlackList() {
    }

    /**
     * Constructor for deserialization.
     *
     * @param separatorChar
     *            the separator char
     */
    public WhiteBlackList(final char separatorChar) {
        this.separatorChar = separatorChar;
    }

    /** Whitelist/blacklist for prefix strings. */
    public static class WhiteBlackListPrefix extends WhiteBlackList {
        /** Deserialization constructor. */
        public WhiteBlackListPrefix() {
            super();
        }

        /**
         * Instantiate a new whitelist/blacklist for prefix strings.
         *
         * @param separatorChar
         *            the separator char
         */
        public WhiteBlackListPrefix(final char separatorChar) {
            super(separatorChar);
        }

        /**
         * Add to the whitelist.
         *
         * @param str
         *            the string to whitelist
         */
        @Override
        public void addToWhitelist(final String str) {
            if (str.contains("*")) {
                throw new IllegalArgumentException("Cannot use a glob wildcard here: " + str);
            }
            if (this.whitelistPrefixesSet == null) {
                this.whitelistPrefixesSet = new HashSet<>();
            }
            this.whitelistPrefixesSet.add(str);
        }

        /**
         * Add to the blacklist.
         *
         * @param str
         *            the string to blacklist
         */
        @Override
        public void addToBlacklist(final String str) {
            if (str.contains("*")) {
                throw new IllegalArgumentException("Cannot use a glob wildcard here: " + str);
            }
            if (this.blacklistPrefixes == null) {
                this.blacklistPrefixes = new ArrayList<>();
            }
            this.blacklistPrefixes.add(str);
        }

        /**
         * Check if the requested string has a whitelisted/non-blacklisted prefix.
         *
         * @param str
         *            the string to test
         * @return true if string is whitelisted and not blacklisted
         */
        @Override
        public boolean isWhitelistedAndNotBlacklisted(final String str) {
            boolean isWhitelisted = whitelistPrefixes == null;
            if (!isWhitelisted) {
                for (final String prefix : whitelistPrefixes) {
                    if (str.startsWith(prefix)) {
                        isWhitelisted = true;
                        break;
                    }
                }
            }
            if (!isWhitelisted) {
                return false;
            }
            if (blacklistPrefixes != null) {
                for (final String prefix : blacklistPrefixes) {
                    if (str.startsWith(prefix)) {
                        return false;
                    }
                }
            }
            return true;
        }

        /**
         * Check if the requested string has a whitelisted prefix.
         *
         * @param str
         *            the string to test
         * @return true if string is whitelisted
         */
        @Override
        public boolean isWhitelisted(final String str) {
            boolean isWhitelisted = whitelistPrefixes == null;
            if (!isWhitelisted) {
                for (final String prefix : whitelistPrefixes) {
                    if (str.startsWith(prefix)) {
                        isWhitelisted = true;
                        break;
                    }
                }
            }
            return isWhitelisted;
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
        public boolean whitelistHasPrefix(final String str) {
            throw new IllegalArgumentException("Can only find prefixes of whole strings");
        }

        /**
         * Check if the requested string has a blacklisted prefix.
         *
         * @param str
         *            the string to test
         * @return true if the string has a blacklisted prefix
         */
        @Override
        public boolean isBlacklisted(final String str) {
            if (blacklistPrefixes != null) {
                for (final String prefix : blacklistPrefixes) {
                    if (str.startsWith(prefix)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** Whitelist/blacklist for whole-strings matches. */
    public static class WhiteBlackListWholeString extends WhiteBlackList {
        /** Deserialization constructor. */
        public WhiteBlackListWholeString() {
            super();
        }

        /**
         * Instantiate a new whitelist/blacklist for whole-string matches.
         *
         * @param separatorChar
         *            the separator char
         */
        public WhiteBlackListWholeString(final char separatorChar) {
            super(separatorChar);
        }

        /**
         * Add to the whitelist.
         *
         * @param str
         *            the string to whitelist
         */
        @Override
        public void addToWhitelist(final String str) {
            if (str.contains("*")) {
                if (this.whitelistGlobs == null) {
                    this.whitelistGlobs = new HashSet<>();
                    this.whitelistPatterns = new ArrayList<>();
                }
                this.whitelistGlobs.add(str);
                this.whitelistPatterns.add(globToPattern(str));
            } else {
                if (this.whitelist == null) {
                    this.whitelist = new HashSet<>();
                }
                this.whitelist.add(str);
            }

            // For WhiteBlackListWholeString, which doesn't perform prefix matches like WhiteBlackListPrefix,
            // use whitelistPrefixes to store all parent prefixes of a whitelisted path, so that
            // whitelistHasPrefix() can operate efficiently on very large whitelists (#338),
            // in particular where the size of the whitelist is much larger than the maximum path depth.
            if (this.whitelistPrefixesSet == null) {
                this.whitelistPrefixesSet = new HashSet<>();
                whitelistPrefixesSet.add("");
                whitelistPrefixesSet.add("/");
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
                whitelistPrefixesSet.add(prefix + separatorChar);
            }
        }

        /**
         * Add to the blacklist.
         *
         * @param str
         *            the string to blacklist
         */
        @Override
        public void addToBlacklist(final String str) {
            if (str.contains("*")) {
                if (this.blacklistGlobs == null) {
                    this.blacklistGlobs = new HashSet<>();
                    this.blacklistPatterns = new ArrayList<>();
                }
                this.blacklistGlobs.add(str);
                this.blacklistPatterns.add(globToPattern(str));
            } else {
                if (this.blacklist == null) {
                    this.blacklist = new HashSet<>();
                }
                this.blacklist.add(str);
            }
        }

        /**
         * Check if the requested string is whitelisted and not blacklisted.
         *
         * @param str
         *            the string to test
         * @return true if the string is whitelisted and not blacklisted
         */
        @Override
        public boolean isWhitelistedAndNotBlacklisted(final String str) {
            return isWhitelisted(str) && !isBlacklisted(str);
        }

        /**
         * Check if the requested string is whitelisted.
         *
         * @param str
         *            the string to test
         * @return true if the string is whitelisted
         */
        @Override
        public boolean isWhitelisted(final String str) {
            return (whitelist == null && whitelistPatterns == null)
                    || (whitelist != null && whitelist.contains(str)) || matchesPatternList(str, whitelistPatterns);
        }

        /**
         * Check if the requested string is a prefix of a whitelisted string.
         *
         * @param str
         *            the string to test
         * @return true if the string is a prefix of a whitelisted string
         */
        @Override
        public boolean whitelistHasPrefix(final String str) {
            if (whitelistPrefixesSet == null) {
                return false;
            }
            return whitelistPrefixesSet.contains(str);
        }

        /**
         * Check if the requested string is blacklisted.
         *
         * @param str
         *            the string to test
         * @return true if the string is blacklisted
         */
        @Override
        public boolean isBlacklisted(final String str) {
            return (blacklist != null && blacklist.contains(str)) || matchesPatternList(str, blacklistPatterns);
        }
    }

    /** Whitelist/blacklist for leaf matches. */
    public static class WhiteBlackListLeafname extends WhiteBlackListWholeString {
        /** Deserialization constructor. */
        public WhiteBlackListLeafname() {
            super();
        }

        /**
         * Instantiates a new whitelist/blacklist for leaf matches.
         *
         * @param separatorChar
         *            the separator char
         */
        public WhiteBlackListLeafname(final char separatorChar) {
            super(separatorChar);
        }

        /**
         * Add to the whitelist.
         *
         * @param str
         *            the string to whitelist
         */
        @Override
        public void addToWhitelist(final String str) {
            super.addToWhitelist(JarUtils.leafName(str));
        }

        /**
         * Add to the blacklist.
         *
         * @param str
         *            the string to blacklist
         */
        @Override
        public void addToBlacklist(final String str) {
            super.addToBlacklist(JarUtils.leafName(str));
        }

        /**
         * Check if the requested string is whitelisted and not blacklisted.
         *
         * @param str
         *            the string to test
         * @return true if the string is whitelisted and not blacklisted
         */
        @Override
        public boolean isWhitelistedAndNotBlacklisted(final String str) {
            return super.isWhitelistedAndNotBlacklisted(JarUtils.leafName(str));
        }

        /**
         * Check if the requested string is whitelisted.
         *
         * @param str
         *            the string to test
         * @return true if the string is whitelisted
         */
        @Override
        public boolean isWhitelisted(final String str) {
            return super.isWhitelisted(JarUtils.leafName(str));
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
        public boolean whitelistHasPrefix(final String str) {
            throw new IllegalArgumentException("Can only find prefixes of whole strings");
        }

        /**
         * Check if the requested string is blacklisted.
         *
         * @param str
         *            the string to test
         * @return true if the string is blacklisted
         */
        @Override
        public boolean isBlacklisted(final String str) {
            return super.isBlacklisted(JarUtils.leafName(str));
        }
    }

    /**
     * Add to the whitelist.
     *
     * @param str
     *            The string to whitelist.
     */
    public abstract void addToWhitelist(final String str);

    /**
     * Add to the blacklist.
     *
     * @param str
     *            The string to blacklist.
     */
    public abstract void addToBlacklist(final String str);

    /**
     * Check if a string is whitelisted and not blacklisted.
     *
     * @param str
     *            The string to test.
     * @return true if the string is whitelisted and not blacklisted.
     */
    public abstract boolean isWhitelistedAndNotBlacklisted(final String str);

    /**
     * Check if a string is whitelisted.
     *
     * @param str
     *            The string to test.
     * @return true if the string is whitelisted.
     */
    public abstract boolean isWhitelisted(final String str);

    /**
     * Check if a string is a prefix of a whitelisted string.
     *
     * @param str
     *            The string to test.
     * @return true if the string is a prefix of a whitelisted string.
     */
    public abstract boolean whitelistHasPrefix(final String str);

    /**
     * Check if a string is blacklisted.
     *
     * @param str
     *            The string to test.
     * @return true if the string is blacklisted.
     */
    public abstract boolean isBlacklisted(final String str);

    /**
     * Remove initial and final '/' characters, if any.
     * 
     * @param path
     *            The path to normalize.
     * @return The normalized path.
     */
    public static String normalizePath(final String path) {
        return FileUtils.sanitizeEntryPath(path, /* removeInitialSlash = */ true);
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
     * Convert a spec with a '*' glob character into a regular expression. Replaces "." with "\." and "*" with ".*",
     * then compiles a regular expression.
     * 
     * @param glob
     *            The glob string.
     * @return The Pattern created from the glob string.
     */
    public static Pattern globToPattern(final String glob) {
        return Pattern.compile("^" + glob.replace(".", "\\.").replace("*", ".*") + "$");
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
     * Check if the whitelist is empty.
     *
     * @return true if there were no whitelist criteria added.
     */
    public boolean whitelistIsEmpty() {
        return whitelist == null && whitelistPrefixes == null && whitelistGlobs == null;
    }

    /**
     * Check if the blacklist is empty.
     *
     * @return true if there were no blacklist criteria added.
     */
    public boolean blacklistIsEmpty() {
        return blacklist == null && blacklistPrefixes == null && blacklistGlobs == null;
    }

    /**
     * Check if the whitelist and blacklist are empty.
     *
     * @return true if there were no whitelist or blacklist criteria added.
     */
    public boolean whitelistAndBlacklistAreEmpty() {
        return whitelistIsEmpty() && blacklistIsEmpty();
    }

    /**
     * Check if a string is specifically whitelisted and not blacklisted.
     *
     * @param str
     *            The string to test.
     * @return true if the requested string is <i>specifically</i> whitelisted and not blacklisted, i.e. will not
     *         return true if the whitelist is empty, or if the string is blacklisted.
     */
    public boolean isSpecificallyWhitelistedAndNotBlacklisted(final String str) {
        return !whitelistIsEmpty() && isWhitelistedAndNotBlacklisted(str);
    }

    /**
     * Check if a string is specifically whitelisted.
     *
     * @param str
     *            The string to test.
     * @return true if the requested string is <i>specifically</i> whitelisted, i.e. will not return true if the
     *         whitelist is empty.
     */
    public boolean isSpecificallyWhitelisted(final String str) {
        return !whitelistIsEmpty() && isWhitelisted(str);
    }

    /** Need to sort prefixes to ensure correct whitelist/blacklist evaluation (see Issue #167). */
    void sortPrefixes() {
        if (whitelistPrefixesSet != null) {
            whitelistPrefixes = new ArrayList<>(whitelistPrefixesSet);
        }
        if (whitelistPrefixes != null) {
            CollectionUtils.sortIfNotEmpty(whitelistPrefixes);
        }
        if (blacklistPrefixes != null) {
            CollectionUtils.sortIfNotEmpty(blacklistPrefixes);
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
        if (whitelist != null) {
            buf.append("whitelist: ");
            quoteList(whitelist, buf);
        }
        if (whitelistPrefixes != null) {
            if (buf.length() > 0) {
                buf.append("; ");
            }
            buf.append("whitelistPrefixes: ");
            quoteList(whitelistPrefixes, buf);
        }
        if (whitelistGlobs != null) {
            if (buf.length() > 0) {
                buf.append("; ");
            }
            buf.append("whitelistGlobs: ");
            quoteList(whitelistGlobs, buf);
        }
        if (blacklist != null) {
            if (buf.length() > 0) {
                buf.append("; ");
            }
            buf.append("blacklist: ");
            quoteList(blacklist, buf);
        }
        if (blacklistPrefixes != null) {
            if (buf.length() > 0) {
                buf.append("; ");
            }
            buf.append("blacklistPrefixes: ");
            quoteList(blacklistPrefixes, buf);
        }
        if (blacklistGlobs != null) {
            if (buf.length() > 0) {
                buf.append("; ");
            }
            buf.append("blacklistGlobs: ");
            quoteList(blacklistGlobs, buf);
        }
        return buf.toString();
    }
}