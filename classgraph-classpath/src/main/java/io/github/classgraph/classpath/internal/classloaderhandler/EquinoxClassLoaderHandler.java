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
import java.util.HashSet;
import java.util.Set;

import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.classpath.internal.ClassLoaderOrder;
import io.github.classgraph.classpath.internal.ClasspathOrder;
import org.jspecify.annotations.Nullable;

/**
 * Extract classpath entries from the Eclipse Equinox ClassLoader.
 */
class EquinoxClassLoaderHandler implements ClassLoaderHandler {
    /** Field names. */
    private static final String[] FIELD_NAMES = { "cp", "nestedDirName" };

    /** Constructor. */
    EquinoxClassLoaderHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable LogNode log) {
        return classIsOrExtendsOrImplements(classLoaderClass,
                "org.eclipse.osgi.internal.loader.EquinoxClassLoader");
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable LogNode log) {
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        classLoaderOrder.add(classLoader, log);
    }

    /**
     * Add the bundle file.
     *
     * @param bundlefile
     *            the bundle file, or null (ignored)
     * @param path
     *            the path
     * @param classLoader
     *            the classloader
     * @param classpathOrderOut
     *            the classpath order
     * @param log
     *            the log node, or null to skip logging
     */
    private static void addBundleFile(final @Nullable Object bundlefile, final Set<Object> path,
            final ClassLoader classLoader, final ClasspathOrder classpathOrderOut, final @Nullable LogNode log) {
        // Don't get stuck in infinite loop
        if (bundlefile != null && path.add(bundlefile)) {
            // type File
            final var baseFile = ReflectionUtils.getFieldVal(false, bundlefile, "basefile");
            if (baseFile != null) {
                var foundClassPathElement = false;
                for (final String fieldName : FIELD_NAMES) {
                    final var fieldVal = ReflectionUtils.getFieldVal(false, bundlefile, fieldName);
                    if (fieldVal != null) {
                        foundClassPathElement = true;
                        // We found the base file and a classpath element, e.g. "bin/"
                        var base = baseFile;
                        var sep = "/";
                        if ("org.eclipse.osgi.storage.bundlefile.NestedDirBundleFile"
                                .equals(bundlefile.getClass().getName())) {
                            // Handle nested ZipBundleFile with "!/" separator
                            final var baseBundleFile = ReflectionUtils.getFieldVal(false, bundlefile,
                                    "baseBundleFile");
                            if (baseBundleFile != null && "org.eclipse.osgi.storage.bundlefile.ZipBundleFile"
                                    .equals(baseBundleFile.getClass().getName())) {
                                base = baseBundleFile;
                                sep = "!/";
                            }
                        }
                        final var pathElement = base + sep + fieldVal;
                        classpathOrderOut.addClasspathEntry(pathElement, classLoader, log);
                        break;
                    }
                }
                if (!foundClassPathElement) {
                    // No classpath element found, just use basefile
                    classpathOrderOut.addClasspathEntry(baseFile.toString(), classLoader, log);
                }

            }
            addBundleFile(ReflectionUtils.getFieldVal(false, bundlefile, "wrapped"), path, classLoader,
                    classpathOrderOut, log);
            addBundleFile(ReflectionUtils.getFieldVal(false, bundlefile, "next"), path, classLoader,
                    classpathOrderOut, log);
        }
    }

    /**
     * Adds the classpath entries.
     *
     * @param owner
     *            the owner, or null
     * @param classLoader
     *            the class loader
     * @param classpathOrderOut
     *            the classpath order out
     * @param log
     *            the log node, or null to skip logging
     */
    private static void addClasspathEntries(final @Nullable Object owner, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final @Nullable LogNode log) {
        // type ClasspathEntry[]
        final var entries = ReflectionUtils.getFieldVal(false, owner, "entries");
        if (entries != null) {
            for (int i = 0, n = Array.getLength(entries); i < n; i++) {
                // type ClasspathEntry
                final var entry = Array.get(entries, i);
                // type BundleFile
                final var bundlefile = ReflectionUtils.getFieldVal(false, entry, "bundlefile");
                addBundleFile(bundlefile, new HashSet<>(), classLoader, classpathOrderOut, log);
            }
        }
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final @Nullable LogNode log) {
        // type ClasspathManager
        final var manager = ReflectionUtils.getFieldVal(false, classLoader, "manager");
        addClasspathEntries(manager, classLoader, classpathOrder, log);

        // type FragmentClasspath[]
        final var fragments = ReflectionUtils.getFieldVal(false, manager, "fragments");
        if (fragments != null) {
            for (int f = 0, fragLength = Array.getLength(fragments); f < fragLength; f++) {
                // type FragmentClasspath
                final var fragment = Array.get(fragments, f);
                addClasspathEntries(fragment, classLoader, classpathOrder, log);
            }
        }
        // Only read system bundles once per scan (all bundles should give the same results for this).
        if (classpathOrder.tryAddEquinoxSystemBundles()) {
            // type BundleLoader
            final var delegate = ReflectionUtils.getFieldVal(false, classLoader, "delegate");
            // type EquinoxContainer
            final var container = ReflectionUtils.getFieldVal(false, delegate, "container");
            // type Storage
            final var storage = ReflectionUtils.getFieldVal(false, container, "storage");
            // type ModuleContainer
            final var moduleContainer = ReflectionUtils.getFieldVal(false, storage, "moduleContainer");
            // type ModuleDatabase
            final var moduleDatabase = ReflectionUtils.getFieldVal(false, moduleContainer, "moduleDatabase");
            // type HashMap<Integer, EquinoxModule>
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
