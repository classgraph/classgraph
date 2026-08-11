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
package nonapi.io.github.classgraph.classpath;

import java.io.File;
import java.io.IOError;
import java.lang.reflect.Array;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import org.jspecify.annotations.Nullable;

/** A class to find the unique ordered classpath elements. */
public class ClasspathOrder {
    /** The scan spec. */
    private final ScanSpec scanSpec;

    /** The reflection utils instance. */
    public final ReflectionUtils reflectionUtils;

    /** Unique classpath entries. */
    private final Set<String> classpathEntryUniqueResolvedPaths = new HashSet<>();

    /** The classpath order. Keys are instances of {@link String} or {@link URL}. */
    private final List<ClasspathEntry> order = new ArrayList<>();

    /**
     * Match URL schemes (must consist of at least two chars, otherwise this is Windows drive letter).
     */
    private static final Pattern schemeMatcher = Pattern.compile("^[a-zA-Z][a-zA-Z+\\-.]+:");

    /**
     * The package root prefixes of the {@code ClassLoaderHandler} whose {@code findClasspathOrder} method is
     * currently being called, or the default prefixes if classpath entries are not currently being obtained from a
     * {@code ClassLoaderHandler} (e.g. for {@code java.class.path} entries, or an overridden classpath).
     */
    private String[] currPackageRootPrefixes = ClassLoaderHandlerRegistry.DEFAULT_PACKAGE_ROOT_PREFIXES;

    /**
     * True once the Equinox system bundles have been added to this classpath order.
     */
    private boolean addedEquinoxSystemBundles;

    /**
     * Test whether the Equinox system bundles still need to be added to the classpath order, and if so, atomically
     * record that they are being added, so that they are only added once.
     *
     * <p>
     * All Equinox bundles yield the same system bundles, so they only need to be read from the first Equinox
     * classloader encountered. This flag is held here, on a per-scan object, rather than in a static field of the
     * {@code ClassLoaderHandler}: a single handler instance is shared between all scans, so a static flag would
     * stay set after the first scan, and every subsequent scan in the same JVM would silently omit the system
     * bundles from the classpath.
     *
     * @return true the first time this method is called for a given scan, false every time thereafter.
     */
    public synchronized boolean tryAddEquinoxSystemBundles() {
        if (addedEquinoxSystemBundles) {
            return false;
        }
        addedEquinoxSystemBundles = true;
        return true;
    }

    /**
     * A classpath element and the string form of the {@link ClassLoader} it was obtained from.
     */
    public static class ClasspathEntry {
        /**
         * The classpath entry object (a {@link String} path, {@link Path}, {@link URL} or {@link URI}).
         */
        public final Object classpathEntryObj;

        /**
         * The string form of the classloader the classpath element was obtained from, or null if unknown. Only the
         * string is kept, not the classloader itself, so that finding the classpath does not keep a classloader
         * alive.
         */
        private final @Nullable String classLoaderStr;

        /**
         * The automatic package root prefixes to look for within this classpath element, as declared by the
         * {@code ClassLoaderHandler} that found it.
         */
        public final String[] packageRootPrefixes;

        /**
         * Constructor.
         *
         * @param classpathEntryObj
         *            the classpath entry object (a {@link String} or {@link URL} or {@link Path}).
         * @param classLoader
         *            the classloader the classpath element was obtained from.
         * @param packageRootPrefixes
         *            the automatic package root prefixes to look for within this classpath element.
         */
        public ClasspathEntry(final Object classpathEntryObj, final @Nullable ClassLoader classLoader,
                final String[] packageRootPrefixes) {
            this.classpathEntryObj = classpathEntryObj;
            this.classLoaderStr = Objects.toString(classLoader, null);
            this.packageRootPrefixes = packageRootPrefixes;
        }

