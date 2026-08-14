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
package io.github.classgraph.classpath.internal.classloaderhandler;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;
import org.jspecify.annotations.Nullable;

/**
 * ClassLoaderHandler that is able to extract the URLs from a CxfContainerClassLoader.
 */
class CxfContainerClassLoaderHandler implements ClassLoaderHandler {
    /** Constructor. */
    CxfContainerClassLoaderHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
        return classIsOrExtendsOrImplements(classLoaderClass,
                "org.apache.openejb.server.cxf.transport.util.CxfContainerClassLoader");
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable ClassGraphLog log) {
        try {
            classLoaderOrder.delegateTo(
                    Class.forName("org.apache.openejb.server.cxf.transport.util.CxfUtil").getClassLoader(),
                    /* isParent = */ true, log);
        } catch (LinkageError | ClassNotFoundException e) {
            // Ignore
        }
        // tccl = TomcatClassLoader
        classLoaderOrder.delegateTo((ClassLoader) ReflectionUtils.invokeMethod(false, classLoader, "tccl"),
                /* isParent = */ false, log);
        // This classloader doesn't actually load any classes, but add it to the order to improve logging
        classLoaderOrder.add(classLoader, log);
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final @Nullable ClassGraphLog log) {
        // Classloader doesn't do any classloading of its own, it only delegates to other classloaders
    }

    /**
     * Get the automatic package root prefixes for classpath elements obtained from this classloader.
     *
     * <p>
     * This classloader does not contribute any classpath elements of its own, so these prefixes are never applied
     * to anything.
     *
     * @return the package root prefixes.
     */
    @Override
    public String[] getPackageRootPrefixes() {
        return ClassLoaderHandlerRegistry.DEFAULT_PACKAGE_ROOT_PREFIXES;
    }

    /**
     * Get the automatic lib dirs for classpath elements obtained from this classloader.
     *
     * <p>
     * OpenEJB deploys wars and ears, whose lib dirs are not listed as classpath elements.
     *
     * @return the lib dir prefixes.
     */
    @Override
    public String[] getLibDirPrefixes() {
        return ClassLoaderHandlerRegistry.ARCHIVE_LIB_DIR_PREFIXES;
    }
}
