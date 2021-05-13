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
 * Copyright (c) 2021 Luke Hutchison
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

import java.util.Iterator;

import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.ReflectionUtils;

/** ClassLoaderHandler that is able to extract the URLs from a TomEEWebappClassLoader. */
class TomEEWebappClassLoaderHandler implements ClassLoaderHandler {
    /** Class cannot be constructed. */
    private TomEEWebappClassLoaderHandler() {
    }

    /**
     * Check whether this {@link ClassLoaderHandler} can handle a given {@link ClassLoader}.
     *
     * @param classLoaderClass
     *            the {@link ClassLoader} class or one of its superclasses.
     * @param log
     *            the log
     * @return true if this {@link ClassLoaderHandler} can handle the {@link ClassLoader}.
     */
    public static boolean canHandle(final Class<?> classLoaderClass, final LogNode log) {
        return "org.apache.tomee.catalina.TomEEWebappClassLoader".equals(classLoaderClass.getName());
    }

    /**
     * Find the {@link ClassLoader} delegation order for a {@link ClassLoader}.
     *
     * @param classLoader
     *            the {@link ClassLoader} to find the order for.
     * @param classLoaderOrder
     *            a {@link ClassLoaderOrder} object to update.
     * @param log
     *            the log
     */
    public static void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final LogNode log) {
        // TomEEWebappClassLoader has a lot of complex delegation rules, including classname-specific delegation,
        // which is not supported by the current ClassGraph model, so we just try to approximate the delegation
        // order with a fixed order here.
        try {
            classLoaderOrder.delegateTo(Class.forName("org.apache.openejb.OpenEJB").getClassLoader(),
                    /* isParent = */ true, log);
        } catch (LinkageError | ClassNotFoundException e) {
            // Ignore
        }
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        classLoaderOrder.add(classLoader, log);
    }

    /**
     * Find the classpath entries for the associated {@link ClassLoader}.
     *
     * @param classLoader
     *            the {@link ClassLoader} to find the classpath entries order for.
     * @param classpathOrder
     *            a {@link ClasspathOrder} object to update.
     * @param scanSpec
     *            the {@link ScanSpec}.
     * @param log
     *            the log.
     */
    public static void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final ScanSpec scanSpec, final LogNode log) {
        // Type WebResourceRoot
        final Object resources = ReflectionUtils.getFieldVal(classLoader, "resources", false);
        if (resources != null) {
            // Type List<WebResourceSet>
            final Object jars = ReflectionUtils.getFieldVal(resources, "jarResources", false);
            if (jars != null && jars instanceof Iterable) {
                final Iterable<?> it = (Iterable<?>) jars;
                // Type Iterator<WebResourceSet>
                final Iterator<?> jarIt = it.iterator();
                while (jarIt.hasNext()) {
                    // Type WebResourceSet
                    final Object set = jarIt.next();
                    final Object baseUrl = ReflectionUtils.invokeMethod(set, "getBaseUrl", false);
                    classpathOrder.addClasspathEntry(baseUrl, classLoader, scanSpec, log);
                }
            }
        }
    }
}
