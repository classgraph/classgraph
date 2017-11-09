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
 * Copyright (c) 2016 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import io.github.lukehutch.fastclasspathscanner.MatchProcessorException;
import io.github.lukehutch.fastclasspathscanner.classloaderhandler.ClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.classloaderhandler.ClassLoaderHandlerRegistry.ClassLoaderHandlerRegistryEntry;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ClassAnnotationMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ClassMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FieldAnnotationMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ImplementingClassMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.MethodAnnotationMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.StaticFinalFieldMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.SubclassMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.SubinterfaceMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.scanner.matchers.ClassMatchProcessorWrapper;
import io.github.lukehutch.fastclasspathscanner.scanner.matchers.FileMatchProcessorAny;
import io.github.lukehutch.fastclasspathscanner.scanner.matchers.FileMatchProcessorWrapper;
import io.github.lukehutch.fastclasspathscanner.scanner.matchers.FileMatchProcessorWrapper.FilePathTester;
import io.github.lukehutch.fastclasspathscanner.utils.JarUtils;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.MultiMapKeyToList;
import io.github.lukehutch.fastclasspathscanner.utils.MultiMapKeyToSet;

/**
 * Parses the scanning specification that was passed to the FastClasspathScanner constructor, and finds all
 * ClassLoaders. Also defines core MatchProcessor matching logic.
 */
public class ScanSpec {
    /** Whitelisted package paths with "/" appended, or the empty list if all packages are whitelisted. */
    public final ArrayList<String> whitelistedPathPrefixes = new ArrayList<>();

    /** Blacklisted package paths with "/" appended. */
    public final ArrayList<String> blacklistedPathPrefixes = new ArrayList<>();

    /** Blacklisted package names with "." appended. */
    public final ArrayList<String> blacklistedPackagePrefixes = new ArrayList<>();

    /** Whitelisted class names, or the empty list if none. */
    public final HashSet<String> specificallyWhitelistedClassRelativePaths = new HashSet<>();

    /** Path prefixes of whitelisted classes, or the empty list if none. */
    public final HashSet<String> specificallyWhitelistedClassParentRelativePaths = new HashSet<>();

    /** Blacklisted class relative paths. */
    public final HashSet<String> specificallyBlacklistedClassRelativePaths = new HashSet<>();

    /** Blacklisted class names. */
    public final HashSet<String> specificallyBlacklistedClassNames = new HashSet<>();

    /** Whitelisted jarfile names. (Leaf filename only.) */
    public final HashSet<String> whitelistedJars = new HashSet<>();

    /** Blacklisted jarfile names. (Leaf filename only.) */
    public final HashSet<String> blacklistedJars = new HashSet<>();

    /** Whitelisted jarfile names containing a glob('*') character, converted to a regexp. (Leaf filename only.) */
    public final ArrayList<Pattern> whitelistedJarPatterns = new ArrayList<>();

    /** Blacklisted jarfile names containing a glob('*') character, converted to a regexp. (Leaf filename only.) */
    public final ArrayList<Pattern> blacklistedJarPatterns = new ArrayList<>();

    // -------------------------------------------------------------------------------------------------------------

    /** True if jarfiles on the classpath should be scanned. */
    public boolean scanJars = true;

    /** True if directories on the classpath should be scanned. */
    public boolean scanDirs = true;

    /** If true, index types of fields. */
    public boolean enableFieldTypeIndexing;

    /** If true, index method annotations. */
    public boolean enableMethodAnnotationIndexing;

    /** If true, index field annotations. */
    public boolean enableFieldAnnotationIndexing;

    /**
     * If true, enables the saving of field info during the scan. This information can be obtained using
     * ClassInfo#getFieldInfo(). By default, field info is not saved for efficiency.
     */
    public boolean enableFieldInfo;

    /**
     * If true, enables the saving of method info during the scan. This information can be obtained using
     * ClassInfo#getMethodInfo(). By default, method info is not saved for efficiency.
     */
    public boolean enableMethodInfo;

    /**
     * If true, remove "external" classes from consideration (i.e. classes outside of whitelisted packages that are
     * referred to by classes within whitelisted packages, e.g. as a superclass). This affects both the ability to
     * use an external classname as a matching criterion, and whether external classes are returned to the user by
     * ScanResult methods.
     */
    public boolean strictWhitelist;

    /**
     * True if JRE system jarfiles (rt.jar etc.) should not be scanned. By default, these are not scanned. This can
     * be overridden by including "!!" in the scan spec. Disabling this blacklisting will increase the time or
     * memory required to scan the classpath.
     */
    public boolean blacklistSystemJars = true;

    /**
     * By default, blacklist all java.* and sun.* packages. This means for example that you can't use
     * java.lang.Comparable as a match criterion. This can be overridden by including "!!" in the scan spec.
     * Disabling this blacklisting may increase the time or memory required to scan the classpath.
     */
    public boolean blacklistSystemPackages = true;

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
     * Annotation retention visibility: RetentionPolicy.CLASS matches all annotations (both RuntimeVisible and
     * RuntimeInvisible); RetentionPolicy.RUNTIME matches only RuntimeVisible annotations.
     */
    public RetentionPolicy annotationVisibility = RetentionPolicy.CLASS;

    /**
     * Whether to disable recursive scanning (enabled by default). If set to false, only toplevel entries within
     * each whitelisted package will be scanned, i.e. sub-packages of whitelisted packages will not be scanned. If
     * no whitelisted packages were provided to the FastClasspathScanner constructor, then only the toplevel
     * directory within each classpath element will be scanned.
     */
    public boolean disableRecursiveScanning = false;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A map from (className + "." + staticFinalFieldName) to StaticFinalFieldMatchProcessor(s) that should be
     * called if that class name and static final field name is encountered with a static constant initializer
     * during scan.
     */
    private MultiMapKeyToList<String, StaticFinalFieldMatchProcessor> //
    fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors;

    /** A map from className to a list of static final fields to match with StaticFinalFieldMatchProcessors. */
    private MultiMapKeyToSet<String, String> classNameToStaticFinalFieldsToMatch;

