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
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.path.PathList;
import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClasspathOrder;

/** Builds the deduplicated, ordered list of classpath elements. */
public class ClasspathOrderBuilder implements ClasspathOrder {
    /** Matches a {@code '%'} that is not followed by two hexadecimal digits. */
    private static final Pattern LONE_PERCENT = Pattern.compile("%(?![0-9A-Fa-f]{2})");

    /** The classpath configuration. */
    private final ClasspathSpec classpathSpec;

    /** Canonical locations of classpath elements added so far, used for deduplication. */
    private final Set<String> classpathEntryUniqueLocations = new HashSet<>();

    /** The ordered list of unique classpath elements. */
    private final List<Entry> order = new ArrayList<>();

    /** Package root prefixes for the currently active {@link ClassLoaderHandler}. */
    private List<String> currPackageRootPrefixes = ClassLoaderHandler.NO_PACKAGE_ROOT_PREFIXES;

    /** Automatic lib directory prefixes for the currently active {@link ClassLoaderHandler}. */
    private List<String> currLibDirPrefixes = ClassLoaderHandler.NO_LIB_DIR_PREFIXES;

    /** Class of the currently active {@link ClassLoaderHandler}, or null if none. */
    private @Nullable Class<?> currHandlerClass;

    /**
     * A per-scan claim key scoped to a {@link ClassLoaderHandler} class.
     *
     * @param handlerClass
     *            the claiming handler class (or {@link ClasspathOrderBuilder} if outside a handler).
     * @param key
     *            the claim key.
     */
    private record Claim(Class<?> handlerClass, String key) {
    }

    /** Keys already claimed via {@link #claimOncePerScan(String)}. */
    private final Set<Claim> claimedOncePerScan = new HashSet<>();

    /**
     * A discovered classpath element and its associated classloader metadata.
     *
     * @param classpathEntryObj
     *            the raw classpath entry object ({@link String}, {@link File}, {@link Path}, {@link URL}, or
     *            {@link URI}).
     * @param location
     *            the normalized canonical location string used for reporting and deduplication.
     * @param classLoaderName
     *            the string representation of the originating classloader, or null if none.
     * @param packageRootPrefixes
     *            automatic package root prefixes declared by the originating {@link ClassLoaderHandler}.
     * @param libDirPrefixes
     *            automatic lib directory prefixes declared by the originating {@link ClassLoaderHandler}.
     */
    public record Entry(Object classpathEntryObj, String location, @Nullable String classLoaderName,
            List<String> packageRootPrefixes, List<String> libDirPrefixes) {
        @Override
        public String toString() {
            return classpathEntryObj + " [" + classLoaderName + "]";
        }
    }

    /**
     * Constructor.
     *
     * @param classpathSpec
     *            the classpath configuration.
     */
    ClasspathOrderBuilder(final ClasspathSpec classpathSpec) {
        this.classpathSpec = classpathSpec;
    }

    @Override
    public synchronized boolean claimOncePerScan(final String key) {
        return claimedOncePerScan
                .add(new Claim(currHandlerClass == null ? ClasspathOrderBuilder.class : currHandlerClass, key));
    }

    /**
     * Returns the deduplicated list of classpath entries in resolution order.
     *
     * @return the classpath entries, as an unmodifiable list.
     */
    public List<Entry> getOrder() {
        return Collections.unmodifiableList(order);
    }

    /**
     * Sets the package root prefixes to attach to subsequently added classpath entries.
     *
     * @param packageRootPrefixes
     *            the package root prefixes, or null to reset to
     *            {@link ClassLoaderHandler#NO_PACKAGE_ROOT_PREFIXES}.
     * @param log
     *            the log node, or null to skip logging.
     */
    void setPackageRootPrefixes(final @Nullable List<String> packageRootPrefixes,
            final @Nullable ClassGraphLog log) {
        this.currPackageRootPrefixes = packageRootPrefixes == null ? ClassLoaderHandler.NO_PACKAGE_ROOT_PREFIXES
                : normalizePrefixes(packageRootPrefixes, log);
    }

    /**
     * Sets the automatic lib directory prefixes to attach to subsequently added classpath entries.
     *
     * @param libDirPrefixes
     *            the lib directory prefixes, or null to reset to {@link ClassLoaderHandler#NO_LIB_DIR_PREFIXES}.
     * @param log
     *            the log node, or null to skip logging.
     */
    void setLibDirPrefixes(final @Nullable List<String> libDirPrefixes, final @Nullable ClassGraphLog log) {
        this.currLibDirPrefixes = libDirPrefixes == null ? ClassLoaderHandler.NO_LIB_DIR_PREFIXES
                : normalizePrefixes(libDirPrefixes, log);
    }

