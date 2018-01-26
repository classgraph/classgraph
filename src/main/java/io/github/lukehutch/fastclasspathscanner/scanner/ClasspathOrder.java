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
import java.io.IOException;
import java.lang.reflect.Array;

import io.github.lukehutch.fastclasspathscanner.utils.AdditionOrderedSet;
import io.github.lukehutch.fastclasspathscanner.utils.JarUtils;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.NestedJarHandler;

/** A class to find the unique ordered classpath elements. */
public class ClasspathOrder {
    final NestedJarHandler nestedJarHandler;

    private final AdditionOrderedSet<RelativePath> classpathOrder = new AdditionOrderedSet<>();

    ClasspathOrder(final NestedJarHandler nestedJarHandler) {
        this.nestedJarHandler = nestedJarHandler;
    }

    /** Get the order of classpath elements. */
    public AdditionOrderedSet<RelativePath> get() {
        return classpathOrder;
    }

    /**
     * Add a classpath element relative to a base file. May be called by a ClassLoaderHandler to add classpath
     * elements that it knows about. ClassLoaders will be called in order.
     *
     * @param pathElement
     *            the URL or path of the classpath element.
     * @param classLoaders
     *            the ClassLoader(s) that this classpath element was obtained from.
     * @param classpathElementOrderOut
     *            the AdditionOrderedSet to add classpath elements to.
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    public boolean addClasspathElement(final String pathElement, final ClassLoader[] classLoaders,
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
                    final File classpathEltParentDir = new RelativePath(ClasspathFinder.currDirPathStr,
                            pathElement.substring(0, pathElement.length() - 2), classLoaders, nestedJarHandler,
                            subLog).getFile(subLog);
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
                    final LogNode subSubLog = subLog == null ? null
                            : subLog.log("Including wildcard classpath element: " + pathElement);
                    for (final File fileInDir : classpathEltParentDir.listFiles()) {
                        final String name = fileInDir.getName();
                        if (!name.equals(".") && !name.equals("..")) {
                            // Add each directory entry as a classpath element
                            addClasspathElement(fileInDir.getPath(), classLoaders, subSubLog);
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
            final RelativePath classpathEltPath = new RelativePath(ClasspathFinder.currDirPathStr, pathElement,
                    classLoaders, nestedJarHandler, subLog);
            if (classpathOrder.add(classpathEltPath)) {
                if (subLog != null) {
                    if (!classpathEltPath.toString().equals(pathElement)) {
                        subLog.log("Normalized path: " + classpathEltPath);
                    }
                }
            } else {
                if (subLog != null) {
                    if (!classpathEltPath.toString().equals(pathElement)) {
                        subLog.log("Ignoring duplicate classpath element: " + classpathEltPath);
                    } else {
                        subLog.log("Ignoring duplicate classpath element");
                    }
                }
            }
            return true;
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
                    addClasspathElement(pathElement, classLoaders, log);
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
        return addClasspathElement(pathElement, new ClassLoader[] { classLoader }, log);
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
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    boolean addClasspathElements(final ClasspathOrder subsequentOrder) {
        return this.classpathOrder.addAll(subsequentOrder.classpathOrder);
    }
}
