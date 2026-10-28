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

import java.util.List;

import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.ClassGraphLog;

/**
 * Extracts classpath entries and delegation order from a custom {@link ClassLoader}.
 *
 * <p>
 * ClassGraph includes built-in handlers for common application servers, build tools, and frameworks. Custom
 * handlers can be registered via {@link ClasspathFinder#registerClassLoaderHandler(ClassLoaderHandler)} and are
 * evaluated before built-in handlers. Among built-in handlers, only those matching the most specific classloader
 * class in the hierarchy are used; a handler for a subclass of {@link java.net.URLClassLoader} replaces the
 * built-in {@code URLClassLoader} handler and must add any {@code URLClassLoader} URLs itself.
 *
 * <p>
 * Implementations must be stateless and thread-safe, as a single instance may be shared across concurrent scans.
 * Per-scan state can be tracked using {@link ClasspathOrder#claimOncePerScan(String)}.
 *
 * <p>
 * Uncaught {@link RuntimeException} and {@link LinkageError} exceptions thrown by handler methods are logged and
 * ignored so that scanning can proceed with remaining classloaders and entries.
 */
public interface ClassLoaderHandler {
    /** Empty package root prefix list for classpath elements without automatic package roots. */
    @Unmodifiable
    List<String> NO_PACKAGE_ROOT_PREFIXES = List.of();

    /** Empty lib directory prefix list for classpath elements without automatic lib directories. */
    @Unmodifiable
    List<String> NO_LIB_DIR_PREFIXES = List.of();

    /**
     * Checks whether this handler supports the given {@link ClassLoader} class.
     *
     * @param classLoaderClass
     *            the {@link ClassLoader} class or one of its superclasses.
     * @param log
     *            the log node, or null to skip logging.
     * @return true if this handler can handle the {@link ClassLoader}.
     */
    boolean canHandle(Class<?> classLoaderClass, @Nullable ClassGraphLog log);

    /**
     * Checks whether {@code cls} is, extends, or implements the class or interface with the given name.
     *
     * @param cls
     *            the class to test, or null.
     * @param className
     *            the fully-qualified name of the class or interface to match.
     * @return true if {@code cls} is, extends, or implements {@code className}.
     */
    default boolean classIsOrExtendsOrImplements(final @Nullable Class<?> cls, final String className) {
        return findMatchingClassName(cls, className) != null;
    }

    /**
     * Returns the first class or interface name in {@code classNames} that {@code cls} is, extends, or implements,
     * searching the class hierarchy from {@code cls} upward through superclasses before interfaces, or null if none
     * match.
     *
     * @param cls
     *            the class to test, or null.
     * @param classNames
     *            the fully-qualified names of the classes or interfaces to match.
     * @return the first matching class or interface name, or null if none match.
     */
    default @Nullable String findMatchingClassName(final @Nullable Class<?> cls, final String... classNames) {
        if (cls == null) {
            return null;
        }
        final var name = cls.getName();
        for (final String className : classNames) {
            if (name.equals(className)) {
                return className;
            }
        }
        final var superclassMatch = findMatchingClassName(cls.getSuperclass(), classNames);
        if (superclassMatch != null) {
            return superclassMatch;
        }
        for (final Class<?> iface : cls.getInterfaces()) {
            final var interfaceMatch = findMatchingClassName(iface, classNames);
            if (interfaceMatch != null) {
                return interfaceMatch;
            }
        }
        return null;
    }

    /**
     * Records the delegation order for {@code classLoader} in {@code classLoaderOrder}.
     *
     * <p>
     * Call {@link ClassLoaderOrder#add(ClassLoader, ClassGraphLog)} after
     * {@link ClassLoaderOrder#delegateToParent(ClassLoader, ClassGraphLog)} for parent-first resolution, or before
     * it for parent-last resolution. If the handler does not call {@code add}, the classloader is placed after all
     * classloaders it delegated to.
     *
     * @param classLoader
     *            the {@link ClassLoader} to record the delegation order for.
     * @param classLoaderOrder
     *            the {@link ClassLoaderOrder} to update.
     * @param log
     *            the log node, or null to skip logging.
     */
    void findClassLoaderOrder(ClassLoader classLoader, ClassLoaderOrder classLoaderOrder,
            @Nullable ClassGraphLog log);

    /**
     * Extracts classpath entries from {@code classLoader} and adds them to {@code classpathOrder}.
     *
     * <p>
     * Do not retain a reference to {@code classpathOrder} after this method returns.
     *
     * @param classLoader
     *            the {@link ClassLoader} to extract classpath entries from.
     * @param classpathOrder
     *            the {@link ClasspathOrder} to update.
     * @param log
     *            the log node, or null to skip logging.
     */
    void findClasspathOrder(ClassLoader classLoader, ClasspathOrder classpathOrder, @Nullable ClassGraphLog log);

    /**
     * Returns relative directory prefixes within a classpath element (such as {@code "WEB-INF/classes/"}) that this
     * classloader treats as package roots without listing them as separate classpath entries. When present within a
     * classpath element contributed by this handler, these prefixes are stripped from class and resource paths.
     *
     * @return the relative package root directory prefixes (normalized with trailing {@code '/'}).
     */
    default List<String> getPackageRootPrefixes() {
        return NO_PACKAGE_ROOT_PREFIXES;
    }

    /**
     * Returns relative directory prefixes within a classpath element (such as {@code "WEB-INF/lib/"}) whose
     * jarfiles this classloader automatically adds to the classpath. Jarfiles found in these directories within a
     * classpath element contributed by this handler are added to the classpath immediately after the enclosing
     * element.
     *
     * @return the relative lib directory prefixes (normalized with trailing {@code '/'}).
     */
    default List<String> getLibDirPrefixes() {
        return NO_LIB_DIR_PREFIXES;
    }
}
