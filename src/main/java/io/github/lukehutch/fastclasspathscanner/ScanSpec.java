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
package io.github.lukehutch.fastclasspathscanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner.ClasspathElementFilter;
import io.github.lukehutch.fastclasspathscanner.classloaderhandler.ClassLoaderHandlerRegistry;
import io.github.lukehutch.fastclasspathscanner.classloaderhandler.ClassLoaderHandlerRegistry.ClassLoaderHandlerRegistryEntry;
import io.github.lukehutch.fastclasspathscanner.utils.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.utils.JarUtils;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;

/**
 * Parses the scanning specification that was passed to the FastClasspathScanner constructor, and finds all
 * ClassLoaders. Also defines core MatchProcessor matching logic.
 */
public class ScanSpec {
    /**
     * Whitelisted package paths with "/" appended, or the empty list if all packages are whitelisted. These
     * packages and any subpackages will be scanned.
     */
    public List<String> whitelistedPathPrefixes = new ArrayList<>();

    /**
     * Whitelisted package paths with "/" appended, or the empty list if all packages are whitelisted. These
     * packages will be scanned, but subpackages will not be scanned unless they are whitelisted.
     */
    public List<String> whitelistedPathsNonRecursive = new ArrayList<>();

    /**
     * Blacklisted package paths with "/" appended. Neither these packages nor any subpackages will be scanned.
     */
    public transient List<String> blacklistedPathPrefixes = new ArrayList<>();

    /**
     * Blacklisted package names with "." appended. Neither these packages nor any subpackages will be scanned.
     */
    public List<String> blacklistedPackagePrefixes = new ArrayList<>();

    /** Whitelisted class names, or the empty list if none. */
    public Set<String> specificallyWhitelistedClassRelativePaths = new HashSet<>();

    /** Path prefixes of whitelisted classes, or the empty list if none. */
    public transient Set<String> specificallyWhitelistedClassParentRelativePaths = new HashSet<>();

    /** Blacklisted class relative paths. */
    public Set<String> specificallyBlacklistedClassRelativePaths = new HashSet<>();

    /** Blacklisted class names. */
    public Set<String> specificallyBlacklistedClassNames = new HashSet<>();

    /** Whitelisted jarfile names. (Leaf filename only.) */
    public Set<String> whitelistedJars = new HashSet<>();

    /** Complete paths of whitelisted jars to scan in the JDK/JRE "lib/" or "ext/" directory. */
    public Set<String> whitelistedLibOrExtJarPaths = new HashSet<>();

    /** Complete paths of blacklisted in the JDK/JRE "lib/" or "ext/" directory. */
    public Set<String> blacklistedLibOrExtJarPaths = new HashSet<>();

    /** Blacklisted jarfile names. (Leaf filename only.) */
    public Set<String> blacklistedJars = new HashSet<>();

    /** Names of whitelisted modules. */
    public Set<String> whitelistedModules = new HashSet<>();

    /** Names of blacklisted modules. */
    public Set<String> blacklistedModules = new HashSet<>();

    /**
     * Whitelisted jarfile names containing a glob('*') character, converted to a regexp. (Leaf filename only.)
     */
    public transient List<Pattern> whitelistedJarPatterns = new ArrayList<>();

    /**
     * Blacklisted jarfile names containing a glob('*') character, converted to a regexp. (Leaf filename only.)
     */
    public transient List<Pattern> blacklistedJarPatterns = new ArrayList<>();

    // -------------------------------------------------------------------------------------------------------------

    /** If true, scan jarfiles. */
    public boolean scanJars = true;

    /** If true, scan directories. */
    public boolean scanDirs = true;

    /** If true, scan modules. */
    public boolean scanModules = true;

    /** If true, scan classfile bytecodes. */
    public boolean scanClassfiles = true;

    /**
     * If true, enables the saving of field info during the scan. This information can be obtained using
     * {@link ClassInfo#getFieldInfo()}. By default, field info is not scanned, for efficiency.
     */
    public boolean enableFieldInfo;

    /**
     * If true, enables the saving of method info during the scan. This information can be obtained using
     * {@link ClassInfo#getMethodInfo()}. By default, method info is not scanned, for efficiency.
     */
    public boolean enableMethodInfo;

