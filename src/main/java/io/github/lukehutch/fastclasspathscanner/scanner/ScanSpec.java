package io.github.lukehutch.fastclasspathscanner.scanner;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.utils.Log;

import java.util.ArrayList;
import java.util.HashSet;

public class ScanSpec {
    /** Whitelisted package prefixes with "." appended, or the empty list if all packages are whitelisted. */
    private final ArrayList<String> whitelistedPackagePrefixes = new ArrayList<>();

    /** Blacklisted package prefixes with "." appended. */
    private final ArrayList<String> blacklistedPackagePrefixes = new ArrayList<>();

    /** Whitelisted class names, or the empty list if none. */
    private final HashSet<String> whitelistedClassNames = new HashSet<>();

    /** Blacklisted class names. */
    private final HashSet<String> blacklistedClassNames = new HashSet<>();

    /** Whitelisted package paths with "/" appended, or the empty list if all packages are whitelisted. */
    private final ArrayList<String> whitelistedPathPrefixes = new ArrayList<>();

    /** Blacklisted package paths with "/" appended. */
    private final ArrayList<String> blacklistedPathPrefixes = new ArrayList<>();

    /** Whitelisted jarfile names. (Leaf filename only.) */
    private final ArrayList<String> whitelistedJars = new ArrayList<>();

    /** Blacklisted jarfile names. (Leaf filename only.) */
    private final ArrayList<String> blacklistedJars = new ArrayList<>();

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
                    Log.log("Only a leaf filename may be used with a \"jar:\" entry in the scan spec, got \""
                            + spec + "\" -- ignoring");
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
                            blacklistedJars.add(spec);
                        } else {
                            whitelistedJars.add(spec);
                        }
                    }
                }
            } else {
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
                if (lastDotIdx > 0 && lastDotIdx < spec.length() - 1) {
                    isClassName = Character.isUpperCase(spec.charAt(lastDotIdx + 1));
                }
                if (isClassName) {
                    // This is a class name
                    if (blacklisted) {
                        blacklistedClassNames.add(spec);
                    } else {
                        whitelistedClassNames.add(spec);
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
        if (!whitelistedJars.isEmpty()) {
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
            if (!blacklistedJars.isEmpty()) {
                Log.log("Blacklisted jars:  " + blacklistedJars);
            }
            if (!scanJars) {
                Log.log("Scanning of jarfiles is disabled");
            }
            if (!scanNonJars) {
                Log.log("Scanning of directories (i.e. non-jarfiles) is disabled");
            }
        }
    }

    /** Check against a whitelist and blacklist. */
    private static boolean isWhitelisted(final String str, final ArrayList<String> whitelist,
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
     */
    public boolean classIsWhitelisted(final String className) {
        return whitelistedClassNames.contains(className) && !blacklistedClassNames.contains(className)
                || isWhitelisted(className, whitelistedPackagePrefixes, blacklistedPackagePrefixes);
    }

    /** Returns true if the given path is within a whitelisted, non-blacklisted package. */
    public boolean pathIsWhitelisted(final String relativePath) {
        return isWhitelisted(relativePath, whitelistedPathPrefixes, blacklistedPathPrefixes);
    }

    /**
     * Whether a path is a descendant of a blacklisted path, or an ancestor or descendant of a whitelisted path.
     */
    public enum ScanSpecPathMatch {
        WITHIN_BLACKLISTED_PATH, WITHIN_WHITELISTED_PATH, ANCESTOR_OF_WHITELISTED_PATH, NOT_WITHIN_WHITELISTED_PATH;
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
            } else if (whitelistedPath.startsWith(path) || path.equals("/")) {
                return ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH;
            }
        }
        return ScanSpecPathMatch.NOT_WITHIN_WHITELISTED_PATH;
    }

    /** Returns true if a jarfile is whitelisted and not blacklisted. */
    public boolean jarIsWhitelisted(final String jarName) {
        return (whitelistedJars.isEmpty() || whitelistedJars.contains(jarName))
                && !blacklistedJars.contains(jarName);
    }
}
