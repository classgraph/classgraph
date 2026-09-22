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
package nonapi.io.github.classgraph.classpath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LinkedIdentitySet;
import nonapi.io.github.classgraph.utils.LogNode;

/** A class to find the unique ordered classpath elements. */
public class ClassLoaderFinder {
    /** The classloaders found, in the order they should be searched in. */
    private final ClassLoader[] contextClassLoaders;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the classloaders to search: the classloaders that the scan spec overrides the environment's with, or else
     * the classloaders found in the environment (the context classloader of the calling thread, the classloader of
     * ClassGraph, the system classloader and the classloaders of the classes on the call stack, with every
     * classloader ordered ahead of its own ancestors), followed by any classloaders the scan spec adds.
     *
     * @return The classloaders, in the order they should be searched in.
     */
    public ClassLoader[] getContextClassLoaders() {
        return contextClassLoaders;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Return true if the class is, extends, or implements a given named class or interface.
     *
     * @param cls
     *            the class to test, or null.
     * @param className
     *            the name of the class or interface to look for.
     * @return true if cls is, extends, or implements the named class or interface.
     */
    // TODO: make this a default method of the ClassLoaderHandler interface in ClassGraph 5.x
    public static boolean classIsOrExtendsOrImplements(final Class<?> cls, final String className) {
        if (cls == null) {
            return false;
        }
        if (cls.getName().equals(className)) {
            return true;
        }
        if (classIsOrExtendsOrImplements(cls.getSuperclass(), className)) {
            return true;
        }
        for (final Class<?> iface : cls.getInterfaces()) {
            if (classIsOrExtendsOrImplements(iface, className)) {
                return true;
            }
        }
        return false;
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
        final Set<ClassLoader> seen = Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
        for (ClassLoader cl = classLoader.getParent(); cl != null && seen.add(cl); cl = cl.getParent()) {
            if (cl == ancestor) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A class to find the unique ordered classpath elements.
     *
     * @param scanSpec
     *            The scan spec, or null if none available.
     * @param callStackInfo
     *            What was read from the thread that started the search.
     * @param log
     *            The log.
     */
    ClassLoaderFinder(final ScanSpec scanSpec, final CallStackInfo callStackInfo, final LogNode log) {
        LinkedIdentitySet<ClassLoader> classLoadersUnique;
        LogNode classLoadersFoundLog;
        if (scanSpec.overrideClassLoaders == null) {
            // ClassLoaders were not overridden

            // Get the context classloader of the thread that asked for the search (this is the first classloader
            // to try, since a context classloader can be set as an override on a per-thread basis). It is the
            // calling thread's context classloader that is wanted, not that of the thread this code happens to
            // run on, which for an asynchronous scan is a worker thread of the ExecutorService.
            // Deduplicated by reference rather than by equals(), since a classloader can claim to be equal to
            // another classloader that loads a different set of classes -- see LinkedIdentitySet
            classLoadersUnique = new LinkedIdentitySet<>();
            final ClassLoader threadClassLoader = callStackInfo.getContextClassLoader();
            if (threadClassLoader != null) {
                classLoadersUnique.add(threadClassLoader);
            }

            // Get the classloader of ClassGraph itself. This is the classloader that can resolve every class
            // that ClassGraph can resolve by name, so anything it can see is worth scanning. (It is not
            // necessarily the classloader of the caller -- when ClassGraph is deployed in a container's shared
            // library directory, the caller is loaded by a descendant of this classloader. The caller's own
            // classloader is found from the call stack, below.)
            final ClassLoader currClassClassLoader = getClass().getClassLoader();
            if (currClassClassLoader != null) {
                classLoadersUnique.add(currClassClassLoader);
            }

            // Get system classloader (this is a fallback if one of the above do not work)
            final ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
            if (systemClassLoader != null) {
                classLoadersUnique.add(systemClassLoader);
            }

            // There is one more classloader in JDK9+, the platform classloader (used for handling extensions),
            // see: http://openjdk.java.net/jeps/261#Class-loaders
            // The method call to get it is ClassLoader.getPlatformClassLoader()
            // However, since it's not possible to get URLs from this classloader, and it is the parent of
            // the application classloader returned by ClassLoader.getSystemClassLoader() (so is delegated to
            // by the application classloader), there is no point adding it here. Modules are scanned
            // directly anyway, so we don't need to get module path entries from the platform classloader. 

            // Find classloaders for classes on callstack, in case any were missed. The call stack is read
            // innermost frame first, so the immediate caller's classloader is preferred over the classloader of
            // the code that called it -- Class.forName(className) resolves against the classloader of its
            // immediate caller.
            for (final Class<?> callStackClass : callStackInfo.getClassContext()) {
                final ClassLoader callerClassLoader = callStackClass.getClassLoader();
                if (callerClassLoader != null) {
                    classLoadersUnique.add(callerClassLoader);
                }
            }

            // Order the classloaders so that a classloader is always ahead of its own ancestors, and otherwise
            // keep the preference order above.
            //
            // Only the position of the first classloader of a delegation chain to be reached is decided here:
            // once a classloader is reached, its ClassLoaderHandler decides where its ancestors' classpath
            // elements go relative to its own, by delegating to its parent before or after adding itself. If an
            // ancestor were left ahead of its own descendant in this list, it would be pinned in front of the
            // descendant before the descendant's handler ever ran, which silently converts parent-last
            // delegation (the default for Tomcat's WebappClassLoader and for Spring Boot DevTools'
            // RestartClassLoader) into parent-first delegation, inverting the class masking order. Each
            // classloader is therefore inserted just ahead of the first of its ancestors that is already listed,
            // which moves it no further forward than it has to go. (Sorting by delegation depth would also put
            // descendants first, but would move a classloader with many ancestors ahead of an unrelated context
            // classloader with fewer.)
            final List<ClassLoader> orderedClassLoaders = new ArrayList<>(classLoadersUnique.size());
            for (final ClassLoader classLoader : classLoadersUnique) {
                int insertionIdx = orderedClassLoaders.size();
                for (int i = 0; i < orderedClassLoaders.size(); i++) {
                    if (isAncestor(orderedClassLoaders.get(i), classLoader)) {
                        insertionIdx = i;
                        break;
                    }
                }
                orderedClassLoaders.add(insertionIdx, classLoader);
            }
            classLoadersUnique = new LinkedIdentitySet<>();
            classLoadersUnique.addAll(orderedClassLoaders);

            // Add any custom-added classloaders after system/context/module classloaders
            if (scanSpec.addedClassLoaders != null) {
                classLoadersUnique.addAll(scanSpec.addedClassLoaders);
            }
            classLoadersFoundLog = log == null ? null : log.log("Found ClassLoaders:");

        } else {
            // ClassLoaders were overridden
            classLoadersUnique = new LinkedIdentitySet<>();
            classLoadersUnique.addAll(scanSpec.overrideClassLoaders);
            classLoadersFoundLog = log == null ? null : log.log("Override ClassLoaders:");
        }

        // Log all identified ClassLoaders
        if (classLoadersFoundLog != null) {
            for (final ClassLoader classLoader : classLoadersUnique) {
                classLoadersFoundLog.log(classLoader.getClass().getName());
            }
        }

        this.contextClassLoaders = classLoadersUnique.toArray(new ClassLoader[0]);
    }
}
