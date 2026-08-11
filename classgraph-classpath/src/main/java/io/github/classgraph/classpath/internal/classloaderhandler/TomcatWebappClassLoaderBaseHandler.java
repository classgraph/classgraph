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
package io.github.classgraph.classpath.internal.classloaderhandler;

import java.io.File;
import java.util.List;

import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.classpath.internal.ClassLoaderOrder;
import io.github.classgraph.classpath.internal.ClasspathOrder;
import io.github.classgraph.classpath.internal.spec.ClasspathSpec;
import org.jspecify.annotations.Nullable;

/** Extract classpath entries from the Tomcat/Catalina WebappClassLoaderBase. */
class TomcatWebappClassLoaderBaseHandler implements ClassLoaderHandler {
    /** Constructor. */
    TomcatWebappClassLoaderBaseHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable LogNode log) {
        return classIsOrExtendsOrImplements(classLoaderClass, "org.apache.catalina.loader.WebappClassLoaderBase");
    }

    /**
     * Return true if this classloader delegates to its parent.
     *
     * @param classLoader
     *            the {@link ClassLoader}.
     * @param reflectionUtils
     *            the reflection utils instance.
     * @return true if this classloader delegates to its parent.
     */
    private static boolean isParentFirst(final ClassLoader classLoader, final ReflectionUtils reflectionUtils) {
        final var delegateObject = reflectionUtils.getFieldVal(false, classLoader, "delegate");
        if (delegateObject != null) {
            return (boolean) delegateObject;
        }
        // Assume parent-first delegation order
        return true;
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable LogNode log) {
        final var isParentFirst = isParentFirst(classLoader, classLoaderOrder.reflectionUtils);
        if (isParentFirst) {
            // Use parent-first delegation order
            classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        }
        if ("org.apache.tomee.catalina.TomEEWebappClassLoader".equals(classLoader.getClass().getName())) {
            // TomEEWebappClassLoader has a lot of complex delegation rules, including classname-specific
            // delegation, which is not supported by the current ClassGraph model, so we just try to approximate the
            // delegation order with a fixed order.
            try {
                classLoaderOrder.delegateTo(Class.forName("org.apache.openejb.OpenEJB").getClassLoader(),
                        /* isParent = */ true, log);
            } catch (LinkageError | ClassNotFoundException e) {
                // Ignore
            }
        }
        classLoaderOrder.add(classLoader, log);
        if (!isParentFirst) {
            // Use parent-last delegation order
            classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        }
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final ClasspathSpec classpathSpec, final @Nullable LogNode log) {
        // type StandardRoot (implements WebResourceRoot)
        var resources = classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getResources");
        if (resources == null) {
            // WebappClassLoaderBase#getResources() was deprecated in Tomcat 8.5 and 9.0, and removed in Tomcat
            // 10.1, so fall back to reading the "resources" field that it returned, which is still present. Without
            // this, none of the WebResourceSets below were found on Tomcat 10.1 or above. (#925)
            resources = classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "resources");
        }
        // type List<URL>
        final var baseURLs = classpathOrder.reflectionUtils.invokeMethod(false, resources, "getBaseUrls");
        classpathOrder.addClasspathEntryObject(baseURLs, classLoader, classpathSpec, log);
        // type List<List<WebResourceSet>> members: preResources, mainResources, classResources, jarResources,
        // postResources
        @SuppressWarnings("unchecked")
        final var allResources = (List<List<?>>) classpathOrder.reflectionUtils.getFieldVal(false, resources,
                "allResources");
        if (allResources != null) {
            // type List<WebResourceSet>
            for (final List<?> webResourceSetList : allResources) {
                // type WebResourceSet
                // {DirResourceSet, FileResourceSet, JarResourceSet, JarWarResourceSet,
                // EmptyResourceSet}
                for (final Object webResourceSet : webResourceSetList) {
                    if (webResourceSet != null) {
                        addWebResourceSet(webResourceSet, classLoader, classpathOrder, classpathSpec, log);
                    }
                }
            }
        }
        // This may or may not duplicate the above
        final var urls = classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getURLs");
        classpathOrder.addClasspathEntryObject(urls, classLoader, classpathSpec, log);
    }

    /**
     * Add the classpath entry that a single Catalina {@code WebResourceSet} serves resources from.
     *
     * @param webResourceSet
     *            the {@code WebResourceSet}, one of {@code DirResourceSet}, {@code FileResourceSet},
     *            {@code JarResourceSet}, {@code JarWarResourceSet} or {@code EmptyResourceSet}.
     * @param classLoader
     *            the classloader the {@code WebResourceSet} was found in.
     * @param classpathOrder
     *            the classpath order to add the classpath entry to.
     * @param classpathSpec
     *            the scan spec.
     * @param log
     *            the log.
     */
    private static void addWebResourceSet(final Object webResourceSet, final ClassLoader classLoader,
            final ClasspathOrder classpathOrder, final ClasspathSpec classpathSpec, final @Nullable LogNode log) {
        // For DirResourceSet
        final var file = (File) classpathOrder.reflectionUtils.invokeMethod(false, webResourceSet, "getFileBase");
        var base = file == null ? null : file.getPath();
        if (base == null) {
            // For FileResourceSet
            base = (String) classpathOrder.reflectionUtils.invokeMethod(false, webResourceSet, "getBase");
        }
        if (base == null) {
            // For JarResourceSet and JarWarResourceSet, the absolute path to the WAR file on the file system in
            // which the JAR is located
            base = (String) classpathOrder.reflectionUtils.invokeMethod(false, webResourceSet, "getBaseUrlString");
        }
        if (base == null) {
            // This WebResourceSet serves nothing from the filesystem (e.g. EmptyResourceSet)
            return;
        }
        // For JarWarResourceSet: the path within the WAR file where the JAR file is located
        final var archivePath = (String) classpathOrder.reflectionUtils.getFieldVal(false, webResourceSet,
                "archivePath");
        if (archivePath != null && !archivePath.isEmpty()) {
            // If archivePath is non-null, this is a jar within a war
            base += "!" + (archivePath.startsWith("/") ? archivePath : "/" + archivePath);
        }
        final var className = webResourceSet.getClass().getName();
        // (These class names previously had a spurious "java." prefix, so isJar was always false, and the internal
        // path of a resource JAR was appended as a directory path rather than as a path within the JAR)
        final var isJar = "org.apache.catalina.webresources.JarResourceSet".equals(className)
                || "org.apache.catalina.webresources.JarWarResourceSet".equals(className);
        // The path within this WebResourceSet where resources will be served from, e.g. for a resource JAR, this
        // would be "META-INF/resources"
        final var internalPath = (String) classpathOrder.reflectionUtils.invokeMethod(false, webResourceSet,
                "getInternalPath");
        if (internalPath != null && !internalPath.isEmpty() && !"/".equals(internalPath)) {
            classpathOrder.addClasspathEntryObject(
                    base + (isJar ? "!" : "") + (internalPath.startsWith("/") ? internalPath : "/" + internalPath),
                    classLoader, classpathSpec, log);
        } else {
            classpathOrder.addClasspathEntryObject(base, classLoader, classpathSpec, log);
        }
    }

    /**
     * Get the automatic package root prefixes for classpath elements obtained from this classloader.
     *
     * <p>
     * Tomcat serves classes from "WEB-INF/classes/" within a webapp, and from a "classes/" dir within
     * $CATALINA_BASE, and does not always list these dirs as classpath elements.
     *
     * @return the package root prefixes.
     */
    @Override
    public String[] getPackageRootPrefixes() {
        return new String[] { "WEB-INF/classes/", "classes/" };
    }
}
