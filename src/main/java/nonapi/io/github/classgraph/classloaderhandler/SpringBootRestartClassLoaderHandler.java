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

import nonapi.io.github.classgraph.classpath.ClassLoaderFinder;
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
    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final LogNode log) {
        return ClassLoaderFinder.classIsOrExtendsOrImplements(classLoaderClass,
                "org.springframework.boot.devtools.restart.classloader.RestartClassLoader");
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final LogNode log) {
        // The Restart classloader is a parent-last classloader, so add the Restart classloader itself to the
        // classloader order first
        classLoaderOrder.add(classLoader, log);

        // Delegate to the parent of the RestartClassLoader
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
    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final ScanSpec scanSpec, final LogNode log) {
        // The Restart classloader doesn't itself store any URLs
    }

    /**
     * Get the automatic package root prefixes for classpath elements obtained from this classloader.
     *
     * <p>
     * Classpath elements from this classloader may be Spring-Boot executable jars or wars.
     *
     * @return the package root prefixes.
     */
    @Override
    public String[] getPackageRootPrefixes() {
        return ClassLoaderHandlerRegistry.DEFAULT_PACKAGE_ROOT_PREFIXES;
    }
}
