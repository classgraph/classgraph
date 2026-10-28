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

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.classpath.ClasspathOrder;

/**
 * Reads classpath entries from a {@code jdk.internal.loader.URLClassPath} instance (its {@code path},
 * {@code unopenedUrls}, and {@code loaders} fields).
 */
final class URLClassPathReader {
    /** The class name of {@code jdk.internal.loader.URLClassPath}. */
    private static final String URL_CLASS_PATH_CLASS_NAME = "jdk.internal.loader.URLClassPath";

    /** Not instantiable. */
    private URLClassPathReader() {
        // Cannot be constructed
    }

    /**
     * Returns the {@code jdk.internal.loader.URLClassPath} held in the {@code ucp} field of {@code classLoader}.
     *
     * @param classLoader
     *            the classloader.
     * @return the {@code URLClassPath}, or {@code null} if not present or inaccessible.
     */
    static @Nullable Object getUcp(final ClassLoader classLoader) {
        final var ucp = ReflectionUtils.getFieldVal(false, classLoader, "ucp");
        // Check the type, since this is also called speculatively for classloaders that are not known to have a
        // URLClassPath, and the fields of some other kind of object should not be read, nor its monitor held
        return ucp != null && URL_CLASS_PATH_CLASS_NAME.equals(ucp.getClass().getName()) ? ucp : null;
    }

    /**
     * Adds all classpath entries of a {@code URLClassPath} to {@code classpathOrder}.
     *
     * @param ucp
     *            the {@code URLClassPath}, as returned by {@link #getUcp(ClassLoader)}.
     * @param classLoader
     *            the classloader that owns {@code ucp}.
     * @param classpathOrder
     *            the classpath order to populate.
     * @param log
     *            the log node, or {@code null} to skip logging.
     */
    static void addAllClasspathEntries(final Object ucp, final ClassLoader classLoader,
            final ClasspathOrder classpathOrder, final @Nullable ClassGraphLog log) {
        classpathOrder.addClasspathEntry(ReflectionUtils.invokeMethod(false, ucp, "getURLs"), classLoader, log);
        addUnlistedClasspathEntries(ucp, classLoader, classpathOrder, log);
    }

    /**
     * Adds classpath entries from the {@code unopenedUrls} and {@code loaders} fields of a {@code URLClassPath}
     * that are not returned by {@code getURLs()}.
     *
     * @param ucp
     *            the {@code URLClassPath}, as returned by {@link #getUcp(ClassLoader)}.
     * @param classLoader
     *            the classloader that owns {@code ucp}.
     * @param classpathOrder
     *            the classpath order to populate.
     * @param log
     *            the log node, or {@code null} to skip logging.
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
            classpathOrder.addClasspathEntry(unopenedUrlsCopy, classLoader, log);
        }

        // The JDK adds to this list while holding the URLClassPath's own monitor, so hold that too. (These two
        // monitors are never held at the same time here, so there is no lock ordering to get wrong.) Read each
        // loader's base URL from this list, which is in classpath order, rather than the keys of the lmap field:
        // lmap is a HashMap, so its keys are in hash order, and they are strings rather than URLs. (lmap is read
        // below only for the jars that this list never holds.)
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
            // index of an opened jar lists, but never adds it to loaders. Its value is null until a lookup opens
            // the jar, and is the jar's loader after that. Add these jars after the loaders: by the base URL of the
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
