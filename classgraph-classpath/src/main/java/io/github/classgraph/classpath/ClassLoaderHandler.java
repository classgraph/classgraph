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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

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

    /**
     * The package root prefixes of the build tools, which compile to a dir within the build dir rather than to the
     * build dir itself.
     *
     * <p>
     * Unlike {@link #ARCHIVE_PACKAGE_ROOT_PREFIXES}, {@code "classes"} and {@code "test-classes"} are both legal
     * Java package names, so treating them as automatic package roots is a heuristic, not a certainty: a real
     * package named {@code classes} is misread as a package root, and its classes are silently dropped. The
     * heuristic is nevertheless relied upon for general-purpose classloaders -- see {@code Issue420Test} and
     * {@code Issue766Test} -- so it can only be removed once package roots are verified against the declared name
     * of a classfile found beneath them, rather than assumed from the directory name.
     */
    List<String> BUILD_TOOL_PACKAGE_ROOT_PREFIXES = List.of(
            // Ant, Maven, Gradle and other build tool output dirs
            "classes/", "test-classes/");

    /**
     * The package root prefixes of the standard packaged-archive layouts: Spring-Boot executable jars, and wars.
     *
     * <p>
     * These are safe to look for in any classpath element, whatever classloader it came from, because neither
     * {@code "BOOT-INF"} nor {@code "WEB-INF"} can ever be a real package name -- a hyphen is not a legal character
     * in a Java identifier, so a directory with one of these names is unambiguously a package root rather than a
     * package.
     */
    List<String> ARCHIVE_PACKAGE_ROOT_PREFIXES = List.of(
            // Spring-Boot
            "BOOT-INF/classes/",
            // War files
            "WEB-INF/classes/");

    /**
     * The package root prefixes to look for in classpath elements from a general-purpose classloader, which could
     * have been handed a classpath element in any of the common build-tool or packaged-archive layouts: the
     * {@link #BUILD_TOOL_PACKAGE_ROOT_PREFIXES} followed by the {@link #ARCHIVE_PACKAGE_ROOT_PREFIXES}.
     */
    // #929
    List<String> DEFAULT_PACKAGE_ROOT_PREFIXES = Stream
            .concat(BUILD_TOOL_PACKAGE_ROOT_PREFIXES.stream(), ARCHIVE_PACKAGE_ROOT_PREFIXES.stream()).toList();

    /** The lib dirs for classpath elements that have no automatic lib dirs at all. */
    List<String> NO_LIB_DIR_PREFIXES = List.of();

    /**
     * The lib dirs of the standard packaged-archive layouts: Spring-Boot executable jars, and wars.
     *
     * <p>
     * These are safe to look for in any classpath element, whatever classloader it came from, because neither
     * {@code "BOOT-INF"} nor {@code "WEB-INF"} can ever be a real package name -- a hyphen is not a legal character
     * in a Java identifier -- so jarfiles found in one of these dirs really are on the classpath of the archive
     * that contains them, and are not just resources that happen to be jarfiles.
     */
    List<String> ARCHIVE_LIB_DIR_PREFIXES = List.of(
            // Spring-Boot
            // https://docs.spring.io/spring-boot/docs/current/reference/html/appendix-executable-jar-format.html
            "BOOT-INF/lib/",
            // War files
            "WEB-INF/lib/", "WEB-INF/lib-provided/");

    /**
     * Extend a list of prefixes with the prefixes of a specific kind of container, for a handler that declares its
     * own package roots or lib dirs on top of the defaults.
     *
     * <p>
     * Every classloader looks in the archive package roots and lib dirs, since any classloader can be handed a
     * Spring-Boot jarfile or a war, whatever kind of container it belongs to, and those layouts are unambiguous.
     * Only the extra prefixes are specific to a container, because their names are ordinary directory names that
     * could mean something else entirely in an archive built by anything else.
     *
     * @param prefixes
     *            the prefixes to extend, e.g. {@link #ARCHIVE_LIB_DIR_PREFIXES}
     * @param extraPrefixes
     *            the container's own prefixes, each ending in a slash
     * @return the given prefixes, followed by the container's own prefixes
     */
    static List<String> prefixesPlus(final List<String> prefixes, final String... extraPrefixes) {
        final List<String> combinedPrefixes = new ArrayList<>(prefixes.size() + extraPrefixes.length);
        combinedPrefixes.addAll(prefixes);
        Collections.addAll(combinedPrefixes, extraPrefixes);
        return List.copyOf(combinedPrefixes);
    }

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
     * elements obtained from this classloader. The default is {@link #DEFAULT_PACKAGE_ROOT_PREFIXES}, which suits a
     * general-purpose classloader that can be handed a classpath element in any of the common layouts.
     *
     * <p>
     * Override this to narrow the list to the layouts the classloader really can produce, or to widen it with
     * {@link #prefixesPlus(List, String...)} for a container that has package roots of its own. Package roots must
     * only be declared here if the classloader really can produce classpath elements in that layout, since a
     * package root prefix that is also a legal package name (e.g. {@code "classes/"}) will otherwise cause real
     * packages of that name to be misread as package roots.
     *
     * @return the package root prefixes, each ending in a slash.
     */
    // #929
    default List<String> getPackageRootPrefixes() {
        return DEFAULT_PACKAGE_ROOT_PREFIXES;
    }

    /**
     * The lib dirs (e.g. {@code "BOOT-INF/lib/"}) whose jarfiles this classloader adds to the classpath without
     * listing them as classpath elements. The jarfiles found in these dirs within a classpath element obtained from
     * this classloader are added to the classpath after the classpath element that contains them. The default is
     * {@link #ARCHIVE_LIB_DIR_PREFIXES}, the lib dirs that any classloader can be handed in a Spring-Boot jarfile
     * or a war.
     *
     * <p>
     * Override this to widen the list with {@link #prefixesPlus(List, String...)} for a container that loads from
     * lib dirs of its own, or to narrow it to {@link #NO_LIB_DIR_PREFIXES} for a classloader that lists every
     * jarfile it loads from. Lib dirs must only be declared here if the classloader really does load from them,
     * since a lib dir prefix that is also a legal package name (e.g. {@code "lib/"}) will otherwise cause the
     * jarfiles of a package of that name to be added to the classpath of every application that has one.
     *
     * @return the lib dir prefixes, each ending in a slash.
     */
    default List<String> getLibDirPrefixes() {
        return ARCHIVE_LIB_DIR_PREFIXES;
    }
}
