/*
 * This file is part of ClassGraph.
 *
 * Author: R. Kempees
 *
 * With contributions from @cpierceworld (#414)
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2017 R. Kempees (contributed to the ClassGraph project)
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

import java.io.File;
import java.net.URL;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;

/**
 * Extracts the classloader delegation order and classpath entries from IBM WebSphere Liberty application and thread
 * context classloaders.
 *
 * @author R. Kempees
 */
class WebsphereLibertyClassLoaderHandler implements ClassLoaderHandler {
    /** {@code "com.ibm.ws.classloading.internal."} */
    private static final String PKG_PREFIX = "com.ibm.ws.classloading.internal.";

    /** {@code "com.ibm.ws.classloading.internal.AppClassLoader"} */
    private static final String IBM_APP_CLASS_LOADER = PKG_PREFIX + "AppClassLoader";

    /** {@code "com.ibm.ws.classloading.internal.ParentLastClassLoader"} */
    private static final String IBM_PARENT_LAST_CLASS_LOADER = PKG_PREFIX + "ParentLastClassLoader";

    /** {@code "com.ibm.ws.classloading.internal.ThreadContextClassLoader"} */
    private static final String IBM_THREAD_CONTEXT_CLASS_LOADER = PKG_PREFIX + "ThreadContextClassLoader";

