/*
 * This file is part of ClassGraph.
 *
 * Author: Michael J. Simons
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
package io.github.classgraph.classpath.internal.classloaderhandler;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.classpath.ClassLoaderOrder;
import org.jspecify.annotations.Nullable;

/**
 * This handler uses parent-last delegation order (i.e. it adds the classloader itself to the classloader order
 * before delegating to the parent) to support the <code>RestartClassLoader</code> of Spring Boot's devtools.
 * <code>RestartClassLoader</code> provides parent-last loading for specified URLs (those are all that are supposed
 * to be changed during development). Therefore the handler for that class loader also has to delegate in
 * parent-last order.
 */
class SpringBootRestartClassLoaderHandler extends URLClassLoaderHandler {
    /** Constructor. */
    SpringBootRestartClassLoaderHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
        return classIsOrExtendsOrImplements(classLoaderClass,
                "org.springframework.boot.devtools.restart.classloader.RestartClassLoader");
    }

    // #267, #268
    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable ClassGraphLog log) {
        // The Restart classloader sits in front of its parent and shades the directories it watches for changes.
        // Those classes are reachable through the parent too, but they have to be loaded from the Restart
        // classloader, so the Restart classloader itself is added to the classloader order first ...
        classLoaderOrder.add(classLoader, log);

        // ... and its parent is delegated to afterwards, so that the parent is searched last
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
    }

    // findClasspathOrder() is inherited from URLClassLoaderHandler, since RestartClassLoader extends
    // URLClassLoader, and is constructed with the URLs of the directories it shades

}
