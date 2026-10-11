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
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.path.PathList;
import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClasspathOrder;
import org.jspecify.annotations.Nullable;

/** A class to find the unique ordered classpath elements. */
public class ClasspathOrderBuilder implements ClasspathOrder {
    /** A "%" that is not followed by two hex digits, so does not start a percent escape. */
    private static final Pattern LONE_PERCENT = Pattern.compile("%(?![0-9A-Fa-f]{2})");

    /** The scan spec. */
    private final ClasspathSpec classpathSpec;

    /** The {@link Entry#location()} of every classpath element found so far, which is what deduplicates them. */
    private final Set<String> classpathEntryUniqueLocations = new HashSet<>();

    /** The classpath elements found so far, in classpath order. */
    private final List<Entry> order = new ArrayList<>();

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

    /**
     * The class of the {@code ClassLoaderHandler} whose {@code findClasspathOrder} method is currently being
     * called, or null if classpath entries are not currently being obtained from a {@code ClassLoaderHandler}.
     */
    private @Nullable Class<?> currHandlerClass;

    /**
     * A key claimed through {@link #claimOncePerScan(String)}, with the class of the handler that claimed it.
     *
     * @param handlerClass
     *            the class of the handler that claimed the key, or {@link ClasspathOrderBuilder} for a claim made
     *            outside any handler.
     * @param key
     *            the key.
     */
    private record Claim(Class<?> handlerClass, String key) {
    }

    /**
     * The keys that {@link #claimOncePerScan(String)} has already been called with. A key is kept with the class of
     * the handler that claimed it, since a key only has to be unique within that handler.
     */
    private final Set<Claim> claimedOncePerScan = new HashSet<>();

    /**
     * A classpath element and the string form of the {@link ClassLoader} it was obtained from.
     *
     * @param classpathEntryObj
     *            the classpath entry object (a {@link String} path, {@link File}, {@link Path}, {@link URL} or
     *            {@link URI}).
     * @param location
     *            the location of the classpath element: the canonical path of a directory or jarfile of a
     *            filesystem, with {@code '/'} as the separator on every platform, or the URL or URI of anything
     *            that is not reached through a filesystem. This is the form the classpath element is reported in,
     *            and the form it is deduplicated by, so a file reached through two different paths is reported
     *            once, under the name it is stored under. It is kept alongside the classpath entry object because
     *            the object's {@link Object#toString()} is not in that form: the {@link Path} of a local file
     *            spells the path with the platform's separator, so on Windows it uses backslashes, and a
     *            {@link URI} keeps its scheme.
     * @param classLoaderName
     *            the string form of the classloader the classpath element was obtained from, or null if unknown.
     *            Only the string is kept, not the classloader itself, so that finding the classpath does not keep a
     *            classloader alive.
     * @param packageRootPrefixes
     *            the automatic package root prefixes to look for within this classpath element, as declared by the
     *            {@code ClassLoaderHandler} that found it.
     * @param libDirPrefixes
     *            the lib dirs whose jarfiles should be added to the classpath, within this classpath element, as
     *            declared by the {@code ClassLoaderHandler} that found it.
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
     *            the scan spec
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
     * Get the order of classpath elements, uniquified and in order.
     *
     * @return the classpath order, as an unmodifiable view.
     */
    public List<Entry> getOrder() {
        return Collections.unmodifiableList(order);
    }

    /**
     * Set the automatic package root prefixes to record for subsequently-added classpath entries. Called before and
     * after invoking the {@code findClasspathOrder} method of a {@code ClassLoaderHandler}, so that each classpath
     * entry records the package roots of the classloader it was obtained from. The prefixes are normalized and
     * copied, as {@link #normalizePrefixes(List, ClassGraphLog)} describes.
     *
     * @param packageRootPrefixes
     *            the package root prefixes, or null to reset to no prefixes at all.
     * @param log
     *            the log node, or null to skip logging
     */
    void setPackageRootPrefixes(final @Nullable List<String> packageRootPrefixes,
            final @Nullable ClassGraphLog log) {
        this.currPackageRootPrefixes = packageRootPrefixes == null ? ClassLoaderHandler.NO_PACKAGE_ROOT_PREFIXES
                : normalizePrefixes(packageRootPrefixes, log);
    }