    /**
     * If true, enables the saving of annotation info (for class, field, method or method parameter annotations)
     * during the scan. This information can be obtained using {@link ClassInfo#getAnnotationInfo()} etc. By
     * default, annotation info is not scanned, for efficiency.
     */
    public boolean enableAnnotationInfo;

    /** Enable the storing of constant initializer values for static final fields in ClassInfo objects. */
    public boolean enableStaticFinalFieldConstValues;

    /**
     * If true, allow external classes (classes outside of whitelisted packages) to be returned in the ScanResult,
     * if they are directly referred to by a whitelisted class, as a superclass, implemented interface or
     * annotation. Disabled by default.
     */
    public boolean enableExternalClasses;

    /**
     * True if JRE system jarfiles (rt.jar etc.) should not be scanned. By default, these are not scanned. This can
     * be overridden by including "!!" in the scan spec. Disabling this blacklisting will increase the time or
     * memory required to scan the classpath.
     */
    public boolean blacklistSystemJarsOrModules = true;

    /**
     * If true, ignore field visibility (affects finding classes with fields of a given type, and matching of static
     * final fields with constant initializers). If false, fields must be public to be indexed/matched.
     */
    public boolean ignoreFieldVisibility = false;

    /**
     * If true, ignore method visibility (affects finding methods with annotations of a given type). If false,
     * methods must be public to be indexed/matched.
     */
    public boolean ignoreMethodVisibility = false;

    /**
     * If true, don't scan runtime-invisible annotations (only scan annotations with RetentionPolicy.RUNTIME).
     */
    public boolean disableRuntimeInvisibleAnnotations = false;

    /**
     * Whether to disable recursive scanning (enabled by default). If set to false, only toplevel entries within
     * each whitelisted package will be scanned, i.e. sub-packages of whitelisted packages will not be scanned. If
     * no whitelisted packages were provided to the FastClasspathScanner constructor, then only the toplevel
     * directory within each classpath element will be scanned.
     */
    public boolean disableRecursiveScanning = false;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * If non-null, specified manually-added classloaders that should be searched after the context classloader(s).
     */
    public transient List<ClassLoader> addedClassLoaders;

    /**
     * If non-null, all ClassLoaders have been overriden. In particular, this causes FastClasspathScanner to ignore
     * the java.class.path system property.
     */
    public transient List<ClassLoader> overrideClassLoaders;

    /** If non-null, specifies a classpath to override the default one. */
    public String overrideClasspath;

    /** If non-null, a list of filter operations to apply to classpath elements. */
    public transient List<ClasspathElementFilter> classpathElementFilters;

    /** Manually-registered ClassLoaderHandlers. */
    public transient final ArrayList<ClassLoaderHandlerRegistryEntry> extraClassLoaderHandlers = new ArrayList<>();

    /**
     * If true, classes loaded with Class.forName() are initialized before passing class references to
     * MatchProcessors. If false (the default), matched classes are loaded but not initialized before passing class
     * references to MatchProcessors (meaning classes are instead initialized lazily on first usage of the class).
     */
    public transient boolean initializeLoadedClasses = false;

    /**
     * If true, nested jarfiles (jarfiles within jarfiles) that are extracted during scanning are removed from their
     * temporary directory (e.g. /tmp/FastClasspathScanner-8JX2u4w) after the scan has completed. If false,
     * temporary files are removed by the {@link ScanResult} finalizer, or on JVM exit.
     */
    public transient boolean removeTemporaryFilesAfterScan = false;

    /** If true, do not fetch paths from parent classloaders. */
    public transient boolean ignoreParentClassLoaders = false;

    /**
     * If true, manually strip the self extracting executable header from zipfiles (i.e. anything before the magic
     * marker "PK", e.g. a Bash script added by Spring-Boot). Slightly increases scanning time, since zipfiles have
     * to be opened twice (once as a byte stream, to check if there is an SFX header, then once as a ZipFile, for
     * decompression).
     * 
     * Should only be needed in rare cases, where you are dealing with jarfiles with prepended (ZipSFX) headers,
     * where your JVM does not already automatically skip forward to the first "PK" marker (Oracle JVM on Linux does
     * this automatically).
     */
    public transient boolean stripSFXHeader = false;

