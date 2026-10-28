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

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.ClassGraphLog;

/**
 * Records the class resolution and delegation order of {@link ClassLoader} instances, populated by
 * {@link ClassLoaderHandler#findClassLoaderOrder(ClassLoader, ClassLoaderOrder, ClassGraphLog)}.
 *
 * <p>
 * A {@link ClassLoaderHandler} adds its classloader with {@link #add(ClassLoader, ClassGraphLog)} and delegates to
 * parent or peer classloaders with {@link #delegateToParent(ClassLoader, ClassGraphLog)} and
 * {@link #delegateToPeer(ClassLoader, ClassGraphLog)}. The relative call order determines whether resolution is
 * parent-first or parent-last.
 *
 * <p>
 * This interface is implemented only by ClassGraph and is not intended to be implemented by callers.
 */
public interface ClassLoaderOrder {
    /**
     * Adds a {@link ClassLoader} to the resolution order at the current position.
     *
     * @param classLoader
     *            the classloader, or null (ignored).
     * @param log
     *            the log node, or null to skip logging.
     */
    void add(@Nullable ClassLoader classLoader, @Nullable ClassGraphLog log);

    /**
     * Recursively delegates to a parent or ancestor {@link ClassLoader} (such as {@link ClassLoader#getParent()} or
     * a shared container classloader).
     *
     * <p>
     * Classloaders reached via this method are excluded when {@link ClasspathFinder#ignoreParentClassLoaders()} is
     * enabled. The delegated classloader's own handler determines its position in the order (or places it after its
     * delegates if it does not call {@link #add(ClassLoader, ClassGraphLog)}), and each classloader is traversed at
     * most once.
     *
     * @param parent
     *            the parent classloader, or null (ignored).
     * @param log
     *            the log node, or null to skip logging.
     */
    void delegateToParent(@Nullable ClassLoader parent, @Nullable ClassGraphLog log);

    /**
     * Recursively delegates to a peer or wrapped {@link ClassLoader} that is not a parent classloader.
     *
     * <p>
     * Classloaders reached only via this method are retained when
     * {@link ClasspathFinder#ignoreParentClassLoaders()} is enabled. The delegated classloader's own handler
     * determines its position in the order (or places it after its delegates if it does not call
     * {@link #add(ClassLoader, ClassGraphLog)}), and each classloader is traversed at most once.
     *
     * @param classLoader
     *            the peer classloader, or null (ignored).
     * @param log
     *            the log node, or null to skip logging.
     */
    void delegateToPeer(@Nullable ClassLoader classLoader, @Nullable ClassGraphLog log);
}
