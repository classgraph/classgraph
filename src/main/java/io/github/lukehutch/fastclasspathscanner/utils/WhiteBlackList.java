/*
 * This file is part of FastClasspathScanner.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
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
package io.github.lukehutch.fastclasspathscanner.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** A class storing whitelist or blacklist criteria. */
public class WhiteBlackList {
    /** Whitelisted items (whole-string match) */
    private Set<String> whitelist;
    /** Blacklisted items (whole-string match) */
    private Set<String> blacklist;
    /** Whitelisted items (prefix match) */
    private List<String> whitelistPrefixes;
    /** Blacklisted items (prefix match) */
    private List<String> blacklistPrefixes;
    /** Whitelist glob strings (saved in serialization to JSON) */
    private Set<String> whitelistGlobs;
    /** Blacklist glob strings (saved in serialization to JSON) */
    private Set<String> blacklistGlobs;
    /** Whitelist regexp patterns */
    private transient List<Pattern> whitelistPatterns;
    /** Blacklist regexp patterns */
    private transient List<Pattern> blacklistPatterns;

    /** The type of match to perform. */
    public enum MatchType {
        PREFIX, LEAFNAME, WHOLE_STRING;
    }

    /** The type of match to perform. */
    public WhiteBlackList.MatchType matchType;

    /** Constructor for deserialization. */
    public WhiteBlackList() {
    }

    /** Constructor for deserialization. */
    public WhiteBlackList(final WhiteBlackList.MatchType matchType) {
        this.matchType = matchType;
    }

    /** Remove initial and final '/' characters, if any. */
    public static String normalizePath(final String path) {
        String normalized = path;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /** Remove initial and final '.' characters, if any. */
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

    /** Convert a path to a package name. */
    public static String pathToPackageName(final String path) {
        return normalizePath(path).replace('/', '.');
    }

    /** Convert a package name to a path. */
    public static String packageNameToPath(final String packageName) {
        return normalizePackageOrClassName(packageName).replace('.', '/');
    }

    /** Convert a class name to a classfile path. */
    public static String classNameToClassfilePath(final String className) {
        return normalizePackageOrClassName(className).replace('.', '/') + ".class";
    }

    /**
     * Convert a spec with a '*' glob character into a regular expression. Replaces "." with "\." and "*" with ".*",
     * then compiles a regular expression.
     */
    public static Pattern globToPattern(final String glob) {
        return Pattern.compile("^" + glob.replace(".", "\\.").replace("*", ".*") + "$");
    }

    public void addToWhitelist(final String str) {
        final boolean isGlob = str.contains("*");
        if (matchType == MatchType.PREFIX) {
            if (isGlob) {
                throw new IllegalArgumentException("Cannot use a glob wildcard here: " + str);
            }
            if (this.whitelistPrefixes == null) {
                this.whitelistPrefixes = new ArrayList<>();
            }
            this.whitelistPrefixes.add(str);
        } else if (isGlob) {
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

    public void addToBlacklist(final String str) {
        final boolean isGlob = str.contains("*");
        if (matchType == MatchType.PREFIX) {
            if (isGlob) {
                throw new IllegalArgumentException("Cannot use a glob wildcard here: " + str);
            }
            if (this.blacklistPrefixes == null) {
                this.blacklistPrefixes = new ArrayList<>();
            }
            this.blacklistPrefixes.add(str);
        } else if (isGlob) {
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
     * If patterns have not yet been compiled, compile them. This should only be run in a single-threaded context,
     * since this can only happen when a ScanResult and ScanSpec are being deserialized from JSON.
     */
    private void compilePatterns() {
        if (whitelistPatterns == null && whitelistGlobs != null) {
            whitelistPatterns = new ArrayList<>();
            for (final String globStr : whitelistGlobs) {
                whitelistPatterns.add(globToPattern(globStr));
            }
        }
        if (blacklistPatterns == null && blacklistGlobs != null) {
            blacklistPatterns = new ArrayList<>();
            for (final String globStr : blacklistGlobs) {
                blacklistPatterns.add(globToPattern(globStr));
            }
        }
    }

    /** Check if the requested string is whitelisted and not blacklisted. */
    public boolean isWhitelistedAndNotBlacklisted(final String str) {
        if (matchType == MatchType.PREFIX) {
            // Test if this string has a whitelisted/non-blacklisted prefix
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

        } else {
            compilePatterns();
            final String stringToTest =
                    // If testing a jar leafname, strip off everything but the leafname
                    matchType == MatchType.LEAFNAME ? JarUtils.leafName(str) :
                    // Otherwise match the whole string
                            str;

            // Perform whitelist/blacklist test on whole string (possibly as pattern)
            return ((whitelist == null && whitelistPatterns.isEmpty())
                    || (whitelist != null && whitelist.contains(stringToTest))
                    || matchesPatternList(stringToTest, whitelistPatterns))
                    && (blacklist == null || !blacklist.contains(stringToTest))
                    && !matchesPatternList(stringToTest, blacklistPatterns);
        }
    }

    /** Check if the requested string is whitelisted. */
    public boolean isWhitelisted(final String str) {
        if (matchType == MatchType.PREFIX) {
            // Test if this string has a whitelisted/non-blacklisted prefix
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

        } else {
            compilePatterns();
            final String stringToTest =
                    // If testing a jar leafname, strip off everything but the leafname
                    matchType == MatchType.LEAFNAME ? JarUtils.leafName(str) :
                    // Otherwise match the whole string
                            str;

            // Perform whitelist/blacklist test on whole string (possibly as pattern)
            return (whitelist == null && whitelistPatterns.isEmpty())
                    || (whitelist != null && whitelist.contains(stringToTest))
                    || matchesPatternList(stringToTest, whitelistPatterns);
        }
    }

    /** Returns true if there were no whitelist criteria added. */
    public boolean whitelistIsEmpty() {
        return whitelist == null && whitelistPrefixes == null && whitelistGlobs == null;
    }

    /**
     * Check if the requested string is <i>specifically</i> whitelisted and not blacklisted, i.e. will not return
     * true if the whitelist is empty.
     */
    public boolean isSpecificallyWhitelistedAndNotBlacklisted(final String str) {
        return !whitelistIsEmpty() && isWhitelistedAndNotBlacklisted(str);
    }

    /**
     * Check if the requested string is blacklisted.
     */
    public boolean isBlacklisted(final String str) {
        if (matchType == MatchType.PREFIX) {
            // Test if this string has a blacklisted prefix
            if (blacklistPrefixes != null) {
                for (final String prefix : blacklistPrefixes) {
                    if (str.startsWith(prefix)) {
                        return true;
                    }
                }
            }
            return false;

        } else {
            compilePatterns();
            final String stringToTest =
                    // If testing a jar leafname, strip off everything but the leafname
                    matchType == MatchType.LEAFNAME ? JarUtils.leafName(str) :
                    // Otherwise match the whole string
                            str;

            // Perform whitelist/blacklist test on whole string (possibly as pattern)
            return (blacklist != null && blacklist.contains(stringToTest))
                    || matchesPatternList(stringToTest, blacklistPatterns);
        }
    }

    /** Check if the requested string is a prefix of a whitelisted string. */
    public boolean whitelistHasPrefix(final String str) {
        if (matchType != MatchType.WHOLE_STRING) {
            throw new IllegalArgumentException("Can only find prefixes of whole strings");
        }
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