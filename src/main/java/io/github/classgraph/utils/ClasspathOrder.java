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
package io.github.classgraph.utils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.LinkedHashSet;

import io.github.classgraph.ClassGraph.ClasspathElementFilter;
import io.github.classgraph.ScanSpec;

/** A class to find the unique ordered classpath elements. */
public class ClasspathOrder {
    private final ScanSpec scanSpec;
    private final NestedJarHandler nestedJarHandler;

    private final LinkedHashSet<ClasspathOrModulePathEntry> classpathOrder = new LinkedHashSet<>();

    ClasspathOrder(final ScanSpec scanSpec, final NestedJarHandler nestedJarHandler) {
        this.scanSpec = scanSpec;
        this.nestedJarHandler = nestedJarHandler;
    }

    /** Get the order of classpath elements. */
    LinkedHashSet<ClasspathOrModulePathEntry> get() {
        return classpathOrder;
    }

    /** Test to see if a RelativePath has been filtered out by the user. */
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
     * Add a classpath element relative to a base file. May be called by a ClassLoaderHandler to add classpath
     * elements that it knows about. ClassLoaders will be called in order.
     *
     * @param pathElement
     *            the URL or path of the classpath element.
     * @param classLoaders
     *            the ClassLoader(s) that this classpath element was obtained from.
     * @param scanSpec
     *            the ScanSpec.
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathElement is not null, empty, nonexistent, or filtered out
     *         by user-specified criteria, otherwise return false.
     */
    boolean addClasspathElement(final String pathElement, final ClassLoader[] classLoaders, final ScanSpec scanSpec,
            final LogNode log) {
        if (pathElement == null || pathElement.isEmpty()) {
            return false;
        }
        LogNode subLog = null;
        if (log != null) {
            subLog = log.log("Found classpath element: " + pathElement);
        }
        if (pathElement.endsWith("*")) {
            if (pathElement.length() == 1 || //
                    (pathElement.length() > 2 && pathElement.charAt(pathElement.length() - 1) == '*'
                            && (pathElement.charAt(pathElement.length() - 2) == File.separatorChar
                                    || (File.separatorChar != '/'
                                            && pathElement.charAt(pathElement.length() - 2) == '/')))) {
                // Got wildcard path element (allowable for local classpaths as of JDK 6)
                try {
                    final ClasspathOrModulePathEntry classpathEltParentDirRelativePath = //
                            new ClasspathOrModulePathEntry(FileUtils.CURR_DIR_PATH,
                                    pathElement.substring(0, pathElement.length() - 2), classLoaders,
                                    nestedJarHandler, scanSpec, subLog);
                    final String classpathEltParentDirPath = classpathEltParentDirRelativePath.getResolvedPath();
                    if (!filter(classpathEltParentDirPath)) {
                        if (log != null) {
                            log.log("Classpath element did not match filter criterion, skipping: "
                                    + classpathEltParentDirRelativePath);
                        }
                        return false;
                    }
                    final File classpathEltParentDir = classpathEltParentDirRelativePath.getFile(subLog);
                    if (!classpathEltParentDir.exists()) {
                        if (subLog != null) {
                            subLog.log("Directory does not exist for wildcard classpath element: " + pathElement);
                        }
                        return false;
                    }
                    if (!classpathEltParentDir.isDirectory()) {
                        if (subLog != null) {
                            subLog.log("Wildcard classpath element is not a directory: " + pathElement);
                        }
                        return false;
                    }
                    for (final File fileInDir : classpathEltParentDir.listFiles()) {
                        final String name = fileInDir.getName();
                        if (!name.equals(".") && !name.equals("..")) {
                            // Add each directory entry as a classpath element
                            final String fileInDirPath = fileInDir.getPath();
                            final LogNode subSubLog = subLog == null ? null
                                    : subLog.log("Including classpath element matching wildcard: " + fileInDirPath);
                            addClasspathElement(fileInDirPath, classLoaders, scanSpec, subSubLog);
                        }
                    }
                    return true;
                } catch (final IOException e) {
                    if (subLog != null) {
                        subLog.log("Could not add wildcard classpath element " + pathElement + " : " + e);
                    }
                    return false;
                }
            } else {
                if (subLog != null) {
                    subLog.log("Wildcard classpath elements can only end with a leaf of \"*\", "
                            + "can't have a partial name and then a wildcard: " + pathElement);
                }
                return false;
            }
        } else {
            final ClasspathOrModulePathEntry classpathEltRelativePath = new ClasspathOrModulePathEntry(
                    FileUtils.CURR_DIR_PATH, pathElement, classLoaders, nestedJarHandler, scanSpec, subLog);
            final String classpathEltPath = classpathEltRelativePath.getResolvedPath();
            if (!filter(classpathEltPath)) {
                if (log != null) {
                    log.log("Classpath element did not match filter criterion, skipping: "
                            + classpathEltRelativePath);
                }
                return false;
            }
            if (classpathOrder.add(classpathEltRelativePath)) {
                if (subLog != null) {
                    if (!classpathEltRelativePath.getResolvedPath().equals(pathElement)) {
                        subLog.log("Normalized path: " + classpathEltRelativePath);
                    }
                }
                return true;
            } else {
                if (subLog != null) {
                    if (!classpathEltRelativePath.getResolvedPath().equals(pathElement)) {
                        subLog.log("Ignoring duplicate classpath element: " + classpathEltRelativePath);
                    } else {
                        subLog.log("Ignoring duplicate classpath element");
                    }
                }
                return false;
            }
        }
    }

