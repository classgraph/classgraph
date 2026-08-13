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
 * classloaders it delegates to with {@link #delegateTo(ClassLoader, boolean, ClassGraphLog)}. The order the two are
 * called in is what tells ClassGraph whether the classloader resolves classes parent-first or parent-last, which in
 * turn decides which copy of a duplicated class wins.
 *
 * <p>
 * This is not implemented outside ClassGraph.
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
     * Recursively delegate to another {@link ClassLoader}.
     *
     * <p>
     * The classloader is not placed in the order here: its own handler places it, by calling
     * {@link #add(ClassLoader, ClassGraphLog)} either before or after it delegates to the classloader's parent,
     * according to whether the classloader resolves classes parent-first or parent-last.
     *
     * @param classLoader
     *            the class loader, or null (ignored)
     * @param isParent
     *            true if this is a parent of another classloader
     * @param log
     *            the log node, or null to skip logging
     */
    void delegateTo(@Nullable ClassLoader classLoader, boolean isParent, @Nullable ClassGraphLog log);
}
