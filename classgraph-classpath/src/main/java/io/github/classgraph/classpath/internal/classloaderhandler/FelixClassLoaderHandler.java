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
package io.github.classgraph.classpath.internal.classloaderhandler;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;
import org.jspecify.annotations.Nullable;

/**
 * Custom Class Loader Handler for OSGi Felix ClassLoader.
 *
 * <p>
 * The handler adds the bundle jar and all associated Bundle-ClassPath jars into the classpath to be scanned.
 *
 * @author elrufaie
 */
class FelixClassLoaderHandler implements OSGiClassLoaderHandler {
    /** Constructor. */
    FelixClassLoaderHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
        return classIsOrExtendsOrImplements(classLoaderClass,
                "org.apache.felix.framework.BundleWiringImpl$BundleClassLoaderJava5")
                || classIsOrExtendsOrImplements(classLoaderClass,
                        "org.apache.felix.framework.BundleWiringImpl$BundleClassLoader");
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable ClassGraphLog log) {
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        classLoaderOrder.add(classLoader, log);
    }

    /**
     * Get the content location.
     *
     * @param content
     *            the content object
     * @return the content location, or null if it could not be determined
     */
    private static @Nullable File getContentLocation(final Object content) {
        return (File) ReflectionUtils.invokeMethod(false, content, "getFile");
    }

    /**
     * Adds the bundle.
     *
     * @param bundleWiring
     *            the bundle wiring, or null
     * @param classLoader
     *            the classloader
     * @param classpathOrderOut
     *            the classpath order out
     * @param bundles
     *            the bundles
     * @param log
     *            the log node, or null to skip logging
     */
    private static void addBundle(final @Nullable Object bundleWiring, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final Set<@Nullable Object> bundles,
            final @Nullable ClassGraphLog log) {
        // Track the bundles we've processed to prevent loops
        bundles.add(bundleWiring);

        // Get the revision for this wiring
        final var revision = ReflectionUtils.invokeMethod(false, bundleWiring, "getRevision");
        // Get the contents
        final var content = ReflectionUtils.invokeMethod(false, revision, "getContent");
        final var location = content != null ? getContentLocation(content) : null;
        if (location != null) {
            // Add the bundle object
            classpathOrderOut.addClasspathEntry(location, classLoader, log);

            // And any embedded content
            final List<?> embeddedContent = (List<?>) ReflectionUtils.invokeMethod(false, revision,
                    "getContentPath");
            if (embeddedContent != null) {
                for (final Object embedded : embeddedContent) {
                    if (embedded != content) {
                        final var embeddedLocation = embedded != null ? getContentLocation(embedded) : null;
                        if (embeddedLocation != null) {
                            classpathOrderOut.addClasspathEntry(embeddedLocation, classLoader, log);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final @Nullable ClassGraphLog log) {
        // Get the wiring for the ClassLoader's bundle
        final Set<@Nullable Object> bundles = new HashSet<>();
        final var bundleWiring = ReflectionUtils.getFieldVal(false, classLoader, "m_wiring");
        addBundle(bundleWiring, classLoader, classpathOrder, bundles, log);

        // Deal with any other bundles we might be wired to. Every wire has to be followed: a wire says nothing
        // about what it provides until its bundle revision has been resolved to a content location, and by then
        // addBundle has done all the work that skipping the wire would have saved. Bundles the user does not want
        // are dropped by ClasspathOrder#addClasspathEntry, which applies the scan spec's classpath element filters.
        final List<?> requiredWires = (List<?>) ReflectionUtils.invokeMethod(false, bundleWiring,
                "getRequiredWires", String.class, null);
        if (requiredWires != null) {
            for (final Object wire : requiredWires) {
                final var provider = ReflectionUtils.invokeMethod(false, wire, "getProviderWiring");
                if (!bundles.contains(provider)) {
                    addBundle(provider, classLoader, classpathOrder, bundles, log);
                }
            }
        }
    }

}
