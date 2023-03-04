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

/** Extract classpath entries from the Uno-Jar's JarClassLoader and One-Jar's JarClassLoader. */
class UnoOneJarClassLoaderHandler implements ClassLoaderHandler {
    /** Class cannot be constructed. */
    private UnoOneJarClassLoaderHandler() {
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
        return "com.needhamsoftware.unojar.JarClassLoader".equals(classLoaderClass.getName())
                || "com.simontuffs.onejar.JarClassLoader".equals(classLoaderClass.getName());
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

        // For Uno-Jar:

        final String unoJarOneJarPath = (String) classpathOrder.reflectionUtils.invokeMethod(false, classLoader,
                "getOneJarPath");
        classpathOrder.addClasspathEntry(unoJarOneJarPath, classLoader, scanSpec, log);

        // If this property is defined, Uno-Jar jar path was specified on commandline. Otherwise, jar path
        // should be contained in java.class.path (which will be separately picked up by ClassGraph, as
        // long as classloaders/classpath are not overloaded and parent classloaders are not ignored).
        final String unoJarJarPath = System.getProperty("uno-jar.jar.path");
        classpathOrder.addClasspathEntry(unoJarJarPath, classLoader, scanSpec, log);

        // For One-Jar:

        // If this property is defined, One-Jar jar path was specified on commandline. Otherwise, jar path
        // should be contained in java.class.path (which will be separately picked up by ClassGraph, as
        // long as classloaders/classpath are not overloaded and parent classloaders are not ignored).
        final String oneJarJarPath = System.getProperty("one-jar.jar.path");
        classpathOrder.addClasspathEntry(oneJarJarPath, classLoader, scanSpec, log);

        // If this property is defined, additional classpath entries were specified in OneJar format
        // on the commandline, with '|' as a separator
        final String oneJarClassPath = System.getProperty("one-jar.class.path");
        if (oneJarClassPath != null) {
            classpathOrder.addClasspathEntryObject(oneJarClassPath.split("\\|"), classLoader, scanSpec, log);
        }

        // For both UnoJar and OneJar, "libs/" and "main/" will be automatically picked up as library roots
        // for nested jars, based on ClassLoaderHandlerRegistry.AUTOMATIC_LIB_DIR_PREFIXES.
        // ("main/" contains "main.jar".) 
    }
}
