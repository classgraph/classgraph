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
import java.util.HashSet;
import java.util.List;

import nonapi.io.github.classgraph.classpath.ClassLoaderFinder;
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

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final LogNode log) {
        return ClassLoaderFinder.classIsOrExtendsOrImplements(classLoaderClass, IBM_APP_CLASS_LOADER)
                || ClassLoaderFinder.classIsOrExtendsOrImplements(classLoaderClass,
                        IBM_THREAD_CONTEXT_CLASS_LOADER);
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
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
     * @param reflectionUtils
     *            the reflection utils instance
     * @return Collection of path objects as a {@link URL} or {@link String}.
     */
    private static Collection<Object> getPaths(final Object containerClassLoader,
            final ReflectionUtils reflectionUtils) {
        if (containerClassLoader == null) {
            return List.of();
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
            return List.of();
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
            return List.of();
        }

        final String path = (String) reflectionUtils.getFieldVal(false, delegate, "path");
        if (path != null && !path.isEmpty()) {
            return List.of(path);
        }

        final Object base = reflectionUtils.getFieldVal(false, delegate, "base");
        if (base == null) {
            // giving up.
            return List.of();
        }

        final Object archiveFile = reflectionUtils.getFieldVal(false, base, "archiveFile");
        if (archiveFile != null) {
            final File file = (File) archiveFile;
            return List.of(file.getAbsolutePath());
        }
        return List.of();
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
     *
     * @param container
     *            the container object to call the method on
     * @param methodName
     *            the name of the "getURLs"-style method to call
     * @param reflectionUtils
     *            the reflection utils instance
     * @return the flattened URLs, or an empty collection if the method could not be called or returned nothing.
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
                        if (result instanceof final Collection<?> resultCollection) {
                            // SmartClassPath returns collection of collection of URLs.
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
            } catch (final UnsupportedOperationException e) {
                /* ignore */
            }
        }
        return List.of();
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
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

    /**
     * Get the automatic package root prefixes for classpath elements obtained from this classloader.
     *
     * <p>
     * Classpath elements from this classloader may be Spring-Boot executable jars or wars.
     *
     * @return the package root prefixes.
     */
    @Override
    public String[] getPackageRootPrefixes() {
        return ClassLoaderHandlerRegistry.DEFAULT_PACKAGE_ROOT_PREFIXES;
    }
}