    /**
     * Set the lib dirs to record for subsequently-added classpath entries. Called before and after invoking the
     * {@code findClasspathOrder} method of a {@code ClassLoaderHandler}, so that each classpath entry records the
     * lib dirs of the classloader it was obtained from. The prefixes are normalized and copied, as
     * {@link #normalizePrefixes(List, ClassGraphLog)} describes.
     *
     * @param libDirPrefixes
     *            the lib dir prefixes, or null to reset to no lib dirs at all.
     * @param log
     *            the log node, or null to skip logging
     */
    void setLibDirPrefixes(final @Nullable List<String> libDirPrefixes, final @Nullable ClassGraphLog log) {
        this.currLibDirPrefixes = libDirPrefixes == null ? ClassLoaderHandler.NO_LIB_DIR_PREFIXES
                : normalizePrefixes(libDirPrefixes, log);
    }

    /**
     * Set the class of the handler whose claims {@link #claimOncePerScan(String)} records. Called before and after
     * invoking the {@code findClasspathOrder} method of a {@code ClassLoaderHandler}, so that each handler class
     * has its own set of keys.
     *
     * @param handlerClass
     *            the class of the handler, or null if no handler is running.
     */
    void setCurrentHandlerClass(final @Nullable Class<?> handlerClass) {
        this.currHandlerClass = handlerClass;
    }

