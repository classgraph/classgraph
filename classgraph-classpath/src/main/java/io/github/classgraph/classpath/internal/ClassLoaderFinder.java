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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.utils.LinkedIdentitySet;
import org.jspecify.annotations.Nullable;

/** Finds the classloaders that are present in the environment of the caller. */
public class ClassLoaderFinder {
    /** The classloaders found, in the order they should be searched in. */
    private final List<ClassLoader> classLoaders;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the classloaders found: the context classloader of the calling thread, the classloader of ClassGraph, the
     * system classloader, and the classloaders of the classes on the call stack, each listed once, with every
     * classloader ordered ahead of its own ancestors.
     *
     * @return The classloaders, in the order they should be searched in.
     */
    public List<ClassLoader> getClassLoaders() {
        return classLoaders;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the classloaders that are present in the environment.
     *
     * @param callStackInfo
     *            The call stack of the thread that started the search.
     * @param log
     *            The log.
     */
    ClassLoaderFinder(final CallStackInfo callStackInfo, final @Nullable LogNode log) {
        final var classLoadersUnique = findDefaultClassLoaders(callStackInfo);

        // Log all identified ClassLoaders
        if (log != null) {
            final var classLoadersFoundLog = log.log("Found ClassLoaders:");
            for (final ClassLoader classLoader : classLoadersUnique) {
                classLoadersFoundLog.log(classLoader.getClass().getName());
            }
        }

        this.classLoaders = List.copyOf(classLoadersUnique);
    }

    /**
     * Find the classloaders that are present in the environment.
     *
     * @param callStackInfo
     *            The call stack of the thread that started the search.
     * @return The classloaders, in the order they should be searched in.
     */
    private static List<ClassLoader> findDefaultClassLoaders(final CallStackInfo callStackInfo) {
        // Deduplicated by reference rather than by equals(), since a classloader can claim to be equal to another
        // classloader that loads a different set of classes -- see LinkedIdentitySet
        final LinkedIdentitySet<ClassLoader> classLoadersUnique = new LinkedIdentitySet<>();

        // Get the context classloader of the thread that asked for the search (this is the first classloader to
        // try, since a context classloader can be set as an override on a per-thread basis).
        final var threadClassLoader = callStackInfo.getContextClassLoader();
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

        // Get system classloader (this is a fallback, in case none of the above works)
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
        // (CallStackInfo#read falls back to naming just its own classloader, rather than throwing, if the stack
        // cannot be read.)
        classLoadersUnique.addAll(callStackInfo.getClassLoaders());

        // Order the classloaders so that a classloader is always ahead of its own ancestors, and otherwise keep the
        // preference order above.
        //
        // Only the position of the first classloader of a delegation chain to be reached is decided here: once a
        // classloader is reached, its ClassLoaderHandler decides where its ancestors' classpath elements go
        // relative to its own, by delegating to its parent before or after adding itself. If an ancestor were
        // left ahead of its own descendant in this list, it would be pinned in front of the descendant before
        // the descendant's handler ever ran, which silently converts parent-last delegation (the default for
        // Tomcat's WebappClassLoader and for Spring Boot DevTools' RestartClassLoader) into parent-first
        // delegation, inverting the class masking order. Each classloader is therefore inserted just ahead of the
        // first of its ancestors that is already listed, which moves it no further forward than it has to go.
        // (Sorting by delegation depth would also put descendants first, but would move a classloader with many
        // ancestors ahead of an unrelated context classloader with fewer.)
        final List<ClassLoader> classLoaders = new ArrayList<>(classLoadersUnique.size());
        for (final ClassLoader classLoader : classLoadersUnique) {
            var insertionIdx = classLoaders.size();
            for (var i = 0; i < classLoaders.size(); i++) {
                if (isAncestor(classLoaders.get(i), classLoader)) {
                    insertionIdx = i;
                    break;
                }
            }
            classLoaders.add(insertionIdx, classLoader);
        }
        return classLoaders;
    }

    /**
     * Determine whether one classloader is an ancestor of another.
     *
     * @param ancestor
     *            The possible ancestor.
     * @param classLoader
     *            The classloader whose parent chain is searched.
     * @return true if {@code ancestor} is reached by following {@link ClassLoader#getParent()} from
     *         {@code classLoader}, comparing by reference.
     */
    private static boolean isAncestor(final ClassLoader ancestor, final ClassLoader classLoader) {
        // Guard against a classloader whose parent chain is cyclic, rather than looping forever
        final Set<ClassLoader> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var cl = classLoader.getParent(); cl != null && seen.add(cl); cl = cl.getParent()) {
            if (cl == ancestor) {
                return true;
            }
        }
        return false;
    }
}