    public MultiMapKeyToSet<String, String> getClassNameToStaticFinalFieldsToMatch() {
        return classNameToStaticFinalFieldsToMatch;
    }

    /** A list of class matchers to call once all classes have been read in from classpath. */
    private ArrayList<ClassMatchProcessorWrapper> classMatchers;

    /** A list of file path testers and match processor wrappers to use for file matching. */
    private final List<FileMatchProcessorWrapper> fileMatchProcessorWrappers = new ArrayList<>();

    // -------------------------------------------------------------------------------------------------------------

    /**
     * If non-null, specified manually-added classloaders that should be searched after the context classloader(s).
     */
    public List<ClassLoader> addedClassLoaders;

    /**
     * If non-null, all ClassLoaders have been overriden. In particular, this causes FastClasspathScanner to ignore
     * the java.class.path system property.
     */
    public List<ClassLoader> overrideClassLoaders;

    /** The ClassLoader finder. */
    public ClassLoaderFinder classLoaderFinder;

    /** If non-null, specifies a classpath to override the default one. */
    public String overrideClasspath;

    /** Manually-registered ClassLoaderHandlers. */
    public final ArrayList<ClassLoaderHandlerRegistryEntry> extraClassLoaderHandlers = new ArrayList<>();

    /**
     * If true, classes loaded with Class.forName() are initialized before passing class references to
     * MatchProcessors. If false (the default), matched classes are loaded but not initialized before passing class
     * references to MatchProcessors (meaning classes are instead initialized lazily on first usage of the class).
     */
    public boolean initializeLoadedClasses = false;

    /**
     * If true, nested jarfiles (jarfiles within jarfiles) that are extracted during scanning are removed from their
     * temporary directory (e.g. /tmp-FastClasspathScanner-8JX2u4w) after the scan has completed. If false,
     * temporary files are removed on JVM exit.
     */
    public boolean removeTemporaryFilesAfterScan = true;

