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
 * Copyright (c) 2026 Luke Hutchison
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
package io.github.classgraph.classpath.internal;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.lang.reflect.Array;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.path.PathList;
import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClasspathOrder;
import org.jspecify.annotations.Nullable;

/** A class to find the unique ordered classpath elements. */
public class ClasspathOrderBuilder implements ClasspathOrder {
    /** The scan spec. */
    private final ClasspathSpec classpathSpec;

    /** The {@link Entry#location} of every classpath element found so far, which is what deduplicates them. */
    private final Set<String> classpathEntryUniqueLocations = new HashSet<>();

    /** The classpath order. Keys are instances of {@link String} or {@link URL}. */
    private final List<Entry> order = new ArrayList<>();

    /**
     * Match URL schemes (must consist of at least two chars, otherwise this is Windows drive letter).
     */
    private static final Pattern schemeMatcher = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+\\-.]+:");

    /**
     * The package root prefixes of the {@code ClassLoaderHandler} whose {@code findClasspathOrder} method is
     * currently being called, or no prefixes at all if classpath entries are not currently being obtained from a
     * {@code ClassLoaderHandler} (e.g. for {@code java.class.path} entries, or an overridden classpath).
     */
    private List<String> currPackageRootPrefixes = ClassLoaderHandler.NO_PACKAGE_ROOT_PREFIXES;

    /**
     * The lib dirs of the {@code ClassLoaderHandler} whose {@code findClasspathOrder} method is currently being
     * called, or no lib dirs at all if classpath entries are not currently being obtained from a
     * {@code ClassLoaderHandler} (e.g. for {@code java.class.path} entries, or an overridden classpath).
     */
    private List<String> currLibDirPrefixes = ClassLoaderHandler.NO_LIB_DIR_PREFIXES;

    /** The keys that {@link #claimOncePerScan(String)} has already been called with. */
    private final Set<String> claimedOncePerScan = new HashSet<>();

    /**
     * A classpath element and the string form of the {@link ClassLoader} it was obtained from.
     */
    public static class Entry {
        /**
         * The classpath entry object (a {@link String} path, {@link Path}, {@link URL} or {@link URI}).
         */
        public final Object classpathEntryObj;

        /**
         * The location of the classpath element: the canonical path of a directory or jarfile of a filesystem, with
         * {@code '/'} as the separator on every platform, or the URL or URI of anything that is not reached through
         * a filesystem. This is the form the classpath element is reported in, and the form it is deduplicated by,
         * so a file reached through two different paths is reported once, under the name it is stored under. It is
         * kept alongside the classpath entry object because the object's {@link Object#toString()} is not in that
         * form: the {@link Path} of a local file spells the path with the platform's separator, so on Windows it
         * uses backslashes, and a {@link URI} keeps its scheme.
         */
        public final String location;

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
        public final List<String> packageRootPrefixes;

        /**
         * The lib dirs whose jarfiles should be added to the classpath, within this classpath element, as declared
         * by the {@code ClassLoaderHandler} that found it.
         */
        public final List<String> libDirPrefixes;

        /**
         * Constructor.
         *
         * @param classpathEntryObj
         *            the classpath entry object (a {@link String} or {@link URL} or {@link Path}).
         * @param location
         *            the location of the classpath element.
         * @param classLoader
         *            the classloader the classpath element was obtained from.
         * @param packageRootPrefixes
         *            the automatic package root prefixes to look for within this classpath element.
         * @param libDirPrefixes
         *            the lib dirs whose jarfiles should be added to the classpath, within this classpath element.
         */
        public Entry(final Object classpathEntryObj, final String location, final @Nullable ClassLoader classLoader,
                final List<String> packageRootPrefixes, final List<String> libDirPrefixes) {
            this.classpathEntryObj = classpathEntryObj;
            this.location = location;
            this.classLoaderStr = Objects.toString(classLoader, null);
            this.packageRootPrefixes = packageRootPrefixes;
            this.libDirPrefixes = libDirPrefixes;
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
            if (!(obj instanceof final Entry other)) {
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
     * @param classpathSpec
     *            the scan spec
     */
    ClasspathOrderBuilder(final ClasspathSpec classpathSpec) {
        this.classpathSpec = classpathSpec;
    }

    @Override
    public synchronized boolean claimOncePerScan(final String key) {
        return claimedOncePerScan.add(key);
    }

    /**
     * Get the order of classpath elements, uniquified and in order.
     *
     * @return the classpath order.
     */
    public List<Entry> getOrder() {
        return order;
    }

    /**
     * Get the location of every classpath element found so far. See {@link Entry#location}.
     *
     * @return the classpath element locations.
     */
    public Set<String> getClasspathEntryUniqueLocations() {
        return classpathEntryUniqueLocations;
    }

    /**
     * Set the automatic package root prefixes to record for subsequently-added classpath entries. Called before and
     * after invoking the {@code findClasspathOrder} method of a {@code ClassLoaderHandler}, so that each classpath
     * entry records the package roots of the classloader it was obtained from.
     *
     * @param packageRootPrefixes
     *            the package root prefixes, or null to reset to no prefixes at all.
     */
    public void setPackageRootPrefixes(final @Nullable List<String> packageRootPrefixes) {
        this.currPackageRootPrefixes = packageRootPrefixes == null ? ClassLoaderHandler.NO_PACKAGE_ROOT_PREFIXES
                : packageRootPrefixes;
    }

    /**
     * Set the lib dirs to record for subsequently-added classpath entries. Called before and after invoking the
     * {@code findClasspathOrder} method of a {@code ClassLoaderHandler}, so that each classpath entry records the
     * lib dirs of the classloader it was obtained from.
     *
     * @param libDirPrefixes
     *            the lib dir prefixes, or null to reset to no lib dirs at all.
     */
    public void setLibDirPrefixes(final @Nullable List<String> libDirPrefixes) {
        this.currLibDirPrefixes = libDirPrefixes == null ? ClassLoaderHandler.NO_LIB_DIR_PREFIXES : libDirPrefixes;
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
        if (classpathSpec.classpathElementURLFilters != null) {
            // FastPathResolver strips the scheme from "file:" and "jar:file:" classpath elements, so for those the
            // URL has to be reconstituted from the resolved path before the URL filters can be applied to it
            final var url = classpathElementURL != null ? classpathElementURL
                    : classpathElementPath == null ? null : toURL(classpathElementPath);
            if (url != null) {
                for (final Predicate<URL> urlFilter : classpathSpec.classpathElementURLFilters) {
                    if (!urlFilter.test(url)) {
                        return false;
                    }
                }
            }
        }
        if (classpathElementPath != null && classpathSpec.classpathElementPathFilters != null) {
            for (final Predicate<String> pathFilter : classpathSpec.classpathElementPathFilters) {
                if (!pathFilter.test(classpathElementPath)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Get the location of a classpath element: the form it is reported in, and the form it is deduplicated by.
     *
     * <p>
     * A classpath element that names a file or directory of a filesystem is located by the canonical path of that
     * file, so that the same file reached through two different paths -- through a symbolic link, through a Windows
     * junction or 8.3 short name, or spelled with a different case on a filesystem that ignores case -- is reported
     * once, under the name it is stored under. A classpath element stored inside an archive is located by the
     * canonical path of the archive, followed by the path within it. Anything that is reached through a URL handler
     * rather than through a filesystem is located by its URL or URI, which has no canonical form.
     *
     * @param pathElement
     *            the {@link String} path, {@link File}, {@link Path}, {@link URL} or {@link URI} of the classpath
     *            element.
     * @param pathElementStr
     *            the resolved path of the classpath element.
     * @param log
     *            the log node, or null to skip logging
     * @return the location of the classpath element, or null if the filesystem says it is not there or that it
     *         cannot be read, in which case the reason has been logged.
     */
    private static @Nullable String toLocation(final Object pathElement, final String pathElementStr,
            final @Nullable ClassGraphLog log) {
        final Path path;
        final String nestedSuffix;
        if (pathElement instanceof final Path pathElementPath) {
            // A Path names a file or directory of its own filesystem, which need not be the default filesystem
            path = pathElementPath;
            nestedSuffix = "";
        } else {
            // A classpath element that still has a URL scheme is reached through a URL handler rather than through
            // a filesystem, since FastPathResolver strips the "file:" and "jar:file:" schemes, leaving a local file
            // with no scheme at all
            if (schemeMatcher.matcher(pathElementStr).find()) {
                return pathElementStr;
            }
            // A classpath element stored inside an archive is reached through the archive, so it is the archive
            // whose path is canonicalized, and the path within the archive is appended to it unchanged
            final var nestedIdx = PathSyntax.indexOfNestedJarSeparator(pathElementStr);
            final var archivePathStr = nestedIdx < 0 ? pathElementStr : pathElementStr.substring(0, nestedIdx);
            nestedSuffix = nestedIdx < 0 ? "" : pathElementStr.substring(nestedIdx);
            try {
                path = Path.of(archivePathStr);
            } catch (final InvalidPathException e) {
                // The path is not valid for the default filesystem (on Windows, for example, it may contain a
                // character that is not allowed in a filename), so there is no file to canonicalize or to test
                return pathElementStr;
            }
        }
        final Path canonicalPath;
        try {
            canonicalPath = path.toRealPath();
        } catch (final NoSuchFileException e) {
            // The filesystem says the classpath element is not there
            if (log != null) {
                log.log("Classpath element does not exist, skipping: " + pathElementStr);
            }
            return null;
        } catch (final IOException | RuntimeException e) {
            // The filesystem cannot say whether the classpath element is there, which is a different answer from
            // saying that it is not there: an unreachable network share answers this way, and so does a path whose
            // parent directory cannot be listed. Keep the classpath element in the form it was found in, and let
            // the scan be the one to find out
            return pathElementStr;
        }
        if (!Files.isReadable(canonicalPath)) {
            if (log != null) {
                log.log("Classpath element cannot be read, skipping: " + pathElementStr);
            }
            return null;
        }
        return FastPathResolver.resolveFilePath(FileUtils.currDirPath(), toPathElementStr(canonicalPath))
                + nestedSuffix;
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
     *            the log node, or null to skip logging
     * @return true if the classpath element passes the filters.
     */
    private boolean passesFilters(final @Nullable URL pathElementURL, final String pathElementStr,
            final String pathElementStrResolved, final @Nullable ClassGraphLog log) {
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
     * Add a classpath entry, and log whether it was added, was a duplicate of an entry already found, or was
     * skipped because it does not exist or cannot be read.
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
     * @param log
     *            the log node, or null to skip logging
     * @return true, if added and unique
     */
    private boolean addClasspathEntryAndLog(final Object pathElement, final String pathElementStr,
            final String pathElementStrResolved, final @Nullable ClassLoader classLoader,
            final @Nullable ClassGraphLog log) {
        // Check if classpath element path ends with an automatic package root. If so, strip it off to eliminate
        // duplication, since automatic package roots are detected automatically (#435)
        var pathElementStrWithoutSuffix = pathElementStrResolved;
        var hasSuffix = false;
        for (final String packageRootPrefix : currPackageRootPrefixes) {
            // Convert package root prefix to a suffix, e.g. "BOOT-INF/classes/" -> "!/BOOT-INF/classes"
            final var suffix = "!/" + packageRootPrefix.substring(0, packageRootPrefix.length() - 1);
            if (pathElementStrResolved.endsWith(suffix)) {
                // Strip off automatic package root suffix
                pathElementStrWithoutSuffix = pathElementStrResolved.substring(0,
                        pathElementStrResolved.length() - suffix.length());
                hasSuffix = true;
                break;
            }
        }
        final var isPathObject = pathElement instanceof URL || pathElement instanceof URI
                || pathElement instanceof Path || pathElement instanceof File;
        var pathElementWithoutSuffix = pathElement;
        if (isPathObject && hasSuffix) {
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
                } catch (MalformedURLException | URISyntaxException e2) {
                    // (Path.of() is not retried, since prefixing an invalid path with "file:" cannot fix it --
                    // the Path degrades to a path string, as a File does)
                    return false;
                }
            }
        }

        // Find the location of the classpath element, which is the form it is reported in and the form it is
        // deduplicated by. A classpath element that names a file or directory of a filesystem is located by the
        // canonical path of that file, and is skipped if the filesystem says it is not there or cannot be read
        final var location = toLocation(pathElementWithoutSuffix, pathElementStrWithoutSuffix, log);
        if (location == null) {
            return false;
        }

        // Deduplicate classpath elements
        if (!classpathEntryUniqueLocations.add(location)) {
            if (log != null) {
                log.log("Ignoring duplicate classpath element: " + describe(pathElementStr, location));
            }
            return false;
        }
        // Record the classpath element in the classpath order, keeping the object in the form it was found in, so
        // that the filesystem of a Path, and the form a File or URI was found in, are not lost
        order.add(new Entry(isPathObject ? pathElementWithoutSuffix : location, location, classLoader,
                currPackageRootPrefixes, currLibDirPrefixes));
        if (log != null) {
            log.log("Found classpath element: " + describe(pathElementStr, location));
        }
        return true;
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
     *            the log node, or null to skip logging
     * @return the {@link URL} of the classpath element, or null if it could not be converted to a {@link URL}.
     */
    private static @Nullable URL toClasspathElementURL(final Object pathElement, final String pathElementStr,
            final @Nullable ClassGraphLog log) {
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
     * Add every file in a directory to the classpath, for a wildcarded classpath entry, i.e. a directory path with
     * a {@code "/*"} suffix (allowable for local classpaths as of JDK 6).
     *
     * @param baseDirPath
     *            the resolved path of the directory, i.e. the classpath entry with the {@code "/*"} suffix removed.
     * @param classLoader
     *            the ClassLoader that this classpath element was obtained from.
     * @param log
     *            the log node, or null to skip logging
     * @return true if the contents of the directory could be listed.
     */
    private boolean addWildcardedDirEntries(final String baseDirPath, final @Nullable ClassLoader classLoader,
            final @Nullable ClassGraphLog log) {
        // A wildcarded classpath entry is only ever reached as a path string, never as a URL, so there is no URL to
        // apply the user's URL filters to
        if (!passesFilters(/* pathElementURL = */ null, baseDirPath, baseDirPath, log)) {
            return false;
        }

        // Check the path before the "/*" suffix is a directory
        final var baseDir = new File(baseDirPath);
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
        // N.B. the entries are deliberately not sorted: the java launcher expands a classpath wildcard by listing
        // the directory too, so leaving the listing order alone gives the same classpath order the runtime itself
        // would use for the same wildcard. (The order is unspecified on both sides -- it is the order the
        // filesystem stores the directory entries in.)
        final var baseDirFiles = baseDir.listFiles();
        if (baseDirFiles == null) {
            return false;
        }
        for (final File fileInDir : baseDirFiles) {
            final var name = fileInDir.getName();
            if (!".".equals(name) && !"..".equals(name)) {
                // Add each directory entry as a classpath element
                final var fileInDirPath = fileInDir.getPath();
                final var fileInDirPathResolved = FastPathResolver.resolveFilePath(FileUtils.currDirPath(),
                        fileInDirPath);
                addClasspathEntryAndLog(fileInDirPathResolved, fileInDirPath, fileInDirPathResolved, classLoader,
                        dirLog);
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
     * @param log
     *            the log node, or null to skip logging
     * @return true if the classpath element was added. A classpath element is not added if it is null or empty, if
     *         it names a file or directory that the filesystem says is not there or cannot be read, if it is
     *         filtered out by the user's classpath element filters, or if it is a duplicate of a classpath element
     *         that has already been added.
     */
    @Override
    public boolean addClasspathEntry(final @Nullable Object pathElement, final @Nullable ClassLoader classLoader,
            final @Nullable ClassGraphLog log) {
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
        pathElementStr = FastPathResolver.resolveFilePath(FileUtils.currDirPath(), pathElementStr);
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
        } else if (!(pathElement instanceof Path) && schemeMatcher.matcher(pathElementStr).find()) {
            // Path element string is a URL with a scheme other than `[jar:]file:`, so the URL has to actually be
            // parsed, since the scheme may be a custom scheme. A Path is exempt: the scheme in its string form is
            // the scheme of its own filesystem, which usually has no URL handler, so parsing it as a URL would at
            // best find nothing and at worst produce a URL that names a different file
            pathElementURL = toClasspathElementURL(pathElement, pathElementStr, log);
        }

        if (!hasWildcardSuffix && (pathElementURL != null || pathElement instanceof URL
                || pathElement instanceof URI || pathElement instanceof File || pathElement instanceof Path)) {
            if (!passesFilters(pathElementURL, pathElementStr, pathElementStr, log)) {
                return false;
            }
            // For a URI or Path that was parsed into a URL, use the URL (so that URL scheme handling can be
            // undertaken later); otherwise use the object itself, so that the filesystem of a Path, and the form a
            // File or URI was found in, are not lost
            final var classpathElementObj = pathElementURL != null ? pathElementURL : pathElement;
            return addClasspathEntryAndLog(classpathElementObj, pathElementStr, pathElementStr, classLoader, log);
        }

        if (hasWildcardSuffix) {
            return addWildcardedDirEntries(pathElementStr, classLoader, log);
        }

        // Non-wildcarded (standard) classpath element
        if (pathElementStr.indexOf('*') >= 0) {
            if (log != null) {
                log.log("Wildcard classpath elements can only end with a suffix of \"/*\", "
                        + "can't use globs elsewhere in the path: " + pathElementStr);
            }
            return false;
        }
        // (pathElementStr was already resolved above, so there is nothing left to resolve here)
        if (!passesFilters(pathElementURL, pathElementStr, pathElementStr, log)) {
            return false;
        }
        if (pathElementStr.startsWith("//")) {
            // Handle Windows UNC paths (#705). File supports UNC paths directly:
            // https://wiki.eclipse.org/Eclipse/UNC_Paths#Programming_with_UNC_paths
            try {
                return addClasspathEntryAndLog(new File(pathElementStr), pathElementStr, pathElementStr,
                        classLoader, log);
            } catch (final Exception e) {
                // Fall through, and add the path as a string rather than as a File
            }
        }
        return addClasspathEntryAndLog(pathElementStr, pathElementStr, pathElementStr, classLoader, log);
    }

    /**
     * Add classpath entries, separated by the system path separator character.
     *
     * @param overrideClasspath
     *            a list of delimited path {@link String}, {@link URL}, {@link URI} or {@link File} objects.
     * @param classLoader
     *            the ClassLoader that this classpath was obtained from.
     * @param log
     *            the log node, or null to skip logging
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    @Override
    public boolean addClasspathEntries(final @Nullable List<Object> overrideClasspath,
            final @Nullable ClassLoader classLoader, final @Nullable ClassGraphLog log) {
        if (overrideClasspath == null || overrideClasspath.isEmpty()) {
            return false;
        } else {
            for (final Object pathElement : overrideClasspath) {
                addClasspathEntry(pathElement, classLoader, log);
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
     * @param log
     *            the log node, or null to skip logging
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    @Override
    public boolean addClasspathPathStr(final @Nullable String pathStr, final @Nullable ClassLoader classLoader,
            final @Nullable ClassGraphLog log) {
        if (pathStr == null || pathStr.isEmpty()) {
            return false;
        } else {
            final var parts = PathList.split(pathStr, classpathSpec.allowedURLSchemes);
            if (parts.length == 0) {
                return false;
            } else {
                for (final String pathElement : parts) {
                    addClasspathEntry(pathElement, classLoader, log);
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
     * @param log
     *            the log node, or null to skip logging
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    @Override
    public boolean addClasspathEntryObject(final @Nullable Object pathObject,
            final @Nullable ClassLoader classLoader, final @Nullable ClassGraphLog log) {
        var valid = false;
        if (pathObject != null) {
            if (pathObject instanceof URL || pathObject instanceof URI || pathObject instanceof Path
                    || pathObject instanceof File) {
                valid |= addClasspathEntry(pathObject, classLoader, log);
            } else if (pathObject instanceof final Iterable<?> iterable) {
                for (final Object elt : iterable) {
                    valid |= addClasspathEntryObject(elt, classLoader, log);
                }
            } else {
                final Class<?> valClass = pathObject.getClass();
                if (valClass.isArray()) {
                    for (int j = 0, n = Array.getLength(pathObject); j < n; j++) {
                        final var elt = Array.get(pathObject, j);
                        valid |= addClasspathEntryObject(elt, classLoader, log);
                    }
                } else {
                    // Try simply calling toString() as a final fallback, to handle String objects, or to try to
                    // handle anything else
                    valid |= addClasspathPathStr(pathObject.toString(), classLoader, log);
                }
            }
        }
        return valid;
    }
}
