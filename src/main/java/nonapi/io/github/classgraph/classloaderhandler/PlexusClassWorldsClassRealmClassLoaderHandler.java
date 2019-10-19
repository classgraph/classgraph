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

import java.util.SortedSet;

import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.ReflectionUtils;

/**
 * Handle the Plexus ClassWorlds ClassRealm ClassLoader.
 * 
 * @author lukehutch
 */
class PlexusClassWorldsClassRealmClassLoaderHandler implements ClassLoaderHandler {
    /** Class cannot be constructed. */
    private PlexusClassWorldsClassRealmClassLoaderHandler() {
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
        return "org.codehaus.plexus.classworlds.realm.ClassRealm".equals(classLoaderClass.getName());
    }

    /**
     * Checks if is this classloader uses a parent-first strategy.
     *
     * @param classRealmInstance
     *            the ClassRealm instance
     * @return true if classloader uses a parent-first strategy
     */
    private static boolean isParentFirstStrategy(final ClassLoader classRealmInstance) {
        final Object strategy = ReflectionUtils.getFieldVal(classRealmInstance, "strategy", false);
        if (strategy != null) {
            final String strategyClassName = strategy.getClass().getName();
            if (strategyClassName.equals("org.codehaus.plexus.classworlds.strategy.SelfFirstStrategy")
                    || strategyClassName.equals("org.codehaus.plexus.classworlds.strategy.OsgiBundleStrategy")) {
                // Strategy is self-first
                return false;
            }
        }
        // Strategy is org.codehaus.plexus.classworlds.strategy.ParentFirstStrategy (or failed to find strategy)
        return true;
    }

    /**
     * Find the {@link ClassLoader} delegation order for a {@link ClassLoader}.
     *
     * @param classRealm
     *            the {@link ClassLoader} to find the order for.
     * @param classLoaderOrder
     *            a {@link ClassLoaderOrder} object to update.
     * @param log
     *            the log
     */
    public static void findClassLoaderOrder(final ClassLoader classRealm, final ClassLoaderOrder classLoaderOrder,
            final LogNode log) {
        // From ClassRealm#loadClassFromImport(String) -> getImportClassLoader(String)
        final Object foreignImports = ReflectionUtils.getFieldVal(classRealm, "foreignImports", false);
        if (foreignImports != null) {
            @SuppressWarnings("unchecked")
            final SortedSet<Object> foreignImportEntries = (SortedSet<Object>) foreignImports;
            for (final Object entry : foreignImportEntries) {
                final ClassLoader foreignImportClassLoader = (ClassLoader) ReflectionUtils.invokeMethod(entry,
                        "getClassLoader", false);
                // Treat foreign import classloader as if it is a parent classloader
                classLoaderOrder.delegateTo(foreignImportClassLoader, /* isParent = */ true, log);
            }
        }

        // Get delegation order -- different strategies have different delegation orders
        final boolean isParentFirst = isParentFirstStrategy(classRealm);

        // From ClassRealm#loadClassFromSelf(String) -> findLoadedClass(String) for self-first strategy
        if (!isParentFirst) {
            // Add self before parent
            classLoaderOrder.add(classRealm, log);
        }

        // From ClassRealm#loadClassFromParent -- N.B. we are ignoring parentImports, which is used to filter
        // a class name before deciding whether or not to call the parent classloader (so ClassGraph will be
        // able to load classes by name that are not imported from the parent classloader).
        final ClassLoader parentClassLoader = (ClassLoader) ReflectionUtils.invokeMethod(classRealm,
                "getParentClassLoader", false);
        classLoaderOrder.delegateTo(parentClassLoader, /* isParent = */ true, log);
        classLoaderOrder.delegateTo(classRealm.getParent(), /* isParent = */ true, log);

        // From ClassRealm#loadClassFromSelf(String) -> findLoadedClass(String) for parent-first strategy
        if (isParentFirst) {
            // Add self after parent
            classLoaderOrder.add(classRealm, log);
        }
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
        // ClassRealm extends URLClassLoader
        URLClassLoaderHandler.findClasspathOrder(classLoader, classpathOrder, scanSpec, log);
    }
}
