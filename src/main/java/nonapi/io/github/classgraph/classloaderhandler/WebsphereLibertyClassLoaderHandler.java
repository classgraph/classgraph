/*
 * This file is part of ClassGraph.
 *
 * Author: R. Kempees
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2017 R. Kempees
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
import java.util.List;

import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.ReflectionUtils;

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
     * Get the path from a classpath object.
     *
     * @param classpath
     *            the classpath object
     * @return the path object as a {@link File} or {@link String}.
     */
    private static String getPath(final Object classpath) {
        final Object container = ReflectionUtils.getFieldVal(classpath, "container", false);
        if (container == null) {
            return "";
        }

        final Object delegate = ReflectionUtils.getFieldVal(container, "delegate", false);
        if (delegate == null) {
            return "";
        }

        final String path = (String) ReflectionUtils.getFieldVal(delegate, "path", false);
        if (path != null && path.length() > 0) {
            return path;
        }

        final Object base = ReflectionUtils.getFieldVal(delegate, "base", false);
        if (base == null) {
            // giving up.
            return "";
        }

        final Object archiveFile = ReflectionUtils.getFieldVal(base, "archiveFile", false);
        if (archiveFile != null) {
            final File file = (File) archiveFile;
            return file.getAbsolutePath();
        }
        return "";
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
        final Object appLoader = ReflectionUtils.getFieldVal(classLoader, "appLoader", false);
        if (appLoader != null) {
            smartClassPath = ReflectionUtils.getFieldVal(appLoader, "smartClassPath", false);
        } else {
            smartClassPath = ReflectionUtils.getFieldVal(classLoader, "smartClassPath", false);
        }
        if (smartClassPath != null) {
            final List<?> classPathElements = (List<?>) ReflectionUtils.getFieldVal(smartClassPath, "classPath",
                    false);
            if (classPathElements != null) {
                for (final Object classpath : classPathElements) {
                    final String path = getPath(classpath);
                    if (path != null && path.length() > 0) {
                        classpathOrder.addClasspathEntry(path, classLoader, scanSpec, log);
                    }
                }
            }
        }
    }
}
