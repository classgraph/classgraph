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

import io.github.classgraph.base.ClassGraphLog;
import org.jspecify.annotations.Nullable;

/**
 * The order in which a {@link ClassLoader} and the classloaders it delegates to resolve classes, built up by
 * {@link ClassLoaderHandler#findClassLoaderOrder(ClassLoader, ClassLoaderOrder, ClassGraphLog)}.
 *
 * <p>
 * A handler places the classloader it was given with {@link #add(ClassLoader, ClassGraphLog)}, and reaches the
 * classloaders it delegates to with {@link #delegateToParent(ClassLoader, ClassGraphLog)} and
 * {@link #delegateToPeer(ClassLoader, ClassGraphLog)}. The order these are called in is what tells ClassGraph
 * whether the classloader resolves classes parent-first or parent-last, which in turn decides which copy of a
 * duplicated class wins.
 *
 * <p>
 * ClassGraph provides the only implementation of this interface, and passes it to a {@link ClassLoaderHandler}. It
 * is not meant to be implemented outside ClassGraph, and methods may be added to it in later releases.
 */
public interface ClassLoaderOrder {
    /**
     * Add a {@link ClassLoader} to the classloader order at the current position.
     *
     * @param classLoader
     *            the class loader, or null (ignored)
     * @param log
     *            the log node, or null to skip logging
     */
    void add(@Nullable ClassLoader classLoader, @Nullable ClassGraphLog log);

    /**
     * Recursively delegate to the parent of the classloader being handled, or to another classloader that acts as
     * one of its ancestors, such as a container's shared classloader or an import that the classloader treats as a
     * parent.
     *
     * <p>
     * {@link ClasspathFinder#ignoreParentClassLoaders()} leaves out the classpath elements of a classloader reached
     * this way. A classloader reached this way by any handler counts as a parent, even if another handler reaches
     * it with {@link #delegateToPeer(ClassLoader, ClassGraphLog)}. The one exception is when more than one set of
     * classloaders is searched, for example after two calls to
     * {@link ClasspathFinder#enableClassLoaders(ClassLoader...)}: a classloader whose classpath elements were
     * already added while an earlier set was searched keeps them.
     *
     * <p>
     * The classloader is not placed in the order here: its own handler places it, by calling
     * {@link #add(ClassLoader, ClassGraphLog)} either before or after it delegates to the classloader's parent,
     * according to whether the classloader resolves classes parent-first or parent-last. The handlers of a
     * classloader are run only the first time it is reached, by either method.
     *
     * @param parent
     *            the parent class loader, or null (ignored)
     * @param log
     *            the log node, or null to skip logging
     */
    void delegateToParent(@Nullable ClassLoader parent, @Nullable ClassGraphLog log);

    /**
     * Recursively delegate to a classloader that is not an ancestor of the classloader being handled, such as a
     * library classloader in a list of delegates, or the classloader that a wrapper classloader forwards to.
     *
     * <p>
     * {@link ClasspathFinder#ignoreParentClassLoaders()} keeps the classpath elements of a classloader reached this
     * way, unless a handler also reaches it with {@link #delegateToParent(ClassLoader, ClassGraphLog)} (see there
     * for the one exception).
     *
     * <p>
     * The classloader is not placed in the order here: its own handler places it, by calling
     * {@link #add(ClassLoader, ClassGraphLog)}. The handlers of a classloader are run only the first time it is
     * reached, by either method.
     *
     * @param classLoader
     *            the class loader, or null (ignored)
     * @param log
     *            the log node, or null to skip logging
     */
    void delegateToPeer(@Nullable ClassLoader classLoader, @Nullable ClassGraphLog log);
}
