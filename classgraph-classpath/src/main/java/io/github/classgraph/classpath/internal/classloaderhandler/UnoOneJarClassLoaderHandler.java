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

import java.util.List;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.base.internal.utils.VersionFinder;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;
import org.jspecify.annotations.Nullable;

/**
 * Extract classpath entries from the Uno-Jar's JarClassLoader and One-Jar's JarClassLoader.
 */
class UnoOneJarClassLoaderHandler implements ClassLoaderHandler {
    /**
     * The lib dirs of an Uno-Jar or One-JAR executable jarfile. {@code JarClassLoader} loads classes from every
     * jarfile it finds under its {@code MAIN_PREFIX} ({@code "main/"}), which holds the jarfile it launches, and
     * under its {@code LIB_PREFIX} ({@code "lib/"}), which holds the jarfiles that jarfile depends upon. (Its third
     * dir, {@code "binlib/"}, holds native libraries, not classes.)
     */
    private static final List<String> UNO_ONE_JAR_LIB_DIR_PREFIXES = List.of("lib/", "main/");

    /** Constructor. */
    UnoOneJarClassLoaderHandler() {
    }

    @Override
    public List<String> getLibDirPrefixes() {
        return UNO_ONE_JAR_LIB_DIR_PREFIXES;
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
        return classIsOrExtendsOrImplements(classLoaderClass, "com.needhamsoftware.unojar.JarClassLoader")
                || classIsOrExtendsOrImplements(classLoaderClass, "com.simontuffs.onejar.JarClassLoader");
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable ClassGraphLog log) {
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        classLoaderOrder.add(classLoader, log);
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final @Nullable ClassGraphLog log) {

        // For Uno-Jar:

        final var unoJarOneJarPath = (String) ReflectionUtils.invokeMethod(false, classLoader, "getOneJarPath");
        classpathOrder.addClasspathEntry(unoJarOneJarPath, classLoader, log);

        // If this property is defined, Uno-Jar jar path was specified on commandline. Otherwise, jar path should be
        // contained in java.class.path (which will be separately picked up by ClassGraph, as long as
        // classloaders/classpath are not overloaded and parent classloaders are not ignored).
        final var unoJarJarPath = VersionFinder.getProperty("uno-jar.jar.path");
        classpathOrder.addClasspathEntry(unoJarJarPath, classLoader, log);

        // If this property is defined, additional classpath entries were specified on the commandline, with '|' as
        // a separator
        final var unoJarClassPath = VersionFinder.getProperty("uno-jar.class.path");
        if (unoJarClassPath != null) {
            classpathOrder.addClasspathEntryObject(unoJarClassPath.split("\\|"), classLoader, log);
        }

        // For One-Jar:

        // If this property is defined, One-Jar jar path was specified on commandline. Otherwise, jar path should be
        // contained in java.class.path (which will be separately picked up by ClassGraph, as long as
        // classloaders/classpath are not overloaded and parent classloaders are not ignored).
        final var oneJarJarPath = VersionFinder.getProperty("one-jar.jar.path");
        classpathOrder.addClasspathEntry(oneJarJarPath, classLoader, log);

        // If this property is defined, additional classpath entries were specified in OneJar format on the
        // commandline, with '|' as a separator
        final var oneJarClassPath = VersionFinder.getProperty("one-jar.class.path");
        if (oneJarClassPath != null) {
            classpathOrder.addClasspathEntryObject(oneJarClassPath.split("\\|"), classLoader, log);
        }

        // For both Uno-Jar and One-Jar, "lib/" and "main/" are automatically picked up as library roots for nested
        // jars -- see getLibDirPrefixes(). ("main/" contains "main.jar".)
    }

}
