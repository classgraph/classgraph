package io.github.lukehutch.fastclasspathscanner.scanner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.regex.Pattern;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.utils.Log;

public class ScanSpec {
    /** Whitelisted package paths with "/" appended, or the empty list if all packages are whitelisted. */
    private final ArrayList<String> whitelistedPathPrefixes = new ArrayList<>();

    /** Blacklisted package paths with "/" appended. */
    private final ArrayList<String> blacklistedPathPrefixes = new ArrayList<>();

    /** Blacklisted package names with "." appended. */
    private final ArrayList<String> blacklistedPackagePrefixes = new ArrayList<>();

    /** Whitelisted class names, or the empty list if none. */
    private final HashSet<String> specificallyWhitelistedClassRelativePaths = new HashSet<>();

    /** Path prefixes of whitelisted classes, or the empty list if none. */
    private final HashSet<String> specificallyWhitelistedClassParentRelativePaths = new HashSet<>();

    /** Blacklisted class relative paths. */
    private final HashSet<String> specificallyBlacklistedClassRelativePaths = new HashSet<>();

    /** Blacklisted class names. */
    private final HashSet<String> specificallyBlacklistedClassNames = new HashSet<>();

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
            blacklistedPathPrefixes.add(pkg.replace('.', '/') + "/");
        }

        final HashSet<String> uniqueWhitelistedPathPrefixes = new HashSet<>();
        final HashSet<String> uniqueBlacklistedPathPrefixes = new HashSet<>();
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
                // Convert classname format to relative path
                final String specPath = spec.replace('.', '/');
                // See if a class name was specified, rather than a package name. Relies on the Java convention
                // that package names should be lower case and class names should be upper case.
                boolean isClassName = false;
                final int lastSlashIdx = specPath.lastIndexOf('/');
                if (lastSlashIdx < specPath.length() - 1) {
                    isClassName = Character.isUpperCase(specPath.charAt(lastSlashIdx + 1));
                }
                if (isClassName) {
                    // Convert class name to classfile filename
                    if (blacklisted) {
                        specificallyBlacklistedClassNames.add(spec);
                        specificallyBlacklistedClassRelativePaths.add(specPath + ".class");
                    } else {
                        specificallyWhitelistedClassRelativePaths.add(specPath + ".class");
                    }
                } else {
                    // This is a package name: convert into a prefix by adding '.', and also convert to path prefix
                    if (blacklisted) {
                        uniqueBlacklistedPathPrefixes.add(specPath + "/");
                    } else {
                        uniqueWhitelistedPathPrefixes.add(specPath + "/");
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
        } else {
            whitelistedPathPrefixes.addAll(uniqueWhitelistedPathPrefixes);
        }
        blacklistedPathPrefixes.addAll(uniqueBlacklistedPathPrefixes);
        // Convert blacklisted path prefixes into package prefixes
        for (final String prefix : blacklistedPathPrefixes) {
            blacklistedPackagePrefixes.add(prefix.replace('/', '.'));
        }

        specificallyWhitelistedClassRelativePaths.removeAll(specificallyBlacklistedClassRelativePaths);
        for (final String whitelistedClass : specificallyWhitelistedClassRelativePaths) {
            final int lastSlashIdx = whitelistedClass.lastIndexOf('/');
            specificallyWhitelistedClassParentRelativePaths.add(whitelistedClass.substring(0, lastSlashIdx + 1));
        }

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
            if (!specificallyWhitelistedClassRelativePaths.isEmpty()) {
                Log.log("Specifically-whitelisted classfiles: " + specificallyWhitelistedClassRelativePaths);
            }
            if (!specificallyBlacklistedClassRelativePaths.isEmpty()) {
                Log.log("Specifically-blacklisted classfiles: " + specificallyBlacklistedClassRelativePaths);
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

    /** Whether a path is a descendant of a blacklisted path, or an ancestor or descendant of a whitelisted path. */
    public enum ScanSpecPathMatch {
        WITHIN_BLACKLISTED_PATH, WITHIN_WHITELISTED_PATH, ANCESTOR_OF_WHITELISTED_PATH, //
        AT_WHITELISTED_CLASS_PACKAGE, NOT_WITHIN_WHITELISTED_PATH, WHITELISTED_FILE, NON_WHITELISTED_FILE;
    }

    /**
     * Returns true if the given directory path is a descendant of a blacklisted path, or an ancestor or descendant
     * of a whitelisted path. The path should end in "/".
     */
    public ScanSpecPathMatch pathWhitelistMatchStatus(final String relativePath) {
        for (final String blacklistedPath : blacklistedPathPrefixes) {
            if (relativePath.startsWith(blacklistedPath)) {
                // The directory or its ancestor is blacklisted.
                return ScanSpecPathMatch.WITHIN_BLACKLISTED_PATH;
            }
        }
        for (final String whitelistedPath : whitelistedPathPrefixes) {
            if (relativePath.startsWith(whitelistedPath)) {
                // The directory is a whitelisted path or its ancestor is a whitelisted path.
                return ScanSpecPathMatch.WITHIN_WHITELISTED_PATH;
            } else if (whitelistedPath.startsWith(relativePath) || "/".equals(relativePath)) {
                // The directory the ancestor is a whitelisted path (so need to keep scanning deeper into hierarchy)
                return ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH;
            }
        }
        if (specificallyWhitelistedClassParentRelativePaths.contains(relativePath)
                && !specificallyBlacklistedClassRelativePaths.contains(relativePath)) {
            return ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE;
        }
        for (final String whitelistedClassPathPrefix : specificallyWhitelistedClassParentRelativePaths) {
            if (whitelistedClassPathPrefix.startsWith(relativePath) || "/".equals(relativePath)) {
                // The directory is an ancestor of a non-whitelisted package containing a whitelisted class 
                return ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH;
            }
        }
        return ScanSpecPathMatch.NOT_WITHIN_WHITELISTED_PATH;
    }

    /**
     * Returns true if the given relative path (for a classfile name, including ".class") matches a
     * specifically-whitelisted (and non-blacklisted) classfile's relative path.
     */
    public boolean isSpecificallyWhitelistedClass(final String relativePath) {
        return (specificallyWhitelistedClassRelativePaths.contains(relativePath)
                && !specificallyBlacklistedClassRelativePaths.contains(relativePath));
    }

    /** Returns true if the class is not specifically blacklisted, and is not within a blacklisted package. */
    public boolean classIsNotBlacklisted(final String className) {
        if (specificallyBlacklistedClassNames.contains(className)) {
            return false;
        }
        for (final String blacklistedPackagePrefix : blacklistedPackagePrefixes) {
            if (className.startsWith(blacklistedPackagePrefix)) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------------------------------------------

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
