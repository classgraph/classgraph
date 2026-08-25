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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.classgraph.base.LogNode;
import org.jspecify.annotations.Nullable;

/** A class to find the classloaders that are present in the environment. */
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
     * Find the classloaders that are present in the environment.
     *
     * @param callStack
     *            The call stack of the thread that started the search.
     * @param log
     *            The log.
     */
    ClassLoaderFinder(final CallStack callStack, final @Nullable LogNode log) {
        final var classLoadersUnique = findDefaultClassLoaders(callStack);

        // Log all identified ClassLoaders
        if (log != null) {
            final var classLoadersFoundLog = log.log("Found ClassLoaders:");
            for (final ClassLoader classLoader : classLoadersUnique) {
                classLoadersFoundLog.log(classLoader.getClass().getName());
            }
        }

        this.contextClassLoaders = classLoadersUnique.toArray(ClassLoader[]::new);
    }

    /**
     * Find the classloaders that are present in the environment.
     *
     * <p>
     * There's some advice here about choosing the best or the right classloader, but it is not complete (e.g. it
     * doesn't cover parent delegation modes):
     * http://www.javaworld.com/article/2077344/core-java/find-a-way-out-of-the-classloader-maze.html?page=2
     *
     * @param callStack
     *            The call stack of the thread that started the search.
     * @return The classloaders, in the order they should be searched in.
     */
    private static List<ClassLoader> findDefaultClassLoaders(final CallStack callStack) {
        final LinkedHashSet<ClassLoader> classLoadersUnique = new LinkedHashSet<>();

        // Get thread context classloader (this is the first classloader to try, since a context classloader can
        // be set as an override on a per-thread basis)
        final var threadClassLoader = Thread.currentThread().getContextClassLoader();
        if (threadClassLoader != null) {
            classLoadersUnique.add(threadClassLoader);
        }

        // Get the classloader of ClassGraph itself. This is the classloader that can resolve every class that
        // ClassGraph can resolve by name, so anything it can see is worth scanning. (It is not necessarily the
        // classloader of the caller -- when ClassGraph is deployed in a container's shared library directory, the
        // caller is loaded by a descendant of this classloader. The caller's own classloader is found from the
        // call stack, below.)
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

        // Find classloaders for classes on callstack, in case any were missed. The call stack is read innermost
        // frame first, so the immediate caller's classloader is preferred over the classloader of the code that
        // called it -- Class.forName(className) resolves against the classloader of its immediate caller.
        // (CallStack#read falls back to naming just itself, rather than throwing, if the stack cannot be read.)
        for (final var callStackClass : callStack.getClassContext()) {
            final var callerClassLoader = callStackClass.getClassLoader();
            if (callerClassLoader != null) {
                classLoadersUnique.add(callerClassLoader);
            }
        }

        // Sort the classloaders so that a classloader is always ordered before its own ancestors, keeping the
        // preference order above between classloaders that are unrelated to each other (List#sort is stable).
        //
        // Only the position of the first classloader of a delegation chain to be reached is decided here: once a
        // classloader is reached, its ClassLoaderHandler decides where its ancestors' classpath elements go
        // relative to its own, by delegating to its parent before or after adding itself. If an ancestor were
        // left ahead of its own descendant in this list, it would be pinned in front of the descendant before
        // the descendant's handler ever ran, which silently converts parent-last delegation (the default for
        // Tomcat's WebappClassLoader and for Spring Boot DevTools' RestartClassLoader) into parent-first
        // delegation, inverting the class masking order. Sorting by descending delegation depth cannot place an
        // ancestor first, since an ancestor is always strictly shallower than its descendants.
        final var classLoaders = new ArrayList<>(classLoadersUnique);
        classLoaders.sort(Comparator.comparingInt(ClassLoaderFinder::delegationDepth).reversed());
        return classLoaders;
    }

    /**
     * Get the number of classloaders in the delegation chain of a classloader, including the classloader itself.
     *
     * @param classLoader
     *            The classloader.
     * @return The number of classloaders from the given classloader up to and including the last non-bootstrap
     *         classloader in its parent chain.
     */
    private static int delegationDepth(final ClassLoader classLoader) {
        // Guard against a classloader whose parent chain is cyclic, rather than looping forever
        final Set<ClassLoader> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var depth = 0;
        for (var cl = classLoader; cl != null && seen.add(cl); cl = cl.getParent()) {
            depth++;
        }
        return depth;
    }
}