        /**
         * Get the string form of the classloader the classpath element was obtained from.
         *
         * @return the string form of the classloader, or null if it was unknown.
         */
        public @Nullable String getClassLoaderString() {
            return classLoaderStr;
        }

        @Override
        public int hashCode() {
            return classpathEntryObj.hashCode();
        }

        @Override
        public boolean equals(final @Nullable Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof final ClasspathEntry other)) {
                return false;
            }
            return this.classpathEntryObj.equals(other.classpathEntryObj);
        }

        @Override
        public String toString() {
            return classpathEntryObj + " [" + classLoaderStr + "]";
        }
    }

    /**
     * Constructor.
     *
     * @param scanSpec
     *            the scan spec
     * @param reflectionUtils
     *            the reflection utils instance
     */
    ClasspathOrder(final ScanSpec scanSpec, final ReflectionUtils reflectionUtils) {
        this.scanSpec = scanSpec;
        this.reflectionUtils = reflectionUtils;
    }

    /**
     * Get the order of classpath elements, uniquified and in order.
     *
     * @return the classpath order.
     */
    public List<ClasspathEntry> getOrder() {
        return order;
    }

    /**
     * Get the unique classpath entry strings.
     *
     * @return the classpath entry strings.
     */
    public Set<String> getClasspathEntryUniqueResolvedPaths() {
        return classpathEntryUniqueResolvedPaths;
    }

    /**
     * Set the automatic package root prefixes to record for subsequently-added classpath entries. Called before and
     * after invoking the {@code findClasspathOrder} method of a {@code ClassLoaderHandler}, so that each classpath
     * entry records the package roots of the classloader it was obtained from.
     *
     * @param packageRootPrefixes
     *            the package root prefixes, or null to reset to the default prefixes.
     */
    public void setPackageRootPrefixes(final String @Nullable [] packageRootPrefixes) {
        this.currPackageRootPrefixes = packageRootPrefixes == null
                ? ClassLoaderHandlerRegistry.DEFAULT_PACKAGE_ROOT_PREFIXES
                : packageRootPrefixes;
    }

    /**
     * Convert a resolved classpath element path back into a {@link URL}, so that it can be tested against the
     * user's {@link URL} filters.
     *
     * @param classpathElementPath
     *            the resolved classpath element path
     * @return the {@link URL} of the classpath element, or null if the path could not be converted to a {@link URL}
     */
    private static @Nullable URL toURL(final String classpathElementPath) {
        try {
            final var nestedPathIdx = classpathElementPath.indexOf("!/");
            if (nestedPathIdx < 0) {
                return new File(classpathElementPath).toURI().toURL();
            }
            // A nested path "outer.jar!/inner" becomes the jar URL "jar:file:/outer.jar!/inner"
            return new URL("jar:" + new File(classpathElementPath.substring(0, nestedPathIdx)).toURI()
                    + classpathElementPath.substring(nestedPathIdx));
        } catch (final MalformedURLException | IllegalArgumentException | IOError | SecurityException e) {
            return null;
        }
    }

    /**
     * Test to see if a classpath element has been filtered out by the user.
     *
     * @param classpathElementURL
     *            the classpath element URL, or null if the classpath element was not given as a URL
     * @param classpathElementPath
     *            the classpath element path
     * @return true, if not filtered out
     */
    private boolean filter(final @Nullable URL classpathElementURL, final @Nullable String classpathElementPath) {
        if (scanSpec.classpathElementURLFilters != null) {
            // FastPathResolver strips the scheme from "file:" and "jar:file:" classpath elements, so for those the
            // URL has to be reconstituted from the resolved path before the URL filters can be applied to it
            final var url = classpathElementURL != null ? classpathElementURL
                    : classpathElementPath == null ? null : toURL(classpathElementPath);
            if (url != null) {
                for (final Predicate<URL> urlFilter : scanSpec.classpathElementURLFilters) {
                    if (!urlFilter.test(url)) {
                        return false;
                    }
                }
            }
        }
        if (classpathElementPath != null && scanSpec.classpathElementPathFilters != null) {
            for (final Predicate<String> pathFilter : scanSpec.classpathElementPathFilters) {
                if (!pathFilter.test(classpathElementPath)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Add a system classpath entry.
     *
     * @param pathEntry
     *            the system classpath entry -- the path string should already have been run through
     *            FastPathResolver.resolve(FileUtils.currDirPath(), path)
     * @param classLoader
     *            the classloader, or null if unknown
     * @return true, if added and unique
     */
    boolean addSystemClasspathEntry(final String pathEntry, final @Nullable ClassLoader classLoader) {
        if (classpathEntryUniqueResolvedPaths.add(pathEntry)) {
            order.add(new ClasspathEntry(pathEntry, classLoader, currPackageRootPrefixes));
            return true;
        }
        return false;
    }

    /**
     * Add a classpath entry.
     *
     * @param pathElement
     *            the {@link String} path, {@link File}, {@link Path}, {@link URL} or {@link URI} of the classpath
     *            element.
     * @param pathElementStr
     *            the path element in string format
     * @param classLoader
     *            the classloader
     * @param scanSpec
     *            the scan spec
     * @return true, if added and unique
     */
    private boolean addClasspathEntry(final Object pathElement, final String pathElementStr,
            final @Nullable ClassLoader classLoader, final ScanSpec scanSpec) {
        // Check if classpath element path ends with an automatic package root. If so, strip it off to eliminate
        // duplication, since automatic package roots are detected automatically (#435)
        var pathElementStrWithoutSuffix = pathElementStr;
        var hasSuffix = false;
        for (final String packageRootPrefix : currPackageRootPrefixes) {
            // Convert package root prefix to a suffix, e.g. "BOOT-INF/classes/" -> "!/BOOT-INF/classes"
            final var suffix = "!/" + packageRootPrefix.substring(0, packageRootPrefix.length() - 1);
            if (pathElementStr.endsWith(suffix)) {
                // Strip off automatic package root suffix
                pathElementStrWithoutSuffix = pathElementStr.substring(0,
                        pathElementStr.length() - suffix.length());
                hasSuffix = true;
                break;
            }
        }
        if (pathElement instanceof URL || pathElement instanceof URI || pathElement instanceof Path
                || pathElement instanceof File) {
            var pathElementWithoutSuffix = pathElement;
            if (hasSuffix) {
                try {
                    pathElementWithoutSuffix = pathElement instanceof URL ? new URL(pathElementStrWithoutSuffix)
                            : pathElement instanceof URI ? new URI(pathElementStrWithoutSuffix)
                                    : pathElement instanceof Path ? Path.of(pathElementStrWithoutSuffix)
                                            // For File, just use path string
                                            : pathElementStrWithoutSuffix;
                } catch (MalformedURLException | URISyntaxException | InvalidPathException e) {
                    try {
                        pathElementWithoutSuffix = pathElement instanceof URL
                                ? new URL("file:" + pathElementStrWithoutSuffix)
                                : pathElement instanceof URI ? new URI("file:" + pathElementStrWithoutSuffix)
                                        : pathElementStrWithoutSuffix;
                    } catch (MalformedURLException | URISyntaxException | InvalidPathException e2) {
                        return false;
                    }
                }
            }
            // Deduplicate classpath elements
            if (classpathEntryUniqueResolvedPaths.add(pathElementStrWithoutSuffix)) {
                // Record classpath element in classpath order
                order.add(new ClasspathEntry(pathElementWithoutSuffix, classLoader, currPackageRootPrefixes));
                return true;
            }
        } else {
            final var pathElementStrResolved = FastPathResolver.resolve(FileUtils.currDirPath(),
                    pathElementStrWithoutSuffix);
            if (scanSpec.overrideClasspath == null
                    && SystemJarFinder.getJreLibOrExtJars().contains(pathElementStrResolved)) {
                // JRE lib and ext jars are handled separately, so reject them as duplicates if they are returned by
                // a system classloader
                return false;
            }
            if (classpathEntryUniqueResolvedPaths.add(pathElementStrResolved)) {
                order.add(new ClasspathEntry(pathElementStrResolved, classLoader, currPackageRootPrefixes));
                return true;
            }
        }
        return false;
    }

    /**
     * Describe a classpath element for the log, showing the resolved form of the path if it differs from the form
     * the classpath element was found in.
     *
     * @param pathElementStr
     *            the path of the classpath element.
     * @param pathElementStrResolved
     *            the resolved path of the classpath element.
     * @return the description of the classpath element.
     */
    private static String describe(final String pathElementStr, final String pathElementStrResolved) {
        return pathElementStr
                + (pathElementStr.equals(pathElementStrResolved) ? "" : " -> " + pathElementStrResolved);
    }

    /**
     * Test a classpath element against the user's classpath element filters, logging it if it is filtered out.
     *
     * @param pathElementURL
     *            the classpath element URL, or null if the classpath element was not given as a URL
     * @param pathElementStr
     *            the path of the classpath element.
     * @param pathElementStrResolved
     *            the resolved path of the classpath element.
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true if the classpath element passes the filters.
     */
    private boolean passesFilters(final @Nullable URL pathElementURL, final String pathElementStr,
            final String pathElementStrResolved, final @Nullable LogNode log) {
        // The path is tested in both the form it was found in and its resolved form, since a filter may have been
        // written to match either
        if (filter(pathElementURL, pathElementStr) && (pathElementStrResolved.equals(pathElementStr)
                || filter(pathElementURL, pathElementStrResolved))) {
            return true;
        }
        if (log != null) {
            log.log("Classpath element did not match filter criterion, skipping: "
                    + describe(pathElementStr, pathElementStrResolved));
        }
        return false;
    }

    /**
     * Add a classpath entry, and log whether it was added or was a duplicate of an entry already found.
     *
     * @param pathElement
     *            the {@link String} path, {@link File}, {@link Path}, {@link URL} or {@link URI} of the classpath
     *            element.
     * @param pathElementStr
     *            the path of the classpath element.
     * @param pathElementStrResolved
     *            the resolved path of the classpath element.
     * @param classLoader
     *            the classloader
     * @param scanSpec
     *            the scan spec
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true, if added and unique
     */
    private boolean addClasspathEntryAndLog(final Object pathElement, final String pathElementStr,
            final String pathElementStrResolved, final @Nullable ClassLoader classLoader, final ScanSpec scanSpec,
            final @Nullable LogNode log) {
        final var added = addClasspathEntry(pathElement, pathElementStrResolved, classLoader, scanSpec);
        if (log != null) {
            log.log((added ? "Found classpath element: " : "Ignoring duplicate classpath element: ")
                    + describe(pathElementStr, pathElementStrResolved));
        }
        return added;
    }

    /**
     * Get the string form of a classpath element object.
     *
     * @param pathElement
     *            the classpath element object.
     * @return the string form of the classpath element.
     */
    private static String toPathElementStr(final Object pathElement) {
        if (pathElement instanceof final Path pathElementPath) {
            try {
                // Path objects have to be converted to URIs before calling .toString(), otherwise the scheme of a
                // path on a non-default filesystem is dropped. A local path comes back in the "file:///path"
                // spelling (or "file:///C:/x/y" on Windows), which FastPathResolver turns back into a plain path
                return pathElementPath.toUri().toString();
            } catch (final IOError | SecurityException e) {
                // Fall back to the string form of the Path
            }
        }
        return pathElement.toString();
    }

    /**
     * Convert a classpath element whose path has a URL scheme into a {@link URL}.
     *
     * @param pathElement
     *            the classpath element object.
     * @param pathElementStr
     *            the path of the classpath element.
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return the {@link URL} of the classpath element, or null if it could not be converted to a {@link URL}.
     */
    private static @Nullable URL toClasspathElementURL(final Object pathElement, final String pathElementStr,
            final @Nullable LogNode log) {
        URL pathElementURL = null;
        try {
            pathElementURL = pathElement instanceof final URL url ? url
                    : pathElement instanceof final URI uri ? uri.toURL()
                            : pathElement instanceof final Path path ? path.toUri().toURL()
                                    : pathElement instanceof final File file ? file.toURI().toURL() : null;
        } catch (final MalformedURLException | IllegalArgumentException | IOError | SecurityException e) {
            // Fall through
        }
        if (pathElementURL == null) {
            // Escape percentage characters in URLs (#255)
            final var urlStr = pathElementStr.replace("%", "%25");
            try {
                pathElementURL = new URL(urlStr);
            } catch (final MalformedURLException e) {
                try {
                    pathElementURL = new File(urlStr).toURI().toURL();
                } catch (final MalformedURLException | IllegalArgumentException | IOError | SecurityException e1) {
                    // Final fallback -- try just using the raw string as a URL
                    try {
                        pathElementURL = new URL(pathElementStr);
                    } catch (final MalformedURLException e2) {
                        // Fall through
                    }
                }
            }
        }
        if (pathElementURL == null && log != null) {
            log.log("Failed to convert classpath element to URL: " + pathElement);
        }
        return pathElementURL;
    }

    /**
     * Add every file in a directory to the classpath, for a wildcarded classpath entry of the form {@code
     *
    <dir>
     * /*} (allowable for local classpaths as of JDK 6).
     *
     * @param baseDirPath
     *            the path of the directory, i.e. the classpath entry with the {@code "/*"} suffix removed.
     * @param classLoader
     *            the ClassLoader that this classpath element was obtained from.
     * @param scanSpec
     *            the scan spec
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true if the contents of the directory could be listed.
     */
    private boolean addWildcardedDirEntries(final String baseDirPath, final @Nullable ClassLoader classLoader,
            final ScanSpec scanSpec, final @Nullable LogNode log) {
        // A wildcarded classpath entry is only ever reached as a path string, never as a URL, so there is no URL to
        // apply the user's URL filters to
        final var baseDirPathResolved = FastPathResolver.resolve(FileUtils.currDirPath(), baseDirPath);
        if (!passesFilters(/* pathElementURL = */ null, baseDirPath, baseDirPathResolved, log)) {
            return false;
        }

        // Check the path before the "/*" suffix is a directory
        final var baseDir = new File(baseDirPathResolved);
        if (!baseDir.exists()) {
            if (log != null) {
                log.log("Directory does not exist for wildcard classpath element: " + baseDirPath);
            }
            return false;
        }
        if (!FileUtils.canRead(baseDir)) {
            if (log != null) {
                log.log("Cannot read directory for wildcard classpath element: " + baseDirPath);
            }
            return false;
        }
        if (!baseDir.isDirectory()) {
            if (log != null) {
                log.log("Wildcard is appended to something other than a directory: " + baseDirPath);
            }
            return false;
        }

        // Add all elements in the requested directory to the classpath
        final var dirLog = log == null ? null
                : log.log("Adding classpath elements from wildcarded directory: " + baseDirPath);
        final var baseDirFiles = baseDir.listFiles();
        if (baseDirFiles == null) {
            return false;
        }
        for (final File fileInDir : baseDirFiles) {
            final var name = fileInDir.getName();
            if (!".".equals(name) && !"..".equals(name)) {
                // Add each directory entry as a classpath element
                final var fileInDirPath = fileInDir.getPath();
                final var fileInDirPathResolved = FastPathResolver.resolve(FileUtils.currDirPath(), fileInDirPath);
                addClasspathEntryAndLog(fileInDirPathResolved, fileInDirPath, fileInDirPathResolved, classLoader,
                        scanSpec, dirLog);
            }
        }
        return true;
    }

    /**
     * Add a classpath element relative to a base file. May be called by a ClassLoaderHandler to add classpath
     * elements that it knows about. ClassLoaders will be called in order.
     *
     * @param pathElement
     *            the {@link String} path, {@link URL} or {@link URI} of the classpath element, or some object whose
     *            {@link Object#toString()} method can be called to obtain the classpath element.
     * @param classLoader
     *            the ClassLoader that this classpath element was obtained from.
     * @param scanSpec
     *            the scan spec
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathElement is not null, empty, nonexistent, or filtered out
     *         by user-specified criteria, otherwise return false.
     */
    public boolean addClasspathEntry(final @Nullable Object pathElement, final @Nullable ClassLoader classLoader,
            final ScanSpec scanSpec, final @Nullable LogNode log) {
        if (pathElement == null) {
            return false;
        }
        var pathElementStr = toPathElementStr(pathElement);
        if (pathElementStr.isEmpty()) {
            // Check for an empty path element before resolving it, not after: resolving an empty path against the
            // current directory yields the current directory, which would silently turn an empty classpath entry
            // into a scan of the whole directory tree below the current directory
            return false;
        }
        pathElementStr = FastPathResolver.resolve(FileUtils.currDirPath(), pathElementStr);
        URL pathElementURL = null;
        var hasWildcardSuffix = false;
        if (pathElementStr.endsWith("/*") || pathElementStr.endsWith("\\*")) {
            hasWildcardSuffix = true;
            pathElementStr = pathElementStr.substring(0, pathElementStr.length() - 2);
            // Leave pathElementURL null, so that wildcards can be handled below
        } else if ("*".equals(pathElementStr)) {
            hasWildcardSuffix = true;
            pathElementStr = "";
            // Leave pathElementURL null, so that wildcards can be handled below
        } else if (schemeMatcher.matcher(pathElementStr).find()) {
            // Path element string is a URL with a scheme other than `[jar:]file:`, so the URL has to actually be
            // parsed, since the scheme may be a custom scheme
            pathElementURL = toClasspathElementURL(pathElement, pathElementStr, log);
        }

        if (pathElementURL != null || pathElement instanceof URI || pathElement instanceof File
                || pathElement instanceof Path) {
            if (!passesFilters(pathElementURL, pathElementStr, pathElementStr, log)) {
                return false;
            }
            // For a URL object, or a URI or Path that was parsed into a URL, use the URL (so that URL scheme
            // handling can be undertaken later); for a File object, use the resolved path string; otherwise use the
            // object itself
            final var classpathElementObj = pathElement instanceof File ? pathElementStr
                    : pathElementURL != null ? pathElementURL : pathElement;
            return addClasspathEntryAndLog(classpathElementObj, pathElementStr, pathElementStr, classLoader,
                    scanSpec, log);
        }

        if (hasWildcardSuffix) {
            return addWildcardedDirEntries(pathElementStr, classLoader, scanSpec, log);
        }

        // Non-wildcarded (standard) classpath element
        if (pathElementStr.indexOf('*') >= 0) {
            if (log != null) {
                log.log("Wildcard classpath elements can only end with a suffix of \"/*\", "
                        + "can't use globs elsewhere in the path: " + pathElementStr);
            }
            return false;
        }
        final var pathElementResolved = FastPathResolver.resolve(FileUtils.currDirPath(), pathElementStr);
        if (!passesFilters(pathElementURL, pathElementStr, pathElementResolved, log)) {
            return false;
        }
        if (pathElementResolved.startsWith("//")) {
            // Handle Windows UNC paths (#705). File supports UNC paths directly:
            // https://wiki.eclipse.org/Eclipse/UNC_Paths#Programming_with_UNC_paths
            try {
                return addClasspathEntryAndLog(new File(pathElementResolved), pathElementStr, pathElementResolved,
                        classLoader, scanSpec, log);
            } catch (final Exception e) {
                // Fall through, and add the path as a string rather than as a File
            }
        }
        return addClasspathEntryAndLog(pathElementResolved, pathElementStr, pathElementResolved, classLoader,
                scanSpec, log);
    }

    /**
     * Add classpath entries, separated by the system path separator character.
     *
     * @param overrideClasspath
     *            a list of delimited path {@link String}, {@link URL}, {@link URI} or {@link File} objects.
     * @param classLoader
     *            the ClassLoader that this classpath was obtained from.
     * @param scanSpec
     *            the scan spec
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    public boolean addClasspathEntries(final @Nullable List<Object> overrideClasspath,
            final @Nullable ClassLoader classLoader, final ScanSpec scanSpec, final @Nullable LogNode log) {
        if (overrideClasspath == null || overrideClasspath.isEmpty()) {
            return false;
        } else {
            for (final Object pathElement : overrideClasspath) {
                addClasspathEntry(pathElement, classLoader, scanSpec, log);
            }
            return true;
        }
    }

    /**
     * Add classpath entries, separated by the system path separator character.
     *
     * @param pathStr
     *            the delimited string of URLs or paths of the classpath.
     * @param classLoader
     *            the ClassLoader that this classpath was obtained from.
     * @param scanSpec
     *            the scan spec
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    public boolean addClasspathPathStr(final @Nullable String pathStr, final @Nullable ClassLoader classLoader,
            final ScanSpec scanSpec, final @Nullable LogNode log) {
        if (pathStr == null || pathStr.isEmpty()) {
            return false;
        } else {
            final var parts = JarUtils.smartPathSplit(pathStr, scanSpec);
            if (parts.length == 0) {
                return false;
            } else {
                for (final String pathElement : parts) {
                    addClasspathEntry(pathElement, classLoader, scanSpec, log);
                }
                return true;
            }
        }
    }

    /**
     * Add classpath entries from an object obtained from reflection. The object may be a {@link URL}, a
     * {@link URI}, a {@link File}, a {@link Path} or a {@link String} (containing a single classpath element path,
     * or several paths separated with File.pathSeparator), a List or other Iterable, or an array object. In the
     * case of Iterables and arrays, the elements may be any type whose {@code toString()} method returns a path or
     * URL string (including the {@code URL} and {@code Path} types).
     *
     * @param pathObject
     *            the object containing a classpath string or strings.
     * @param classLoader
     *            the ClassLoader that this classpath was obtained from.
     * @param scanSpec
     *            the scan spec
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    public boolean addClasspathEntryObject(final @Nullable Object pathObject,
            final @Nullable ClassLoader classLoader, final ScanSpec scanSpec, final @Nullable LogNode log) {
        var valid = false;
        if (pathObject != null) {
            if (pathObject instanceof URL || pathObject instanceof URI || pathObject instanceof Path
                    || pathObject instanceof File) {
                valid |= addClasspathEntry(pathObject, classLoader, scanSpec, log);
            } else if (pathObject instanceof final Iterable<?> iterable) {
                for (final Object elt : iterable) {
                    valid |= addClasspathEntryObject(elt, classLoader, scanSpec, log);
                }
            } else {
                final Class<?> valClass = pathObject.getClass();
                if (valClass.isArray()) {
                    for (int j = 0, n = Array.getLength(pathObject); j < n; j++) {
                        final var elt = Array.get(pathObject, j);
                        valid |= addClasspathEntryObject(elt, classLoader, scanSpec, log);
                    }
                } else {
                    // Try simply calling toString() as a final fallback, to handle String objects, or to try to
                    // handle anything else
                    valid |= addClasspathPathStr(pathObject.toString(), classLoader, scanSpec, log);
                }
            }
        }
        return valid;
    }
}
