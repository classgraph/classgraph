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
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getClassPath"), classLoader,
                scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getClasspath"), classLoader,
                scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "classpath"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "classPath"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "cp"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "classpath"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "classPath"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "cp"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getPath"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getPaths"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "path"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "paths"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "paths"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "paths"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getDir"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getDirs"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "dir"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "dirs"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "dir"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "dirs"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getFile"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getFiles"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "file"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "files"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "file"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "files"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getJar"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getJars"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "jar"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "jars"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "jar"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "jars"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getURL"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getURLs"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getUrl"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getUrls"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "url"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "urls"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "url"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "urls"), classLoader, scanSpec, log);
        if (log != null) {
            log.log("FallbackClassLoaderHandler " + (valid ? "found" : "did not find")
                    + " classpath entries in unknown ClassLoader " + classLoader);
        }
    }
}
