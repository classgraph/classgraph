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

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;
import org.jspecify.annotations.Nullable;

/**
 * Finds the classloader delegation order and the classpath of the IBM WebSphere Liberty application and thread
 * context classloaders.
 *
 * @author R. Kempees
 */
class WebsphereLibertyClassLoaderHandler implements ClassLoaderHandler {
    /** {@code "com.ibm.ws.classloading.internal."} */
    private static final String PKG_PREFIX = "com.ibm.ws.classloading.internal.";

    /** {@code "com.ibm.ws.classloading.internal.AppClassLoader"} */
    private static final String IBM_APP_CLASS_LOADER = PKG_PREFIX + "AppClassLoader";

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
     * Delegate to every classloader held in one of the delegate-classloader fields of a Liberty classloader. The
     * field holds an {@link Iterable} of classloaders, or null if this classloader has no delegates of that kind.
     *
     * @param classLoader
     *            the classloader
     * @param fieldName
     *            the name of the field holding the delegate classloaders
     * @param classLoaderOrder
     *            the classloader order
     * @param log
     *            the log node, or null to skip logging
     */
    private static void delegateToAll(final ClassLoader classLoader, final String fieldName,
            final ClassLoaderOrder classLoaderOrder, final @Nullable ClassGraphLog log) {
        final var delegates = ReflectionUtils.getFieldVal(false, classLoader, fieldName);
        if (delegates instanceof final Iterable<?> delegateList) {
            for (final Object delegate : delegateList) {
                if (delegate instanceof final ClassLoader delegateClassLoader) {
                    classLoaderOrder.delegateTo(delegateClassLoader, /* isParent = */ false, log);
                }
            }
        }
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable ClassGraphLog log) {
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        // An AppClassLoader holds the classloaders of the libraries it delegates to, split by the precedence
        // configured for each library: "beforeApp" libraries are searched before the application's own classpath,
        // "afterApp" libraries after it
        delegateToAll(classLoader, "beforeAppDelegateLoaders", classLoaderOrder, log);
        classLoaderOrder.add(classLoader, log);
        delegateToAll(classLoader, "afterAppDelegateLoaders", classLoaderOrder, log);
        // A ThreadContextClassLoader extends UnifiedClassLoader, which searches its parent, then in turn each of
        // the classloaders in "followOnClassLoaders"
        delegateToAll(classLoader, "followOnClassLoaders", classLoaderOrder, log);
    }

    /**
     * Get the paths of a {@code com.ibm.ws.classloading.internal.ContainerClassLoader} classpath element, from its
     * {@code getContainerURLs} method if it has one, otherwise from the container it wraps.
     *
     * @param containerClassLoader
     *            the classpath element, or null
     * @return the paths, each a {@link URL} or a {@link String}, or an empty collection if none were found.
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
     * Call a "getURLs"-style method, flattening a collection of collections into a single collection. A container
     * that does not implement the method throws {@link UnsupportedOperationException}, which
     * {@link ReflectionUtils#invokeMethod(boolean, Object, String)} turns into a null result, so that container
     * contributes no URLs.
     *
     * <p>
     * All of the "getURLs"-style methods end up calling {@code com.ibm.wsspi.adaptable.module.Container#getURLs()},
     * which returns "a collection of URLs that represent all of the locations on disk that contribute to this
     * container".
     *
     * @param container
     *            the container object to call the method on
     * @param methodName
     *            the name of the "getURLs"-style method to call
     * @return the flattened URLs, or an empty collection if the method could not be called or returned nothing.
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
