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

import io.github.classgraph.base.ClassGraphLog;
import org.jspecify.annotations.Nullable;

/**
 * Teaches ClassGraph how to read the classpath out of a {@link ClassLoader} that it does not already know about.
 *
 * <p>
 * ClassGraph ships with handlers for the classloaders of the common application servers, build tools and
 * frameworks. Write one of these only for a classloader that none of those handle, then register it with
 * {@code ClassGraph#registerClassLoaderHandler(ClassLoaderHandler)} before scanning. A registered handler is
 * offered every classloader before the built-in handlers are, and is never dropped, so it can also override a
 * built-in handler.
 *
 * <p>
 * When more than one handler can handle the same classloader, only the handlers that name the most specific
 * classloader class are used, so a handler written for a subclass of {@link java.net.URLClassLoader} takes the
 * place of the built-in {@code URLClassLoader} handler rather than running alongside it, and has to add the
 * classloader's own URLs itself. The handlers that are kept run in turn, the registered ones first, and a
 * classloader or classpath entry that has already been placed keeps the position the first handler to place it gave
 * it.
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
    /** The package root prefixes for classpath elements that have no automatic package roots at all. */
    List<String> NO_PACKAGE_ROOT_PREFIXES = List.of();

    /** The lib dirs for classpath elements that have no automatic lib dirs at all. */
    List<String> NO_LIB_DIR_PREFIXES = List.of();

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
        return findMatchingClassName(cls, className) != null;
    }

    /**
     * Return the first of the given class or interface names that the class is, extends or implements, or null if
     * it is none of them.
     *
     * <p>
     * Use this rather than a chain of {@link #classIsOrExtendsOrImplements(Class, String)} calls in a handler that
     * recognizes more than one {@link ClassLoader} class and then has to know which of them it was given, so that
     * the handler decides what a classloader is in the same way that {@link #canHandle(Class, ClassGraphLog)}
     * decided that it could handle it. Comparing {@code classLoader.getClass().getName()} against each name instead
     * misses a subclass of a classloader that {@code canHandle} accepted, and a handler that is chosen but then
     * handles nothing leaves the classloader's classpath entries out of the scan entirely, since no other handler
     * is offered the classloader.
     *
     * <p>
     * The class hierarchy is walked once, with every name tested at each class, rather than once per name. The walk
     * visits the class itself, then its superclass and everything above it, and only then the interfaces the class
     * implements directly, so a class is always reached before its own supertypes, and the more specific of a class
     * and one of its supertypes is the one that is returned. Two interfaces that the class inherits from different
     * places are reached superclass-first, though, so a name that is only reachable through an interface of a
     * superclass is returned ahead of one reachable through an interface of the class itself.
     *
     * @param cls
     *            the class to test, or null.
     * @param classNames
     *            the names of the classes or interfaces to look for.
     * @return the first matching name, or null if the class is, extends or implements none of them.
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
     * The dirs within a classpath element (e.g. {@code "WEB-INF/classes/"}) that this classloader loads classes
     * from without listing them as classpath elements of their own. Each of these dirs found within a classpath
     * element obtained from this classloader is scanned as a package root, i.e. the prefix is stripped from the
     * paths of the classfiles beneath it.
     *
     * <p>
     * The default is {@link #NO_PACKAGE_ROOT_PREFIXES}, since a classloader normally loads classes only from the
     * classpath elements it was given -- {@link java.net.URLClassLoader} has no automatic package roots at all.
     * Override this only for a classloader whose own code goes looking for classes in a dir with a fixed name, and
     * declare exactly the dirs that code looks in. The declaration is taken at face value: a prefix that is also a
     * legal package name (e.g. {@code "classes/"}) is stripped from any classpath element this classloader yields
     * that has a dir of that name, so declaring one that the classloader does not really look in makes a real
     * package of that name be read as a package root.
     *
     * @return the package root prefixes, each ending in a slash.
     */
    default List<String> getPackageRootPrefixes() {
        return NO_PACKAGE_ROOT_PREFIXES;
    }

    /**
     * The dirs within a classpath element (e.g. {@code "WEB-INF/lib/"}) whose jarfiles this classloader adds to the
     * classpath without listing them as classpath elements of their own. The jarfiles found in these dirs within a
     * classpath element obtained from this classloader are added to the classpath after the classpath element that
     * contains them.
     *
     * <p>
     * The default is {@link #NO_LIB_DIR_PREFIXES}, since a classloader normally loads from the jarfiles it was
     * given -- {@link java.net.URLClassLoader} has no automatic lib dirs at all. Override this only for a
     * classloader whose own code goes looking for jarfiles in a dir with a fixed name, and declare exactly the dirs
     * that code looks in: a lib dir prefix that is also a legal package name (e.g. {@code "lib/"}) otherwise causes
     * the jarfiles of a package of that name to be added to the classpath of every application that has one.
     *
     * @return the lib dir prefixes, each ending in a slash.
     */
    default List<String> getLibDirPrefixes() {
        return NO_LIB_DIR_PREFIXES;
    }
}
