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

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClasspathOrder;
import org.jspecify.annotations.Nullable;

/** A {@link ClassLoaderHandler} for the classloader of an OSGi bundle. */
interface OSGiClassLoaderHandler extends ClassLoaderHandler {
    /** The fields of a {@code BundleFile} that hold a sub-path within the bundle's base file. */
    String[] BUNDLE_FILE_SUBPATH_FIELD_NAMES = { "cp", "nestedDirName" };

    /**
     * Return true if a {@code BundleFile} is an instance of the named Equinox {@code bundlefile} class. Equinox
     * moved these classes from {@code org.eclipse.osgi.baseadaptor.bundlefile} to
     * {@code org.eclipse.osgi.storage.bundlefile} in 3.9, so only the part of the name from the package that both
     * versions share is compared.
     *
     * @param bundlefile
     *            the {@code BundleFile}.
     * @param className
     *            the simple name of the {@code bundlefile} class.
     * @return true if the {@code BundleFile} is an instance of that class.
     */
    private static boolean isBundleFileClass(final Object bundlefile, final String className) {
        return bundlefile.getClass().getName().endsWith(".bundlefile." + className);
    }

    /**
     * Add the classpath entry of a {@code BundleFile}, and of every {@code BundleFile} it wraps or is chained to.
     *
     * @param bundlefile
     *            the {@code BundleFile}, or null (ignored)
     * @param path
     *            the {@code BundleFile}s already visited, so that a cycle cannot cause infinite recursion
     * @param classLoader
     *            the classloader
     * @param classpathOrderOut
     *            the classpath order
     * @param log
     *            the log node, or null to skip logging
     */
    static void addBundleFile(final @Nullable Object bundlefile, final Set<Object> path,
            final ClassLoader classLoader, final ClasspathOrder classpathOrderOut,
            final @Nullable ClassGraphLog log) {
        // Don't get stuck in infinite loop
        if (bundlefile != null && path.add(bundlefile)) {
            // type File
            var baseFile = ReflectionUtils.getFieldVal(false, bundlefile, "basefile");
            if (baseFile == null) {
                baseFile = ReflectionUtils.invokeMethod(false, bundlefile, "getBaseFile");
            }
            if (baseFile != null) {
                var foundClassPathElement = false;
                for (final String fieldName : BUNDLE_FILE_SUBPATH_FIELD_NAMES) {
                    final var fieldVal = ReflectionUtils.getFieldVal(false, bundlefile, fieldName);
                    if (fieldVal != null) {
                        foundClassPathElement = true;
                        // We found the base file and a classpath element, e.g. "bin/"
                        var base = baseFile;
                        var sep = "/";
                        if (isBundleFileClass(bundlefile, "NestedDirBundleFile")) {
                            // Handle nested ZipBundleFile with "!/" separator
                            final var baseBundleFile = ReflectionUtils.getFieldVal(false, bundlefile,
                                    "baseBundleFile");
                            if (baseBundleFile != null && isBundleFileClass(baseBundleFile, "ZipBundleFile")) {
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
            // A BundleFileWrapperChain holds the BundleFile it wraps in "wrapped", and the rest of the chain in
            // "next"
            addBundleFile(ReflectionUtils.getFieldVal(false, bundlefile, "wrapped"), path, classLoader,
                    classpathOrderOut, log);
            addBundleFile(ReflectionUtils.getFieldVal(false, bundlefile, "next"), path, classLoader,
                    classpathOrderOut, log);
            // A framework extension can install a ClassLoaderHook that replaces a bundle file with a
            // BundleFileWrapper around it (Storage#wrapBundleFile). The wrapper copies only the "basefile" field of
            // the bundle file it wraps, not its "cp" or "nestedDirName", so without following the delegate held in
            // the wrapper's "bundleFile" field, the sub-path within the bundle would be lost.
            addBundleFile(ReflectionUtils.getFieldVal(false, bundlefile, "bundleFile"), path, classLoader,
                    classpathOrderOut, log);
        }
    }

    /**
     * Add the classpath entries of the {@code ClasspathEntry[] entries} field of a {@code ClasspathManager} or a
     * {@code FragmentClasspath}.
     *
     * @param owner
     *            the {@code ClasspathManager} or {@code FragmentClasspath}, or null
     * @param classLoader
     *            the class loader
     * @param classpathOrderOut
     *            the classpath order out
     * @param log
     *            the log node, or null to skip logging
     */
    private static void addEntries(final @Nullable Object owner, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final @Nullable ClassGraphLog log) {
        // type ClasspathEntry[]
        final var entries = ReflectionUtils.getFieldVal(false, owner, "entries");
        if (entries != null) {
            for (int i = 0, n = Array.getLength(entries); i < n; i++) {
                // type ClasspathEntry
                final var entry = Array.get(entries, i);
                // type BundleFile
                var bundlefile = ReflectionUtils.getFieldVal(false, entry, "bundlefile");
                if (bundlefile == null) {
                    bundlefile = ReflectionUtils.invokeMethod(false, entry, "getBundleFile");
                }
                addBundleFile(bundlefile, new HashSet<>(), classLoader, classpathOrderOut, log);
            }
        }
    }

    /**
     * Add the classpath entries of a {@code ClasspathManager}, both its own and those of the fragments attached to
     * its bundle.
     *
     * @param manager
     *            the {@code ClasspathManager}, or null
     * @param classLoader
     *            the class loader
     * @param classpathOrderOut
     *            the classpath order out
     * @param log
     *            the log node, or null to skip logging
     */
    static void addClasspathManagerEntries(final @Nullable Object manager, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final @Nullable ClassGraphLog log) {
        addEntries(manager, classLoader, classpathOrderOut, log);

        // type FragmentClasspath[]
        final var fragments = ReflectionUtils.getFieldVal(false, manager, "fragments");
        if (fragments != null) {
            for (int f = 0, fragLength = Array.getLength(fragments); f < fragLength; f++) {
                // type FragmentClasspath
                final var fragment = Array.get(fragments, f);
                addEntries(fragment, classLoader, classpathOrderOut, log);
            }
        }
    }
}
