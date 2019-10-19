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

import java.lang.reflect.Array;
import java.util.HashSet;
import java.util.Set;

import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.ReflectionUtils;

/**
 * Extract classpath entries from the Eclipse Equinox ClassLoader.
 */
class EquinoxClassLoaderHandler implements ClassLoaderHandler {
    /**
     * True if system bundles have been read. We assume there is only one system bundle on the classpath, so this is
     * static.
     */
    private static boolean alreadyReadSystemBundles;

    /** Field names. */
    private static final String[] FIELD_NAMES = { "cp", "nestedDirName" };

    /** Class cannot be constructed. */
    private EquinoxClassLoaderHandler() {
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
        return "org.eclipse.osgi.internal.loader.EquinoxClassLoader".equals(classLoaderClass.getName());
    }

    /**
     * Find the {@link ClassLoader} delegation order for a {@link ClassLoader}.
     *
     * @param classLoader
     *            the {@link ClassLoader} to find the order for.
     * @param classLoaderOrder
     *            a {@link ClassLoaderOrder} object to update.
     * @param log
     *            the log
     */
    public static void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final LogNode log) {
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        classLoaderOrder.add(classLoader, log);
    }

    /**
     * Add the bundle file.
     *
     * @param bundlefile
     *            the bundle file
     * @param path
     *            the path
     * @param classLoader
     *            the classloader
     * @param classpathOrderOut
     *            the classpath order
     * @param scanSpec
     *            the scan spec
     * @param log
     *            the log
     */
    private static void addBundleFile(final Object bundlefile, final Set<Object> path,
            final ClassLoader classLoader, final ClasspathOrder classpathOrderOut, final ScanSpec scanSpec,
            final LogNode log) {
        // Don't get stuck in infinite loop
        if (bundlefile != null && path.add(bundlefile)) {
            // type File
            final Object baseFile = ReflectionUtils.getFieldVal(bundlefile, "basefile", false);
            if (baseFile != null) {
                boolean foundClassPathElement = false;
                for (final String fieldName : FIELD_NAMES) {
                    final Object fieldVal = ReflectionUtils.getFieldVal(bundlefile, fieldName, false);
                    if (fieldVal != null) {
                        foundClassPathElement = true;
                        // We found the base file and a classpath element, e.g. "bin/"
                        Object base = baseFile;
                        String sep = "/";
                        if (bundlefile.getClass().getName()
                                .equals("org.eclipse.osgi.storage.bundlefile.NestedDirBundleFile")) {
                            // Handle nested ZipBundleFile with "!/" separator
                            final Object baseBundleFile = ReflectionUtils.getFieldVal(bundlefile, "baseBundleFile",
                                    false);
                            if (baseBundleFile != null && baseBundleFile.getClass().getName()
                                    .equals("org.eclipse.osgi.storage.bundlefile.ZipBundleFile")) {
                                base = baseBundleFile;
                                sep = "!/";
                            }
                        }
                        final String pathElement = base.toString() + sep + fieldVal.toString();
                        classpathOrderOut.addClasspathEntry(pathElement, classLoader, scanSpec, log);
                        break;
                    }
                }
                if (!foundClassPathElement) {
                    // No classpath element found, just use basefile
                    classpathOrderOut.addClasspathEntry(baseFile.toString(), classLoader, scanSpec, log);
                }

            }
            addBundleFile(ReflectionUtils.getFieldVal(bundlefile, "wrapped", false), path, classLoader,
                    classpathOrderOut, scanSpec, log);
            addBundleFile(ReflectionUtils.getFieldVal(bundlefile, "next", false), path, classLoader,
                    classpathOrderOut, scanSpec, log);
        }
    }

    /**
     * Adds the classpath entries.
     *
     * @param owner
     *            the owner
     * @param classLoader
     *            the class loader
     * @param classpathOrderOut
     *            the classpath order out
     * @param scanSpec
     *            the scan spec
     * @param log
     *            the log
     */
    private static void addClasspathEntries(final Object owner, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final ScanSpec scanSpec, final LogNode log) {
        // type ClasspathEntry[]
        final Object entries = ReflectionUtils.getFieldVal(owner, "entries", false);
        if (entries != null) {
            for (int i = 0, n = Array.getLength(entries); i < n; i++) {
                // type ClasspathEntry
                final Object entry = Array.get(entries, i);
                // type BundleFile
                final Object bundlefile = ReflectionUtils.getFieldVal(entry, "bundlefile", false);
                addBundleFile(bundlefile, new HashSet<>(), classLoader, classpathOrderOut, scanSpec, log);
            }
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
        // type ClasspathManager
        final Object manager = ReflectionUtils.getFieldVal(classLoader, "manager", false);
        addClasspathEntries(manager, classLoader, classpathOrder, scanSpec, log);

        // type FragmentClasspath[]
        final Object fragments = ReflectionUtils.getFieldVal(manager, "fragments", false);
        if (fragments != null) {
            for (int f = 0, fragLength = Array.getLength(fragments); f < fragLength; f++) {
                // type FragmentClasspath
                final Object fragment = Array.get(fragments, f);
                addClasspathEntries(fragment, classLoader, classpathOrder, scanSpec, log);
            }
        }
        // Only read system bundles once (all bundles should give the same results for this).
        if (!alreadyReadSystemBundles) {
            // type BundleLoader
            final Object delegate = ReflectionUtils.getFieldVal(classLoader, "delegate", false);
            // type EquinoxContainer
            final Object container = ReflectionUtils.getFieldVal(delegate, "container", false);
            // type Storage
            final Object storage = ReflectionUtils.getFieldVal(container, "storage", false);
            // type ModuleContainer
            final Object moduleContainer = ReflectionUtils.getFieldVal(storage, "moduleContainer", false);
            // type ModuleDatabase
            final Object moduleDatabase = ReflectionUtils.getFieldVal(moduleContainer, "moduleDatabase", false);
            // type HashMap<Integer, EquinoxModule>
            final Object modulesById = ReflectionUtils.getFieldVal(moduleDatabase, "modulesById", false);
            // type EquinoxSystemModule (module 0 is always the system module)
            final Object module0 = ReflectionUtils.invokeMethod(modulesById, "get", Object.class, 0L, false);
            // type Bundle
            final Object bundle = ReflectionUtils.invokeMethod(module0, "getBundle", false);
            // type BundleContext
            final Object bundleContext = ReflectionUtils.invokeMethod(bundle, "getBundleContext", false);
            // type Bundle[]
            final Object bundles = ReflectionUtils.invokeMethod(bundleContext, "getBundles", false);
            if (bundles != null) {
                for (int i = 0, n = Array.getLength(bundles); i < n; i++) {
                    // type EquinoxBundle
                    final Object equinoxBundle = Array.get(bundles, i);
                    // type EquinoxModule
                    final Object module = ReflectionUtils.getFieldVal(equinoxBundle, "module", false);
                    // type String
                    String location = (String) ReflectionUtils.getFieldVal(module, "location", false);
                    if (location != null) {
                        final int fileIdx = location.indexOf("file:");
                        if (fileIdx >= 0) {
                            location = location.substring(fileIdx);
                            classpathOrder.addClasspathEntry(location, classLoader, scanSpec, log);
                        }
                    }
                }
            }
            alreadyReadSystemBundles = true;
        }
    }
}
