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
package nonapi.io.github.classgraph.classloaderhandler;

import java.lang.reflect.Array;
import java.util.HashSet;
import java.util.Set;

import nonapi.io.github.classgraph.classpath.ClassLoaderFinder;
import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;

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
    public boolean canHandle(final Class<?> classLoaderClass, final LogNode log) {
        return ClassLoaderFinder.classIsOrExtendsOrImplements(classLoaderClass,
                "org.eclipse.osgi.internal.loader.EquinoxClassLoader");
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
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
    static void addBundleFile(final Object bundlefile, final Set<Object> path, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final ScanSpec scanSpec, final LogNode log) {
        // Don't get stuck in infinite loop
        if (bundlefile != null && path.add(bundlefile)) {
            // type File
            Object baseFile = classpathOrderOut.reflectionUtils.getFieldVal(false, bundlefile, "basefile");
            if (baseFile == null) {
                baseFile = classpathOrderOut.reflectionUtils.invokeMethod(false, bundlefile, "getBaseFile");
            }
            if (baseFile != null) {
                boolean foundClassPathElement = false;
                for (final String fieldName : FIELD_NAMES) {
                    final Object fieldVal = classpathOrderOut.reflectionUtils.getFieldVal(false, bundlefile,
                            fieldName);
                    if (fieldVal != null) {
                        foundClassPathElement = true;
                        // We found the base file and a classpath element, e.g. "bin/"
                        Object base = baseFile;
                        String sep = "/";
                        if (isBundleFileClass(bundlefile, "NestedDirBundleFile")) {
                            // Handle nested ZipBundleFile with "!/" separator
                            final Object baseBundleFile = classpathOrderOut.reflectionUtils.getFieldVal(false,
                                    bundlefile, "baseBundleFile");
                            if (baseBundleFile != null && isBundleFileClass(baseBundleFile, "ZipBundleFile")) {
                                base = baseBundleFile;
                                sep = "!/";
                            }
                        }
                        final String pathElement = base + sep + fieldVal;
                        classpathOrderOut.addClasspathEntry(pathElement, classLoader, scanSpec, log);
                        break;
                    }
                }
                if (!foundClassPathElement) {
                    // No classpath element found, just use basefile
                    classpathOrderOut.addClasspathEntry(baseFile.toString(), classLoader, scanSpec, log);
                }

            }
            addBundleFile(classpathOrderOut.reflectionUtils.getFieldVal(false, bundlefile, "wrapped"), path,
                    classLoader, classpathOrderOut, scanSpec, log);
            addBundleFile(classpathOrderOut.reflectionUtils.getFieldVal(false, bundlefile, "next"), path,
                    classLoader, classpathOrderOut, scanSpec, log);
            // A framework extension can install a ClassLoaderHook that replaces a bundle file with a
            // BundleFileWrapper around it (Storage#wrapBundleFile). The wrapper copies only the "basefile" field of
            // the bundle file it wraps, not its "cp" or "nestedDirName", so without following the delegate held in
            // the wrapper's "bundleFile" field, the sub-path within the bundle would be lost.
            addBundleFile(classpathOrderOut.reflectionUtils.getFieldVal(false, bundlefile, "bundleFile"), path,
                    classLoader, classpathOrderOut, scanSpec, log);
        }
    }

    /**
     * Test whether a bundle file is of the named {@code BundleFile} class. The name is matched by suffix, since
     * these classes moved from {@code org.eclipse.osgi.baseadaptor.bundlefile} to
     * {@code org.eclipse.osgi.storage.bundlefile} in Equinox 3.9.
     *
     * @param bundlefile
     *            the bundle file
     * @param className
     *            the simple name of the {@code BundleFile} class
     * @return true if the bundle file is of that class
     */
    private static boolean isBundleFileClass(final Object bundlefile, final String className) {
        return bundlefile.getClass().getName().endsWith(".bundlefile." + className);
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
        final Object entries = classpathOrderOut.reflectionUtils.getFieldVal(false, owner, "entries");
        if (entries != null) {
            for (int i = 0, n = Array.getLength(entries); i < n; i++) {
                // type ClasspathEntry
                final Object entry = Array.get(entries, i);
                // type BundleFile
                Object bundlefile = classpathOrderOut.reflectionUtils.getFieldVal(false, entry, "bundlefile");
                if (bundlefile == null) {
                    bundlefile = classpathOrderOut.reflectionUtils.invokeMethod(false, entry, "getBundleFile");
                }
                addBundleFile(bundlefile, new HashSet<>(), classLoader, classpathOrderOut, scanSpec, log);
            }
        }
    }

    /**
     * Add the classpath entries of a {@code ClasspathManager}, and of each of the bundle's fragments.
     *
     * @param manager
     *            the classpath manager
     * @param classLoader
     *            the class loader
     * @param classpathOrderOut
     *            the classpath order out
     * @param scanSpec
     *            the scan spec
     * @param log
     *            the log
     */
    static void addClasspathManagerEntries(final Object manager, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final ScanSpec scanSpec, final LogNode log) {
        addClasspathEntries(manager, classLoader, classpathOrderOut, scanSpec, log);

        // type FragmentClasspath[]
        final Object fragments = classpathOrderOut.reflectionUtils.getFieldVal(false, manager, "fragments");
        if (fragments != null) {
            for (int f = 0, fragLength = Array.getLength(fragments); f < fragLength; f++) {
                // type FragmentClasspath
                final Object fragment = Array.get(fragments, f);
                addClasspathEntries(fragment, classLoader, classpathOrderOut, scanSpec, log);
            }
        }
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final ScanSpec scanSpec, final LogNode log) {
        // type ClasspathManager
        Object manager = classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getClasspathManager");
        if (manager == null) {
            manager = classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "manager");
        }
        addClasspathManagerEntries(manager, classLoader, classpathOrder, scanSpec, log);

        // Only read system bundles once per scan (all bundles should give the same results for this).
        if (classpathOrder.tryAddEquinoxSystemBundles()) {
            // type BundleLoader
            final Object delegate = classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "delegate");
            // type EquinoxContainer
            final Object container = classpathOrder.reflectionUtils.getFieldVal(false, delegate, "container");
            // type Storage
            final Object storage = classpathOrder.reflectionUtils.getFieldVal(false, container, "storage");
            // type ModuleContainer
            final Object moduleContainer = classpathOrder.reflectionUtils.getFieldVal(false, storage,
                    "moduleContainer");
            // type ModuleDatabase
            final Object moduleDatabase = classpathOrder.reflectionUtils.getFieldVal(false, moduleContainer,
                    "moduleDatabase");
            // type HashMap<Long, EquinoxModule> -- an OSGi bundle id is a long, so the key below is 0L, not 0
            final Object modulesById = classpathOrder.reflectionUtils.getFieldVal(false, moduleDatabase,
                    "modulesById");
            // type EquinoxSystemModule (module 0 is always the system module)
            final Object module0 = classpathOrder.reflectionUtils.invokeMethod(false, modulesById, "get",
                    Object.class, 0L);
            // type Bundle
            final Object bundle = classpathOrder.reflectionUtils.invokeMethod(false, module0, "getBundle");
            // type BundleContext
            final Object bundleContext = classpathOrder.reflectionUtils.invokeMethod(false, bundle,
                    "getBundleContext");
            // type Bundle[]
            final Object bundles = classpathOrder.reflectionUtils.invokeMethod(false, bundleContext, "getBundles");
            if (bundles != null) {
                for (int i = 0, n = Array.getLength(bundles); i < n; i++) {
                    // type EquinoxBundle
                    final Object equinoxBundle = Array.get(bundles, i);
                    // type EquinoxModule
                    final Object module = classpathOrder.reflectionUtils.getFieldVal(false, equinoxBundle,
                            "module");
                    // type String
                    String location = (String) classpathOrder.reflectionUtils.getFieldVal(false, module,
                            "location");
                    if (location != null) {
                        final int fileIdx = location.indexOf("file:");
                        if (fileIdx >= 0) {
                            location = location.substring(fileIdx);
                            classpathOrder.addClasspathEntry(location, classLoader, scanSpec, log);
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
