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

import nonapi.io.github.classgraph.classpath.ClassLoaderFinder;
import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;
import org.jspecify.annotations.Nullable;

/**
 * Custom Class Loader Handler for OSGi Felix ClassLoader.
 *
 * <p>
 * The handler adds the bundle jar and all associated Bundle-ClassPath jars into
 * the classpath to be scanned.
 *
 * @author elrufaie
 */
class FelixClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable LogNode log) {
        return ClassLoaderFinder.classIsOrExtendsOrImplements(classLoaderClass,
                "org.apache.felix.framework.BundleWiringImpl$BundleClassLoaderJava5")
                || ClassLoaderFinder.classIsOrExtendsOrImplements(classLoaderClass,
                        "org.apache.felix.framework.BundleWiringImpl$BundleClassLoader");
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable LogNode log) {
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        classLoaderOrder.add(classLoader, log);
    }

    /**
     * Get the content location.
     *
     * @param content         the content object
     * @param reflectionUtils the reflection utils instance
     * @return the content location, or null if it could not be determined
     */
    private static @Nullable File getContentLocation(final Object content, final ReflectionUtils reflectionUtils) {
        return (File) reflectionUtils.invokeMethod(false, content, "getFile");
    }

    /**
     * Adds the bundle.
     *
     * @param bundleWiring      the bundle wiring, or null
     * @param classLoader       the classloader
     * @param classpathOrderOut the classpath order out
     * @param bundles           the bundles
     * @param scanSpec          the scan spec
     * @param log               the log
     */
    private static void addBundle(final @Nullable Object bundleWiring, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final Set<@Nullable Object> bundles, final ScanSpec scanSpec,
            final @Nullable LogNode log) {
        // Track the bundles we've processed to prevent loops
        bundles.add(bundleWiring);

        // Get the revision for this wiring
        final var revision = classpathOrderOut.reflectionUtils.invokeMethod(false, bundleWiring, "getRevision");
        // Get the contents
        final var content = classpathOrderOut.reflectionUtils.invokeMethod(false, revision, "getContent");
        final var location = content != null ? getContentLocation(content, classpathOrderOut.reflectionUtils) : null;
        if (location != null) {
            // Add the bundle object
            classpathOrderOut.addClasspathEntry(location, classLoader, scanSpec, log);

            // And any embedded content
            final List<?> embeddedContent = (List<?>) classpathOrderOut.reflectionUtils.invokeMethod(false, revision,
                    "getContentPath");
            if (embeddedContent != null) {
                for (final Object embedded : embeddedContent) {
                    if (embedded != content) {
                        final var embeddedLocation = embedded != null
                                ? getContentLocation(embedded, classpathOrderOut.reflectionUtils)
                                : null;
                        if (embeddedLocation != null) {
                            classpathOrderOut.addClasspathEntry(embeddedLocation, classLoader, scanSpec, log);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final ScanSpec scanSpec, final @Nullable LogNode log) {
        // Get the wiring for the ClassLoader's bundle
        final Set<@Nullable Object> bundles = new HashSet<>();
        final var bundleWiring = classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "m_wiring");
        addBundle(bundleWiring, classLoader, classpathOrder, bundles, scanSpec, log);

        // Deal with any other bundles we might be wired to. TODO: Use the ScanSpec to
        // narrow down the list of wires
        // that we follow.

        final List<?> requiredWires = (List<?>) classpathOrder.reflectionUtils.invokeMethod(false, bundleWiring,
                "getRequiredWires", String.class, null);
        if (requiredWires != null) {
            for (final Object wire : requiredWires) {
                final var provider = classpathOrder.reflectionUtils.invokeMethod(false, wire, "getProviderWiring");
                if (!bundles.contains(provider)) {
                    addBundle(provider, classLoader, classpathOrder, bundles, scanSpec, log);
                }
            }
        }
    }

    /**
     * Get the automatic package root prefixes for classpath elements obtained from
     * this classloader.
     *
     * <p>
     * Classpath elements from this classloader may be Spring-Boot executable jars
     * or wars.
     *
     * @return the package root prefixes.
     */
    @Override
    public String[] getPackageRootPrefixes() {
        return ClassLoaderHandlerRegistry.DEFAULT_PACKAGE_ROOT_PREFIXES;
    }
}
