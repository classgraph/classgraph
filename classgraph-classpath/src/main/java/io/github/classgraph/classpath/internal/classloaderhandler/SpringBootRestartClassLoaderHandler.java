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
package io.github.classgraph.classpath.internal.classloaderhandler;

import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.classpath.internal.ClassLoaderOrder;
import io.github.classgraph.classpath.internal.ClasspathOrder;
import io.github.classgraph.classpath.internal.spec.ClasspathSpec;
import org.jspecify.annotations.Nullable;

/**
 * This handler uses parent-last delegation order (i.e. it adds the classloader itself to the classloader order
 * before delegating to the parent) to support the <code>RestartClassLoader</code> of Spring Boot's devtools.
 * <code>RestartClassLoader</code> provides parent-last loading for specified URLs (those are all that are supposed
 * to be changed during development). Therefore the handler for that class loader also has to delegate in
 * parent-last order.
 */
class SpringBootRestartClassLoaderHandler implements ClassLoaderHandler {
    /** Constructor. */
    SpringBootRestartClassLoaderHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable LogNode log) {
        return classIsOrExtendsOrImplements(classLoaderClass,
                "org.springframework.boot.devtools.restart.classloader.RestartClassLoader");
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable LogNode log) {
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
     * always be loaded through direct access from the RestartClassLoader until it's completely turned off by means
     * of Spring Boot Developer tools.
     *
     * The RestartClassLoader shades only the project classes and additional directories that are configurable, so
     * itself needs access to parent, but last.
     *
     * @param classLoader
     *            the {@link ClassLoader} to find the classpath entries order for.
     * @param classpathOrder
     *            a {@link ClasspathOrder} object to update.
     * @param classpathSpec
     *            the {@link ClasspathSpec}.
     * @param log
     *            the log node, or null to skip logging
     */
    // #267, #268
    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final ClasspathSpec classpathSpec, final @Nullable LogNode log) {
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
