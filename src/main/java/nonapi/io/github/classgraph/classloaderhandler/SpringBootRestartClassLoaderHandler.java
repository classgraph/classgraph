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

import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;

/**
 * This handler uses
 * {@link nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandler.DelegationOrder#PARENT_LAST} to support
 * the <code>RestartClassLoader</code> of Spring Boot's devtools. <code>RestartClassLoader</code> provides parent
 * last loading for specified URLs (those are all that are supposed to be changed during development). Therefor the
 * handler for that class loader also has to delegate in <code>PARENT_LAST</code> order.
 */
class SpringBootRestartClassLoaderHandler implements ClassLoaderHandler {
    /** Class cannot be constructed. */
    private SpringBootRestartClassLoaderHandler() {
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
        return "org.springframework.boot.devtools.restart.classloader.RestartClassLoader"
                .equals(classLoaderClass.getName());
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
        // The Restart classloader is a parent-last classloader, so add the Restart classloader itself to the
        // classloader order first
        classLoaderOrder.add(classLoader, log);

        // Finally delegate to the parent of the RestartClassLoader
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
    }

    /**
     * Find the classpath entries for the associated {@link ClassLoader}.
     * 
     * Spring Boot's RestartClassLoader sits in front of the parent class loader and watches a given set of
     * directories for changes. While those classes are reachable from the parent class loader directly, they should
     * always be loaded through direct access from the RestartClassLoader until it's completely turned of by means
     * of Spring Boot Developer tools.
     * 
     * The RestartClassLoader shades only the project classes and additional directories that are configurable, so
     * itself needs access to parent, but last.
     * 
     * See: <a href="https://github.com/classgraph/classgraph/issues/267">#267</a>,
     * <a href="https://github.com/classgraph/classgraph/issues/268">#268</a>
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
        // The Restart classloader doesn't itself store any URLs
    }
}