    /**
     * Add classpath elements, separated by the system path separator character. May be called by a
     * ClassLoaderHandler to add a path string that it knows about.
     *
     * @param pathStr
     *            the delimited string of URLs or paths of the classpath.
     * @param classLoaders
     *            the ClassLoader(s) that this classpath was obtained from.
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    public boolean addClasspathElements(final String pathStr, final ClassLoader[] classLoaders, final LogNode log) {
        if (pathStr == null || pathStr.isEmpty()) {
            return false;
        } else {
            final String[] parts = JarUtils.smartPathSplit(pathStr);
            if (parts.length == 0) {
                return false;
            } else {
                for (final String pathElement : parts) {
                    addClasspathElement(pathElement, classLoaders, scanSpec, log);
                }
                return true;
            }
        }
    }

    /**
     * Add a classpath element relative to a base file. May be called by a ClassLoaderHandler to add classpath
     * elements that it knows about.
     *
     * @param pathElement
     *            the URL or path of the classpath element.
     * @param classLoader
     *            the ClassLoader that this classpath element was obtained from.
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    public boolean addClasspathElement(final String pathElement, final ClassLoader classLoader, final LogNode log) {
        return addClasspathElement(pathElement, new ClassLoader[] { classLoader }, scanSpec, log);
    }

    /**
     * Add classpath elements from an object obtained from reflection. The object may be a String (containing a
     * single path, or several paths separated with File.pathSeparator), a List or other Iterable, or an array
     * object. In the case of Iterables and arrays, the elements may be any type whose {@code toString()} method
     * returns a path or URL string (including the {@code URL} and {@code Path} types).
     *
     * @param pathObject
     *            the object containing a classpath string or strings.
     * @param classLoader
     *            the ClassLoader that this classpath was obtained from.
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathEl)ement is not null or empty, otherwise return false.
     */
    public boolean addClasspathElementObject(final Object pathObject, final ClassLoader classLoader,
            final LogNode log) {
        boolean valid = false;
        if (pathObject != null) {
            if (pathObject instanceof String) {
                valid |= addClasspathElements((String) pathObject, classLoader, log);
            } else if (pathObject instanceof Iterable) {
                for (final Object p : (Iterable<?>) pathObject) {
                    if (p != null) {
                        valid |= addClasspathElements(p.toString(), classLoader, log);
                    }
                }
            } else {
                final Class<? extends Object> valClass = pathObject.getClass();
                if (valClass.isArray()) {
                    for (int j = 0, n = Array.getLength(pathObject); j < n; j++) {
                        final Object elt = Array.get(pathObject, j);
                        if (elt != null) {
                            valid |= addClasspathElementObject(elt, classLoader, log);
                        }
                    }
                } else {
                    // Try simply calling toString() as a final fallback, in case this returns something sensible
                    valid |= addClasspathElements(pathObject.toString(), classLoader, log);
                }
            }
        }
        return valid;
    }

    /**
     * Add classpath elements, separated by the system path separator character. May be called by a
     * ClassLoaderHandler to add a path string that it knows about.
     *
     * @param pathStr
     *            the delimited string of URLs or paths of the classpath.
     * @param classLoader
     *            the ClassLoader that this classpath was obtained from.
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathEl)ement is not null or empty, otherwise return false.
     */
    public boolean addClasspathElements(final String pathStr, final ClassLoader classLoader, final LogNode log) {
        return addClasspathElements(pathStr, new ClassLoader[] { classLoader }, log);
    }

    /**
     * Add all classpath elements in another ClasspathElementOrder after the elements in this order.
     *
     * @param subsequentOrder
     *            the ordering to add after this one.
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    public boolean addClasspathElements(final ClasspathOrder subsequentOrder) {
        return this.classpathOrder.addAll(subsequentOrder.classpathOrder);
    }
}