    /**
     * Sets the active {@link ClassLoaderHandler} class for scoping {@link #claimOncePerScan(String)} calls.
     *
     * @param handlerClass
     *            the active handler class, or null if no handler is active.
     */
    void setCurrentHandlerClass(final @Nullable Class<?> handlerClass) {
        this.currHandlerClass = handlerClass;
    }

    /**
     * Normalizes directory prefixes into relative paths with {@code '/'} separators and a trailing {@code '/'},
     * removing empty, root, or duplicate prefixes.
     *
     * @param prefixes
     *            the raw directory prefixes.
     * @param log
     *            the log node, or null to skip logging.
     * @return the normalized prefixes, as an immutable list.
     */
    static List<String> normalizePrefixes(final List<String> prefixes, final @Nullable ClassGraphLog log) {
        final List<String> normalizedPrefixes = new ArrayList<>(prefixes.size());
        var changed = false;
        for (final String prefix : prefixes) {
            final var sanitized = prefix == null ? ""
                    : PathSyntax.sanitizeRelativePath(prefix.replace('\\', '/'), /* removeFinalSlash = */ true);
            final var normalizedPrefix = sanitized.isEmpty() ? "" : sanitized + "/";
            if (normalizedPrefix.isEmpty()) {
                changed = true;
                if (log != null) {
                    log.log("Ignoring prefix that names no dir: " + (prefix == null ? null : "\"" + prefix + "\""));
                }
            } else if (normalizedPrefixes.contains(normalizedPrefix)) {
                changed = true;
                if (log != null) {
                    log.log("Ignoring duplicate prefix: \"" + prefix + "\"");
                }
            } else {
                if (!normalizedPrefix.equals(prefix)) {
                    changed = true;
                    if (log != null) {
                        log.log("Normalized prefix \"" + prefix + "\" to \"" + normalizedPrefix + "\"");
                    }
                }
                normalizedPrefixes.add(normalizedPrefix);
            }
        }
        // List.copyOf returns a list that is already immutable unchanged, so a handler that declares its prefixes
        // with List.of in normal form costs nothing here
        return List.copyOf(changed ? normalizedPrefixes : prefixes);
    }

    /**
     * Tests whether a classpath element is accepted by all configured URL and path filters.
     *
     * @param classpathElementURL
     *            the classpath element URL, or null.
     * @param classpathElementPath
     *            the classpath element path, or null.
     * @return true if the classpath element is accepted.
     */
    private boolean filter(final @Nullable URL classpathElementURL, final @Nullable String classpathElementPath) {
        return classpathSpec.acceptsClasspathElement(classpathElementURL, classpathElementPath);
    }

