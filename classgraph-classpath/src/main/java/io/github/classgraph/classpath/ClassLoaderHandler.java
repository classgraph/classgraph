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
package io.github.classgraph.classpath;

import io.github.classgraph.base.ClassGraphLog;
import org.jspecify.annotations.Nullable;

/**
 * Teaches ClassGraph how to read the classpath out of a {@link ClassLoader} that it does not already know about.
 *
 * <p>
 * ClassGraph ships with handlers for the classloaders of the common application servers, build tools and
 * frameworks. Write one of these only for a classloader that none of those handle, then register it with
 * {@code ClassGraph#registerClassLoaderHandler(ClassLoaderHandler)} before scanning. A registered handler is
 * offered every classloader before the built-in handlers are, so it can also override a built-in handler: the
 * built-in handlers still run afterwards, but a classloader or classpath entry that has already been placed keeps
 * the position the registered handler gave it.
 *
 * <p>
 * Implementations must be stateless: a single instance handles every classloader in every scan, and scans can run
 * concurrently. State that belongs to one scan goes in the {@link ClasspathOrder} that is passed in; see
 * {@link ClasspathOrder#claimOncePerScan(String)}.
 *
 * <p>
 * If you write a handler for a classloader that others are likely to hit, please consider contributing it to the
 * ClassGraph project.
 */
public interface ClassLoaderHandler {
    /**
     * Check whether this {@link ClassLoaderHandler} can handle a given {@link ClassLoader}.
     *
     * @param classLoaderClass
     *            the {@link ClassLoader} class or one of its superclasses.
     * @param log
     *            the log node, or null to skip logging
     * @return true if this {@link ClassLoaderHandler} can handle the {@link ClassLoader}.
     */
    boolean canHandle(Class<?> classLoaderClass, @Nullable ClassGraphLog log);

    /**
     * Return true if the class is, extends, or implements a given named class or interface. Used by
     * {@link #canHandle(Class, ClassGraphLog)} implementations to recognize a {@link ClassLoader} by name without
     * loading its class.
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
    void findClassLoaderOrder(ClassLoader classLoader, ClassLoaderOrder classLoaderOrder,
            @Nullable ClassGraphLog log);

    /**
     * Find the classpath entries for the associated {@link ClassLoader}.
     *
     * @param classLoader
     *            the {@link ClassLoader} to find the classpath entries order for.
     * @param classpathOrder
     *            a {@link ClasspathOrder} object to update.
     * @param log
     *            the log node, or null to skip logging
     */
    void findClasspathOrder(ClassLoader classLoader, ClasspathOrder classpathOrder, @Nullable ClassGraphLog log);

    /**
     * The automatic package root prefixes (e.g. {@code "BOOT-INF/classes/"}) to look for and strip within classpath
     * elements obtained from this classloader. The default is an empty array, meaning that this classloader's
     * classpath elements always have their classes at the root.
     *
     * <p>
     * Package roots must only be declared here if the classloader really can produce classpath elements in that
     * layout, since a package root prefix that is also a legal package name (e.g. {@code "classes/"}) will
     * otherwise cause real packages of that name to be misread as package roots.
     *
     * @return the package root prefixes.
     */
    // #929
    default String[] getPackageRootPrefixes() {
        return new String[0];
    }

    /**
     * The lib dirs (e.g. {@code "BOOT-INF/lib/"}) whose jarfiles this classloader adds to the classpath without
     * listing them as classpath elements. The jarfiles found in these dirs within a classpath element obtained from
     * this classloader are added to the classpath after the classpath element that contains them. The default is an
     * empty array, meaning that this classloader lists every jarfile it loads from.
     *
     * <p>
     * Lib dirs must only be declared here if the classloader really does load from them, since a lib dir prefix
     * that is also a legal package name (e.g. {@code "lib/"}) will otherwise cause the jarfiles of a package of
     * that name to be added to the classpath of every application that has one.
     *
     * @return the lib dir prefixes, each ending in a slash.
     */
    default String[] getLibDirPrefixes() {
        return new String[0];
    }
}
