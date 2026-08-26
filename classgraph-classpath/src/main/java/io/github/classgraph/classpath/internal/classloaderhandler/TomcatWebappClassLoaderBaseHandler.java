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

import java.io.File;
import java.util.List;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;
import org.jspecify.annotations.Nullable;

/** Extract classpath entries from the Tomcat/Catalina WebappClassLoaderBase. */
class TomcatWebappClassLoaderBaseHandler extends URLClassLoaderHandler {
    /**
     * The dir a webapp's own classes are served from. Catalina mounts it at a fixed location within the webapp's
     * main resource set, which is the webapp's directory or war file: {@code StandardRoot#getClassLoaderResource}
     * looks up every class under {@code "/WEB-INF/classes"}, so the webapp's directory or war is on the classpath
     * with that dir as an automatic package root.
     */
    private static final List<String> WEBAPP_PACKAGE_ROOT_PREFIXES = List.of("WEB-INF/classes/");

    /**
     * The dir a webapp's own jarfiles are served from. {@code StandardRoot#processWebInfLib} lists
     * {@code "/WEB-INF/lib"} at startup and mounts every {@code .jar} it finds there, so those jarfiles are on the
     * classpath even though the webapp never lists them.
     */
    private static final List<String> WEBAPP_LIB_DIR_PREFIXES = List.of("WEB-INF/lib/");

    /** The TomEE classloader, which has its own delegation rules. */
    private static final String TOMEE_WEBAPP_CLASS_LOADER = "org.apache.tomee.catalina.TomEEWebappClassLoader";

    /** The {@code WebResourceSet} classes that serve resources from within a jarfile. */
    private static final String[] JAR_RESOURCE_SETS = { "org.apache.catalina.webresources.JarResourceSet",
            "org.apache.catalina.webresources.JarWarResourceSet" };

    /** Constructor. */
    TomcatWebappClassLoaderBaseHandler() {
    }

    @Override
    public List<String> getPackageRootPrefixes() {
        return WEBAPP_PACKAGE_ROOT_PREFIXES;
    }

    @Override
    public List<String> getLibDirPrefixes() {
        return WEBAPP_LIB_DIR_PREFIXES;
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
        return classIsOrExtendsOrImplements(classLoaderClass, "org.apache.catalina.loader.WebappClassLoaderBase");
    }

    /**
     * Return true if this classloader delegates to its parent.
     *
     * @param classLoader
     *            the {@link ClassLoader}.
     * @return true if this classloader delegates to its parent.
     */
    private static boolean isParentFirst(final ClassLoader classLoader) {
        final var delegateObject = ReflectionUtils.getFieldVal(false, classLoader, "delegate");
        if (delegateObject != null) {
            return (boolean) delegateObject;
        }
        // Assume parent-first delegation order
        return true;
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable ClassGraphLog log) {
        final var isParentFirst = isParentFirst(classLoader);
        if (isParentFirst) {
            // Use parent-first delegation order
            classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        }
        if (classIsOrExtendsOrImplements(classLoader.getClass(), TOMEE_WEBAPP_CLASS_LOADER)) {
            // TomEEWebappClassLoader has a lot of complex delegation rules, including classname-specific
            // delegation, which is not supported by the current ClassGraph model, so we just try to approximate the
            // delegation order with a fixed order.
            // The container's classes are looked up in the classloader being handled, since they are not
            // necessarily visible to ClassGraph's own classloader
            final var openEJB = ReflectionUtils.classForNameOrNull("org.apache.openejb.OpenEJB", classLoader);
            if (openEJB != null) {
                classLoaderOrder.delegateTo(openEJB.getClassLoader(), /* isParent = */ true, log);
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
            final @Nullable ClassGraphLog log) {
        // type StandardRoot (implements WebResourceRoot)
        var resources = ReflectionUtils.invokeMethod(false, classLoader, "getResources");
        if (resources == null) {
            // WebappClassLoaderBase#getResources() was deprecated in Tomcat 8.5 and 9.0, and removed in Tomcat
            // 10.1, so fall back to reading the "resources" field that it returned, which is still present. Without
            // this, none of the WebResourceSets below were found on Tomcat 10.1 or above. (#925)
            resources = ReflectionUtils.getFieldVal(false, classLoader, "resources");
        }
        // type List<URL>
        final var baseURLs = ReflectionUtils.invokeMethod(false, resources, "getBaseUrls");
        classpathOrder.addClasspathEntryObject(baseURLs, classLoader, log);
        // type List<List<WebResourceSet>> members: preResources, mainResources, classResources, jarResources,
        // postResources
        @SuppressWarnings("unchecked")
        final var allResources = (List<List<?>>) ReflectionUtils.getFieldVal(false, resources, "allResources");
        if (allResources != null) {
            // type List<WebResourceSet>
            for (final List<?> webResourceSetList : allResources) {
                // type WebResourceSet
                // {DirResourceSet, FileResourceSet, JarResourceSet, JarWarResourceSet,
                // EmptyResourceSet}
                for (final Object webResourceSet : webResourceSetList) {
                    if (webResourceSet != null) {
                        addWebResourceSet(webResourceSet, classLoader, classpathOrder, log);
                    }
                }
            }
        }
        // WebappClassLoaderBase extends URLClassLoader, so add the URLs it holds as a URLClassLoader too. This
        // may or may not duplicate the resources above -- duplicate classpath entries are dropped.
        super.findClasspathOrder(classLoader, classpathOrder, log);
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
     * @param log
     *            the log.
     */
    private void addWebResourceSet(final Object webResourceSet, final ClassLoader classLoader,
            final ClasspathOrder classpathOrder, final @Nullable ClassGraphLog log) {
        // For DirResourceSet
        final var file = (File) ReflectionUtils.invokeMethod(false, webResourceSet, "getFileBase");
        var base = file == null ? null : file.getPath();
        if (base == null) {
            // For FileResourceSet
            base = (String) ReflectionUtils.invokeMethod(false, webResourceSet, "getBase");
        }
        if (base == null) {
            // For JarResourceSet and JarWarResourceSet, the absolute path to the WAR file on the file system in
            // which the JAR is located
            base = (String) ReflectionUtils.invokeMethod(false, webResourceSet, "getBaseUrlString");
        }
        if (base == null) {
            // This WebResourceSet serves nothing from the filesystem (e.g. EmptyResourceSet)
            return;
        }
        // For JarWarResourceSet: the path within the WAR file where the JAR file is located
        final var archivePath = (String) ReflectionUtils.getFieldVal(false, webResourceSet, "archivePath");
        if (archivePath != null && !archivePath.isEmpty()) {
            // If archivePath is non-null, this is a jar within a war
            base += "!" + (archivePath.startsWith("/") ? archivePath : "/" + archivePath);
        }
        // (These class names previously had a spurious "java." prefix, so isJar was always false, and the internal
        // path of a resource JAR was appended as a directory path rather than as a path within the JAR)
        final var isJar = findMatchingClassName(webResourceSet.getClass(), JAR_RESOURCE_SETS) != null;
        // The path within this WebResourceSet where resources will be served from, e.g. for a resource JAR, this
        // would be "META-INF/resources"
        final var internalPath = (String) ReflectionUtils.invokeMethod(false, webResourceSet, "getInternalPath");
        if (internalPath != null && !internalPath.isEmpty() && !"/".equals(internalPath)) {
            classpathOrder.addClasspathEntryObject(
                    base + (isJar ? "!" : "") + (internalPath.startsWith("/") ? internalPath : "/" + internalPath),
                    classLoader, log);
        } else {
            classpathOrder.addClasspathEntryObject(base, classLoader, log);
        }
    }

}
