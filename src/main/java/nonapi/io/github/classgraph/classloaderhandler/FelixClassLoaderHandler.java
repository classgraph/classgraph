/*
 * This file is part of ClassGraph.
 *
 * Author: Harith Elrufaie
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2017 Harith Elrufaie
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.ReflectionUtils;

/**
 * Custom Class Loader Handler for OSGi Felix ClassLoader.
 *
 * <p>
 * The handler adds the bundle jar and all assocaited Bundle-Claspath jars into the classpath to be scanned.
 *
 * @author elrufaie
 */
class FelixClassLoaderHandler implements ClassLoaderHandler {
    /** Class cannot be constructed. */
    private FelixClassLoaderHandler() {
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
        return "org.apache.felix.framework.BundleWiringImpl$BundleClassLoaderJava5"
                .equals(classLoaderClass.getName())
                || "org.apache.felix.framework.BundleWiringImpl$BundleClassLoader"
                        .equals(classLoaderClass.getName());
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
     * Get the content location.
     *
     * @param content
     *            the content object
     * @return the content location
     */
    private static File getContentLocation(final Object content) {
        return (File) ReflectionUtils.invokeMethod(content, "getFile", false);
    }

    /**
     * Adds the bundle.
     *
     * @param bundleWiring
     *            the bundle wiring
     * @param classLoader
     *            the classloader
     * @param classpathOrderOut
     *            the classpath order out
     * @param bundles
     *            the bundles
     * @param scanSpec
     *            the scan spec
     * @param log
     *            the log
     */
    private static void addBundle(final Object bundleWiring, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final Set<Object> bundles, final ScanSpec scanSpec,
            final LogNode log) {
        // Track the bundles we've processed to prevent loops
        bundles.add(bundleWiring);

        // Get the revision for this wiring
        final Object revision = ReflectionUtils.invokeMethod(bundleWiring, "getRevision", false);
        // Get the contents
        final Object content = ReflectionUtils.invokeMethod(revision, "getContent", false);
        final File location = content != null ? getContentLocation(content) : null;
        if (location != null) {
            // Add the bundle object
            classpathOrderOut.addClasspathEntry(location, classLoader, scanSpec, log);

            // And any embedded content
            final List<?> embeddedContent = (List<?>) ReflectionUtils.invokeMethod(revision, "getContentPath",
                    false);
            if (embeddedContent != null) {
                for (final Object embedded : embeddedContent) {
                    if (embedded != content) {
                        final File embeddedLocation = embedded != null ? getContentLocation(embedded) : null;
                        if (embeddedLocation != null) {
                            classpathOrderOut.addClasspathEntry(embeddedLocation, classLoader, scanSpec, log);
                        }
                    }
                }
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
        // Get the wiring for the ClassLoader's bundle
        final Set<Object> bundles = new HashSet<>();
        final Object bundleWiring = ReflectionUtils.getFieldVal(classLoader, "m_wiring", false);
        addBundle(bundleWiring, classLoader, classpathOrder, bundles, scanSpec, log);

        // Deal with any other bundles we might be wired to. TODO: Use the ScanSpec to narrow down the list of wires
        // that we follow.

        final List<?> requiredWires = (List<?>) ReflectionUtils.invokeMethod(bundleWiring, "getRequiredWires",
                String.class, null, false);
        if (requiredWires != null) {
            for (final Object wire : requiredWires) {
                final Object provider = ReflectionUtils.invokeMethod(wire, "getProviderWiring", false);
                if (!bundles.contains(provider)) {
                    addBundle(provider, classLoader, classpathOrder, bundles, scanSpec, log);
                }
            }
        }
    }
}
