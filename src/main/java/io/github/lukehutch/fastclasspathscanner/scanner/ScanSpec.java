package io.github.lukehutch.fastclasspathscanner.scanner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.regex.Pattern;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.utils.Log;

public class ScanSpec {
    /** Whitelisted package prefixes with "." appended, or the empty list if all packages are whitelisted. */
    private final ArrayList<String> whitelistedPackagePrefixes = new ArrayList<>();

    /** Blacklisted package prefixes with "." appended. */
    private final ArrayList<String> blacklistedPackagePrefixes = new ArrayList<>();

    /** Whitelisted class names, or the empty list if none. */
    private final HashSet<String> whitelistedClassNames = new HashSet<>();

    /** Path prefixes of whitelisted classes, or the empty list if none. */
    private final HashSet<String> whitelistedClassPathPrefixes = new HashSet<>();

    /** Blacklisted class names. */
    private final HashSet<String> blacklistedClassNames = new HashSet<>();

    /** Whitelisted package paths with "/" appended, or the empty list if all packages are whitelisted. */
    private final ArrayList<String> whitelistedPathPrefixes = new ArrayList<>();

    /** Blacklisted package paths with "/" appended. */
    private final ArrayList<String> blacklistedPathPrefixes = new ArrayList<>();

    /** Whitelisted jarfile names. (Leaf filename only.) */
    private final HashSet<String> whitelistedJars = new HashSet<>();

    /** Blacklisted jarfile names. (Leaf filename only.) */
    private final HashSet<String> blacklistedJars = new HashSet<>();

    /** Whitelisted jarfile names containing a glob('*') character, converted to a regexp. (Leaf filename only.) */
    private final ArrayList<Pattern> whitelistedJarPatterns = new ArrayList<>();

    /** Blacklisted jarfile names containing a glob('*') character, converted to a regexp. (Leaf filename only.) */
    private final ArrayList<Pattern> blacklistedJarPatterns = new ArrayList<>();

    /** True if jarfiles should be scanned. */
    public final boolean scanJars;

    /** True if non-jarfiles (directories) should be scanned. */
    public final boolean scanNonJars;

    /**
     * Blacklist all java.* and sun.* packages. (The Java standard library jars, e.g rt.jar, are also blacklisted by
     * the file/directory scanner.)
     */
    private static String[] BLACKLISTED_PACKAGES = { "java", "sun" };

