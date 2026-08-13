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
package io.github.classgraph.classpath;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;

import io.github.classgraph.base.ClassGraphLog;
import org.jspecify.annotations.Nullable;

/**
 * The classpath entries found so far, in the order that classes are resolved from them, built up by
 * {@link ClassLoaderHandler#findClasspathOrder(ClassLoader, ClasspathOrder, ClassGraphLog)}.
 *
 * <p>
 * Entries are added in resolution order, and a duplicate entry is ignored, so the first classloader to contribute a
 * given classpath element is the one it is attributed to.
 *
 * <p>
 * This is not implemented outside ClassGraph.
 */
public interface ClasspathOrder {
    /**
     * Add a classpath entry.
     *
     * @param pathElement
     *            the {@link String} path, {@link URL} or {@link URI} of the classpath element, or some object whose
     *            {@link Object#toString()} method can be called to obtain the classpath element.
     * @param classLoader
     *            the {@link ClassLoader} that this classpath element was obtained from.
     * @param log
     *            the log node, or null to skip logging
     * @return true if the classpath element was added, or false if it was null, empty, nonexistent, or filtered out
     *         by the scan spec.
     */
    boolean addClasspathEntry(@Nullable Object pathElement, @Nullable ClassLoader classLoader,
            @Nullable ClassGraphLog log);

    /**
     * Add several classpath entries.
     *
     * @param classpathEntries
     *            a list of {@link String} path, {@link URL}, {@link URI} or {@link File} objects. A {@link String}
     *            may hold several paths, separated by the system path separator character.
     * @param classLoader
     *            the {@link ClassLoader} that these classpath entries were obtained from.
     * @param log
     *            the log node, or null to skip logging
     * @return true if at least one classpath element was added.
     */
    boolean addClasspathEntries(@Nullable List<Object> classpathEntries, @Nullable ClassLoader classLoader,
            @Nullable ClassGraphLog log);

    /**
     * Add classpath entries from a string of paths or URLs separated by the system path separator character.
     *
     * @param pathStr
     *            the delimited string of paths or URLs.
     * @param classLoader
     *            the {@link ClassLoader} that this classpath was obtained from.
     * @param log
     *            the log node, or null to skip logging
     * @return true if at least one classpath element was added.
     */
    boolean addClasspathPathStr(@Nullable String pathStr, @Nullable ClassLoader classLoader,
            @Nullable ClassGraphLog log);

    /**
     * Add classpath entries from an object obtained by reflection, of a type that is only known at runtime. The
     * object may be a {@link URL}, {@link URI}, {@link File}, {@link Path} or {@link String} (holding a single
     * path, or several paths separated by the system path separator character), or an array or {@link Iterable} of
     * any of those. Anything else is converted with {@link Object#toString()}.
     *
     * @param pathObject
     *            the object holding a classpath entry or entries.
     * @param classLoader
     *            the {@link ClassLoader} that this classpath was obtained from.
     * @param log
     *            the log node, or null to skip logging
     * @return true if at least one classpath element was added.
     */
    boolean addClasspathEntryObject(@Nullable Object pathObject, @Nullable ClassLoader classLoader,
            @Nullable ClassGraphLog log);

    /**
     * Claim a piece of work that only needs doing once per scan.
     *
     * <p>
     * A {@link ClassLoaderHandler} instance is shared between all scans, so it cannot remember in a field of its
     * own that it has already done something: the field would stay set for every later scan in the same JVM. This
     * object belongs to one scan, so it can.
     *
     * <p>
     * For example, all Equinox classloaders yield the same OSGi system bundles, so the built-in Equinox handler
     * only reads them from the first Equinox classloader it is given in each scan.
     *
     * @param key
     *            a name for the piece of work, unique within the handler that claims it.
     * @return true the first time this method is called with a given key during a scan, false every time
     *         thereafter.
     */
    boolean claimOncePerScan(String key);
}