    /**
     * If this method is called, a new {@link java.net.URLClassLoader} is created for all classes found on the
     * classpath that match whitelist criteria. This may be needed if you get a ClassNotFoundException,
     * UnsatisfiedLinkError, NoClassDefFoundError, etc., due to trying to load classes that depend upon each other
     * but that are loaded by different ClassLoaders in the classpath.
     */
    public transient boolean createClassLoaderForMatchingClasses = false;

    // -------------------------------------------------------------------------------------------------------------

    ScanSpec() {
    }

    /**
     * Parses the scanning specification that was passed to the FastClasspathScanner constructor, and finds all
     * ClassLoaders.
     * 
     * @param scanSpecFields
     *            The scan spec fields, passed into the FastClasspathScanner constructor.
     * @param log
     *            The log.
     */
    public ScanSpec(final String[] scanSpecFields, final LogNode log) {
        final HashSet<String> uniqueWhitelistedPathPrefixes = new HashSet<>();
        final HashSet<String> uniqueBlacklistedPathPrefixes = new HashSet<>();
        for (final String scanSpecEntry : scanSpecFields) {
            String spec = scanSpecEntry;
            if ("!".equals(scanSpecEntry)) {
                if (log != null) {
                    log.log("Ignoring deprecated scan spec option \"!\"");
                }
            } else if ("!!".equals(scanSpecEntry)) {
                blacklistSystemJarsOrModules = false;
            } else {
                final boolean blacklisted = spec.startsWith("-");
                if (blacklisted) {
                    // Strip off "-"
                    spec = spec.substring(1);
                }
                if (spec.startsWith("jar:")) {
                    // Strip off "jar:"
                    spec = spec.substring(4);
                    if (spec.indexOf('/') >= 0) {
                        if (log != null) {
                            log.log("Only a leaf filename may be used with a \"jar:\" entry in the "
                                    + "scan spec, got \"" + spec + "\" -- ignoring");
                        }
                    } else {
                        if (spec.isEmpty()) {
                            if (blacklisted) {
                                // "-jar:" disables jar scanning
                                scanJars = false;
                            } else {
                                // "jar:" with no jar name has no effect
                                if (log != null) {
                                    log.log("Ignoring scan spec entry with no effect: \"" + scanSpecEntry + "\"");
                                }
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
                } else if (spec.startsWith("lib:") || spec.startsWith("ext:")) {
                    final boolean isLib = spec.startsWith("lib:");
                    // Strip off "lib:" / "ext:"
                    spec = spec.substring(4);
                    if (spec.indexOf('/') >= 0) {
                        if (log != null) {
                            log.log("Only a leaf filename may be used with a \"lib:\" or \"ext:\" entry in the "
                                    + "scan spec, ignoring: \"" + scanSpecEntry + "\"");
                        }
                    } else {
                        if (spec.isEmpty()) {
                            // Blacklist or whitelist all "lib/" or "ext/" jars
                            (blacklisted ? blacklistedLibOrExtJarPaths : whitelistedLibOrExtJarPaths)
                                    .addAll(isLib ? JarUtils.getJreLibJars() : JarUtils.getJreExtJars());
                        } else {
                            if (spec.contains("*")) {
                                // Support wildcards with "lib:" and "ext:"
                                final Pattern pattern = specToPattern(spec);
                                boolean found = false;
                                for (final String path : (isLib ? JarUtils.getJreLibJars()
                                        : JarUtils.getJreExtJars())) {
                                    final int slashIdx = path.lastIndexOf('/');
                                    final String pathLeaf = path.substring(slashIdx + 1);
                                    if (pattern.matcher(pathLeaf).matches()) {
                                        (blacklisted ? blacklistedLibOrExtJarPaths : whitelistedLibOrExtJarPaths)
                                                .add(path);
                                        found = true;
                                    }
                                }
                                if (!found && log != null) {
                                    log.log("Could not find " + (isLib ? "lib" : "ext") + " jar matching wildcard: "
                                            + scanSpecEntry);
                                }
                            } else {
                                // No wildcards, just blacklist or whitelist the named jar, if present
                                boolean found = false;
                                for (final String path : (isLib ? JarUtils.getJreLibJars()
                                        : JarUtils.getJreExtJars())) {
                                    final int slashIdx = path.lastIndexOf('/');
                                    final String pathLeaf = path.substring(slashIdx + 1);
                                    if (spec.equals(pathLeaf)) {
                                        (blacklisted ? blacklistedLibOrExtJarPaths : whitelistedLibOrExtJarPaths)
                                                .add(path);
                                        found = true;
                                        break;
                                    }
                                }
                                if (!found && log != null) {
                                    log.log("Could not find " + (isLib ? "lib" : "ext") + " jar: " + scanSpecEntry);
                                }
                            }
                        }
                    }
                } else if (spec.startsWith("dir:")) {
                    // Strip off "dir:"
                    spec = spec.substring(4);
                    if (!spec.isEmpty()) {
                        if (log != null) {
                            log.log("Ignoring extra text after \"dir:\" in scan spec entry: \"" + scanSpecEntry
                                    + "\"");
                        }
                    }
                    if (blacklisted) {
                        // "-dir:" disables directory scanning
                        scanDirs = false;
                    } else {
                        // "dir:" with no dir name has no effect
                        if (log != null) {
                            log.log("Ignoring scan spec entry with no effect: \"" + scanSpecEntry + "\"");
                        }
                    }
                } else if (spec.startsWith("mod:")) {
                    // Strip off "mod:"
                    spec = spec.substring(4);
                    if (spec.indexOf('/') >= 0) {
                        if (log != null) {
                            log.log("module names cannot contain '/' -- ignoring: \"" + scanSpecEntry + "\"");
                        }
                    } else {
                        if (spec.contains("*")) {
                            if (log != null) {
                                log.log("Module name cannot contain wildcards: \"" + scanSpecEntry + "\"");
                            }
                        } else {
                            (blacklisted ? blacklistedModules : whitelistedModules).add(spec);
                        }
                    }
                } else {
                    // Convert classname format to relative path
                    final String specPath = spec.replace('.', '/');
                    // Strip off initial '/' if present
                    if (spec.startsWith("/")) {
                        spec = spec.substring(1);
                    }
                    // See if a class name was specified, rather than a package name. Relies on the Java convention
                    // that package names should be lower case and class names should be upper case.
                    boolean isClassName = false;
                    final int lastSlashIdx = specPath.lastIndexOf('/');
                    if (lastSlashIdx < specPath.length() - 1) {
                        isClassName = Character.isUpperCase(specPath.charAt(lastSlashIdx + 1))
                                && !specPath.substring(lastSlashIdx + 1).equals("META-INF");
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
                        // This is a package name: convert into a prefix by adding '.', and also convert to path
                        // prefix
                        if (blacklisted) {
                            uniqueBlacklistedPathPrefixes.add(specPath + "/");
                        } else {
                            uniqueWhitelistedPathPrefixes.add(specPath + "/");
                        }
                    }
                }
            }
        }

        if (blacklistSystemJarsOrModules) {
            // Blacklist Java paths by default
            for (final String prefix : JarUtils.SYSTEM_PACKAGE_PATH_PREFIXES) {
                uniqueBlacklistedPathPrefixes.add(prefix);
            }
        }
        blacklistedPathPrefixes.addAll(uniqueBlacklistedPathPrefixes);

        if (uniqueBlacklistedPathPrefixes.contains("/")) {
            if (log != null) {
                log.log("Ignoring blacklist of root package, it would prevent all scanning");
            }
            uniqueBlacklistedPathPrefixes.remove("/");
        }

        // Convert blacklisted path prefixes into package prefixes
        for (final String prefix : blacklistedPathPrefixes) {
            blacklistedPackagePrefixes.add(prefix.replace('/', '.'));
        }

        specificallyWhitelistedClassRelativePaths.removeAll(specificallyBlacklistedClassRelativePaths);
        for (final String whitelistedClass : specificallyWhitelistedClassRelativePaths) {
            final int lastSlashIdx = whitelistedClass.lastIndexOf('/');
            specificallyWhitelistedClassParentRelativePaths.add(whitelistedClass.substring(0, lastSlashIdx + 1));
        }

        uniqueWhitelistedPathPrefixes.removeAll(uniqueBlacklistedPathPrefixes);
        whitelistedPathPrefixes.addAll(uniqueWhitelistedPathPrefixes);
        if (whitelistedPathPrefixes.isEmpty() && whitelistedPathsNonRecursive.isEmpty()
                && specificallyWhitelistedClassRelativePaths.isEmpty()) {
            // If no whitelisted package names or class names were given, scan all packages. Having a whitelisted
            // class name but no whitelisted package name should not trigger the scanning of all packages (Issue
            // #78.)
            whitelistedPathPrefixes.add("/");
        }

        // Allow the root path to be matched using either "" or "/"
        if (whitelistedPathPrefixes.contains("/")) {
            whitelistedPathPrefixes.add("");
        }

        whitelistedJars.removeAll(blacklistedJars);
        if (!scanJars && !scanDirs) {
            // Can't disable scanning of everything, so if specified, arbitrarily pick one to re-enable.
            if (log != null) {
                log.log("Scanning of jars and dirs are both disabled -- re-enabling scanning of dirs");
            }
            scanDirs = true;
        }

        // Sort the whitelistedPathPrefixes and whitelistedPathsNonRecursive to ensure correct evaluation (see Issue
        // #167).
        Collections.sort(whitelistedPathPrefixes);
        Collections.sort(whitelistedPathsNonRecursive);
        Collections.sort(blacklistedPathPrefixes);
        Collections.sort(blacklistedPackagePrefixes);

        if (log != null) {
            if (!blacklistedPathPrefixes.isEmpty()) {
                log.log("Blacklisted relative path prefixes:  " + blacklistedPathPrefixes);
            }
            if (!blacklistedJars.isEmpty()) {
                log.log("Blacklisted jars:  " + blacklistedJars);
            }
            if (!blacklistedJarPatterns.isEmpty()) {
                log.log("Whitelisted jars with glob wildcards:  " + blacklistedJarPatterns);
            }
            if (!specificallyBlacklistedClassRelativePaths.isEmpty()) {
                log.log("Specifically-blacklisted classfiles: " + specificallyBlacklistedClassRelativePaths);
            }

            if (!whitelistedPathPrefixes.isEmpty()) {
                log.log("Whitelisted relative path prefixes:  " + whitelistedPathPrefixes);
            }
            if (!whitelistedPathsNonRecursive.isEmpty()) {
                log.log("Whitelisted paths (non-recursive):  " + whitelistedPathsNonRecursive);
            }
            if (!whitelistedJars.isEmpty()) {
                log.log("Whitelisted jars:  " + whitelistedJars);
            }
            if (!whitelistedJarPatterns.isEmpty()) {
                log.log("Whitelisted jars with glob wildcards:  " + whitelistedJarPatterns);
            }
            if (!specificallyWhitelistedClassRelativePaths.isEmpty()) {
                log.log("Specifically-whitelisted classfiles: " + specificallyWhitelistedClassRelativePaths);
            }
            if (!whitelistedModules.isEmpty()) {
                log.log("Whitelisted modules: " + whitelistedModules);
            }
            if (!blacklistedModules.isEmpty()) {
                log.log("Blacklisted modules: " + blacklistedModules);
            }
            if (!scanJars) {
                log.log("Scanning of jarfiles is disabled");
            }
            if (!scanDirs) {
                log.log("Scanning of directories (i.e. non-jarfiles) is disabled");
            }
            if (ignoreParentClassLoaders) {
                log.log("Ignoring parent classloaders");
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Register an extra ClassLoaderHandler.
     *
     * @param classLoaderHandler
     *            The class of the ClassLoaderHandler that can handle those ClassLoaders.
     */
    public void registerClassLoaderHandler(final Class<? extends ClassLoaderHandler> classLoaderHandler) {
        this.extraClassLoaderHandlers.add(new ClassLoaderHandlerRegistryEntry(classLoaderHandler));
    }

    /**
     * Override the automatically-detected classpath with a custom search path. You can specify multiple elements,
     * separated by File.pathSeparatorChar. If this method is called, nothing but the provided classpath will be
     * scanned, i.e. causes ClassLoaders to be ignored, as well as the java.class.path system property.
     * 
     * @param overrideClasspath
     *            The classpath to scan.
     */
    public void overrideClasspath(final String overrideClasspath) {
        this.overrideClasspath = overrideClasspath;
    }

    /**
     * Add a classpath element filter. The provided ClasspathElementFilter should return true if the path string
     * passed to it is a path you want to scan.
     * 
     * @param classpathElementFilter
     *            The classpath element filter to apply to all discovered classpath elements, to decide which should
     *            be scanned.
     */
    public void filterClasspathElements(final ClasspathElementFilter classpathElementFilter) {
        if (this.classpathElementFilters == null) {
            this.classpathElementFilters = new ArrayList<>(2);
        }
        this.classpathElementFilters.add(classpathElementFilter);
    }

    /**
     * Add a ClassLoader to the list of ClassLoaders to scan. (This only works if overrideClasspath() is not
     * called.)
     * 
     * @param classLoader
     *            The classloader to add.
     */
    public void addClassLoader(final ClassLoader classLoader) {
        if (this.addedClassLoaders == null) {
            this.addedClassLoaders = new ArrayList<>();
        }
        if (classLoader != null) {
            this.addedClassLoaders.add(classLoader);
        }
    }

    /**
     * Completely override the list of ClassLoaders to scan. (This only works if overrideClasspath() is not called.)
     * Causes the java.class.path system property to be ignored.
     * 
     * @param overrideClassLoaders
     *            The classloaders to override the default context classloaders with.
     */
    public void overrideClassLoaders(final ClassLoader... overrideClassLoaders) {
        this.addedClassLoaders = null;
        this.overrideClassLoaders = new ArrayList<>();
        for (final ClassLoader classLoader : overrideClassLoaders) {
            if (classLoader != null) {
                this.overrideClassLoaders.add(classLoader);
            }
        }
        if (this.overrideClassLoaders.isEmpty()) {
            // Reset back to null if the list of classloaders is empty
            this.overrideClassLoaders = null;
        } else {
            // overrideClassLoaders() overrides addClassloader()
            this.addedClassLoaders = null;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Convert a spec with a '*' glob character into a regular expression. Replaces "." with "\." and "*" with ".*",
     * then compiles a regulare expression.
     */
    private static Pattern specToPattern(final String spec) {
        return Pattern.compile("^" + spec.replace(".", "\\.").replace("*", ".*") + "$");
    }

    /**
     * Whether a path is a descendant of a blacklisted path, or an ancestor or descendant of a whitelisted path.
     */
    static enum ScanSpecPathMatch {
        HAS_BLACKLISTED_PATH_PREFIX, HAS_WHITELISTED_PATH_PREFIX, AT_WHITELISTED_PATH, //
        ANCESTOR_OF_WHITELISTED_PATH, AT_WHITELISTED_CLASS_PACKAGE, NOT_WITHIN_WHITELISTED_PATH;
    }

    /**
     * Returns true if the given directory path is a descendant of a blacklisted path, or an ancestor or descendant
     * of a whitelisted path. The path should end in "/".
     */
    ScanSpecPathMatch dirWhitelistMatchStatus(final String relativePath) {
        // In blacklisted path

        for (final String blacklistedPath : blacklistedPathPrefixes) {
            if (relativePath.startsWith(blacklistedPath)) {
                // The directory or its ancestor is blacklisted.
                return ScanSpecPathMatch.HAS_BLACKLISTED_PATH_PREFIX;
            }
        }

        // At whitelisted path

        if (specificallyWhitelistedClassParentRelativePaths.contains(relativePath)
                && !specificallyBlacklistedClassRelativePaths.contains(relativePath)) {
            // Reached a package containing a specifically-whitelisted class
            return ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE;
        }
        if (whitelistedPathPrefixes.contains(relativePath) || whitelistedPathsNonRecursive.contains(relativePath)) {
            // Reached a whitelisted path
            return ScanSpecPathMatch.AT_WHITELISTED_PATH;
        }

        // Ancestor of whitelisted path

        if (relativePath.equals("/")) {
            // The default package is always the ancestor of whitelisted paths (need to keep recursing)
            return ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH;
        }
        for (final String whitelistedPathPrefix : whitelistedPathPrefixes) {
            if (whitelistedPathPrefix.startsWith(relativePath)) {
                // relativePath is an ancestor (prefix) of a whitelisted path
                return ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH;
            }
        }
        for (final String whitelistedPathNonRecursive : whitelistedPathsNonRecursive) {
            if (whitelistedPathNonRecursive.startsWith(relativePath)) {
                // relativePath is an ancestor (prefix) of a whitelisted path
                return ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH;
            }
        }
        for (final String whitelistedClassPathPrefix : specificallyWhitelistedClassParentRelativePaths) {
            if (whitelistedClassPathPrefix.startsWith(relativePath)) {
                // The directory is an ancestor of a non-whitelisted package containing a whitelisted class
                return ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH;
            }
        }

        // Descendant of whitelisted path

        if (!disableRecursiveScanning) {
            for (final String whitelistedPathPrefix : whitelistedPathPrefixes) {
                if (relativePath.startsWith(whitelistedPathPrefix)) {
                    // Path prefix matches one in the whitelist
                    return ScanSpecPathMatch.HAS_WHITELISTED_PATH_PREFIX;
                }
            }
        }

        // Not in whitelisted path

        return ScanSpecPathMatch.NOT_WITHIN_WHITELISTED_PATH;
    }

    /**
     * Returns true if the given relative path (for a classfile name, including ".class") matches a
     * specifically-whitelisted (and non-blacklisted) classfile's relative path.
     */
    boolean isSpecificallyWhitelistedClass(final String relativePath) {
        return (specificallyWhitelistedClassRelativePaths.contains(relativePath)
                && !specificallyBlacklistedClassRelativePaths.contains(relativePath));
    }

    /** Returns true if the class is specifically blacklisted, or is within a blacklisted package. */
    boolean classIsBlacklisted(final String className) {
        boolean classIsBlacklisted = false;
        if (specificallyBlacklistedClassNames.contains(className)) {
            classIsBlacklisted = true;
        } else {
            for (final String blacklistedPackagePrefix : blacklistedPackagePrefixes) {
                if (className.startsWith(blacklistedPackagePrefix)) {
                    classIsBlacklisted = true;
                    break;
                }
            }
        }
        return classIsBlacklisted;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Test if a list of jar names contains the requested name, allowing for globs. */
    private static boolean containsJarName(final Set<String> jarNames, final List<Pattern> jarNamePatterns,
            final String jarName) {
        final String jarLeafName = JarUtils.leafName(jarName);
        if (jarNames.contains(jarLeafName)) {
            return true;
        }
        for (final Pattern jarNamePattern : jarNamePatterns) {
            if (jarNamePattern.matcher(jarLeafName).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Returns true if a jarfile is whitelisted and not blacklisted. */
    boolean jarIsWhitelisted(final String jarName) {
        final String jarLeafName = JarUtils.leafName(jarName);
        return ((whitelistedJars.isEmpty() && whitelistedJarPatterns.isEmpty())
                || containsJarName(whitelistedJars, whitelistedJarPatterns, jarLeafName))
                && !containsJarName(blacklistedJars, blacklistedJarPatterns, jarLeafName);
    }

    /**
     * True if JRE system jarfiles (rt.jar etc.) should not be scanned. By default, these are not scanned. This can
     * be overridden by including "!!" in the scan spec. Disabling this blacklisting will increase the time or
     * memory required to scan the classpath.
     */
    public boolean blacklistSystemJars() {
        return blacklistSystemJarsOrModules;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the list of all ClassLoaderHandlerRegistryEntry objects for user-defined ClassLoaderHandlers, followed by
     * the defaults in the ClassLoaderHandlerRegistry.
     */
    public List<ClassLoaderHandlerRegistryEntry> getAllClassLoaderHandlerRegistryEntries() {
        // Get all manually-added ClassLoaderHandlers (these are added before the default ClassLoaderHandlers,
        // so that the behavior of the defaults can be overridden)
        List<ClassLoaderHandlerRegistryEntry> allClassLoaderHandlerRegistryEntries;
        if (extraClassLoaderHandlers.isEmpty()) {
            allClassLoaderHandlerRegistryEntries = ClassLoaderHandlerRegistry.DEFAULT_CLASS_LOADER_HANDLERS;
        } else {
            allClassLoaderHandlerRegistryEntries = new ArrayList<>(extraClassLoaderHandlers);
            allClassLoaderHandlerRegistryEntries.addAll(ClassLoaderHandlerRegistry.DEFAULT_CLASS_LOADER_HANDLERS);
        }
        return allClassLoaderHandlerRegistryEntries;
    }

    public ClassLoaderHandler findClassLoaderHandlerForClassLoader(final ClassLoader classLoader,
            final LogNode log) {
        return ClasspathFinder.findClassLoaderHandlerForClassLoader(this, classLoader, log);
    }
}
