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

import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.ReflectionUtils;

/**
 * Fallback ClassLoaderHandler. Tries to get classpath from a range of possible method and field names.
 */
class FallbackClassLoaderHandler implements ClassLoaderHandler {
    /** Class cannot be constructed. */
    private FallbackClassLoaderHandler() {
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
        // This is the fallback handler, it handles anything
        return true;
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
        boolean valid = false;
        valid |= classpathOrder.addClasspathEntryObject(
                ReflectionUtils.invokeMethod(classLoader, "getClassPath", false), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                ReflectionUtils.invokeMethod(classLoader, "getClasspath", false), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                ReflectionUtils.invokeMethod(classLoader, "classpath", false), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                ReflectionUtils.invokeMethod(classLoader, "classPath", false), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "cp", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                ReflectionUtils.getFieldVal(classLoader, "classpath", false), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                ReflectionUtils.getFieldVal(classLoader, "classPath", false), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.getFieldVal(classLoader, "cp", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "getPath", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                ReflectionUtils.invokeMethod(classLoader, "getPaths", false), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "path", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "paths", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.getFieldVal(classLoader, "paths", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.getFieldVal(classLoader, "paths", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "getDir", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "getDirs", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "dir", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "dirs", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.getFieldVal(classLoader, "dir", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.getFieldVal(classLoader, "dirs", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "getFile", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                ReflectionUtils.invokeMethod(classLoader, "getFiles", false), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "file", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "files", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.getFieldVal(classLoader, "file", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.getFieldVal(classLoader, "files", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "getJar", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "getJars", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "jar", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "jars", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.getFieldVal(classLoader, "jar", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.getFieldVal(classLoader, "jars", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "getURL", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "getURLs", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "getUrl", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "getUrls", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "url", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.invokeMethod(classLoader, "urls", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.getFieldVal(classLoader, "url", false),
                classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(ReflectionUtils.getFieldVal(classLoader, "urls", false),
                classLoader, scanSpec, log);
        if (log != null) {
            log.log("FallbackClassLoaderHandler " + (valid ? "found" : "did not find")
                    + " classpath entries in unknown ClassLoader " + classLoader);
        }
    }
}
