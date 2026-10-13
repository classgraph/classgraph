/*
 * This file is part of ClassGraph.
 *
 * Author: @jacobg on GitHub
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2026 @jacobg, Luke Hutchison
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
package io.github.classgraph.classpath.internal.classloaderhandler;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;

/** Extract classpath entries from the Ant ClassLoader. */
class AntClassLoaderHandler implements ClassLoaderHandler {
    /** Constructor. */
    AntClassLoaderHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
        return classIsOrExtendsOrImplements(classLoaderClass, "org.apache.tools.ant.AntClassLoader");
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable ClassGraphLog log) {
        // AntClassLoader does not pass its parent to the ClassLoader constructor, so getParent() returns the system
        // classloader. The parent that it delegates to is in its "parent" field. If that is null, it delegates to
        // the system classloader.
        final var parentField = ReflectionUtils.getFieldVal(false, classLoader, "parent");
        final var parent = parentField instanceof final ClassLoader p ? p : classLoader.getParent();
        // AntClassLoader can also be set to delegate to its parent last, as Ant's junit and java tasks do. The java
        // task also makes it isolated, and an isolated classloader that delegates to its parent last does not
        // delegate to it at all. Whatever these settings are, the classes in some packages can be delegated to the
        // parent first and others last, which cannot be represented here.
        final var parentFirstField = ReflectionUtils.getFieldVal(false, classLoader, "parentFirst");
        final var parentFirst = !Boolean.FALSE.equals(parentFirstField);
        final var isolated = Boolean.TRUE.equals(ReflectionUtils.getFieldVal(false, classLoader, "ignoreBase"));
        if (parentFirst) {
            classLoaderOrder.delegateToParent(parent, log);
        }
        classLoaderOrder.add(classLoader, log);
        if (!parentFirst && !isolated) {
            classLoaderOrder.delegateToParent(parent, log);
        }
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final @Nullable ClassGraphLog log) {
        classpathOrder.addClasspathPathStr(
                (String) ReflectionUtils.invokeMethod(false, classLoader, "getClasspath"), classLoader, log);
    }

}
