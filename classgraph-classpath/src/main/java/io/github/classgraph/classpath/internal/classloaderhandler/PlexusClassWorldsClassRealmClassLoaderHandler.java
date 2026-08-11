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

import java.util.SortedSet;

import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.classpath.internal.ClassLoaderOrder;
import org.jspecify.annotations.Nullable;

/**
 * Handle the Plexus ClassWorlds ClassRealm ClassLoader.
 *
 * @author Luke Hutchison
 */
class PlexusClassWorldsClassRealmClassLoaderHandler extends URLClassLoaderHandler {
    /** Constructor. */
    PlexusClassWorldsClassRealmClassLoaderHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable LogNode log) {
        return classIsOrExtendsOrImplements(classLoaderClass, "org.codehaus.plexus.classworlds.realm.ClassRealm");
    }

    /**
     * Checks if is this classloader uses a parent-first strategy.
     *
     * @param classRealmInstance
     *            the ClassRealm instance
     * @param reflectionUtils
     *            the reflection utils instance
     * @return true if classloader uses a parent-first strategy
     */
    private static boolean isParentFirstStrategy(final ClassLoader classRealmInstance,
            final ReflectionUtils reflectionUtils) {
        final var strategy = reflectionUtils.getFieldVal(false, classRealmInstance, "strategy");
        if (strategy != null) {
            final var strategyClassName = strategy.getClass().getName();
            if ("org.codehaus.plexus.classworlds.strategy.SelfFirstStrategy".equals(strategyClassName)
                    || "org.codehaus.plexus.classworlds.strategy.OsgiBundleStrategy".equals(strategyClassName)) {
                // Strategy is self-first
                return false;
            }
        }
        // Strategy is org.codehaus.plexus.classworlds.strategy.ParentFirstStrategy (or failed to find strategy)
        return true;
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable LogNode log) {
        // From ClassRealm#loadClassFromImport(String) -> getImportClassLoader(String)
        final var foreignImports = classLoaderOrder.reflectionUtils.getFieldVal(false, classLoader,
                "foreignImports");
        if (foreignImports != null) {
            @SuppressWarnings("unchecked")
            final var foreignImportEntries = (SortedSet<Object>) foreignImports;
            for (final Object entry : foreignImportEntries) {
                final var foreignImportClassLoader = (ClassLoader) classLoaderOrder.reflectionUtils
                        .invokeMethod(false, entry, "getClassLoader");
                // Treat foreign import classloader as if it is a parent classloader
                classLoaderOrder.delegateTo(foreignImportClassLoader, /* isParent = */ true, log);
            }
        }

        // Get delegation order -- different strategies have different delegation orders
        final var isParentFirst = isParentFirstStrategy(classLoader, classLoaderOrder.reflectionUtils);

        // From ClassRealm#loadClassFromSelf(String) -> findLoadedClass(String) for self-first strategy
        if (!isParentFirst) {
            // Add self before parent
            classLoaderOrder.add(classLoader, log);
        }

        // From ClassRealm#loadClassFromParent -- N.B. we are ignoring parentImports, which is used to filter a
        // class name before deciding whether or not to call the parent classloader (so ClassGraph will be able to
        // load classes by name that are not imported from the parent classloader).
        final var parentClassLoader = (ClassLoader) classLoaderOrder.reflectionUtils.invokeMethod(false,
                classLoader, "getParentClassLoader");
        classLoaderOrder.delegateTo(parentClassLoader, /* isParent = */ true, log);
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);

        // From ClassRealm#loadClassFromSelf(String) -> findLoadedClass(String) for parent-first strategy
        if (isParentFirst) {
            // Add self after parent
            classLoaderOrder.add(classLoader, log);
        }
    }

    // findClasspathOrder() is inherited from URLClassLoaderHandler, since ClassRealm extends URLClassLoader

    /**
     * Get the automatic package root prefixes for classpath elements obtained from this classloader.
     *
     * <p>
     * Classpath elements from this classloader may be Spring-Boot executable jars or wars.
     *
     * @return the package root prefixes.
     */
    @Override
    public String[] getPackageRootPrefixes() {
        return ClassLoaderHandlerRegistry.DEFAULT_PACKAGE_ROOT_PREFIXES;
    }
}
