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
import java.lang.reflect.Array;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.classgraph.ClassGraph.ClasspathElementFilter;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** A class to find the unique ordered classpath elements. */
public class ClasspathOrder {
    /** The scan spec. */
    private final ScanSpec scanSpec;

    /** Unique classpath entries. */
    private final Set<String> classpathEntryUniqueResolvedPaths = new HashSet<>();

    /** The classpath order. Keys are instances of {@link String} or {@link URL}. */
    private final List<ClasspathElementAndClassLoader> order = new ArrayList<>();

    /**
     * A classpath element and the {@link ClassLoader} it was obtained from.
     */
    public static class ClasspathElementAndClassLoader {
        /** The classpath element (a {@link String} or {@link URL}). */
        public final Object classpathElement;

        /** The classloader the classpath element was obtained from. */
        public final ClassLoader classLoader;

        /**
         * Constructor.
         *
         * @param classpathElement
         *            the classpath element (a {@link String} or {@link URL}).
         * @param classLoader
         *            the classloader the classpath element was obtained from.
         */
        public ClasspathElementAndClassLoader(final Object classpathElement, final ClassLoader classLoader) {
            this.classpathElement = classpathElement;
            this.classLoader = classLoader;
        }
    }

    /**
     * Constructor.
     *
     * @param scanSpec
     *            the scan spec
     */
    ClasspathOrder(final ScanSpec scanSpec) {
        this.scanSpec = scanSpec;
    }