    /** If true, do not fetch paths from parent classloaders. */
    public boolean ignoreParentClassLoaders = false;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Parses the scanning specification that was passed to the FastClasspathScanner constructor, and finds all
     * ClassLoaders.
     */
    public ScanSpec(final String[] scanSpec, final LogNode log) {
        final HashSet<String> uniqueWhitelistedPathPrefixes = new HashSet<>();
        final HashSet<String> uniqueBlacklistedPathPrefixes = new HashSet<>();
        for (final String scanSpecEntry : scanSpec) {
            String spec = scanSpecEntry;
            if ("!".equals(scanSpecEntry)) {
                blacklistSystemPackages = false;
            } else if ("!!".equals(scanSpecEntry)) {
                blacklistSystemJars = false;
                blacklistSystemPackages = false;
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
                } else if (spec.startsWith("dir:")) {
                    // Strip off "dir:"
                    spec = spec.substring(4);
                    if (!spec.isEmpty()) {
                        if (log != null) {
                            log.log("Ignoring extra text after \"dir:\" in scan spec entry: " + scanSpecEntry);
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
                        // This is a package name:
                        // convert into a prefix by adding '.', and also convert to path prefix
                        if (blacklisted) {
                            uniqueBlacklistedPathPrefixes.add(specPath + "/");
                        } else {
                            uniqueWhitelistedPathPrefixes.add(specPath + "/");
                        }
                    }
                }
            }
        }

        if (blacklistSystemPackages) {
            // Blacklist Java types by default
            uniqueBlacklistedPathPrefixes.add("java/");
            uniqueBlacklistedPathPrefixes.add("javax/");
            uniqueBlacklistedPathPrefixes.add("sun/");
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
        if (whitelistedPathPrefixes.isEmpty() && specificallyWhitelistedClassRelativePaths.isEmpty()) {
            // If no whitelisted package names or class names were given, scan all packages.
            // Having a whitelisted class name but no whitelisted package name should not trigger the scanning
            // of all packages (Issue #78.)
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

        if (log != null) {
            log.log("Whitelisted relative path prefixes:  " + whitelistedPathPrefixes);
            if (!blacklistedPathPrefixes.isEmpty()) {
                log.log("Blacklisted relative path prefixes:  " + blacklistedPathPrefixes);
            }
            if (!whitelistedJars.isEmpty()) {
                log.log("Whitelisted jars:  " + whitelistedJars);
            }
            if (!whitelistedJarPatterns.isEmpty()) {
                log.log("Whitelisted jars with glob wildcards:  " + whitelistedJarPatterns);
            }
            if (!blacklistedJars.isEmpty()) {
                log.log("Blacklisted jars:  " + blacklistedJars);
            }
            if (!blacklistedJarPatterns.isEmpty()) {
                log.log("Whitelisted jars with glob wildcards:  " + blacklistedJarPatterns);
            }
            if (!specificallyWhitelistedClassRelativePaths.isEmpty()) {
                log.log("Specifically-whitelisted classfiles: " + specificallyWhitelistedClassRelativePaths);
            }
            if (!specificallyBlacklistedClassRelativePaths.isEmpty()) {
                log.log("Specifically-blacklisted classfiles: " + specificallyBlacklistedClassRelativePaths);
            }
            if (!scanJars) {
                log.log("Scanning of jarfiles is disabled");
            }
            if (!scanDirs) {
                log.log("Scanning of directories (i.e. non-jarfiles) is disabled");
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
     */
    public void overrideClasspath(final String overrideClasspath) {
        this.overrideClasspath = overrideClasspath;
    }

    /**
     * Add a ClassLoader to the list of ClassLoaders to scan. (This only works if overrideClasspath() is not
     * called.)
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

    public void ignoreParentClassLoaders(final boolean ignoreParentClassloaders) {
        this.ignoreParentClassLoaders = ignoreParentClassloaders;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Return true if any MatchProcessors have been added.
     */
    public boolean hasMatchProcessors() {
        return (fileMatchProcessorWrappers != null && fileMatchProcessorWrappers.size() > 0)
                || (classMatchers != null && classMatchers.size() > 0)
                || (fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors != null
                        && !fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors.isEmpty());

    }

    /**
     * Run the MatchProcessors after a scan has completed.
     */
    public void callMatchProcessors(final ScanResult scanResult) {
        final LogNode log = scanResult.log;
        try {
            // Call any FileMatchProcessors
            for (final ClasspathElement classpathElement : scanResult.classpathOrder) {
                classpathElement.callFileMatchProcessors(scanResult, log);
            }

            // Call any class, interface or annotation MatchProcessors
            if (classMatchers != null) {
                final LogNode subLog = log == null ? null : log.log("Calling ClassMatchProcessors");
                for (final ClassMatchProcessorWrapper classMatcher : classMatchers) {
                    classMatcher.lookForMatches(scanResult, subLog);
                    scanResult.interruptionChecker.check();
                }
            }

            // Call any static final field match processors
            if (fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors != null) {
                for (final Entry<String, List<StaticFinalFieldMatchProcessor>> ent : //
                fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors.getRawMap().entrySet()) {
                    final String fullyQualifiedFieldName = ent.getKey();
                    final int dotIdx = fullyQualifiedFieldName.lastIndexOf('.');
                    final String className = fullyQualifiedFieldName.substring(0, dotIdx);
                    final ClassInfo classInfo = scanResult.classGraphBuilder.classNameToClassInfo.get(className);
                    if (classInfo != null) {
                        final String fieldName = fullyQualifiedFieldName.substring(dotIdx + 1);
                        final Object constValue = classInfo.getStaticFinalFieldConstantInitializerValue(fieldName);
                        if (constValue == null) {
                            if (log != null) {
                                log.log("No constant initializer value found for field " + className + "."
                                        + fieldName);
                            }
                        } else {
                            final List<StaticFinalFieldMatchProcessor> staticFinalFieldMatchProcessors = ent
                                    .getValue();
                            if (log != null) {
                                log.log("Calling MatchProcessor"
                                        + (staticFinalFieldMatchProcessors.size() == 1 ? "" : "s")
                                        + " for static final field " + className + "." + fieldName + " = "
                                        + ((constValue instanceof Character)
                                                ? '\'' + constValue.toString().replace("'", "\\'") + '\''
                                                : (constValue instanceof String)
                                                        ? '"' + constValue.toString().replace("\"", "\\\"") + '"'
                                                        : constValue.toString()));
                            }
                            for (final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor : ent
                                    .getValue()) {
                                try {
                                    staticFinalFieldMatchProcessor.processMatch(className, fieldName, constValue);
                                } catch (final Throwable e) {
                                    if (log != null) {
                                        log.log("Exception while calling StaticFinalFieldMatchProcessor: " + e);
                                    }
                                    scanResult.addMatchProcessorException(e);
                                }
                                scanResult.interruptionChecker.check();
                            }
                        }
                    } else {
                        if (log != null) {
                            log.log("No matching class found in scan results for static final field "
                                    + fullyQualifiedFieldName);
                        }
                    }
                }
            }
            final List<Throwable> matchProcessorExceptions = scanResult.getMatchProcessorExceptions();
            if (matchProcessorExceptions.size() > 0) {
                // If one or more non-IO exceptions were thrown outside of FastClasspathScanner,
                // throw MatchProcessorException
                if (log != null) {
                    log.log("Number of exceptions raised during classloading and/or while calling "
                            + "MatchProcessors: " + matchProcessorExceptions.size());
                }
                throw MatchProcessorException.newInstance(matchProcessorExceptions);
            }
        } catch (InterruptedException | ExecutionException e) {
            if (log != null) {
                log.log("Exception while calling MatchProcessors", e);
            }
            throw MatchProcessorException.newInstance(e);
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

    /** Whether a path is a descendant of a blacklisted path, or an ancestor or descendant of a whitelisted path. */
    enum ScanSpecPathMatch {
        WITHIN_BLACKLISTED_PATH, WITHIN_WHITELISTED_PATH, ANCESTOR_OF_WHITELISTED_PATH, //
        AT_WHITELISTED_CLASS_PACKAGE, NOT_WITHIN_WHITELISTED_PATH;
    }

    /**
     * Returns true if the given directory path is a descendant of a blacklisted path, or an ancestor or descendant
     * of a whitelisted path. The path should end in "/".
     */
    ScanSpecPathMatch pathWhitelistMatchStatus(final String relativePath) {
        for (final String blacklistedPath : blacklistedPathPrefixes) {
            if (relativePath.startsWith(blacklistedPath)) {
                // The directory or its ancestor is blacklisted.
                return ScanSpecPathMatch.WITHIN_BLACKLISTED_PATH;
            }
        }
        for (final String whitelistedPath : whitelistedPathPrefixes) {
            if (disableRecursiveScanning && relativePath.equals(whitelistedPath)) {
                // Recursive scanning is disabled, and the directory is a toplevel whitelisted path.
                return ScanSpecPathMatch.WITHIN_WHITELISTED_PATH;
            } else if (!disableRecursiveScanning && relativePath.startsWith(whitelistedPath)) {
                // Recursive scanning is enabled, and the directory is a whitelisted path or subdirectory.
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

    /** Checks that the named class is not blacklisted. Throws IllegalArgumentException otherwise. */
    void checkClassIsNotBlacklisted(final String className) {
        if (strictWhitelist && classIsBlacklisted(className)) {
            final boolean isSystemPackage = className.startsWith("java.") || className.startsWith("javax.")
                    || className.startsWith("sun.");
            throw new IllegalArgumentException("Can't scan for " + className + ", it is in a blacklisted "
                    + (!isSystemPackage ? "package" : "system package")
                    + ", and and strictWhitelist() was called before scan()."
                    + (!isSystemPackage ? ""
                            : "You can override this by adding \"!\" or \"!!\" to the "
                                    + "scan spec to disable system package blacklisting or system jar "
                                    + "blacklisting respectively (see the docs)"));
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Test if a list of jar names contains the requested name, allowing for globs. */
    private static boolean containsJarName(final HashSet<String> jarNames, final ArrayList<Pattern> jarNamePatterns,
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
    boolean blacklistSystemJars() {
        return blacklistSystemJars;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Call the classloader using Class.forName(className, initializeLoadedClasses, classLoader), for all known
     * ClassLoaders, until one is able to load the class, or until there are no more ClassLoaders to try.
     * 
     * @throw MatchProcessorException if LinkageError (including ExceptionInInitializerError) is thrown.
     * @return a reference to the loaded class, or null if the class could not be loaded by its ClassLoader(s).
     */
    private Class<?> loadClassForMatchProcessor(final String className, final ScanResult scanResult,
            final LogNode log) throws MatchProcessorException {
        try {
            return scanResult.loadClass(className, /* returnNullIfClassNotFound = */ true, log);
        } catch (final Throwable e) {
            Throwable cause = e.getCause();
            if (cause == null) {
                cause = e;
            }
            throw MatchProcessorException.newInstance(cause);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Check a class is an annotation, and that it is in a whitelisted package. Throws IllegalArgumentException
     * otherwise. Returns the name of the annotation.
     */
    String getAnnotationName(final Class<?> annotation) {
        final String annotationName = annotation.getName();
        checkClassIsNotBlacklisted(annotationName);
        if (!annotation.isAnnotation()) {
            throw new IllegalArgumentException(annotationName + " is not an annotation");
        }
        return annotation.getName();
    }

    /**
     * Check each element of an array of classes is an annotation, and that it is in a whitelisted package. Throws
     * IllegalArgumentException otherwise. Returns the names of the classes as an array of strings.
     */
    String[] getAnnotationNames(final Class<?>[] annotations) {
        final String[] annotationNames = new String[annotations.length];
        for (int i = 0; i < annotations.length; i++) {
            annotationNames[i] = getAnnotationName(annotations[i]);
        }
        return annotationNames;
    }

    /**
     * Check a class is an interface, and that it is in a whitelisted package. Throws IllegalArgumentException
     * otherwise. Returns the name of the interface.
     */
    String getInterfaceName(final Class<?> iface) {
        final String ifaceName = iface.getName();
        checkClassIsNotBlacklisted(ifaceName);
        if (!iface.isInterface()) {
            throw new IllegalArgumentException(ifaceName + " is not an interface");
        }
        return iface.getName();
    }

    /**
     * Check each element of an array of classes is an interface, and that it is in a whitelisted package. Throws
     * IllegalArgumentException otherwise. Returns the names of the classes as an array of strings.
     */
    String[] getInterfaceNames(final Class<?>[] interfaces) {
        final String[] interfaceNames = new String[interfaces.length];
        for (int i = 0; i < interfaces.length; i++) {
            interfaceNames[i] = getInterfaceName(interfaces[i]);
        }
        return interfaceNames;
    }

    /**
     * Check a class is a regular class or interface and not an annotation, and that it is in a whitelisted package.
     * Throws IllegalArgumentException otherwise. Returns the name of the class or interface.
     */
    String getClassOrInterfaceName(final Class<?> classOrInterface) {
        final String classOrIfaceName = classOrInterface.getName();
        checkClassIsNotBlacklisted(classOrIfaceName);
        if (classOrInterface.isAnnotation()) {
            throw new IllegalArgumentException(
                    classOrIfaceName + " is an annotation, not a regular class or interface");
        }
        return classOrInterface.getName();
    }

    /**
     * Check a class is a standard class (not an interface or annotation), and that it is in a whitelisted package.
     * Returns the name of the class if it is a standard class and it is in a whitelisted package, otherwise throws
     * an IllegalArgumentException.
     */
    String getStandardClassName(final Class<?> cls) {
        final String className = cls.getName();
        checkClassIsNotBlacklisted(className);
        if (cls.isAnnotation()) {
            throw new IllegalArgumentException(className + " is an annotation, not a standard class");
        } else if (cls.isInterface()) {
            throw new IllegalArgumentException(cls.getName() + " is an interface, not a standard class");
        }
        return className;
    }

    /**
     * Check a class is in a whitelisted package. Returns the name of the class if it is in a whitelisted package,
     * otherwise throws an IllegalArgumentException.
     */
    private String getClassName(final Class<?> cls) {
        final String className = cls.getName();
        checkClassIsNotBlacklisted(className);
        return className;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Add a ClassMatcher. */
    private void addClassMatcher(final ClassMatchProcessorWrapper classMatcher) {
        if (classMatchers == null) {
            classMatchers = new ArrayList<>();
        }
        classMatchers.add(classMatcher);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided ClassMatchProcessor for all standard classes, interfaces and annotations found in
     * whitelisted packages on the classpath.
     * 
     * @param classMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public void matchAllClasses(final ClassMatchProcessor classMatchProcessor) {
        addClassMatcher(new ClassMatchProcessorWrapper() {
            @Override
            public void lookForMatches(final ScanResult scanResult, final LogNode log) {
                for (final String className : scanResult.getNamesOfAllClasses()) {
                    LogNode subLog = null;
                    if (log != null) {
                        subLog = log.log("Matched class: " + className);
                    }
                    try {
                        // Call classloader
                        final Class<?> cls = loadClassForMatchProcessor(className, scanResult, log);
                        // Process match
                        if (cls != null) {
                            classMatchProcessor.processMatch(cls);
                        }
                    } catch (final Throwable e) {
                        if (subLog != null) {
                            subLog.log("Exception while processing match for class " + className, e);
                        }
                        scanResult.addMatchProcessorException(e);
                    }
                }
            }
        });
    }

    /**
     * Calls the provided ClassMatchProcessor for all standard classes (i.e. non-interface, non-annotation classes)
     * found in whitelisted packages on the classpath.
     * 
     * @param classMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public void matchAllStandardClasses(final ClassMatchProcessor classMatchProcessor) {
        addClassMatcher(new ClassMatchProcessorWrapper() {
            @Override
            public void lookForMatches(final ScanResult scanResult, final LogNode log) {
                for (final String className : scanResult.getNamesOfAllStandardClasses()) {
                    LogNode subLog = null;
                    if (log != null) {
                        subLog = log.log("Matched standard class: " + className);
                    }
                    try {
                        // Call classloader
                        final Class<?> cls = loadClassForMatchProcessor(className, scanResult, log);
                        // Process match
                        if (cls != null) {
                            classMatchProcessor.processMatch(cls);
                        }
                    } catch (final Throwable e) {
                        if (subLog != null) {
                            subLog.log("Exception while processing match for class " + className, e);
                        }
                        scanResult.addMatchProcessorException(e);
                    }
                }
            }
        });
    }

    /**
     * Calls the provided ClassMatchProcessor for all interface classes (interface definitions) found in whitelisted
     * packages on the classpath.
     * 
     * @param classMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public void matchAllInterfaceClasses(final ClassMatchProcessor classMatchProcessor) {
        addClassMatcher(new ClassMatchProcessorWrapper() {
            @Override
            public void lookForMatches(final ScanResult scanResult, final LogNode log) {
                for (final String className : scanResult.getNamesOfAllInterfaceClasses()) {
                    LogNode subLog = null;
                    if (log != null) {
                        subLog = log.log("Matched interface class: " + className);
                    }
                    try {
                        // Call classloader
                        final Class<?> cls = loadClassForMatchProcessor(className, scanResult, log);
                        // Process match
                        if (cls != null) {
                            classMatchProcessor.processMatch(cls);
                        }
                    } catch (final Throwable e) {
                        if (subLog != null) {
                            subLog.log("Exception while processing match for class " + className, e);
                        }
                        scanResult.addMatchProcessorException(e);
                    }
                }
            }
        });
    }

    /**
     * Calls the provided ClassMatchProcessor for all annotation classes (annotation definitions) found in
     * whitelisted packages on the classpath.
     * 
     * @param classMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public void matchAllAnnotationClasses(final ClassMatchProcessor classMatchProcessor) {
        addClassMatcher(new ClassMatchProcessorWrapper() {
            @Override
            public void lookForMatches(final ScanResult scanResult, final LogNode log) {
                for (final String className : scanResult.getNamesOfAllAnnotationClasses()) {
                    LogNode subLog = null;
                    if (log != null) {
                        subLog = log.log("Matched annotation class: " + className);
                    }
                    try {
                        // Call classloader
                        final Class<?> cls = loadClassForMatchProcessor(className, scanResult, log);
                        // Process match
                        if (cls != null) {
                            classMatchProcessor.processMatch(cls);
                        }
                    } catch (final Throwable e) {
                        if (subLog != null) {
                            subLog.log("Exception while processing match for class " + className, e);
                        }
                        scanResult.addMatchProcessorException(e);
                    }
                }
            }
        });
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided SubclassMatchProcessor if classes are found on the classpath that extend the specified
     * superclass.
     * 
     * @param superclass
     *            The superclass to match (i.e. the class that subclasses need to extend to match).
     * @param subclassMatchProcessor
     *            the SubclassMatchProcessor to call when a match is found.
     */
    public <T> void matchSubclassesOf(final Class<T> superclass,
            final SubclassMatchProcessor<T> subclassMatchProcessor) {
        addClassMatcher(new ClassMatchProcessorWrapper() {
            @Override
            public void lookForMatches(final ScanResult scanResult, final LogNode log) {
                final String superclassName = getStandardClassName(superclass);
                for (final String subclassName : scanResult.getNamesOfSubclassesOf(superclassName)) {
                    LogNode subLog = null;
                    if (log != null) {
                        subLog = log.log("Matched subclass of " + superclassName + ": " + subclassName);
                    }
                    try {
                        // Call classloader
                        @SuppressWarnings("unchecked")
                        final Class<? extends T> cls = (Class<? extends T>) loadClassForMatchProcessor(subclassName,
                                scanResult, log);
                        // Process match
                        if (cls != null) {
                            subclassMatchProcessor.processMatch(cls);
                        }
                    } catch (final Throwable e) {
                        if (subLog != null) {
                            subLog.log("Exception while processing match for class " + subclassName, e);
                        }
                        scanResult.addMatchProcessorException(e);
                    }
                }
            }
        });

    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided SubinterfaceMatchProcessor if an interface that extends a given superinterface is found on
     * the classpath.
     * 
     * @param superinterface
     *            The superinterface to match (i.e. the interface that subinterfaces need to extend to match).
     * @param subinterfaceMatchProcessor
     *            the SubinterfaceMatchProcessor to call when a match is found.
     */
    public <T> void matchSubinterfacesOf(final Class<T> superinterface,
            final SubinterfaceMatchProcessor<T> subinterfaceMatchProcessor) {
        addClassMatcher(new ClassMatchProcessorWrapper() {
            @Override
            public void lookForMatches(final ScanResult scanResult, final LogNode log) {
                final String superinterfaceName = getInterfaceName(superinterface);
                for (final String subinterfaceName : scanResult.getNamesOfSubinterfacesOf(superinterfaceName)) {
                    LogNode subLog = null;
                    if (log != null) {
                        subLog = log.log("Matched subinterface of " + superinterfaceName + ": " + subinterfaceName);
                    }
                    try {
                        // Call classloader
                        @SuppressWarnings("unchecked")
                        final Class<? extends T> cls = (Class<? extends T>) loadClassForMatchProcessor(
                                subinterfaceName, scanResult, log);
                        // Process match
                        if (cls != null) {
                            subinterfaceMatchProcessor.processMatch(cls);
                        }
                    } catch (final Throwable e) {
                        if (subLog != null) {
                            subLog.log("Exception while processing match for class " + subinterfaceName, e);
                        }
                        scanResult.addMatchProcessorException(e);
                    }
                }
            }
        });
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided InterfaceMatchProcessor for classes on the classpath that implement the specified
     * interface or a subinterface, or whose superclasses implement the specified interface or a sub-interface.
     * 
     * @param implementedInterface
     *            The interface that classes need to implement.
     * @param implementingClassMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public <T> void matchClassesImplementing(final Class<T> implementedInterface,
            final ImplementingClassMatchProcessor<T> implementingClassMatchProcessor) {
        addClassMatcher(new ClassMatchProcessorWrapper() {
            @Override
            public void lookForMatches(final ScanResult scanResult, final LogNode log) {
                final String implementedInterfaceName = getInterfaceName(implementedInterface);
                for (final String implementingClassName : scanResult
                        .getNamesOfClassesImplementing(implementedInterfaceName)) {
                    LogNode subLog = null;
                    if (log != null) {
                        subLog = log.log("Matched class implementing interface " + implementedInterfaceName + ": "
                                + implementingClassName);
                    }
                    try {
                        // Call classloader
                        @SuppressWarnings("unchecked")
                        final Class<? extends T> cls = (Class<? extends T>) loadClassForMatchProcessor(
                                implementingClassName, scanResult, log);
                        // Process match
                        if (cls != null) {
                            implementingClassMatchProcessor.processMatch(cls);
                        }
                    } catch (final Throwable e) {
                        if (subLog != null) {
                            subLog.log("Exception while processing match for class " + implementingClassName, e);
                        }
                        scanResult.addMatchProcessorException(e);
                    }
                }
            }
        });
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided ClassMatchProcessor for classes on the classpath that have a field of the given type.
     * Matches classes that have fields of the given type, array fields with an element type of the given type, and
     * fields of parameterized type that have a type parameter of the given type.
     * 
     * @param fieldType
     *            The type of the field to match..
     * @param classMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public <T> void matchClassesWithFieldOfType(final Class<T> fieldType,
            final ClassMatchProcessor classMatchProcessor) {
        addClassMatcher(new ClassMatchProcessorWrapper() {
            @Override
            public void lookForMatches(final ScanResult scanResult, final LogNode log) {
                final String fieldTypeName = getClassName(fieldType);
                for (final String className : scanResult.getNamesOfClassesWithFieldOfType(fieldTypeName)) {
                    LogNode subLog = null;
                    if (log != null) {
                        subLog = log.log("Matched class with field of type " + fieldTypeName + ": " + className);
                    }
                    try {
                        // Call classloader
                        final Class<?> cls = loadClassForMatchProcessor(className, scanResult, log);
                        // Process match
                        if (cls != null) {
                            classMatchProcessor.processMatch(cls);
                        }
                    } catch (final Throwable e) {
                        if (subLog != null) {
                            subLog.log("Exception while processing match for class " + fieldTypeName, e);
                        }
                        scanResult.addMatchProcessorException(e);
                    }
                }
            }
        });
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided ClassAnnotationMatchProcessor if classes are found on the classpath that have the
     * specified annotation.
     * 
     * @param annotation
     *            The class annotation to match.
     * @param classAnnotationMatchProcessor
     *            the ClassAnnotationMatchProcessor to call when a match is found.
     */
    public void matchClassesWithAnnotation(final Class<?> annotation,
            final ClassAnnotationMatchProcessor classAnnotationMatchProcessor) {
        addClassMatcher(new ClassMatchProcessorWrapper() {
            @Override
            public void lookForMatches(final ScanResult scanResult, final LogNode log) {
                final String annotationName = getAnnotationName(annotation);
                for (final String classWithAnnotation : scanResult
                        .getNamesOfClassesWithAnnotation(annotationName)) {
                    LogNode subLog = null;
                    if (log != null) {
                        subLog = log.log(
                                "Matched class with annotation " + annotationName + ": " + classWithAnnotation);
                    }
                    try {
                        // Call classloader
                        final Class<?> cls = loadClassForMatchProcessor(classWithAnnotation, scanResult, log);
                        // Process match
                        if (cls != null) {
                            classAnnotationMatchProcessor.processMatch(cls);
                        }
                    } catch (final Throwable e) {
                        if (subLog != null) {
                            subLog.log("Exception while processing match for class " + classWithAnnotation, e);
                        }
                        scanResult.addMatchProcessorException(e);
                    }
                }
            }
        });
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided MethodAnnotationMatchProcessor if classes are found on the classpath that have one or more
     * methods with the specified annotation.
     * 
     * @param annotation
     *            The method annotation to match.
     * @param methodAnnotationMatchProcessor
     *            the MethodAnnotationMatchProcessor to call when a match is found.
     */
    public void matchClassesWithMethodAnnotation(final Class<? extends Annotation> annotation,
            final MethodAnnotationMatchProcessor methodAnnotationMatchProcessor) {
        addClassMatcher(new ClassMatchProcessorWrapper() {
            @Override
            public void lookForMatches(final ScanResult scanResult, final LogNode log) {
                final String annotationName = getAnnotationName(annotation);
                for (final String classWithMethodAnnotation : scanResult
                        .getNamesOfClassesWithMethodAnnotation(annotationName)) {
                    Class<?> cls = null;
                    try {
                        // Call classloader
                        cls = loadClassForMatchProcessor(classWithMethodAnnotation, scanResult, log);
                    } catch (final Throwable e) {
                        if (log != null) {
                            log.log("Exception while loading class " + classWithMethodAnnotation, e);
                        }
                        scanResult.addMatchProcessorException(e);
                        return;
                    }
                    if (cls != null) {
                        // Find methods with the specified annotation
                        for (final Constructor<?> ctor : cls.getDeclaredConstructors()) {
                            if ((ignoreMethodVisibility || (ctor.getModifiers() & Modifier.PUBLIC) != 0)
                                    && ctor.isAnnotationPresent(annotation)) {
                                LogNode subLog = null;
                                if (log != null) {
                                    subLog = log.log("Constructor matched method annotation " + annotationName
                                            + ": " + ctor);
                                }
                                try {
                                    // Process match
                                    methodAnnotationMatchProcessor.processMatch(cls, ctor);
                                } catch (final Throwable e) {
                                    if (subLog != null) {
                                        subLog.log("Exception while processing match for class "
                                                + classWithMethodAnnotation, e);
                                    }
                                    scanResult.addMatchProcessorException(e);
                                }
                            }
                        }
                        for (final Method method : cls.getDeclaredMethods()) {
                            if ((ignoreMethodVisibility || (method.getModifiers() & Modifier.PUBLIC) != 0)
                                    && method.isAnnotationPresent(annotation)) {
                                LogNode subLog = null;
                                if (log != null) {
                                    subLog = log.log("Matched method annotation " + annotationName + ": " + method);
                                }
                                try {
                                    // Process match
                                    methodAnnotationMatchProcessor.processMatch(cls, method);
                                } catch (final Throwable e) {
                                    if (subLog != null) {
                                        subLog.log("Exception while processing match for class "
                                                + classWithMethodAnnotation, e);
                                    }
                                    scanResult.addMatchProcessorException(e);
                                }
                            }
                        }
                    }
                }
            }
        });
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided FieldAnnotationMatchProcessor if classes are found on the classpath that have one or more
     * fields with the specified annotation.
     * 
     * @param annotation
     *            The method annotation to match.
     * @param fieldAnnotationMatchProcessor
     *            the FieldAnnotationMatchProcessor to call when a match is found.
     */
    public void matchClassesWithFieldAnnotation(final Class<? extends Annotation> annotation,
            final FieldAnnotationMatchProcessor fieldAnnotationMatchProcessor) {
        addClassMatcher(new ClassMatchProcessorWrapper() {
            @Override
            public void lookForMatches(final ScanResult scanResult, final LogNode log) {
                final String annotationName = getAnnotationName(annotation);
                for (final String classWithFieldAnnotation : scanResult
                        .getNamesOfClassesWithFieldAnnotation(annotationName)) {
                    Class<?> cls = null;
                    try {
                        // Call classloader
                        cls = loadClassForMatchProcessor(classWithFieldAnnotation, scanResult, log);
                    } catch (final Throwable e) {
                        if (log != null) {
                            log.log("Exception while loading class " + classWithFieldAnnotation, e);
                        }
                        scanResult.addMatchProcessorException(e);
                        return;
                    }
                    if (cls != null) {
                        // Find fields with the specified annotation
                        for (final Field field : ignoreFieldVisibility ? cls.getDeclaredFields()
                                : cls.getFields()) {
                            if (field.isAnnotationPresent(annotation)) {
                                LogNode subLog = null;
                                if (log != null) {
                                    subLog = log.log("Matched field annotation " + annotationName + ": " + field);
                                }
                                try {
                                    // Process match
                                    fieldAnnotationMatchProcessor.processMatch(cls, field);
                                } catch (final Throwable e) {
                                    if (subLog != null) {
                                        subLog.log("Exception while processing match for class "
                                                + classWithFieldAnnotation, e);
                                    }
                                    scanResult.addMatchProcessorException(e);
                                }
                            }
                        }
                    }
                }
            }
        });
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add a StaticFinalFieldMatchProcessor that should be called if a static final field with the given name is
     * encountered with a constant initializer value while reading a classfile header.
     */
    private void addStaticFinalFieldProcessor(final String className, final String fieldName,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        final String fullyQualifiedFieldName = className + "." + fieldName;
        if (fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors == null) {
            fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors = new MultiMapKeyToList<>();
            classNameToStaticFinalFieldsToMatch = new MultiMapKeyToSet<>();
        }
        fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors.put(fullyQualifiedFieldName,
                staticFinalFieldMatchProcessor);
        classNameToStaticFinalFieldsToMatch.put(className, fieldName);
    }

    /**
     * Calls the given StaticFinalFieldMatchProcessor if classes are found on the classpath that contain static
     * final fields that match one of a set of fully-qualified field names, e.g.
     * "com.package.ClassName.STATIC_FIELD_NAME".
     * 
     * Field values are obtained from the constant pool in classfiles, not from a loaded class using reflection.
     * This allows you to detect changes to the classpath and then run another scan that picks up the new values of
     * selected static constants without reloading the class. (Class reloading is fraught with issues, see:
     * http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html )
     * 
     * Note: Only static final fields with constant-valued literals are matched, not fields with initializer values
     * that are the result of an expression or reference, except for cases where the compiler is able to simplify an
     * expression into a single constant at compiletime, such as in the case of string concatenation.
     * 
     * Note that the visibility of the fields is not checked if ignoreFieldVisibility() was called before scan().
     * 
     * @param fullyQualifiedStaticFinalFieldNames
     *            The set of fully-qualified static field names to match.
     * @param staticFinalFieldMatchProcessor
     *            the StaticFinalFieldMatchProcessor to call when a match is found.
     */
    public void matchStaticFinalFieldNames(final Set<String> fullyQualifiedStaticFinalFieldNames,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        for (final String fullyQualifiedFieldName : fullyQualifiedStaticFinalFieldNames) {
            final int lastDotIdx = fullyQualifiedFieldName.lastIndexOf('.');
            if (lastDotIdx > 0) {
                final String className = fullyQualifiedFieldName.substring(0, lastDotIdx);
                final String fieldName = fullyQualifiedFieldName.substring(lastDotIdx + 1);
                addStaticFinalFieldProcessor(className, fieldName, staticFinalFieldMatchProcessor);
            }
        }
    }

    /**
     * Calls the given StaticFinalFieldMatchProcessor if classes are found on the classpath that contain static
     * final fields that match a fully-qualified field name, e.g. "com.package.ClassName.STATIC_FIELD_NAME".
     * 
     * Field values are obtained from the constant pool in classfiles, *not* from a loaded class using reflection.
     * This allows you to detect changes to the classpath and then run another scan that picks up the new values of
     * selected static constants without reloading the class. (Class reloading is fraught with issues, see:
     * http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html )
     * 
     * Note: Only static final fields with constant-valued literals are matched, not fields with initializer values
     * that are the result of an expression or reference, except for cases where the compiler is able to simplify an
     * expression into a single constant at compiletime, such as in the case of string concatenation.
     * 
     * Note that the visibility of the fields is not checked if ignoreFieldVisibility() was called before scan().
     * 
     * @param fullyQualifiedStaticFinalFieldName
     *            The fully-qualified static field name to match
     * @param staticFinalFieldMatchProcessor
     *            the StaticFinalFieldMatchProcessor to call when a match is found.
     */
    public void matchStaticFinalFieldNames(final String fullyQualifiedStaticFinalFieldName,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        final HashSet<String> fullyQualifiedStaticFinalFieldNamesSet = new HashSet<>();
        fullyQualifiedStaticFinalFieldNamesSet.add(fullyQualifiedStaticFinalFieldName);
        matchStaticFinalFieldNames(fullyQualifiedStaticFinalFieldNamesSet, staticFinalFieldMatchProcessor);
    }

    /**
     * Calls the given StaticFinalFieldMatchProcessor if classes are found on the classpath that contain static
     * final fields that match one of a list of fully-qualified field names, e.g.
     * "com.package.ClassName.STATIC_FIELD_NAME".
     * 
     * Field values are obtained from the constant pool in classfiles, *not* from a loaded class using reflection.
     * This allows you to detect changes to the classpath and then run another scan that picks up the new values of
     * selected static constants without reloading the class. (Class reloading is fraught with issues, see:
     * http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html )
     * 
     * Note: Only static final fields with constant-valued literals are matched, not fields with initializer values
     * that are the result of an expression or reference, except for cases where the compiler is able to simplify an
     * expression into a single constant at compiletime, such as in the case of string concatenation.
     * 
     * Note that the visibility of the fields is not checked if ignoreFieldVisibility() was called before scan().
     * 
     * @param fullyQualifiedStaticFinalFieldNames
     *            The list of fully-qualified static field names to match.
     * @param staticFinalFieldMatchProcessor
     *            the StaticFinalFieldMatchProcessor to call when a match is found.
     */
    public void matchStaticFinalFieldNames(final String[] fullyQualifiedStaticFinalFieldNames,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        final HashSet<String> fullyQualifiedStaticFinalFieldNamesSet = new HashSet<>();
        for (final String fullyQualifiedFieldName : fullyQualifiedStaticFinalFieldNames) {
            fullyQualifiedStaticFinalFieldNamesSet.add(fullyQualifiedFieldName);
        }
        matchStaticFinalFieldNames(fullyQualifiedStaticFinalFieldNamesSet, staticFinalFieldMatchProcessor);
    }

    // -------------------------------------------------------------------------------------------------------------

    List<FileMatchProcessorWrapper> getFileMatchProcessorWrappers() {
        return fileMatchProcessorWrappers;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath with the given regexp pattern in their
     * path.
     * 
     * @param pathRegexp
     *            The regexp to match, e.g. "app/templates/.*\\.html"
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     */
    public void matchFilenamePattern(final String pathRegexp, final FileMatchProcessorAny fileMatchProcessor) {
        fileMatchProcessorWrappers.add(new FileMatchProcessorWrapper(new FilePathTester() {
            private final Pattern pattern = Pattern.compile(pathRegexp);

            @Override
            public boolean filePathMatches(final File classpathElt, final String relativePathStr,
                    final LogNode log) {
                final boolean matched = pattern.matcher(relativePathStr).matches();
                if (matched && log != null) {
                    log.log("File " + relativePathStr + " matched filename pattern " + pathRegexp);
                }
                return matched;
            }
        }, fileMatchProcessor));
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that exactly match the given relative
     * path.
     * 
     * @param relativePathToMatch
     *            The complete path to match relative to the classpath entry, e.g.
     *            "app/templates/WidgetTemplate.html"
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     */
    public void matchFilenamePath(final String relativePathToMatch,
            final FileMatchProcessorAny fileMatchProcessor) {
        fileMatchProcessorWrappers.add(new FileMatchProcessorWrapper(new FilePathTester() {
            @Override
            public boolean filePathMatches(final File classpathElt, final String relativePathStr,
                    final LogNode log) {
                final boolean matched = relativePathStr.equals(relativePathToMatch);
                if (matched && log != null) {
                    log.log("Matched filename path " + relativePathToMatch);
                }
                return matched;
            }
        }, fileMatchProcessor));
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that exactly match the given path
     * leafname.
     * 
     * @param pathLeafToMatch
     *            The complete path leaf to match, e.g. "WidgetTemplate.html"
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     */
    public void matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchProcessorAny fileMatchProcessor) {
        fileMatchProcessorWrappers.add(new FileMatchProcessorWrapper(new FilePathTester() {
            private final String leafToMatch = pathLeafToMatch.substring(pathLeafToMatch.lastIndexOf('/') + 1);

            @Override
            public boolean filePathMatches(final File classpathElt, final String relativePathStr,
                    final LogNode log) {
                final String relativePathLeaf = relativePathStr.substring(relativePathStr.lastIndexOf('/') + 1);
                final boolean matched = relativePathLeaf.equals(leafToMatch);
                if (matched && log != null) {
                    log.log("File " + relativePathStr + " matched path leaf " + pathLeafToMatch);
                }
                return matched;
            }
        }, fileMatchProcessor));
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that have the given file extension.
     * 
     * @param extensionToMatch
     *            The extension to match, e.g. "html" matches "WidgetTemplate.html" and "WIDGET.HTML".
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     */
    public void matchFilenameExtension(final String extensionToMatch,
            final FileMatchProcessorAny fileMatchProcessor) {
        fileMatchProcessorWrappers.add(new FileMatchProcessorWrapper(new FilePathTester() {
            final int extLen = extensionToMatch.length();

            @Override
            public boolean filePathMatches(final File classpathElt, final String relativePath, final LogNode log) {
                final int relativePathLen = relativePath.length();
                final int extIdx = relativePathLen - extLen;
                final boolean matched = relativePathLen > extLen + 1 && relativePath.charAt(extIdx - 1) == '.'
                        && relativePath.regionMatches(true, extIdx, extensionToMatch, 0, extLen);
                if (matched && log != null) {
                    log.log("File " + relativePath + " matched extension ." + extensionToMatch);
                }
                return matched;
            }
        }, fileMatchProcessor));
    }
}
