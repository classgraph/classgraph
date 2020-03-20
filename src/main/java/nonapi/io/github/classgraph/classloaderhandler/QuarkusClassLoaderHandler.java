/*
 * This file is part of ClassGraph.
 *
 * Author: @mcollovati
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 @mcollovati, contributed to the ClassGraph project
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

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.ReflectionUtils;

/**
 * Extract classpath entries from the Quarkus ClassLoader.
 */
class QuarkusClassLoaderHandler implements ClassLoaderHandler {
    /** Class cannot be constructed. */
    private QuarkusClassLoaderHandler() {
    }

    /**
     * Can handle.
     *
     * @param classLoaderClass
     *            the classloader class
     * @param log
     *            the log
     * @return true, if classLoaderClass is the Quarkus RuntimeClassloader
     */
    public static boolean canHandle(final Class<?> classLoaderClass, final LogNode log) {
        return "io.quarkus.runner.RuntimeClassLoader".equals(classLoaderClass.getName());
    }

    /**
     * Find classloader order.
     *
     * @param classLoader
     *            the class loader
     * @param classLoaderOrder
     *            the classloader order
     * @param log
     *            the log
     */
    public static void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final LogNode log) {
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
        @SuppressWarnings("unchecked")
        final Map<String, Path> applicationClasses = (Map<String, Path>) ReflectionUtils.getFieldVal(classLoader,
                "applicationClasses", false);
        if (applicationClasses != null) {
            for (final Path path : applicationClasses.values()) {
                classpathOrder.addClasspathEntry(path.toUri(), classLoader, scanSpec, log);
            }
        }
        @SuppressWarnings("unchecked")
        final Collection<Path> applicationClassDirectories = (Collection<Path>) ReflectionUtils
                .getFieldVal(classLoader, "applicationClassDirectories", false);
        if (applicationClassDirectories != null) {
            for (final Path path : applicationClassDirectories) {
                classpathOrder.addClasspathEntryObject(path.toUri(), classLoader, scanSpec, log);
            }
        }
    }
}
