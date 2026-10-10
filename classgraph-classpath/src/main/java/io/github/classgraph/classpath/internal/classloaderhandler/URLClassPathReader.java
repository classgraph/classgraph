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
package io.github.classgraph.classpath.internal.classloaderhandler;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.classpath.ClasspathOrder;
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
 * <li>{@code loaders} holds a loader for each entry that has been opened, in search order, including the
 * {@code Class-Path} expansions, which are removed from {@code unopenedUrls} as they are opened. Each loader's
 * {@code getBaseURL()} method returns the URL of its entry. (The {@code lmap} field holds the same loaders, but it
 * is keyed by Strings rather than URLs, and is a hash map, so it is not in search order. On a JDK that reads JAR
 * indexes ({@code META-INF/INDEX.LIST}), {@code lmap} also holds the jars that the index of an opened jar lists,
 * which are never added to {@code loaders}, so those jars are read from {@code lmap}.)
 * </ul>
 *
 * <p>
 * A {@code Class-Path} expansion is therefore listed in {@code unopenedUrls} before it is opened and in
 * {@code loaders} afterwards, and in {@code path} at no point, so all three have to be read to see everything.
 * (ClassGraph expands the {@code Class-Path} manifest attribute itself, so in practice these expansions are
 * duplicates of entries it already has -- they are read so that nothing is missed if a {@code URLClassPath} is
 * given entries by some other route.)
 *
 * <p>
 * The fields that no public API exposes can only be read if the {@code jdk.internal.loader} package is open, and,
 * for a {@link java.net.URLClassLoader}, whose {@code ucp} field is declared in {@code java.net}, only if that
 * package is open too. Neither is open by default, so in practice these entries are only found if Narcissus is on
 * the classpath or is a resolved module.
 */
final class URLClassPathReader {
    /** The name of the class whose fields are read here. */
    private static final String URL_CLASS_PATH_CLASS_NAME = "jdk.internal.loader.URLClassPath";

    /** Not instantiable. */
    private URLClassPathReader() {
        // Cannot be constructed
    }

    /**
     * Get the {@code jdk.internal.loader.URLClassPath} of a classloader, which is held in a field named {@code ucp}
     * by both {@link java.net.URLClassLoader} and the JDK's own builtin classloaders.
     *
     * @param classLoader
     *            the classloader.
     * @return the {@code URLClassPath}, or null if the classloader does not have one, or if the field could not be
     *         read.
     */
    static @Nullable Object getUcp(final ClassLoader classLoader) {
        final var ucp = ReflectionUtils.getFieldVal(false, classLoader, "ucp");
        // Check the type, since this is also called speculatively for classloaders that are not known to have a
        // URLClassPath, and the fields of some other kind of object should not be read, nor its monitor held
        return ucp != null && URL_CLASS_PATH_CLASS_NAME.equals(ucp.getClass().getName()) ? ucp : null;
    }

    /**
     * Add all the classpath entries of a {@code URLClassPath} to the classpath order.
     *
     * @param ucp
     *            the {@code URLClassPath}, as returned by {@link #getUcp(ClassLoader)}.
     * @param classLoader
     *            the classloader the {@code URLClassPath} was obtained from.
     * @param classpathOrder
     *            the classpath order to add to.
     * @param log
     *            the log node, or null to skip logging.
     */
    static void addAllClasspathEntries(final Object ucp, final ClassLoader classLoader,
            final ClasspathOrder classpathOrder, final @Nullable ClassGraphLog log) {
        classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(false, ucp, "getURLs"), classLoader,
                log);
        addUnlistedClasspathEntries(ucp, classLoader, classpathOrder, log);
    }

    /**
     * Add the classpath entries of a {@code URLClassPath} that its {@code getURLs()} method does not return, i.e.
     * those held only in the {@code unopenedUrls}, {@code loaders} and {@code lmap} fields.
     *
     * @param ucp
     *            the {@code URLClassPath}, as returned by {@link #getUcp(ClassLoader)}.
     * @param classLoader
     *            the classloader the {@code URLClassPath} was obtained from.
     * @param classpathOrder
     *            the classpath order to add to.
     * @param log
     *            the log node, or null to skip logging.
     */
    static void addUnlistedClasspathEntries(final Object ucp, final ClassLoader classLoader,
            final ClasspathOrder classpathOrder, final @Nullable ClassGraphLog log) {

        // The JDK adds to and removes from this deque while holding the deque's own monitor, so hold it too
        final var unopenedUrls = ReflectionUtils.getFieldVal(false, ucp, "unopenedUrls");
        if (unopenedUrls instanceof final Collection<?> unopenedUrlsCollection) {
            final List<Object> unopenedUrlsCopy;
            synchronized (unopenedUrls) {
                unopenedUrlsCopy = new ArrayList<>(unopenedUrlsCollection);
            }
            classpathOrder.addClasspathEntryObject(unopenedUrlsCopy, classLoader, log);
        }

        // The JDK adds to this list while holding the URLClassPath's own monitor, so hold that too. (These two
        // monitors are never held at the same time here, so there is no lock ordering to get wrong.) Read each
        // loader's base URL, rather than the String keys of the lmap field: those keys are not URLs, and passing a
        // String to addClasspathEntryObject would split it at every path separator character in the path.
        final List<URL> openedUrls = new ArrayList<>();
        final Set<Object> loadersRead = Collections.newSetFromMap(new IdentityHashMap<>());
        synchronized (ucp) {
            if (ReflectionUtils.getFieldVal(false, ucp, "loaders") instanceof final List<?> loaders) {
                for (final Object loader : loaders) {
                    loadersRead.add(loader);
                    if (ReflectionUtils.invokeMethod(false, loader, "getBaseURL") instanceof final URL baseUrl) {
                        openedUrls.add(baseUrl);
                    }
                }
            }

            // A JDK that reads JAR indexes (META-INF/INDEX.LIST: JDK 17, or JDK 18 to 20 with the system property
            // jdk.net.URLClassPath.enableJarIndex set; JDK 21 removed them) also puts in lmap each jar that the
            // index of an opened jar lists, but never adds it to loaders. Its value is null until a lookup opens the
            // jar, and is the jar's loader after that. Add these jars after the loaders: by the base URL of the
            // loader, or, for a jar that is not open yet, by parsing its key as a URL. When a lookup opens one of
            // these jars, the JDK writes its entry without holding any monitor, so a concurrent lookup can make
            // reading lmap fail; then these jars are left out.
            if (ReflectionUtils.getFieldVal(false, ucp, "lmap") instanceof final Map<?, ?> lmap) {
                try {
                    for (final Map.Entry<?, ?> ent : lmap.entrySet()) {
                        final var loader = ent.getValue();
                        if (loader == null) {
                            if (ent.getKey() instanceof final String key) {
                                try {
                                    openedUrls.add(new URL(key));
                                } catch (final MalformedURLException e) {
                                    if (log != null) {
                                        log.log("Could not parse JAR index entry as a URL: " + key);
                                    }
                                }
                            }
                        } else if (!loadersRead.contains(loader) && ReflectionUtils.invokeMethod(false, loader,
                                "getBaseURL") instanceof final URL baseUrl) {
                            openedUrls.add(baseUrl);
                        }
                    }
                } catch (final ConcurrentModificationException e) {
                    if (log != null) {
                        log.log("Could not read the jars listed by JAR indexes, since they changed during the read");
                    }
                }
            }
        }
        classpathOrder.addClasspathEntry(openedUrls, classLoader, log);
    }
}
