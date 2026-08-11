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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;
import org.jspecify.annotations.Nullable;

/**
 * Reads the classpath entries out of a {@code jdk.internal.loader.URLClassPath}.
 *
 * <p>
 * A {@code URLClassPath} is the search path of both {@link java.net.URLClassLoader} and the JDK's own builtin
 * classloaders, and it records its entries in three separate fields, no one of which holds all of them:
 *
 * <ul>
 * <li>{@code path} holds the search path the {@code URLClassPath} was constructed with, plus everything appended to
 * it since. {@code getURLs()} returns a copy of this, and it is therefore the only one of the three that any public
 * API exposes.
 * <li>{@code unopenedUrls} holds the entries of {@code path} that have not been opened yet, and additionally the
 * entries expanded from the {@code Class-Path} manifest attribute of jars that have been opened. Those expansions
 * are never added to {@code path}.
 * <li>{@code lmap} is keyed by the entries that have been opened, including the {@code Class-Path} expansions,
 * which are removed from {@code unopenedUrls} as they are opened.
 * </ul>
 *
 * <p>
 * A {@code Class-Path} expansion is therefore listed in {@code unopenedUrls} before it is opened and in
 * {@code lmap} afterwards, and in {@code path} at no point, so all three have to be read to see everything.
 * (ClassGraph expands the {@code Class-Path} manifest attribute itself, so in practice these expansions are
 * duplicates of entries it already has -- they are read so that nothing is missed if a {@code URLClassPath} is
 * given entries by some other route.)
 *
 * <p>
 * The two fields that no public API exposes can only be read if the {@code jdk.internal.loader} package is open,
 * which it is not by default, so in practice these entries are only found if Narcissus is on the classpath.
 */
final class URLClassPathReader {
    /** The name of the class whose fields are read here. */
    private static final String URL_CLASS_PATH_CLASS_NAME = "jdk.internal.loader.URLClassPath";

    /** Constructor. */
    private URLClassPathReader() {
        // Cannot be constructed
    }

    /**
     * Get the {@code jdk.internal.loader.URLClassPath} of a classloader, which is held in a field named {@code ucp}
     * by both {@link java.net.URLClassLoader} and the JDK's own builtin classloaders.
     *
     * @param classLoader
     *            the classloader.
     * @param reflectionUtils
     *            the reflection utils instance.
     * @return the {@code URLClassPath}, or null if the classloader does not have one, or if the field could not be
     *         read.
     */
    static @Nullable Object getUcp(final ClassLoader classLoader, final ReflectionUtils reflectionUtils) {
        final var ucp = reflectionUtils.getFieldVal(false, classLoader, "ucp");
        // Check the type, since this is also called speculatively for classloaders that are not known to have a
        // URLClassPath, and the fields of some other kind of object should not be read, nor its monitor held
        return ucp != null && URL_CLASS_PATH_CLASS_NAME.equals(ucp.getClass().getName()) ? ucp : null;
    }

    /**
     * Add all the classpath entries of a {@code URLClassPath} to the classpath order.
     *
     * @param ucp
     *            the {@code URLClassPath}, as returned by {@link #getUcp(ClassLoader, ReflectionUtils)}.
     * @param classLoader
     *            the classloader the {@code URLClassPath} was obtained from.
     * @param classpathOrder
     *            the classpath order to add to.
     * @param scanSpec
     *            the scan spec.
     * @param log
     *            the log node, or null to skip logging.
     */
    static void addAllClasspathEntries(final Object ucp, final ClassLoader classLoader,
            final ClasspathOrder classpathOrder, final ScanSpec scanSpec, final @Nullable LogNode log) {
        classpathOrder.addClasspathEntryObject(classpathOrder.reflectionUtils.invokeMethod(false, ucp, "getURLs"),
                classLoader, scanSpec, log);
        addUnlistedClasspathEntries(ucp, classLoader, classpathOrder, scanSpec, log);
    }

    /**
     * Add the classpath entries of a {@code URLClassPath} that its {@code getURLs()} method does not return, i.e.
     * those held only in the {@code unopenedUrls} and {@code lmap} fields.
     *
     * @param ucp
     *            the {@code URLClassPath}, as returned by {@link #getUcp(ClassLoader, ReflectionUtils)}.
     * @param classLoader
     *            the classloader the {@code URLClassPath} was obtained from.
     * @param classpathOrder
     *            the classpath order to add to.
     * @param scanSpec
     *            the scan spec.
     * @param log
     *            the log node, or null to skip logging.
     */
    static void addUnlistedClasspathEntries(final Object ucp, final ClassLoader classLoader,
            final ClasspathOrder classpathOrder, final ScanSpec scanSpec, final @Nullable LogNode log) {
        final var reflectionUtils = classpathOrder.reflectionUtils;

        // The JDK adds to and removes from this deque while holding the deque's own monitor, so hold it too
        final var unopenedUrls = reflectionUtils.getFieldVal(false, ucp, "unopenedUrls");
        if (unopenedUrls instanceof final Collection<?> unopenedUrlsCollection) {
            final List<Object> unopenedUrlsCopy;
            synchronized (unopenedUrls) {
                unopenedUrlsCopy = new ArrayList<>(unopenedUrlsCollection);
            }
            classpathOrder.addClasspathEntryObject(unopenedUrlsCopy, classLoader, scanSpec, log);
        }

        // The JDK adds to this map while holding the URLClassPath's own monitor, so hold that too. (These two
        // monitors are never held at the same time here, so there is no lock ordering to get wrong.)
        final List<Object> openedUrls = new ArrayList<>();
        synchronized (ucp) {
            if (reflectionUtils.getFieldVal(false, ucp, "lmap") instanceof final Map<?, ?> lmap) {
                openedUrls.addAll(lmap.keySet());
            }
        }
        classpathOrder.addClasspathEntryObject(openedUrls, classLoader, scanSpec, log);
    }
}
