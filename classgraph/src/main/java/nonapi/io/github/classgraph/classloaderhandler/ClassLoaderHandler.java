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

import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;
import org.jspecify.annotations.Nullable;

/**
 * A ClassLoader handler.
 *
 * <p>
 * Implementations must have a no-argument constructor, and are instantiated once each by
 * {@link ClassLoaderHandlerRegistry.ClassLoaderHandlerRegistryEntry}. Implementations must be stateless, since a
 * single instance is shared across all scans.
 *
 * <p>
 * If you create a custom ClassLoaderHandler, please consider submitting it to the ClassGraph open source project.
 */
interface ClassLoaderHandler {
    /**
     * Check whether this {@link ClassLoaderHandler} can handle a given {@link ClassLoader}.
     *
     * @param classLoaderClass
     *            the {@link ClassLoader} class or one of its superclasses.
     * @param log
     *            the log node, or null to skip logging
     * @return true if this {@link ClassLoaderHandler} can handle the {@link ClassLoader}.
     */
    boolean canHandle(Class<?> classLoaderClass, @Nullable LogNode log);

    /**
     * Return true if the class is, extends, or implements a given named class or interface. Used by
     * {@link #canHandle(Class, LogNode)} implementations to recognize a {@link ClassLoader} by name without loading
     * its class.
     *
     * @param cls
     *            the class to test, or null.
     * @param className
     *            the name of the class or interface to look for.
     * @return true if cls is, extends, or implements the named class or interface.
     */
    default boolean classIsOrExtendsOrImplements(final @Nullable Class<?> cls, final String className) {
        if (cls == null) {
            return false;
        }
        if (cls.getName().equals(className) || classIsOrExtendsOrImplements(cls.getSuperclass(), className)) {
            return true;
        }
        for (final Class<?> iface : cls.getInterfaces()) {
            if (classIsOrExtendsOrImplements(iface, className)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find the {@link ClassLoader} delegation order for a {@link ClassLoader}.
     *
     * @param classLoader
     *            the {@link ClassLoader} to find the order for.
     * @param classLoaderOrder
     *            a {@link ClassLoaderOrder} object to update.
     * @param log
     *            the log node, or null to skip logging
     */
    void findClassLoaderOrder(ClassLoader classLoader, ClassLoaderOrder classLoaderOrder, @Nullable LogNode log);

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
     *            the log node, or null to skip logging
     */
    void findClasspathOrder(ClassLoader classLoader, ClasspathOrder classpathOrder, ScanSpec scanSpec,
            @Nullable LogNode log);

    /**
     * The automatic package root prefixes (e.g. {@code "BOOT-INF/classes/"}) to look for and strip within classpath
     * elements obtained from this classloader, or {@link ClassLoaderHandlerRegistry#NO_PACKAGE_ROOT_PREFIXES} if
     * this classloader's classpath elements always have their classes at the root.
     *
     * <p>
     * Package roots must only be declared here if the classloader really can produce classpath elements in that
     * layout, since a package root prefix that is also a legal package name (e.g. {@code "classes/"}) will
     * otherwise cause real packages of that name to be misread as package roots.
     *
     * @return the package root prefixes.
     */
    // #929
    String[] getPackageRootPrefixes();
}
