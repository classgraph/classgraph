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
package io.github.classgraph.classpath.internal;

import java.util.LinkedHashSet;

import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.classpath.internal.spec.ClassLoaderAndModuleLayerSpec;
import org.jspecify.annotations.Nullable;

/** A class to find the unique ordered classpath elements. */
public class ClassLoaderFinder {
    /** The context class loaders. */
    private final ClassLoader[] contextClassLoaders;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the context class loaders.
     *
     * @return The context classloader, and any other classloader that is not an ancestor of context classloader.
     */
    public ClassLoader[] getContextClassLoaders() {
        return contextClassLoaders;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A class to find the unique ordered classpath elements.
     *
     * @param classLoaderAndModuleLayerSpec
     *            The classloaders and module layers the caller asked to be scanned.
     * @param log
     *            The log.
     */
    ClassLoaderFinder(final ClassLoaderAndModuleLayerSpec classLoaderAndModuleLayerSpec,
            final @Nullable LogNode log) {
        final LinkedHashSet<ClassLoader> classLoadersUnique;
        final @Nullable LogNode classLoadersFoundLog;
        if (classLoaderAndModuleLayerSpec.overrideClassLoaders == null) {
            classLoadersUnique = findDefaultClassLoaders(classLoaderAndModuleLayerSpec, log);
            classLoadersFoundLog = log == null ? null : log.log("Found ClassLoaders:");
        } else {
            // ClassLoaders were overridden
            classLoadersUnique = new LinkedHashSet<>(classLoaderAndModuleLayerSpec.overrideClassLoaders);
            classLoadersFoundLog = log == null ? null : log.log("Override ClassLoaders:");
        }

        // Log all identified ClassLoaders
        if (classLoadersFoundLog != null) {
            for (final ClassLoader classLoader : classLoadersUnique) {
                classLoadersFoundLog.log(classLoader.getClass().getName());
            }
        }

        this.contextClassLoaders = classLoadersUnique.toArray(ClassLoader[]::new);
    }

    /**
     * Find the classloaders to scan when the classloaders have not been overridden by the scan spec.
     *
     * <p>
     * There's some advice here about choosing the best or the right classloader, but it is not complete (e.g. it
     * doesn't cover parent delegation modes):
     * http://www.javaworld.com/article/2077344/core-java/find-a-way-out-of-the-classloader-maze.html?page=2
     *
     * @param classLoaderAndModuleLayerSpec
     *            The classloaders and module layers the caller asked to be scanned.
     * @param log
     *            The log.
     * @return The classloaders, in the order they should be scanned in.
     */
    private static LinkedHashSet<ClassLoader> findDefaultClassLoaders(
            final ClassLoaderAndModuleLayerSpec classLoaderAndModuleLayerSpec, final @Nullable LogNode log) {
        final LinkedHashSet<ClassLoader> classLoadersUnique = new LinkedHashSet<>();

        // Get thread context classloader (this is the first classloader to try, since a context classloader can
        // be set as an override on a per-thread basis)
        final var threadClassLoader = Thread.currentThread().getContextClassLoader();
        if (threadClassLoader != null) {
            classLoadersUnique.add(threadClassLoader);
        }

        // Get classloader for this class, which will generally be the classloader of the class that called
        // ClassGraph (the classloader of the caller is used by Class.forName(className), when no classloader is
        // provided)
        final var currClassClassLoader = ClassLoaderFinder.class.getClassLoader();
        if (currClassClassLoader != null) {
            classLoadersUnique.add(currClassClassLoader);
        }

        // Get system classloader (this is a fallback if one of the above do not work)
        final var systemClassLoader = ClassLoader.getSystemClassLoader();
        if (systemClassLoader != null) {
            classLoadersUnique.add(systemClassLoader);
        }

        // There is one more classloader in JDK9+, the platform classloader (used for handling extensions), see:
        // http://openjdk.java.net/jeps/261#Class-loaders
        // The method call to get it is ClassLoader.getPlatformClassLoader(). However, since it's not possible
        // to get URLs from this classloader, and it is the parent of the application classloader returned by
        // ClassLoader.getSystemClassLoader() (so is delegated to by the application classloader), there is no
        // point adding it here. Modules are scanned directly anyway, so we don't need to get module path
        // entries from the platform classloader.

        // Find classloaders for classes on callstack, in case any were missed
        try {
            final var callStack = CallStackReader.getClassContext();
            for (var i = callStack.length - 1; i >= 0; --i) {
                final var callerClassLoader = callStack[i].getClassLoader();
                if (callerClassLoader != null) {
                    classLoadersUnique.add(callerClassLoader);
                }
            }
        } catch (final IllegalArgumentException e) {
            if (log != null) {
                log.log("Could not get call stack", e);
            }
        }

        // Add any custom-added classloaders after system/context/module classloaders
        if (classLoaderAndModuleLayerSpec.addedClassLoaders != null) {
            classLoadersUnique.addAll(classLoaderAndModuleLayerSpec.addedClassLoaders);
        }
        return classLoadersUnique;
    }
}