    /**
     * Normalize the package root or lib dir prefixes that a {@code ClassLoaderHandler} declared into the form the
     * rest of ClassGraph relies on: a relative path, with {@code '/'} as the separator and no empty, {@code "."} or
     * {@code ".."} segments, ending in {@code '/'}. A backslash is read as a separator, and leading slashes are
     * removed. A null prefix, and a prefix that names no dir once normalized (such as {@code ""} or {@code "/"}),
     * is dropped, as is a prefix that duplicates an earlier one. Each change is logged.
     *
     * <p>
     * The result is an immutable copy, so it does not change if the handler later changes the list it handed over.
     * A list that is already immutable and in normal form is returned unchanged.
     *
     * @param prefixes
     *            the prefixes the handler declared.
     * @param log
     *            the log node, or null to skip logging
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
     * Test to see if a classpath element has been filtered out by the user.
     *
     * @param classpathElementURL
     *            the classpath element URL, or null if the classpath element was not given as a URL
     * @param classpathElementPath
     *            the classpath element path
     * @return true if the classpath element passes every filter
     */
    private boolean filter(final @Nullable URL classpathElementURL, final @Nullable String classpathElementPath) {
        return classpathSpec.acceptsClasspathElement(classpathElementURL, classpathElementPath);
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
            } catch (final InvalidPathException _) {
                // The path is not valid for the default filesystem (on Windows, for example, it may contain a
                // character that is not allowed in a filename), so there is no file to canonicalize or to test
                return pathElementStr;
            }
        }
        final Path canonicalPath;
        try {
            canonicalPath = path.toRealPath();
        } catch (final NoSuchFileException _) {
            // The filesystem says the classpath element is not there
            if (log != null) {
                log.log("Classpath element does not exist, skipping: " + pathElementStr);
            }
            return null;
        } catch (final IOException | RuntimeException _) {
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
     * @return true if the classpath element was added, false if it was a duplicate or could not be found
     */
    // new URL(String) is deprecated from JDK 20, but it accepts URLs that new URI(String) rejects (such as one
    // containing a space), and a URL classpath element is rebuilt here with the same leniency it was given with
    @SuppressWarnings("deprecation")
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
                // another zip filesystem), what is left ("/x/outer.jar!/app.war") names a nested archive, which is a
                // file of no filesystem, so the entry degrades to that path string, which the scanner opens as a
                // nested archive
                if (pathElementStrWithoutSuffix.contains("!/")) {
                    pathElementWithoutSuffix = pathElementStrWithoutSuffix;
                } else {
                    try {
                        pathElementWithoutSuffix = Path.of(pathElementStrWithoutSuffix);
                    } catch (final InvalidPathException _) {
                        pathElementWithoutSuffix = pathElementStrWithoutSuffix;
                    }
                }
            } else if (!URLPaths.startsWithURLScheme(pathElementStrWithoutSuffix)) {
                // The resolved path of a URL or URI that names a local file has lost its "file:" or "jar:file:"
                // scheme and its percent encoding, so the URL or URI is rebuilt from the path of the file, which
                // Path#toUri() encodes again. (Parsing the resolved path as a URL or URI would give a URI with no
                // scheme, a URL with a space in it that cannot be turned into a URI, or a URL that names a different
                // file once the scanner decodes a "%" that had been encoded as "%25".) The "jar:" scheme is kept if
                // the path is still nested inside an archive once the suffix has been stripped (a URI scheme is not
                // case sensitive, so it may be spelled "JAR:")
                try {
                    var uri = Path.of(pathElementStrWithoutSuffix).toUri();
                    if (pathElement.toString().regionMatches(/* ignoreCase = */ true, 0, "jar:", 0, 4)
                            && pathElementStrWithoutSuffix.contains("!/")) {
                        uri = new URI("jar:" + uri);
                    }
                    pathElementWithoutSuffix = pathElement instanceof URL ? uri.toURL() : uri;
                } catch (InvalidPathException | IOError | URISyntaxException | MalformedURLException _) {
                    pathElementWithoutSuffix = pathElementStrWithoutSuffix;
                }
            } else {
                // A URL or URI whose resolved path still has a URL scheme (such as "http:"), which keeps its percent
                // encoding, so it can be parsed again
                try {
                    pathElementWithoutSuffix = pathElement instanceof URL ? new URL(pathElementStrWithoutSuffix)
                            : new URI(pathElementStrWithoutSuffix);
                } catch (MalformedURLException | URISyntaxException _) {
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
            } catch (final IOError _) {
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
    // new URL(String) is deprecated from JDK 20, but it accepts URLs that new URI(String) rejects (such as one
    // containing a space), which is what the parsing of the path string below relies on
    @SuppressWarnings("deprecation")
    private static @Nullable URL toClasspathElementURL(final Object pathElement, final String pathElementStr,
            final @Nullable ClassGraphLog log) {
        URL pathElementURL = null;
        try {
            pathElementURL = pathElement instanceof final URL url ? url
                    : pathElement instanceof final URI uri ? uri.toURL()
                            : pathElement instanceof final Path path ? path.toUri().toURL()
                                    : pathElement instanceof final File file ? file.toURI().toURL() : null;
        } catch (final MalformedURLException | IllegalArgumentException | IOError _) {
            // Fall through
        }
        if (pathElementURL == null) {
            // Escape a "%" that does not start a percent escape, so that it stands for itself (#255). An existing
            // escape is kept, since escaping its "%" would make the URL name a different resource ("a%20b.jar"
            // would become "a%2520b.jar")
            final var urlStr = LONE_PERCENT.matcher(pathElementStr).replaceAll("%25");
            try {
                pathElementURL = new URL(urlStr);
            } catch (final MalformedURLException _) {
                // The path is not a valid URL, usually because no URL handler is registered for its scheme. It is
                // only a local file if a file of that name exists, such as "lib:/x.jar" in a directory named "lib:"
                // in the current directory. Otherwise, turning it into a "file:" URL would name a file below the
                // current directory that does not exist, such as "file:/cwd/custom:/host/x.jar", so the classpath
                // element is left in the form it was given
                final var file = new File(pathElementStr);
                if (file.exists()) {
                    try {
                        pathElementURL = file.toURI().toURL();
                    } catch (final MalformedURLException | IllegalArgumentException | IOError _) {
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
     * Add every jarfile in a directory to the classpath, for a wildcarded classpath entry, i.e. a directory path
     * with a {@code "/*"} suffix (allowable for local classpaths as of JDK 6). As in the java launcher, a jarfile
     * is any directory entry whose name ends in {@code ".jar"} or {@code ".JAR"}.
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
        // The directory itself is not tested against the user's filters, since it is not a classpath element (the
        // java launcher never adds it to the classpath): each jarfile in it is tested instead, below

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
        return addFlattened(pathElement, /* splitStrings = */ false, classLoader, log,
                Collections.newSetFromMap(new IdentityHashMap<>()));
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
        return addFlattened(pathObject, /* splitStrings = */ true, classLoader, log,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * Add the classpath entries held by an object, adding each element of an array or {@link Iterable}, nested to
     * any depth.
     *
     * @param pathObject
     *            a {@link URL}, {@link URI}, {@link File}, {@link Path} or {@link CharSequence}, an array or
     *            {@link Iterable} of these, or any other object, which is converted with {@link Object#toString()}.
     * @param splitStrings
     *            true to split a {@link CharSequence}, or the string form of an object of any other type, at the
     *            system path separator character; false to add it as one path.
     * @param classLoader
     *            the {@link ClassLoader} that the classpath entries were obtained from.
     * @param log
     *            the log node, or null to skip logging
     * @param expanding
     *            the arrays and {@link Iterable}s whose elements are being added, by identity. One that is met
     *            again while its own elements are being added is not expanded again, which would never end: an
     *            array or {@link Collection} is skipped, and any other {@link Iterable} is converted with
     *            {@link Object#toString()}. (An Apache Ant {@code FileResource} is an {@link Iterable} that yields
     *            only itself, and its string form is the path of its file.)
     * @return true if at least one classpath element was added.
     */
    private boolean addFlattened(final @Nullable Object pathObject, final boolean splitStrings,
            final @Nullable ClassLoader classLoader, final @Nullable ClassGraphLog log,
            final Set<Object> expanding) {
        if (pathObject == null) {
            return false;
        }
        // A Path is checked before Iterable, since a Path is an Iterable of its own name elements
        if (pathObject instanceof URL || pathObject instanceof URI || pathObject instanceof Path
                || pathObject instanceof File) {
            return addSingleClasspathEntry(pathObject, classLoader, log);
        }
        final var isArray = pathObject.getClass().isArray();
        if ((isArray || pathObject instanceof Collection<?>) && expanding.contains(pathObject)) {
            // The string form of an array or Collection names no file, and the string form of a Collection that
            // holds itself through another Collection recurses until the stack overflows, so it is skipped
            return false;
        }
        if ((isArray || pathObject instanceof Iterable<?>) && expanding.add(pathObject)) {
            var added = false;
            try {
                if (isArray) {
                    for (int j = 0, n = Array.getLength(pathObject); j < n; j++) {
                        added |= addFlattened(Array.get(pathObject, j), splitStrings, classLoader, log, expanding);
                    }
                } else {
                    for (final Object elt : (Iterable<?>) pathObject) {
                        added |= addFlattened(elt, splitStrings, classLoader, log, expanding);
                    }
                }
            } finally {
                expanding.remove(pathObject);
            }
            return added;
        }
        // A string, or any other object, which is converted with toString()
        return splitStrings ? addClasspathPathStr(pathObject.toString(), classLoader, log)
                : addSingleClasspathEntry(pathObject, classLoader, log);
    }

    /**
     * Add a single classpath entry.
     *
     * @param pathElement
     *            the {@link String} path, {@link URL}, {@link URI}, {@link File} or {@link Path} of the classpath
     *            element, or some other object whose {@link Object#toString()} method gives the classpath element.
     * @param classLoader
     *            the {@link ClassLoader} that this classpath element was obtained from.
     * @param log
     *            the log node, or null to skip logging
     * @return true if the classpath element was added.
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
                && !new File(pathElementStr).exists()) {
            hasWildcardSuffix = true;
            pathElementStr = pathElementStr.substring(0, pathElementStr.length() - 2);
            // Leave pathElementURL null, so that wildcards can be handled below
        } else if ("*".equals(pathElementStr) && !new File(pathElementStr).exists()) {
            // Reached when the current directory path is empty, because user.dir could not be read. "." names the
            // current directory
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
