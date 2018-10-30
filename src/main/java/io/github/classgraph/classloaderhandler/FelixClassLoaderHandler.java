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
package io.github.classgraph.classloaderhandler;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.classgraph.ScanSpec;
import io.github.classgraph.utils.ClasspathOrder;
import io.github.classgraph.utils.LogNode;
import io.github.classgraph.utils.ReflectionUtils;

/**
 * Custom Class Loader Handler for OSGi Felix ClassLoader.
 *
 * <p>
 * The handler adds the bundle jar and all assocaited Bundle-Claspath jars into the classpath to be scanned.
 *
 * @author elrufaie
 */
public class FelixClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public String[] handledClassLoaders() {
        return new String[] { //
                "org.apache.felix.framework.BundleWiringImpl$BundleClassLoaderJava5",
                "org.apache.felix.framework.BundleWiringImpl$BundleClassLoader" };
    }

    @Override
    public ClassLoader getEmbeddedClassLoader(final ClassLoader outerClassLoaderInstance) {
        return null;
    }

    @Override
    public DelegationOrder getDelegationOrder(final ClassLoader classLoaderInstance) {
        return DelegationOrder.PARENT_FIRST;
    }

    final Set<Object> bundles = new HashSet<>();

    private String getContentLocation(final Object content) {
        final File file = (File) ReflectionUtils.invokeMethod(content, "getFile", false);
        return file != null ? file.toURI().toString() : null;
    }

    private void addBundle(final Object bundleWiring, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final LogNode log) {
        // Track the bundles we've processed to prevent loops
        bundles.add(bundleWiring);

        // Get the revision for this wiring
        final Object revision = ReflectionUtils.invokeMethod(bundleWiring, "getRevision", false);
        // Get the contents
        final Object content = ReflectionUtils.invokeMethod(revision, "getContent", false);
        final String location = content != null ? getContentLocation(content) : null;
        if (location != null) {
            // Add the bundle object
            classpathOrderOut.addClasspathElement(location, classLoader, log);

            // And any embedded content
            final List<?> embeddedContent = (List<?>) ReflectionUtils.invokeMethod(revision, "getContentPath",
                    false);
            if (embeddedContent != null) {
                for (final Object embedded : embeddedContent) {
                    if (embedded != content) {
                        final String embeddedLocation = embedded != null ? getContentLocation(embedded) : null;
                        if (embeddedLocation != null) {
                            classpathOrderOut.addClasspathElement(embeddedLocation, classLoader, log);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void handle(final ScanSpec scanSpec, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final LogNode log) {

        // Get the wiring for the ClassLoader's bundle
        final Object bundleWiring = ReflectionUtils.getFieldVal(classLoader, "m_wiring", false);
        addBundle(bundleWiring, classLoader, classpathOrderOut, log);

        // Deal with any other bundles we might be wired to. TODO: Use the ScanSpec to narrow down the list of wires
        // that we follow.

        final List<?> requiredWires = (List<?>) ReflectionUtils.invokeMethod(bundleWiring, "getRequiredWires",
                String.class, null, false);
        if (requiredWires != null) {
            for (final Object wire : requiredWires) {
                final Object provider = ReflectionUtils.invokeMethod(wire, "getProviderWiring", false);
                if (!bundles.contains(provider)) {
                    addBundle(provider, classLoader, classpathOrderOut, log);
                }
            }
        }
    }
}