    /**
     * Get the order of classpath elements, uniquified and in order.
     *
     * @return the classpath order.
     */
    public List<ClasspathElementAndClassLoader> getOrder() {
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
     * Test to see if a classpath element has been filtered out by the user.
     *
     * @param classpathElementPath
     *            the classpath element path
     * @return true, if not filtered out
     */
    private boolean filter(final String classpathElementPath) {
        if (scanSpec.classpathElementFilters != null) {
            for (final ClasspathElementFilter filter : scanSpec.classpathElementFilters) {
                if (!filter.includeClasspathElement(classpathElementPath)) {
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
     *            FastPathResolver.resolve(FileUtils.CURR_DIR_PATH, path
     * @param classLoader
     *            the classloader
     * @return true, if added and unique
     */
    boolean addSystemClasspathEntry(final String pathEntry, final ClassLoader classLoader) {
        if (classpathEntryUniqueResolvedPaths.add(pathEntry)) {
            order.add(new ClasspathElementAndClassLoader(pathEntry, classLoader));
            return true;
        }
        return false;
    }

    /**
     * Add a classpath entry.
     *
     * @param pathElement
     *            the {@link String} path, {@link URL} or {@link URI} of the classpath element. If a string, the
     *            path string should already have been run through FastPathResolver.resolve(FileUtils.CURR_DIR_PATH,
     *            path)
     * @param pathElementStr
     *            the path element in string format
     * @param classLoader
     *            the classloader
     * @param scanSpec
     *            the scan spec
     * @return true, if added and unique
     */
    private boolean addClasspathEntry(final Object pathElement, final String pathElementStr,
            final ClassLoader classLoader, final ScanSpec scanSpec) {
        if (pathElement instanceof URL || pathElement instanceof URI) {
            // Assume that any custom URLs or URIs passed in are not in lib or ext dir, and are not system jars
            if (classpathEntryUniqueResolvedPaths.add(pathElementStr)) {
                order.add(new ClasspathElementAndClassLoader(pathElement, classLoader));
                return true;
            }
        } else {
            if (scanSpec.overrideClasspath == null //
                    && (SystemJarFinder.getJreLibOrExtJars().contains(pathElementStr)
                            || pathElementStr.equals(SystemJarFinder.getJreRtJarPath()))) {
                // JRE lib and ext jars are handled separately, so reject them as duplicates if they are 
                // returned by a system classloader
                return false;
            }
            if (classpathEntryUniqueResolvedPaths.add(pathElementStr)) {
                order.add(new ClasspathElementAndClassLoader(pathElementStr, classLoader));
                return true;
            }
        }
        return false;
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
    public boolean addClasspathEntry(final Object pathElement, final ClassLoader classLoader,
            final ScanSpec scanSpec, final LogNode log) {
        if (pathElement == null) {
            return false;
        }
        final String pathElementStr = pathElement.toString();
        if (pathElementStr.isEmpty()) {
            return false;
        }
        if (pathElement instanceof URL || pathElement instanceof URI || pathElement instanceof File) {
            if (!filter(pathElementStr)) {
                if (log != null) {
                    log.log("Classpath element did not match filter criterion, skipping: " + pathElementStr);
                }
                return false;
            }
            // For URL objects, use the object itself (so that URL scheme handling can be undertaken later);
            // for URI objects, convert to URL; for File objects, use the toString result (the path)
            final Object classpathElementObj;
            try {
                classpathElementObj = pathElement instanceof File ? pathElementStr
                        : pathElement instanceof URI ? ((URI) pathElement).toURL() : pathElement;
            } catch (final MalformedURLException e) {
                if (log != null) {
                    log.log("Cannot convert from URI to URL: " + pathElementStr);
                }
                return false;
            }
            if (addClasspathEntry(classpathElementObj, pathElementStr, classLoader, scanSpec)) {
                if (log != null) {
                    log.log("Found classpath element: " + pathElementStr);
                }
                return true;
            } else {
                if (log != null) {
                    log.log("Ignoring duplicate classpath element: " + pathElementStr);
                }
                return false;
            }
        } else {
            // Check for wildcard path element (allowable for local classpaths as of JDK 6)
            if (pathElementStr.endsWith("*")) {
                if (pathElementStr.length() == 1 || //
                        (pathElementStr.length() > 2 && pathElementStr.charAt(pathElementStr.length() - 1) == '*'
                                && (pathElementStr.charAt(pathElementStr.length() - 2) == File.separatorChar
                                        || (File.separatorChar != '/'
                                                && pathElementStr.charAt(pathElementStr.length() - 2) == '/')))) {
                    // Apply classpath element filters, if any 
                    final String baseDirPath = pathElementStr.length() == 1 ? ""
                            : pathElementStr.substring(0, pathElementStr.length() - 2);
                    final String baseDirPathResolved = FastPathResolver.resolve(FileUtils.CURR_DIR_PATH,
                            baseDirPath);
                    if (!filter(baseDirPath)
                            || (!baseDirPathResolved.equals(baseDirPath) && !filter(baseDirPathResolved))) {
                        if (log != null) {
                            log.log("Classpath element did not match filter criterion, skipping: "
                                    + pathElementStr);
                        }
                        return false;
                    }

                    // Check the path before the "/*" suffix is a directory 
                    final File baseDir = new File(baseDirPathResolved);
                    if (!baseDir.exists()) {
                        if (log != null) {
                            log.log("Directory does not exist for wildcard classpath element: " + pathElementStr);
                        }
                        return false;
                    }
                    if (!FileUtils.canRead(baseDir)) {
                        if (log != null) {
                            log.log("Cannot read directory for wildcard classpath element: " + pathElementStr);
                        }
                        return false;
                    }
                    if (!baseDir.isDirectory()) {
                        if (log != null) {
                            log.log("Wildcard is appended to something other than a directory: " + pathElementStr);
                        }
                        return false;
                    }

                    // Add all elements in the requested directory to the classpath
                    final LogNode dirLog = log == null ? null
                            : log.log("Adding classpath elements from wildcarded directory: " + pathElementStr);
                    final File[] baseDirFiles = baseDir.listFiles();
                    if (baseDirFiles != null) {
                        for (final File fileInDir : baseDirFiles) {
                            final String name = fileInDir.getName();
                            if (!name.equals(".") && !name.equals("..")) {
                                // Add each directory entry as a classpath element
                                final String fileInDirPath = fileInDir.getPath();
                                final String fileInDirPathResolved = FastPathResolver
                                        .resolve(FileUtils.CURR_DIR_PATH, fileInDirPath);
                                if (addClasspathEntry(fileInDirPathResolved, fileInDirPathResolved, classLoader,
                                        scanSpec)) {
                                    if (dirLog != null) {
                                        dirLog.log("Found classpath element: " + fileInDirPath
                                                + (fileInDirPath.equals(fileInDirPathResolved) ? ""
                                                        : " -> " + fileInDirPathResolved));
                                    }
                                } else {
                                    if (dirLog != null) {
                                        dirLog.log("Ignoring duplicate classpath element: " + fileInDirPath
                                                + (fileInDirPath.equals(fileInDirPathResolved) ? ""
                                                        : " -> " + fileInDirPathResolved));
                                    }
                                }
                            }
                        }
                        return true;
                    } else {
                        return false;
                    }
                } else {
                    if (log != null) {
                        log.log("Wildcard classpath elements can only end with a leaf of \"*\", "
                                + "can't have a partial name and then a wildcard: " + pathElementStr);
                    }
                    return false;
                }
            } else {
                // Non-wildcarded (standard) classpath element
                final String pathElementResolved = FastPathResolver.resolve(FileUtils.CURR_DIR_PATH,
                        pathElementStr);
                if (!filter(pathElementStr)
                        || (!pathElementResolved.equals(pathElementStr) && !filter(pathElementResolved))) {
                    if (log != null) {
                        log.log("Classpath element did not match filter criterion, skipping: " + pathElementStr
                                + (pathElementStr.equals(pathElementResolved) ? "" : " -> " + pathElementResolved));
                    }
                    return false;
                }
                if (addClasspathEntry(pathElementResolved, pathElementResolved, classLoader, scanSpec)) {
                    if (log != null) {
                        log.log("Found classpath element: " + pathElementStr
                                + (pathElementStr.equals(pathElementResolved) ? "" : " -> " + pathElementResolved));
                    }
                    return true;
                } else {
                    if (log != null) {
                        log.log("Ignoring duplicate classpath element: " + pathElementStr
                                + (pathElementStr.equals(pathElementResolved) ? "" : " -> " + pathElementResolved));
                    }
                    return false;
                }
            }
        }
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
    public boolean addClasspathEntries(final List<Object> overrideClasspath, final ClassLoader classLoader,
            final ScanSpec scanSpec, final LogNode log) {
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
    public boolean addClasspathPathStr(final String pathStr, final ClassLoader classLoader, final ScanSpec scanSpec,
            final LogNode log) {
        if (pathStr == null || pathStr.isEmpty()) {
            return false;
        } else {
            final String[] parts = JarUtils.smartPathSplit(pathStr, scanSpec);
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
     * {@link URI}, a {@link File} or a {@link String} (containing a single classpath element path, or several paths
     * separated with File.pathSeparator), a List or other Iterable, or an array object. In the case of Iterables
     * and arrays, the elements may be any type whose {@code toString()} method returns a path or URL string
     * (including the {@code URL} and {@code Path} types).
     *
     * @param pathObject
     *            the object containing a classpath string or strings.
     * @param classLoader
     *            the ClassLoader that this classpath was obtained from.
     * @param scanSpec
     *            the scan spec
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathEl)ement is not null or empty, otherwise return false.
     */
    public boolean addClasspathEntryObject(final Object pathObject, final ClassLoader classLoader,
            final ScanSpec scanSpec, final LogNode log) {
        boolean valid = false;
        if (pathObject != null) {
            if (pathObject instanceof URL || pathObject instanceof URI) {
                valid |= addClasspathEntry(pathObject, classLoader, scanSpec, log);
            } else if (pathObject instanceof Iterable) {
                for (final Object elt : (Iterable<?>) pathObject) {
                    valid |= addClasspathEntryObject(elt, classLoader, scanSpec, log);
                }
            } else {
                final Class<?> valClass = pathObject.getClass();
                if (valClass.isArray()) {
                    for (int j = 0, n = Array.getLength(pathObject); j < n; j++) {
                        final Object elt = Array.get(pathObject, j);
                        valid |= addClasspathEntryObject(elt, classLoader, scanSpec, log);
                    }
                } else {
                    // Try simply calling toString() as a final fallback, to handle String objects, or to
                    // try to handle anything else
                    valid |= addClasspathPathStr(pathObject.toString(), classLoader, scanSpec, log);
                }
            }
        }
        return valid;
    }
}
