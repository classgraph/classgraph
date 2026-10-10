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

import io.github.classgraph.base.ClassGraphLog;
import org.jspecify.annotations.Nullable;

/**
 * The classpath entries found so far, in the order that classes are resolved from them, built up by
 * {@link ClassLoaderHandler#findClasspathOrder(ClassLoader, ClasspathOrder, ClassGraphLog)}.
 *
 * <p>
 * Entries are added in resolution order, and a duplicate entry is ignored, so the first classloader to contribute a
 * given classpath element is the one it is attributed to. A classpath element that names a file or directory is
 * deduplicated by the canonical path of that file, so the same file added twice under two different paths --
 * through a symbolic link, or spelled with a different case on a filesystem that ignores case -- is added only
 * once.
 *
 * <p>
 * A classpath element that names a file or directory the filesystem says is not there, or that cannot be read, is
 * logged and skipped rather than added, since it cannot contribute any class to the scan.
 *
 * <p>
 * A {@code ClasspathOrder} is valid only during the {@code findClasspathOrder} call it is passed to. Do not keep a
 * reference to it: an entry added after that call returns is not recorded with the package root prefixes and lib
 * dir prefixes of the handler it was passed to, and may not be seen by the scan.
 *
 * <p>
 * ClassGraph provides the only implementation of this interface, and passes it to a {@link ClassLoaderHandler}. It
 * is not meant to be implemented outside ClassGraph, and methods may be added to it in later releases.
 */
public interface ClasspathOrder {
    /**
     * Add a classpath entry, or each classpath entry in an array or {@link Iterable}.
     *
     * <p>
     * The object may be a {@link String} path, {@link URL}, {@link URI}, {@link File} or {@link Path}, or an array
     * or {@link Iterable} of any of those, nested to any depth. A {@link Path} is one classpath entry, although it
     * is an {@link Iterable} of its name elements. A {@link Path} keeps its own filesystem, so it need not be on
     * the default filesystem; do not convert it to a string first. Anything else is converted with
     * {@link Object#toString()}. An array or {@link Iterable} that is met again while its own elements are being
     * added is not expanded again: an array or {@link java.util.Collection} is skipped, and any other
     * {@link Iterable} is converted with {@link Object#toString()}.
     *
     * <p>
     * A {@link String} is always one path, and is never split at the system path separator character. Pass a string
     * that holds several paths to {@link #addClasspathPathStr(String, ClassLoader, ClassGraphLog)} or
     * {@link #addClasspathEntryObject(Object, ClassLoader, ClassGraphLog)} instead.
     *
     * @param pathElement
     *            the classpath entry, or the array or {@link Iterable} of classpath entries.
     * @param classLoader
     *            the {@link ClassLoader} that the classpath entries were obtained from.
     * @param log
     *            the log node, or null to skip logging
     * @return true if at least one classpath element was added, or false if each one was null or empty, named a
     *         file or directory that the filesystem says is not there or cannot be read, was filtered out by the
     *         user's classpath element filters, or duplicated a classpath element already added.
     */
    boolean addClasspathEntry(@Nullable Object pathElement, @Nullable ClassLoader classLoader,
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
     * any of those, nested to any depth. Anything else is converted with {@link Object#toString()}, and the result
     * is split at the system path separator character, as a {@link String} is.
     *
     * <p>
     * This differs from {@link #addClasspathEntry(Object, ClassLoader, ClassGraphLog)} only in splitting strings,
     * so a directory whose name holds the system path separator character cannot be added with this method. As
     * there, an array or {@link Iterable} that is met again while its own elements are being added is not expanded
     * again.
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
     *            a name for the piece of work, unique within the handler that claims it. Handlers of different
     *            classes have separate sets of keys, so they cannot claim each other's work, while all instances of
     *            one handler class share one set of keys.
     * @return true the first time a handler of a given class calls this method with a given key during a scan,
     *         false every time thereafter.
     */
    boolean claimOncePerScan(String key);
}
