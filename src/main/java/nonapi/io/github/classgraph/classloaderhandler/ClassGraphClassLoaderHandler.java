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
 * Copyright (c) 2019 Luke Hutchison
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
package nonapi.io.github.classgraph.classloaderhandler;

import java.net.URL;

import io.github.classgraph.ClassGraphClassLoader;
import nonapi.io.github.classgraph.classpath.ClassLoaderFinder;
import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;

/**
 * Allow for overrideClassLoaders to be called with a ClassGraphClassLoader as a
 * parameter, so that nested scans can share a single classloader (#485).
 */
class ClassGraphClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final LogNode log) {
        final var matches = ClassLoaderFinder.classIsOrExtendsOrImplements(classLoaderClass,
                "io.github.classgraph.ClassGraphClassLoader");
        if (matches && log != null) {
            log.log("Sharing a `ClassGraphClassLoader` between multiple nested scans is not advisable, "
                    + "because scan criteria may differ between scans. "
                    + "See: https://github.com/classgraph/classgraph/issues/485");
        }
        return matches;
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final LogNode log) {
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        classLoaderOrder.add(classLoader, log);
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final ScanSpec scanSpec, final LogNode log) {
        // ClassGraphClassLoader overrides URLClassLoader, so we can get the basic
        // classpath URLs the same
        // way as for URLClassLoader. However, classloading will try to preferentially
        // reuse the older
        // ClassGraphClassLoader before loading with the new ClassGraphClassLoader from
        // the current scan,
        // so the following URLs will be scanned by the current scan, but classes will
        // only be loaded from
        // these URLs if the older classloader fails.
        for (final URL url : ((ClassGraphClassLoader) classLoader).getURLs()) {
            if (url != null) {
                classpathOrder.addClasspathEntry(url, classLoader, scanSpec, log);
            }
        }
    }

    /**
     * Get the automatic package root prefixes for classpath elements obtained from
     * this classloader.
     *
     * <p>
     * This handler only delegates to a previous scan's ClassGraphClassLoader, and
     * adds no classpath elements of its own.
     *
     * @return the package root prefixes.
     */
    @Override
    public String[] getPackageRootPrefixes() {
        return ClassLoaderHandlerRegistry.NO_PACKAGE_ROOT_PREFIXES;
    }
}
