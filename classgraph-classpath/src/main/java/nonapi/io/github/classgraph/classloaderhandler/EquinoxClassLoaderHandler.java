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
import nonapi.io.github.classgraph.classpathspec.ClassPathSpec;
import nonapi.io.github.classgraph.utils.LogNode;
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
     * @param classPathSpec
     *            the scan spec
     * @param log
     *            the log node, or null to skip logging
     */
    private static void addBundleFile(final @Nullable Object bundlefile, final Set<Object> path,
            final ClassLoader classLoader, final ClasspathOrder classpathOrderOut,
            final ClassPathSpec classPathSpec, final @Nullable LogNode log) {
        // Don't get stuck in infinite loop
        if (bundlefile != null && path.add(bundlefile)) {
            // type File
            final var baseFile = classpathOrderOut.reflectionUtils.getFieldVal(false, bundlefile, "basefile");
            if (baseFile != null) {
                var foundClassPathElement = false;
                for (final String fieldName : FIELD_NAMES) {
                    final var fieldVal = classpathOrderOut.reflectionUtils.getFieldVal(false, bundlefile,
                            fieldName);
                    if (fieldVal != null) {
                        foundClassPathElement = true;
                        // We found the base file and a classpath element, e.g. "bin/"
                        var base = baseFile;
                        var sep = "/";
                        if ("org.eclipse.osgi.storage.bundlefile.NestedDirBundleFile"
                                .equals(bundlefile.getClass().getName())) {
                            // Handle nested ZipBundleFile with "!/" separator
                            final var baseBundleFile = classpathOrderOut.reflectionUtils.getFieldVal(false,
                                    bundlefile, "baseBundleFile");
                            if (baseBundleFile != null && "org.eclipse.osgi.storage.bundlefile.ZipBundleFile"
                                    .equals(baseBundleFile.getClass().getName())) {
                                base = baseBundleFile;
                                sep = "!/";
                            }
                        }
                        final var pathElement = base + sep + fieldVal;
                        classpathOrderOut.addClasspathEntry(pathElement, classLoader, classPathSpec, log);
                        break;
                    }
                }
                if (!foundClassPathElement) {
                    // No classpath element found, just use basefile
                    classpathOrderOut.addClasspathEntry(baseFile.toString(), classLoader, classPathSpec, log);
                }

            }
            addBundleFile(classpathOrderOut.reflectionUtils.getFieldVal(false, bundlefile, "wrapped"), path,
                    classLoader, classpathOrderOut, classPathSpec, log);
            addBundleFile(classpathOrderOut.reflectionUtils.getFieldVal(false, bundlefile, "next"), path,
                    classLoader, classpathOrderOut, classPathSpec, log);
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
     * @param classPathSpec
     *            the scan spec
     * @param log
     *            the log node, or null to skip logging
     */
    private static void addClasspathEntries(final @Nullable Object owner, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final ClassPathSpec classPathSpec,
            final @Nullable LogNode log) {
        // type ClasspathEntry[]
        final var entries = classpathOrderOut.reflectionUtils.getFieldVal(false, owner, "entries");
        if (entries != null) {
            for (int i = 0, n = Array.getLength(entries); i < n; i++) {
                // type ClasspathEntry
                final var entry = Array.get(entries, i);
                // type BundleFile
                final var bundlefile = classpathOrderOut.reflectionUtils.getFieldVal(false, entry, "bundlefile");
                addBundleFile(bundlefile, new HashSet<>(), classLoader, classpathOrderOut, classPathSpec, log);
            }
        }
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final ClassPathSpec classPathSpec, final @Nullable LogNode log) {
        // type ClasspathManager
        final var manager = classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "manager");
        addClasspathEntries(manager, classLoader, classpathOrder, classPathSpec, log);

        // type FragmentClasspath[]
        final var fragments = classpathOrder.reflectionUtils.getFieldVal(false, manager, "fragments");
        if (fragments != null) {
            for (int f = 0, fragLength = Array.getLength(fragments); f < fragLength; f++) {
                // type FragmentClasspath
                final var fragment = Array.get(fragments, f);
                addClasspathEntries(fragment, classLoader, classpathOrder, classPathSpec, log);
            }
        }
        // Only read system bundles once per scan (all bundles should give the same results for this).
        if (classpathOrder.tryAddEquinoxSystemBundles()) {
            // type BundleLoader
            final var delegate = classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "delegate");
            // type EquinoxContainer
            final var container = classpathOrder.reflectionUtils.getFieldVal(false, delegate, "container");
            // type Storage
            final var storage = classpathOrder.reflectionUtils.getFieldVal(false, container, "storage");
            // type ModuleContainer
            final var moduleContainer = classpathOrder.reflectionUtils.getFieldVal(false, storage,
                    "moduleContainer");
            // type ModuleDatabase
            final var moduleDatabase = classpathOrder.reflectionUtils.getFieldVal(false, moduleContainer,
                    "moduleDatabase");
            // type HashMap<Integer, EquinoxModule>
            final var modulesById = classpathOrder.reflectionUtils.getFieldVal(false, moduleDatabase,
                    "modulesById");
            // type EquinoxSystemModule (module 0 is always the system module)
            final var module0 = classpathOrder.reflectionUtils.invokeMethod(false, modulesById, "get", Object.class,
                    0L);
            // type Bundle
            final var bundle = classpathOrder.reflectionUtils.invokeMethod(false, module0, "getBundle");
            // type BundleContext
            final var bundleContext = classpathOrder.reflectionUtils.invokeMethod(false, bundle,
                    "getBundleContext");
            // type Bundle[]
            final var bundles = classpathOrder.reflectionUtils.invokeMethod(false, bundleContext, "getBundles");
            if (bundles != null) {
                for (int i = 0, n = Array.getLength(bundles); i < n; i++) {
                    // type EquinoxBundle
                    final var equinoxBundle = Array.get(bundles, i);
                    // type EquinoxModule
                    final var module = classpathOrder.reflectionUtils.getFieldVal(false, equinoxBundle, "module");
                    // type String
                    var location = (String) classpathOrder.reflectionUtils.getFieldVal(false, module, "location");
                    if (location != null) {
                        final var fileIdx = location.indexOf("file:");
                        if (fileIdx >= 0) {
                            location = location.substring(fileIdx);
                            classpathOrder.addClasspathEntry(location, classLoader, classPathSpec, log);
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
