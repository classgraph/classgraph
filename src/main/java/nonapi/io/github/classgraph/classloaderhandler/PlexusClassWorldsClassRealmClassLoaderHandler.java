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

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;

import nonapi.io.github.classgraph.ScanSpec;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.ReflectionUtils;

/**
 * Handle the Plexus ClassWorlds ClassRealm ClassLoader.
 * 
 * @author lukehutch
 */
class PlexusClassWorldsClassRealmClassLoaderHandler implements ClassLoaderHandler {

    /* (non-Javadoc)
     * @see nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandler#handledClassLoaders()
     */
    @Override
    public String[] handledClassLoaders() {
        return new String[] { "org.codehaus.plexus.classworlds.realm.ClassRealm" };
    }

    /* (non-Javadoc)
     * @see nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandler#getEmbeddedClassLoader(java.lang.ClassLoader)
     */
    @Override
    public ClassLoader getEmbeddedClassLoader(final ClassLoader classRealmInstance) {
        //        Set<ClassLoader> classLoaderOrder = new LinkedHashSet<>();
        //
        //        // From ClassRealm#loadClassFromImport(String) -> getImportClassLoader(String)
        //        final Object foreignImports = ReflectionUtils.getFieldVal(classRealmInstance, "foreignImports", false);
        //        if (foreignImports != null) {
        //            @SuppressWarnings("unchecked")
        //            SortedSet<Object> foreignImportEntries = (SortedSet<Object>) foreignImports;
        //            for (Object entry : foreignImportEntries) {
        //                final Object classLoader = ReflectionUtils.invokeMethod(entry, "getClassLoader", false);
        //                if (classLoader instanceof ClassLoader) {
        //                    classLoaderOrder.add((ClassLoader) classLoader);
        //                }
        //            }
        //        }
        //
        //        // Get delegation order -- different strategies have different delegation orders
        //        DelegationOrder delegationOrder = getDelegationOrder(classRealmInstance);
        //
        //        // From ClassRealm#loadClassFromSelf(String) -> findLoadedClass(String) for self-first strategy
        //        if (delegationOrder == DelegationOrder.PARENT_LAST) {
        //            classLoaderOrder.add(classRealmInstance);
        //        }
        //
        //        // From ClassRealm#loadClassFromParent -- N.B. we are ignoring parentImports, which is used to filter
        //        // a class name before deciding whether or not to call the parent classloader (so ClassGraph will be
        //        // able to load classes by name that are not imported from the parent classloader).
        //        final Object parentClassLoader = ReflectionUtils.invokeMethod(classRealmInstance, "getParentClassLoader",
        //                false);
        //        if (parentClassLoader instanceof ClassLoader) {
        //            classLoaderOrder.add((ClassLoader) parentClassLoader);
        //        }
        //
        //        // From ClassRealm#loadClassFromSelf(String) -> findLoadedClass(String) for parent-first strategy
        //        if (delegationOrder == DelegationOrder.PARENT_FIRST) {
        //            classLoaderOrder.add(classRealmInstance);
        //        }
        //
        //        return classLoaderOrder;
        return null;
    }

    /* (non-Javadoc)
     * @see nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandler#getDelegationOrder(java.lang.ClassLoader)
     */
    @Override
    public DelegationOrder getDelegationOrder(final ClassLoader classRealmInstance) {
        final Object strategy = ReflectionUtils.getFieldVal(classRealmInstance, "strategy", false);
        if (strategy != null) {
            String strategyClassName = strategy.getClass().getName();
            if (strategyClassName.equals("org.codehaus.plexus.classworlds.strategy.SelfFirstStrategy")
                    || strategyClassName.equals("org.codehaus.plexus.classworlds.strategy.OsgiBundleStrategy")) {
                return DelegationOrder.PARENT_LAST;
            }
        }
        // org.codehaus.plexus.classworlds.strategy.ParentFirstStrategy (or failed to find strategy)
        return DelegationOrder.PARENT_FIRST;
    }

    /* (non-Javadoc)
     * @see nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandler#handle(
     * nonapi.io.github.classgraph.ScanSpec, java.lang.ClassLoader,
     * nonapi.io.github.classgraph.classpath.ClasspathOrder, nonapi.io.github.classgraph.utils.LogNode)
     */
    @Override
    public void handle(final ScanSpec scanSpec, final ClassLoader classloader,
            final ClasspathOrder classpathOrderOut, final LogNode log) {
        //        final Object[] entries = (Object[]) ReflectionUtils.getFieldVal(classpathManager, "entries", false);
        //        if (entries != null) {
        //            for (final Object entry : entries) {
        //                final Object bundleFile = ReflectionUtils.invokeMethod(entry, "getBundleFile", false);
        //                final File baseFile = (File) ReflectionUtils.invokeMethod(bundleFile, "getBaseFile", false);
        //                if (baseFile != null) {
        //                    classpathOrderOut.addClasspathEntry(baseFile.getPath(), classloader, log);
        //                }
        //            }
        //        }
    }
}
