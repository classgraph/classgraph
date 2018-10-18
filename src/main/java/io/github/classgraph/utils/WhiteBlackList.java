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
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.classgraph.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** A class storing whitelist or blacklist criteria. */
public abstract class WhiteBlackList {
    /** Whitelisted items (whole-string match) */
    protected Set<String> whitelist;
    /** Blacklisted items (whole-string match) */
    protected Set<String> blacklist;
    /** Whitelisted items (prefix match) */
    protected List<String> whitelistPrefixes;
    /** Blacklisted items (prefix match) */
    protected List<String> blacklistPrefixes;
    /** Whitelist glob strings. (Serialized to JSON, for logging purposes.) */
    protected Set<String> whitelistGlobs;
    /** Blacklist glob strings. (Serialized to JSON, for logging purposes.) */
    protected Set<String> blacklistGlobs;
    /** Whitelist regexp patterns. (Not serialized to JSON.) */
    protected transient List<Pattern> whitelistPatterns;
    /** Blacklist regexp patterns. (Not serialized to JSON.) */
    protected transient List<Pattern> blacklistPatterns;

    /** Constructor for deserialization. */
    public WhiteBlackList() {
    }

    /** Whitelist/blacklist for prefix strings. */
    public static class WhiteBlackListPrefix extends WhiteBlackList {
        /** Add to the whitelist. */
        @Override
        public void addToWhitelist(final String str) {
            if (str.contains("*")) {
                throw new IllegalArgumentException("Cannot use a glob wildcard here: " + str);
            }
            if (this.whitelistPrefixes == null) {
                this.whitelistPrefixes = new ArrayList<>();
            }
            this.whitelistPrefixes.add(str);
        }

        /** Add to the blacklist. */
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

        /** Check if the requested string has a whitelisted/non-blacklisted prefix. */
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

        /** Check if the requested string has a whitelisted prefix. */
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

        /** Prefix-of-prefix is invalid. */
        @Override
        public boolean whitelistHasPrefix(final String str) {
            throw new IllegalArgumentException("Can only find prefixes of whole strings");
        }

        /** Check if the requested string has a blacklisted prefix. */
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
        /** Add to the whitelist. */
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
        }

        /** Add to the blacklist. */
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

        /** Check if the requested string is whitelisted and not blacklisted. */
        @Override
        public boolean isWhitelistedAndNotBlacklisted(final String str) {
            return ((whitelist == null && whitelistPatterns == null)
                    || (whitelist != null && whitelist.contains(str)) || matchesPatternList(str, whitelistPatterns))
                    && (blacklist == null || !blacklist.contains(str))
                    && !matchesPatternList(str, blacklistPatterns);
        }

        /** Check if the requested string is whitelisted. */
        @Override
        public boolean isWhitelisted(final String str) {
            return (whitelist == null && whitelistPatterns == null)
                    || (whitelist != null && whitelist.contains(str)) || matchesPatternList(str, whitelistPatterns);
        }

        /** Check if the requested string is a prefix of a whitelisted string. */
        @Override
        public boolean whitelistHasPrefix(final String str) {
            if (whitelist == null) {
                return false;
            }
            for (final String w : whitelist) {
                if (w.startsWith(str)) {
                    return true;
                }
            }
            return false;
        }

