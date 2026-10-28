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

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.ClassGraphLog;

/**
 * Accumulates classpath entries in resolution order as they are extracted by
 * {@link ClassLoaderHandler#findClasspathOrder(ClassLoader, ClasspathOrder, ClassGraphLog)}.
 *
 * <p>
 * Entries are deduplicated by canonical location (retaining the first occurrence), and local files or directories
 * that do not exist or cannot be read are skipped.
 *
 * <p>
 * A {@code ClasspathOrder} instance is valid only during the {@code findClasspathOrder} invocation to which it is
 * passed. This interface is implemented only by ClassGraph and is not intended to be implemented by callers.
 */
public interface ClasspathOrder {
    /**
     * Adds a classpath entry, or each entry in a nested array or {@link Iterable}.
     *
     * <p>
     * {@code pathElement} may be a {@link String} (treated as a single path, not split on the system path
     * separator), {@link URL}, {@link URI}, {@link File}, {@link Path} (treated as a single entry and preserving
     * its {@link java.nio.file.FileSystem}), or a nested array or {@link Iterable} of those types. Other objects
     * are converted via {@link Object#toString()}.
     *
     * @param pathElement
     *            the classpath entry, or an array or {@link Iterable} of classpath entries.
     * @param classLoader
     *            the {@link ClassLoader} from which the entries were obtained.
     * @param log
     *            the log node, or null to skip logging.
     * @return true if at least one new, valid classpath entry was added.
     */
    boolean addClasspathEntry(@Nullable Object pathElement, @Nullable ClassLoader classLoader,
            @Nullable ClassGraphLog log);

    /**
     * Splits a {@link File#pathSeparator}-delimited string of paths or URLs and adds each entry.
     *
     * @param pathStr
     *            the delimited string of paths or URLs.
     * @param classLoader
     *            the {@link ClassLoader} from which the classpath string was obtained.
     * @param log
     *            the log node, or null to skip logging.
     * @return true if at least one new, valid classpath entry was added.
     */
    boolean addClasspathPathStr(@Nullable String pathStr, @Nullable ClassLoader classLoader,
            @Nullable ClassGraphLog log);

    /**
     * Adds classpath entries from an arbitrary object obtained via reflection. Behaves like
     * {@link #addClasspathEntry(Object, ClassLoader, ClassGraphLog)}, except that {@link String} values (and
     * objects converted via {@link Object#toString()}) are split on {@link File#pathSeparator}.
     *
     * @param pathObject
     *            the object holding one or more classpath entries.
     * @param classLoader
     *            the {@link ClassLoader} from which the entries were obtained.
     * @param log
     *            the log node, or null to skip logging.
     * @return true if at least one new, valid classpath entry was added.
     */
    boolean addClasspathEntryObject(@Nullable Object pathObject, @Nullable ClassLoader classLoader,
            @Nullable ClassGraphLog log);

    /**
     * Marks a named action as performed for the current scan and returns true on the first call for a given
     * {@link ClassLoaderHandler} class and {@code key} within the scan, or false on subsequent calls.
     *
     * @param key
     *            an identifier scoped to the calling {@link ClassLoaderHandler} class.
     * @return true if this is the first time the calling handler class has claimed {@code key} in the current scan.
     */
    boolean claimOncePerScan(String key);
}
