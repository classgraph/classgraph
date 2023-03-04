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
package nonapi.io.github.classgraph.classloaderhandler;

import java.io.File;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;

/**
 * WebsphereLibertyClassLoaderHandler.
 *
 * <p>
 * Used to support WAS Liberty Profile classloading in io.github.classgraph
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

    /** Class cannot be constructed. */
    private WebsphereLibertyClassLoaderHandler() {
    }

    /**
     * Check whether this {@link ClassLoaderHandler} can handle a given {@link ClassLoader}.
     *
     * @param classLoaderClass
     *            the {@link ClassLoader} class or one of its superclasses.
     * @param log
     *            the log
     * @return true if this {@link ClassLoaderHandler} can handle the {@link ClassLoader}.
     */
    public static boolean canHandle(final Class<?> classLoaderClass, final LogNode log) {
        return IBM_APP_CLASS_LOADER.equals(classLoaderClass.getName())
                || IBM_THREAD_CONTEXT_CLASS_LOADER.equals(classLoaderClass.getName());
    }

    /**
     * Find the {@link ClassLoader} delegation order for a {@link ClassLoader}.
     *
     * @param classLoader
     *            the {@link ClassLoader} to find the order for.
     * @param classLoaderOrder
     *            a {@link ClassLoaderOrder} object to update.
     * @param log
     *            the log
     */
    public static void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final LogNode log) {
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        classLoaderOrder.add(classLoader, log);
    }

    /**
     * Get the paths from a containerClassLoader object.
     *
     * <p>
     * The passed in object should be an instance of "com.ibm.ws.classloading.internal.ContainerClassLoader".
     * <p>
     * Will attempt to use "getContainerURLs" methods to recap the classpath.
     * 
     * @param containerClassLoader
     *            the containerClassLoader object
     * @return Collection of path objects as a {@link URL} or {@link String}.
     */
    private static Collection<Object> getPaths(final Object containerClassLoader,
            final ReflectionUtils reflectionUtils) {
        if (containerClassLoader == null) {
            return Collections.emptyList();
        }

        // Expecting this to be an instance of
        // "com.ibm.ws.classloading.internal.ContainerClassLoader$UniversalContainer".
        // Call "getContainerURLs" to get its container's classpath.
        Collection<Object> urls = callGetUrls(containerClassLoader, "getContainerURLs", reflectionUtils);
        if (urls != null && !urls.isEmpty()) {
            return urls;
        }

        // "getContainerURLs" didn't work, try getting the container object...
        final Object container = reflectionUtils.getFieldVal(false, containerClassLoader, "container");
        if (container == null) {
            return Collections.emptyList();
        }

        // Should be an instance of "com.ibm.wsspi.adaptable.module.Container".
        // Call "getURLs" to get its classpath.
        urls = callGetUrls(container, "getURLs", reflectionUtils);
        if (urls != null && !urls.isEmpty()) {
            return urls;
        }

        // "getURLs" did not work, reverting to previous logic of introspection of the "delegate".
        final Object delegate = reflectionUtils.getFieldVal(false, container, "delegate");
        if (delegate == null) {
            return Collections.emptyList();
        }

        final String path = (String) reflectionUtils.getFieldVal(false, delegate, "path");
        if (path != null && path.length() > 0) {
            return Collections.singletonList((Object) path);
        }

        final Object base = reflectionUtils.getFieldVal(false, delegate, "base");
        if (base == null) {
            // giving up.
            return Collections.emptyList();
        }

        final Object archiveFile = reflectionUtils.getFieldVal(false, base, "archiveFile");
        if (archiveFile != null) {
            final File file = (File) archiveFile;
            return Collections.singletonList((Object) file.getAbsolutePath());
        }
        return Collections.emptyList();
    }

    /**
     * Utility to call a "getURLs" method, flattening "collections of collections" and ignoring
     * "UnsupportedOperationException".
     * 
     * All of the "getURLs" methods eventually call "com.ibm.wsspi.adaptable.module.Container#getURLs()".
     * 
     * https://www.ibm.com/support/knowledgecenter/SSEQTP_liberty/com.ibm.websphere.javadoc.liberty.doc
     * /com.ibm.websphere.appserver.spi.artifact_1.2-javadoc
     * /com/ibm/wsspi/adaptable/module/Container.html?view=embed#getURLs() "A collection of URLs that represent all
     * of the locations on disk that contribute to this container"
     */
    @SuppressWarnings("unchecked")
    private static Collection<Object> callGetUrls(final Object container, final String methodName,
            final ReflectionUtils reflectionUtils) {
        if (container != null) {
            try {
                final Collection<Object> results = (Collection<Object>) reflectionUtils.invokeMethod(false,
                        container, methodName);
                if (results != null && !results.isEmpty()) {
                    final Collection<Object> allUrls = new HashSet<>();
                    for (final Object result : results) {
                        if (result instanceof Collection) {
                            // SmartClassPath returns collection of collection of URLs.
                            for (final Object url : ((Collection<Object>) result)) {
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
            } catch (final UnsupportedOperationException e) {
                /* ignore */
            }
        }
        return Collections.emptyList();
    }

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
     *            the log.
     */
    public static void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final ScanSpec scanSpec, final LogNode log) {
        Object smartClassPath;
        final Object appLoader = classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "appLoader");
        if (appLoader != null) {
            smartClassPath = classpathOrder.reflectionUtils.getFieldVal(false, appLoader, "smartClassPath");
        } else {
            smartClassPath = classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "smartClassPath");
        }
        if (smartClassPath != null) {
            // "com.ibm.ws.classloading.internal.ContainerClassLoader$SmartClassPath" 
            // interface specifies a "getClassPath" to return all urls that makeup its path.
            final Collection<Object> paths = callGetUrls(smartClassPath, "getClassPath",
                    classpathOrder.reflectionUtils);
            if (!paths.isEmpty()) {
                for (final Object path : paths) {
                    classpathOrder.addClasspathEntry(path, classLoader, scanSpec, log);
                }
            } else {
                // "getClassPath" didn't work... reverting to looping over "classPath" elements.
                @SuppressWarnings("unchecked")
                final List<Object> classPathElements = (List<Object>) classpathOrder.reflectionUtils
                        .getFieldVal(false, smartClassPath, "classPath");
                if (classPathElements != null && !classPathElements.isEmpty()) {
                    for (final Object classPathElement : classPathElements) {
                        final Collection<Object> subPaths = getPaths(classPathElement,
                                classpathOrder.reflectionUtils);
                        for (final Object path : subPaths) {
                            classpathOrder.addClasspathEntry(path, classLoader, scanSpec, log);
                        }
                    }
                }
            }
        }
    }
}