    /**
     * Resolves the canonical location string for a classpath element, or returns null if the local file or
     * directory does not exist or cannot be read.
     *
     * @param pathElement
     *            the classpath element object.
     * @param pathElementStr
     *            the resolved path string of the classpath element.
     * @param log
     *            the log node, or null to skip logging.
     * @return the canonical location string, or null if nonexistent or unreadable.
     */
    public static @Nullable String toLocation(final Object pathElement, final String pathElementStr,
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
            if (URLPaths.startsWithURLScheme(pathElementStr)) {
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
        boolean isReadable;
        try {
            isReadable = Files.isReadable(canonicalPath);
        } catch (final SecurityException e) {
            // A security manager refused to let the file be read, so the scan would be refused too. (toRealPath
            // tests only the path it is given, so this is reached when a symbolic link may be read, but the file it
            // points to may not.)
            isReadable = false;
        }
        if (!isReadable) {
            if (log != null) {
                log.log("Classpath element cannot be read, skipping: " + pathElementStr);
            }
            return null;
        }
        return FastPathResolver.resolveFilePath(FileUtils.currDirPath(), toPathElementStr(canonicalPath))
                + nestedSuffix;
    }

    /**
     * Formats a classpath element path and its resolved form for logging.
     *
     * @param pathElementStr
     *            the original path string.
     * @param pathElementStrResolved
     *            the resolved path string.
     * @return the formatted description string.
     */
    private static String describe(final String pathElementStr, final String pathElementStrResolved) {
        return pathElementStr
                + (pathElementStr.equals(pathElementStrResolved) ? "" : " -> " + pathElementStrResolved);
    }

    /**
     * Tests a classpath element against configured filters and logs a message if rejected.
     *
     * @param pathElementURL
     *            the classpath element URL, or null.
     * @param pathElementStr
     *            the original path string.
     * @param pathElementStrResolved
     *            the resolved path string.
     * @param log
     *            the log node, or null to skip logging.
     * @return true if the element passes all filters.
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
     * Resolves, deduplicates, and adds a classpath entry, logging the outcome.
     *
     * @param pathElement
     *            the classpath element object.
     * @param pathElementStr
     *            the original path string.
     * @param pathElementStrResolved
     *            the resolved path string.
     * @param classLoader
     *            the originating classloader, or null.
     * @param log
     *            the log node, or null to skip logging.
     * @return true if the classpath element was added.
     */
    private boolean addClasspathEntryAndLog(final Object pathElement, final String pathElementStr,
            final String pathElementStrResolved, final @Nullable ClassLoader classLoader,
            final @Nullable ClassGraphLog log) {
        // Check if classpath element path ends with an automatic package root. If so, strip it off to eliminate
        // duplication, since automatic package roots are detected automatically (#435)
        var pathElementStrWithoutSuffix = pathElementStrResolved;
        var hasSuffix = false;
        for (final String packageRootPrefix : currPackageRootPrefixes) {
            // Convert package root prefix to a suffix, e.g. "WEB-INF/classes/" -> "!/WEB-INF/classes"
            final var suffix = "!/" + packageRootPrefix.substring(0, packageRootPrefix.length() - 1);
            if (pathElementStrResolved.endsWith(suffix)) {
                // A path that names an existing file or directory as it is written, such as
                // "/x/app!/WEB-INF/classes" where "app!" is a directory holding an unpacked war, is scanned as it
                // is, since its '!' is part of a directory name, not a nested jar separator (#903). A path that a
                // security manager refuses to let be tested is taken not to exist, and the package root is stripped
                if (FileUtils.exists(new File(pathElementStrResolved))) {
                    break;
                }
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
            // Rebuild the classpath element as an object of the same type, so that the form it was found in is not
            // lost. If it cannot be rebuilt, it degrades to the path string, rather than being dropped entirely
            if (pathElement instanceof File) {
                pathElementWithoutSuffix = new File(pathElementStrWithoutSuffix);
            } else if (pathElement instanceof Path) {
                // A Path whose string form ends in a package root suffix is normally a path in a zip filesystem,
                // whose string form is a "jar:" URI such as "jar:file:///x/app.war!/WEB-INF/classes". What is left
                // once the suffix is stripped names the zipfile, which is a file of the default filesystem, not of
                // the zip filesystem, so the Path is rebuilt with Path.of rather than with the Path's own
                // filesystem. If the zipfile is itself inside another archive (a zip filesystem opened on a path in
                // another zip filesystem), what is left ("/x/outer.jar!/app.war") names a nested archive, which is
                // a file of no filesystem, so the entry degrades to that path string, which the scanner opens as a
                // nested archive. Whether it is nested is decided by testing the filesystem (#903), since a "!/" in
                // what is left may end a directory name, as in "/x/build!/app.war", which names a zipfile
                try {
                    if (PathSyntax.indexOfNestedJarSeparator(pathElementStrWithoutSuffix) >= 0) {
                        pathElementWithoutSuffix = pathElementStrWithoutSuffix;
                    } else {
                        pathElementWithoutSuffix = Path.of(pathElementStrWithoutSuffix);
                    }
                } catch (final InvalidPathException e) {
                    pathElementWithoutSuffix = pathElementStrWithoutSuffix;
                }
            } else if (!URLPaths.startsWithURLScheme(pathElementStrWithoutSuffix)) {
                // The resolved path of a URL or URI that names a local file has lost its "file:" or "jar:file:"
                // scheme and its percent encoding, so the URL or URI is rebuilt from the path of the file, which
                // URLPaths#toURI encodes again. (Parsing the resolved path as a URL or URI would give a URI with no
                // scheme, a URL with a space in it that cannot be turned into a URI, or a URL that names a
                // different file once the scanner decodes a "%" that had been encoded as "%25".) The rebuilt URI
                // is the "file:" URI of the archive or directory that is left, or a "jar:" URI if the path is still
                // nested inside another archive once the suffix has been stripped. Whether it is nested is decided
                // by testing the filesystem (#903), since a "!/" in the path may end a directory name, as in
                // "/x/build!/app.war", and such a "!/" is percent-encoded in a "jar:" URI, which would otherwise
                // end the outer archive's path there. A path that is not nested is turned into a URI by its Path,
                // which ends the URI of a directory in '/', as java.net.URLClassLoader requires of a directory. The
                // scanner forms the URI it reports for an archive or a directory the same way
                try {
                    final var uri = PathSyntax.indexOfNestedJarSeparator(pathElementStrWithoutSuffix) >= 0
                            ? URLPaths.toURI(pathElementStrWithoutSuffix)
                            : URLPaths.toURI(Path.of(pathElementStrWithoutSuffix));
                    pathElementWithoutSuffix = pathElement instanceof URL ? uri.toURL() : uri;
                } catch (InvalidPathException | IllegalStateException | MalformedURLException e) {
                    pathElementWithoutSuffix = pathElementStrWithoutSuffix;
                }
            } else {
                // A URL or URI whose resolved path still has a URL scheme (such as "http:"), which keeps its
                // percent encoding, so it can be parsed again
                try {
                    pathElementWithoutSuffix = pathElement instanceof URL ? new URL(pathElementStrWithoutSuffix)
                            : new URI(pathElementStrWithoutSuffix);
                } catch (MalformedURLException | URISyntaxException e) {
                    pathElementWithoutSuffix = pathElementStrWithoutSuffix;
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
        order.add(new Entry(isPathObject ? pathElementWithoutSuffix : location, location,
                Objects.toString(classLoader, null), currPackageRootPrefixes, currLibDirPrefixes));
        if (log != null) {
            log.log("Found classpath element: " + describe(pathElementStr, location));
        }
        return true;
    }

    /**
     * Converts a classpath element object to its string representation.
     *
     * @param pathElement
     *            the classpath element object.
     * @return the string representation of the classpath element.
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
     * Converts a classpath element with a URL scheme into a {@link URL}, or returns null if conversion fails.
     *
     * @param pathElement
     *            the classpath element object.
     * @param pathElementStr
     *            the path string of the classpath element.
     * @param log
     *            the log node, or null to skip logging.
     * @return the {@link URL}, or null if conversion failed.
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
            // Escape a "%" that does not start a percent escape, so that it stands for itself (#255). An existing
            // escape is kept, since escaping its "%" would make the URL name a different resource ("a%20b.jar"
            // would become "a%2520b.jar")
            final var urlStr = LONE_PERCENT.matcher(pathElementStr).replaceAll("%25");
            try {
                pathElementURL = new URL(urlStr);
            } catch (final MalformedURLException e) {
                // The path is not a valid URL, usually because no URL handler is registered for its scheme. It is
                // only a local file if a file of that name exists, such as "lib:/x.jar" in a directory named "lib:"
                // in the current directory. Otherwise, turning it into a "file:" URL would name a file below the
                // current directory that does not exist, such as "file:/cwd/custom:/host/x.jar", so the classpath
                // element is left in the form it was given. (A security manager may refuse to let the file be
                // checked or resolved, which also leaves the classpath element in that form)
                final var file = new File(pathElementStr);
                try {
                    if (file.exists()) {
                        pathElementURL = file.toURI().toURL();
                    }
                } catch (final MalformedURLException | IllegalArgumentException | IOError | SecurityException e1) {
                    // Fall through
                }
            }
        }
        if (pathElementURL == null && log != null) {
            log.log("Failed to convert classpath element to URL: " + pathElement);
        }
        return pathElementURL;
    }

    /**
     * Expands a wildcarded directory entry (ending in {@code "/*"}) by adding all {@code .jar} and {@code .JAR}
     * files in the directory.
     *
     * @param baseDirPath
     *            the resolved directory path with the {@code "/*"} suffix removed.
     * @param classLoader
     *            the originating classloader, or null.
     * @param log
     *            the log node, or null to skip logging.
     * @return true if the directory exists and could be listed.
     */
    private boolean addWildcardedDirEntries(final String baseDirPath, final @Nullable ClassLoader classLoader,
            final @Nullable ClassGraphLog log) {
        // The directory itself is not tested against the user's filters, since it is not a classpath element (the
        // java launcher never adds it to the classpath): each jarfile in it is tested instead, below

        // Check the path before the "/*" suffix is a directory
        final var baseDir = new File(baseDirPath);
        if (!FileUtils.exists(baseDir)) {
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
            // The java launcher adds only the entries whose names end in ".jar" or ".JAR" (not ".Jar"), and decides
            // by the name alone, so it also adds a directory with such a name
            if (name.endsWith(".jar") || name.endsWith(".JAR")) {
                final var fileInDirPath = fileInDir.getPath();
                final var fileInDirPathResolved = FastPathResolver.resolveFilePath(FileUtils.currDirPath(),
                        fileInDirPath);
                // Each jarfile is a classpath element in its own right, so it has to pass the filters too
                if (!passesFilters(/* pathElementURL = */ null, fileInDirPath, fileInDirPathResolved, dirLog)) {
                    continue;
                }
                addClasspathEntryAndLog(fileInDirPathResolved, fileInDirPath, fileInDirPathResolved, classLoader,
                        dirLog);
            }
        }
        return true;
    }

    @Override
    public boolean addClasspathEntry(final @Nullable Object pathElement, final @Nullable ClassLoader classLoader,
            final @Nullable ClassGraphLog log) {
        return ClasspathEntryFlattener.forEachEntry(pathElement,
                entry -> entry != null && addSingleClasspathEntry(entry, classLoader, log));
    }

    @Override
    public boolean addClasspathPathStr(final @Nullable String pathStr, final @Nullable ClassLoader classLoader,
            final @Nullable ClassGraphLog log) {
        var added = false;
        if (pathStr != null && !pathStr.isEmpty()) {
            for (final String pathElement : PathList.split(pathStr, classpathSpec.getAllowedURLSchemes())) {
                added |= addSingleClasspathEntry(pathElement, classLoader, log);
            }
        }
        return added;
    }

    @Override
    public boolean addClasspathEntryObject(final @Nullable Object pathObject,
            final @Nullable ClassLoader classLoader, final @Nullable ClassGraphLog log) {
        return ClasspathEntryFlattener.forEachEntry(pathObject,
                entry -> entry != null && (entry instanceof URL || entry instanceof URI || entry instanceof Path
                        || entry instanceof File ? addSingleClasspathEntry(entry, classLoader, log)
                                // A string, or any other object, which is converted with toString() and split
                                : addClasspathPathStr(entry.toString(), classLoader, log)));
    }

    /**
     * Resolves and adds a single classpath entry (or expands a wildcarded directory entry).
     *
     * @param pathElement
     *            the classpath element object.
     * @param classLoader
     *            the originating {@link ClassLoader}, or null.
     * @param log
     *            the log node, or null to skip logging.
     * @return true if at least one classpath element was added.
     */
    private boolean addSingleClasspathEntry(final Object pathElement, final @Nullable ClassLoader classLoader,
            final @Nullable ClassGraphLog log) {
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
        // As in the java launcher, a path ending in '*' is not a wildcard if a file with that exact name exists
        // (Windows does not allow '*' in a file name, so this only applies elsewhere)
        if ((pathElementStr.endsWith("/*") || pathElementStr.endsWith("\\*"))
                && !FileUtils.exists(new File(pathElementStr))) {
            hasWildcardSuffix = true;
            pathElementStr = pathElementStr.substring(0, pathElementStr.length() - 2);
            // Leave pathElementURL null, so that wildcards can be handled below
        } else if ("*".equals(pathElementStr) && !FileUtils.exists(new File(pathElementStr))) {
            // Reached when the current directory path is empty, because user.dir could not be read. "." rather than
            // "" names the current directory, since before JDK 25 new File("") does not exist and lists nothing
            hasWildcardSuffix = true;
            pathElementStr = ".";
            // Leave pathElementURL null, so that wildcards can be handled below
        } else if (!(pathElement instanceof Path) && URLPaths.startsWithURLScheme(pathElementStr)) {
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

        // Non-wildcarded (standard) classpath element. As in the java launcher, a '*' anywhere but in a "/*" suffix
        // (or in a "/*" suffix that names an existing file) is not a wildcard: it is part of a file name, which
        // Linux and macOS allow
        // (pathElementStr was already resolved above, so there is nothing left to resolve here)
        if (!passesFilters(pathElementURL, pathElementStr, pathElementStr, log)) {
            return false;
        }
        if (pathElementStr.startsWith("//")) {
            // Handle Windows UNC paths (#705). File supports UNC paths directly:
            // https://wiki.eclipse.org/Eclipse/UNC_Paths#Programming_with_UNC_paths
            return addClasspathEntryAndLog(new File(pathElementStr), pathElementStr, pathElementStr, classLoader,
                    log);
        }
        return addClasspathEntryAndLog(pathElementStr, pathElementStr, pathElementStr, classLoader, log);
    }
}