    public ScanSpec(final String[] scanSpecs) {
        // If scanning all packages, blacklist Java types (they are always excluded from scanning,
        // but may occur as the type of a field)
        for (final String pkg : BLACKLISTED_PACKAGES) {
            final String pkgPrefix = pkg + ".";
            blacklistedPackagePrefixes.add(pkgPrefix);
            blacklistedPathPrefixes.add(pkgPrefix.replace('.', '/'));
        }

        final HashSet<String> uniqueWhitelistedPathPrefixes = new HashSet<>();
        final HashSet<String> uniqueBlacklistedPathPrefixes = new HashSet<>();
        final HashSet<String> uniqueWhitelistedPackagePrefixes = new HashSet<>();
        final HashSet<String> uniqueBlacklistedPackagePrefixes = new HashSet<>();
        boolean scanJars = true, scanNonJars = true;
        for (final String scanSpecEntry : scanSpecs) {
            String spec = scanSpecEntry;
            final boolean blacklisted = spec.startsWith("-");
            if (blacklisted) {
                // Strip off "-"
                spec = spec.substring(1);
            }
            final boolean isJar = spec.startsWith("jar:");
            if (isJar) {
                // Strip off "jar:"
                spec = spec.substring(4);
                if (spec.indexOf('/') >= 0) {
                    Log.log("Only a leaf filename may be used with a \"jar:\" entry in the scan spec, got \"" + spec
                            + "\" -- ignoring");
                } else {
                    if (spec.isEmpty()) {
                        if (blacklisted) {
                            // Specifying "-jar:" blacklists all jars for scanning
                            scanJars = false;
                        } else {
                            // Specifying "jar:" causes only jarfiles to be scanned, while whitelisting all jarfiles
                            scanNonJars = false;
                        }
                    } else {
                        if (blacklisted) {
                            if (spec.contains("*")) {
                                blacklistedJarPatterns.add(specToPattern(spec));
                            } else {
                                blacklistedJars.add(spec);
                            }
                        } else {
                            if (spec.contains("*")) {
                                whitelistedJarPatterns.add(specToPattern(spec));
                            } else {
                                whitelistedJars.add(spec);
                            }
                        }
                    }
                }
            } else {
                // Strip final ".class", in case classfiles are listed directly
                if (spec.endsWith(".class")) {
                    spec = spec.substring(0, spec.length() - 6);
                }
                // Support using either '.' or '/' as package separator
                spec = spec.replace('/', '.');
                // Strip initial '.', in case scan spec started with '/'
                if (spec.startsWith(".")) {
                    spec = spec.substring(1);
                }
                // See if a class name was specified, rather than a package name. Relies on the Java convention
                // that package names should be lower case and class names should be upper case.
                boolean isClassName = false;
                final int lastDotIdx = spec.lastIndexOf('.');
                if (lastDotIdx < spec.length() - 1) {
                    isClassName = Character.isUpperCase(spec.charAt(lastDotIdx + 1));
                }
                if (isClassName) {
                    // This is a class name
                    if (blacklisted) {
                        blacklistedClassNames.add(spec);
                    } else {
                        whitelistedClassNames.add(spec);
                        whitelistedClassPathPrefixes.add(spec.substring(0, lastDotIdx + 1).replace('.', '/'));
                    }
                } else {
                    // This is a package name: convert into a prefix by adding '.', and also convert to path prefix
                    spec = spec + ".";
                    if (blacklisted) {
                        uniqueBlacklistedPackagePrefixes.add(spec);
                        uniqueBlacklistedPathPrefixes.add(spec.replace('.', '/'));
                    } else {
                        uniqueWhitelistedPackagePrefixes.add(spec);
                        uniqueWhitelistedPathPrefixes.add(spec.replace('.', '/'));
                    }
                }
            }
        }
        if (uniqueBlacklistedPathPrefixes.contains("/")) {
            Log.log("Ignoring blacklist of root package, it would prevent all scanning");
            uniqueBlacklistedPathPrefixes.remove("/");
        }
        uniqueWhitelistedPathPrefixes.removeAll(uniqueBlacklistedPathPrefixes);
        whitelistedJars.removeAll(blacklistedJars);
        if (!(whitelistedJars.isEmpty() && whitelistedJarPatterns.isEmpty())) {
            // Specifying "jar:somejar.jar" causes only the specified jarfile to be scanned
            scanNonJars = false;
        }
        if (!scanJars && !scanNonJars) {
            // Can't disable scanning of everything, so if specified, arbitrarily pick one to re-enable.
            Log.log("Scanning of jars and non-jars are both disabled -- re-enabling scanning of non-jars");
            scanNonJars = true;
        }
        if (uniqueWhitelistedPathPrefixes.isEmpty() || uniqueWhitelistedPathPrefixes.contains("/")) {
            // Scan all packages
            whitelistedPathPrefixes.add("");
            whitelistedPackagePrefixes.add("");
        } else {
            whitelistedPathPrefixes.addAll(uniqueWhitelistedPathPrefixes);
            whitelistedPackagePrefixes.addAll(uniqueWhitelistedPackagePrefixes);
        }
        blacklistedPathPrefixes.addAll(uniqueBlacklistedPathPrefixes);
        blacklistedPackagePrefixes.addAll(uniqueBlacklistedPackagePrefixes);
        whitelistedClassNames.removeAll(blacklistedClassNames);
        this.scanJars = scanJars;
        this.scanNonJars = scanNonJars;

        if (FastClasspathScanner.verbose) {
            Log.log("Whitelisted relative path prefixes:  " + whitelistedPathPrefixes);
            if (!blacklistedPathPrefixes.isEmpty()) {
                Log.log("Blacklisted relative path prefixes:  " + blacklistedPathPrefixes);
            }
            if (!whitelistedJars.isEmpty()) {
                Log.log("Whitelisted jars:  " + whitelistedJars);
            }
            if (!whitelistedJarPatterns.isEmpty()) {
                Log.log("Whitelisted jars with glob wildcards:  " + whitelistedJarPatterns);
            }
            if (!blacklistedJars.isEmpty()) {
                Log.log("Blacklisted jars:  " + blacklistedJars);
            }
            if (!blacklistedJarPatterns.isEmpty()) {
                Log.log("Whitelisted jars with glob wildcards:  " + blacklistedJarPatterns);
            }
            if (!scanJars) {
                Log.log("Scanning of jarfiles is disabled");
            }
            if (!scanNonJars) {
                Log.log("Scanning of directories (i.e. non-jarfiles) is disabled");
            }
        }
    }

    /**
     * Convert a spec with a '*' glob character into a regular expression. Replaces "." with "\." and "*" with ".*",
     * then compiles a regulare expression.
     */
    private static Pattern specToPattern(final String spec) {
        return Pattern.compile("^" + spec.replace(".", "\\.").replace("*", ".*") + "$");
    }

    /** Returns true if a class' package is not blacklisted or is explicitly whitelisted. */
    private static boolean packageIsNotBlacklisted(final String str, final ArrayList<String> whitelist,
            final ArrayList<String> blacklist) {
        boolean isWhitelisted = false;
        for (final String whitelistPrefix : whitelist) {
            if (str.startsWith(whitelistPrefix)) {
                isWhitelisted = true;
                break;
            }
        }
        boolean isBlacklisted = false;
        for (final String blacklistPrefix : blacklist) {
            if (str.startsWith(blacklistPrefix)) {
                isBlacklisted = true;
                break;
            }
        }
        return !isBlacklisted || isWhitelisted;
    }