        /** Check if the requested string is blacklisted. */
        @Override
        public boolean isBlacklisted(final String str) {
            return (blacklist != null && blacklist.contains(str)) || matchesPatternList(str, blacklistPatterns);
        }
    }

    /** Whitelist/blacklist for prefix strings. */
    public static class WhiteBlackListLeafname extends WhiteBlackListWholeString {
        /** Add to the whitelist. */
        @Override
        public void addToWhitelist(final String str) {
            super.addToWhitelist(JarUtils.leafName(str));
        }

        /** Add to the blacklist. */
        @Override
        public void addToBlacklist(final String str) {
            super.addToBlacklist(JarUtils.leafName(str));
        }

        /** Check if the requested string is whitelisted and not blacklisted. */
        @Override
        public boolean isWhitelistedAndNotBlacklisted(final String str) {
            return super.isWhitelistedAndNotBlacklisted(JarUtils.leafName(str));
        }

        /** Check if the requested string is whitelisted. */
        @Override
        public boolean isWhitelisted(final String str) {
            return super.isWhitelisted(JarUtils.leafName(str));
        }

        /** Prefix tests are invalid for jar leafnames. */
        @Override
        public boolean whitelistHasPrefix(final String str) {
            throw new IllegalArgumentException("Can only find prefixes of whole strings");
        }

        /** Check if the requested string is blacklisted. */
        @Override
        public boolean isBlacklisted(final String str) {
            return super.isBlacklisted(JarUtils.leafName(str));
        }
    }

    /**
     * @param str
     *            The string to whitelist.
     */
    public abstract void addToWhitelist(final String str);

    /**
     * @param str
     *            The string to blacklist.
     */
    public abstract void addToBlacklist(final String str);

    /**
     * @param str
     *            The string to test.
     * @return true if the string is whitelisted and not blacklisted.
     */
    public abstract boolean isWhitelistedAndNotBlacklisted(final String str);

    /**
     * @param str
     *            The string to test.
     * @return true if the string is whitelisted.
     */
    public abstract boolean isWhitelisted(final String str);

    /**
     * @param str
     *            The string to test.
     * @return true if the string is a prefix of a whitelisted string.
     */
    public abstract boolean whitelistHasPrefix(final String str);

    /**
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
        String normalized = path;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    /**
     * Remove initial and final '.' characters, if any.
     * 
     * @param packageOrClassName
     *            The package or class name.
     * @return The normalized package or class name.
     */
    public static String normalizePackageOrClassName(final String packageOrClassName) {
        String normalized = packageOrClassName;
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * Convert a path to a package name.
     * 
     * @param path
     *            The path.
     * @return The package name.
     */
    public static String pathToPackageName(final String path) {
        return normalizePath(path).replace('/', '.');
    }

    /**
     * Convert a package name to a path.
     * 
     * @param packageName
     *            The package name.
     * @return The path.
     */
    public static String packageNameToPath(final String packageName) {
        return normalizePackageOrClassName(packageName).replace('.', '/') + "/";
    }

    /**
     * Convert a class name to a classfile path.
     * 
     * @param className
     *            The class name.
     * @return The classfile path (including a ".class" suffix).
     */
    public static String classNameToClassfilePath(final String className) {
        return JarUtils.classNameToClassfilePath(normalizePackageOrClassName(className));
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
     * @return true if there were no whitelist criteria added.
     */
    public boolean whitelistIsEmpty() {
        return whitelist == null && whitelistPrefixes == null && whitelistGlobs == null;
    }

    /**
     * @param str
     *            The string to test.
     * @return true if the requested string is <i>specifically</i> whitelisted and not blacklisted, i.e. will not
     *         return true if the whitelist is empty, or if the string is blacklisted.
     */
    public boolean isSpecificallyWhitelistedAndNotBlacklisted(final String str) {
        return !whitelistIsEmpty() && isWhitelistedAndNotBlacklisted(str);
    }

    /**
     * @param str
     *            The string to test.
     * @return true if the requested string is <i>specifically</i> whitelisted, i.e. will not return true if the
     *         whitelist is empty.
     */
    public boolean isSpecificallyWhitelisted(final String str) {
        return !whitelistIsEmpty() && isWhitelisted(str);
    }

    /** Need to sort prefixes to ensure correct whitelist/blacklist evaluation (see Issue #167). */
    public void sortPrefixes() {
        if (whitelistPrefixes != null) {
            Collections.sort(whitelistPrefixes);
        }
        if (blacklistPrefixes != null) {
            Collections.sort(blacklistPrefixes);
        }
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        if (whitelist != null) {
            buf.append("whitelist: " + whitelist);
        }
        if (whitelistPrefixes != null) {
            if (buf.length() > 0) {
                buf.append("; ");
            }
            buf.append("whitelistPrefixes: " + whitelistPrefixes);
        }
        if (whitelistGlobs != null) {
            if (buf.length() > 0) {
                buf.append("; ");
            }
            buf.append("whitelistGlobs: " + whitelistGlobs);
        }
        if (blacklist != null) {
            if (buf.length() > 0) {
                buf.append("; ");
            }
            buf.append("blacklist: " + blacklist);
        }
        if (blacklistPrefixes != null) {
            if (buf.length() > 0) {
                buf.append("; ");
            }
            buf.append("blacklistPrefixes: " + blacklistPrefixes);
        }
        if (blacklistGlobs != null) {
            if (buf.length() > 0) {
                buf.append("; ");
            }
            buf.append("blacklistGlobs: " + blacklistGlobs);
        }
        return buf.toString();
    }
}