    /** Constructor. */
    WebsphereLibertyClassLoaderHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
        return classIsOrExtendsOrImplements(classLoaderClass, IBM_APP_CLASS_LOADER)
                || classIsOrExtendsOrImplements(classLoaderClass, IBM_THREAD_CONTEXT_CLASS_LOADER);
    }

    /**
     * Delegates to each classloader in the specified iterable field of a Liberty classloader.
     *
     * @param classLoader
     *            the classloader.
     * @param fieldName
     *            the name of the field holding the delegate classloaders.
     * @param classLoaderOrder
     *            the classloader order.
     * @param log
     *            the log node, or {@code null} to skip logging.
     */
    private static void delegateToAll(final ClassLoader classLoader, final String fieldName,
            final ClassLoaderOrder classLoaderOrder, final @Nullable ClassGraphLog log) {
        final var delegates = ReflectionUtils.getFieldVal(false, classLoader, fieldName);
        if (delegates instanceof final Iterable<?> delegateList) {
            for (final Object delegate : delegateList) {
                if (delegate instanceof final ClassLoader delegateClassLoader) {
                    classLoaderOrder.delegateToPeer(delegateClassLoader, log);
                }
            }
        }
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable ClassGraphLog log) {
        // An AppClassLoader holds the classloaders of the libraries it delegates to, split by the precedence
        // configured for each library: "beforeApp" libraries are searched first, even before the parent, and
        // "afterApp" libraries right after the application's own classpath. A parent-first AppClassLoader searches
        // its parent between the "beforeApp" libraries and its own classpath; a ParentLastClassLoader (an
        // application configured with delegation="parentLast") searches its parent after everything else.
        final var parentLast = classIsOrExtendsOrImplements(classLoader.getClass(), IBM_PARENT_LAST_CLASS_LOADER);
        delegateToAll(classLoader, "beforeAppDelegateLoaders", classLoaderOrder, log);
        if (!parentLast) {
            classLoaderOrder.delegateToParent(classLoader.getParent(), log);
        }
        classLoaderOrder.add(classLoader, log);
        delegateToAll(classLoader, "afterAppDelegateLoaders", classLoaderOrder, log);
        if (parentLast) {
            classLoaderOrder.delegateToParent(classLoader.getParent(), log);
        }
        // A ThreadContextClassLoader extends UnifiedClassLoader, which searches its parent, then in turn each of
        // the classloaders in "followOnClassLoaders"
        delegateToAll(classLoader, "followOnClassLoaders", classLoaderOrder, log);
    }

    /**
     * Extracts paths or URLs from a {@code com.ibm.ws.classloading.internal.ContainerClassLoader} classpath
     * element.
     *
     * @param containerClassLoader
     *            the classpath element, or {@code null}.
     * @return the extracted {@link URL} or {@link String} paths, or an empty collection if none were found.
     */
    private static Collection<Object> getPaths(final @Nullable Object containerClassLoader) {
        if (containerClassLoader == null) {
            return List.of();
        }

        // Expecting this to be an instance of
        // "com.ibm.ws.classloading.internal.ContainerClassLoader$UniversalContainer". Call "getContainerURLs" to
        // get its container's classpath.
        var urls = callGetUrls(containerClassLoader, "getContainerURLs");
        if (!urls.isEmpty()) {
            return urls;
        }

        // "getContainerURLs" didn't work, try getting the container object...
        final var container = ReflectionUtils.getFieldVal(false, containerClassLoader, "container");
        if (container == null) {
            return List.of();
        }

        // Should be an instance of "com.ibm.wsspi.adaptable.module.Container". Call "getURLs" to get its classpath.
        urls = callGetUrls(container, "getURLs");
        if (!urls.isEmpty()) {
            return urls;
        }

        // "getURLs" did not work, reverting to previous logic of introspection of the "delegate".
        final var delegate = ReflectionUtils.getFieldVal(false, container, "delegate");
        if (delegate == null) {
            return List.of();
        }

        final var path = (String) ReflectionUtils.getFieldVal(false, delegate, "path");
        if (path != null && !path.isEmpty()) {
            return List.of(path);
        }

        final var base = ReflectionUtils.getFieldVal(false, delegate, "base");
        if (base == null) {
            // giving up.
            return List.of();
        }

        if (ReflectionUtils.getFieldVal(false, base, "archiveFile") instanceof final File archiveFile) {
            return List.of(archiveFile.getAbsolutePath());
        }
        return List.of();
    }

    /**
     * Invokes a URL-returning method on {@code container} and flattens any nested collections into a single
     * collection.
     *
     * @param container
     *            the container object.
     * @param methodName
     *            the name of the method to invoke.
     * @return the flattened URLs, or an empty collection if the method returned nothing.
     */
    private static Collection<Object> callGetUrls(final Object container, final String methodName) {
        if (!(ReflectionUtils.invokeMethod(false, container, methodName) instanceof final Collection<?> results)
                || results.isEmpty()) {
            return List.of();
        }
        // Classpath order decides which copy of a duplicated class is loaded, so keep the order the container
        // returned rather than letting it depend on the hash order of the URL objects
        final Collection<Object> allUrls = new LinkedHashSet<>();
        for (final Object result : results) {
            if (result instanceof final Collection<?> resultCollection) {
                // SmartClassPath returns a collection of collections of URLs
                for (final Object url : resultCollection) {
                    if (url != null) {
                        allUrls.add(url);
                    }
                }
            } else if (result != null) {
                allUrls.add(result);
            }
        }
        return allUrls;
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final @Nullable ClassGraphLog log) {
        final var appLoader = ReflectionUtils.getFieldVal(false, classLoader, "appLoader");
        final var smartClassPath = ReflectionUtils.getFieldVal(false, appLoader != null ? appLoader : classLoader,
                "smartClassPath");
        if (smartClassPath != null) {
            // The "com.ibm.ws.classloading.internal.ContainerClassLoader$SmartClassPath" interface has a
            // "getClassPath" method that returns all the URLs that make up its path
            final var paths = callGetUrls(smartClassPath, "getClassPath");
            if (!paths.isEmpty()) {
                for (final Object path : paths) {
                    classpathOrder.addClasspathEntry(path, classLoader, log);
                }
            } else {
                // "getClassPath" didn't work, so fall back to reading the "classPath" elements one at a time
                if (ReflectionUtils.getFieldVal(false, smartClassPath,
                        "classPath") instanceof final Iterable<?> classPathElements) {
                    for (final Object classPathElement : classPathElements) {
                        for (final Object path : getPaths(classPathElement)) {
                            classpathOrder.addClasspathEntry(path, classLoader, log);
                        }
                    }
                }
            }
        }
    }

}