    /**
     * Returns true if the given class name is not blacklisted or is explicitly whitelisted.
     * 
     * This does not apply the normal logic of "if (whitelisted && !blacklisted)", but instead "if (whitelisted ||
     * !blacklisted)", because it is used for links that may come from outside a whitelisted path, but still may
     * need to be used as a query criterion (e.g. a class name to match against, or a field type to look up
     * containing classes from).
     */
    public boolean classIsNotBlacklisted(final String className) {
        return !blacklistedClassNames.contains(className) || whitelistedClassNames.contains(className)
                || packageIsNotBlacklisted(className, whitelistedPackagePrefixes, blacklistedPackagePrefixes);
    }

    /** Returns true if a class' package is whitelisted and not blacklisted. */
    private static boolean packageIsWhitelisted(final String str, final ArrayList<String> whitelist,
            final ArrayList<String> blacklist) {
        boolean isWhitelisted = false;
        for (final String whitelistPrefix : whitelist) {
            if (str.startsWith(whitelistPrefix)) {
                isWhitelisted = true;
                break;
            }
        }
        boolean isBlacklisted = false;
        for (final String blacklistPrefix : blacklist) {
            if (str.startsWith(blacklistPrefix)) {
                isBlacklisted = true;
                break;
            }
        }
        return isWhitelisted && !isBlacklisted;
    }

    /**
     * Returns true if the given class name is whitelisted and not blacklisted, or if it is in a whitelisted,
     * non-blacklisted package.
     * 
     * This does not apply the normal logic of "if (whitelisted && !blacklisted)", but instead "if (whitelisted ||
     * !blacklisted)", because it is used for links that may come from outside a whitelisted path, but still may
     * need to be used as a query criterion (e.g. a class name to match against, or a field type to look up
     * containing classes from).
     */
    public boolean classIsWhitelisted(final String className) {
        return (whitelistedClassNames.contains(className)
                || packageIsWhitelisted(className, whitelistedPackagePrefixes, blacklistedPackagePrefixes))
                && !blacklistedClassNames.contains(className);
    }

    /** Returns true if the given path is within a whitelisted, non-blacklisted package. */
    public boolean pathIsWhitelisted(final String relativePath) {
        return packageIsWhitelisted(relativePath, whitelistedPathPrefixes, blacklistedPathPrefixes);
    }

    /**
     * Whether a path is a descendant of a blacklisted path, or an ancestor or descendant of a whitelisted path.
     * 
     * TODO: need an enum value for when within the path of a whitelisted class that is not itself in a whitelisted
     * package (so that whitelisted classes get scanned, even if their packages are not scanned).
     */
    public enum ScanSpecPathMatch {
        WITHIN_BLACKLISTED_PATH, WITHIN_WHITELISTED_PATH, ANCESTOR_OF_WHITELISTED_PATH, //
        AT_WHITELISTED_CLASS_PACKAGE, NOT_WITHIN_WHITELISTED_PATH;
    }

    /**
     * See whether the given path is a descendant of a blacklisted path, or an ancestor or descendant of a
     * whitelisted path. The path should end in "/".
     */
    public ScanSpecPathMatch pathWhitelistMatchStatus(final String path) {
        for (final String blacklistedPath : blacklistedPathPrefixes) {
            if (path.startsWith(blacklistedPath)) {
                return ScanSpecPathMatch.WITHIN_BLACKLISTED_PATH;
            }
        }
        for (final String whitelistedPath : whitelistedPathPrefixes) {
            if (path.startsWith(whitelistedPath)) {
                return ScanSpecPathMatch.WITHIN_WHITELISTED_PATH;
            } else if (whitelistedPath.startsWith(path) || "/".equals(path)) {
                return ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH;
            }
        }
        for (final String whitelistedClassPathPrefix : whitelistedClassPathPrefixes) {
            if (path.equals(whitelistedClassPathPrefix)) {
                return ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE;
            } else if (whitelistedClassPathPrefix.startsWith(path) || "/".equals(path)) {
                return ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH;
            }
        }
        return ScanSpecPathMatch.NOT_WITHIN_WHITELISTED_PATH;
    }

    /** Test if a list of jar names contains the requested name, allowing for globs. */
    private static boolean containsJarName(final HashSet<String> jarNames, final ArrayList<Pattern> jarNamePatterns,
            final String jarName) {
        if (jarNames.contains(jarName)) {
            return true;
        }
        for (final Pattern jarNamePattern : jarNamePatterns) {
            if (jarNamePattern.matcher(jarName).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Returns true if a jarfile is whitelisted and not blacklisted. */
    public boolean jarIsWhitelisted(final String jarName) {
        return ((whitelistedJars.isEmpty() && whitelistedJarPatterns.isEmpty())
                || containsJarName(whitelistedJars, whitelistedJarPatterns, jarName))
                && !containsJarName(blacklistedJars, blacklistedJarPatterns, jarName);
    }
}
