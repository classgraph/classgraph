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

import java.lang.reflect.Array;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;
import org.jspecify.annotations.Nullable;

/**
 * Extract classpath entries from the Eclipse Equinox ClassLoader.
 */
class EquinoxClassLoaderHandler implements OSGiClassLoaderHandler {
    /** Constructor. */
    EquinoxClassLoaderHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
        // EquinoxClassLoader is the classloader Equinox uses by default, but a framework extension can install a
        // ClassLoaderHook that supplies its own subclass of the abstract ModuleClassLoader instead
        return classIsOrExtendsOrImplements(classLoaderClass, "org.eclipse.osgi.internal.loader.ModuleClassLoader")
                || classIsOrExtendsOrImplements(classLoaderClass,
                        "org.eclipse.osgi.internal.loader.EquinoxClassLoader");
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
        // type ClasspathManager -- ModuleClassLoader declares getClasspathManager() as public abstract, so any
        // subclass has it, whereas the "manager" field is specific to Equinox's own EquinoxClassLoader
        var manager = ReflectionUtils.invokeMethod(false, classLoader, "getClasspathManager");
        if (manager == null) {
            manager = ReflectionUtils.getFieldVal(false, classLoader, "manager");
        }
        OSGiClassLoaderHandler.addClasspathManagerEntries(manager, classLoader, classpathOrder, log);

        // Only read system bundles once per scan (all bundles should give the same results for this).
        if (classpathOrder.claimOncePerScan("system-bundles")) {
            // type BundleLoader -- as with getClasspathManager() above, prefer the public accessor declared by
            // ModuleClassLoader over the "delegate" field of EquinoxClassLoader
            var delegate = ReflectionUtils.invokeMethod(false, classLoader, "getBundleLoader");
            if (delegate == null) {
                delegate = ReflectionUtils.getFieldVal(false, classLoader, "delegate");
            }
            // type EquinoxContainer
            final var container = ReflectionUtils.getFieldVal(false, delegate, "container");
            // type Storage
            final var storage = ReflectionUtils.getFieldVal(false, container, "storage");
            // type ModuleContainer
            final var moduleContainer = ReflectionUtils.getFieldVal(false, storage, "moduleContainer");
            // type ModuleDatabase
            final var moduleDatabase = ReflectionUtils.getFieldVal(false, moduleContainer, "moduleDatabase");
            // type HashMap<Long, EquinoxModule> -- an OSGi bundle id is a long, so the key below is 0L, not 0
            final var modulesById = ReflectionUtils.getFieldVal(false, moduleDatabase, "modulesById");
            // type EquinoxSystemModule (module 0 is always the system module)
            final var module0 = ReflectionUtils.invokeMethod(false, modulesById, "get", Object.class, 0L);
            // type Bundle
            final var bundle = ReflectionUtils.invokeMethod(false, module0, "getBundle");
            // type BundleContext
            final var bundleContext = ReflectionUtils.invokeMethod(false, bundle, "getBundleContext");
            // type Bundle[]
            final var bundles = ReflectionUtils.invokeMethod(false, bundleContext, "getBundles");
            if (bundles != null) {
                for (int i = 0, n = Array.getLength(bundles); i < n; i++) {
                    // type EquinoxBundle
                    final var equinoxBundle = Array.get(bundles, i);
                    // type EquinoxModule
                    final var module = ReflectionUtils.getFieldVal(false, equinoxBundle, "module");
                    // type String
                    var location = (String) ReflectionUtils.getFieldVal(false, module, "location");
                    if (location != null) {
                        final var fileIdx = location.indexOf("file:");
                        if (fileIdx >= 0) {
                            location = location.substring(fileIdx);
                            classpathOrder.addClasspathEntry(location, classLoader, log);
                        }
                    }
                }
            }
        }
    }

}
