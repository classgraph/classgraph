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

import java.io.File;
import java.util.List;

import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.ReflectionUtils;

/** Extract classpath entries from the Tomcat/Catalina WebappClassLoaderBase. */
class TomcatWebappClassLoaderBaseHandler implements ClassLoaderHandler {
    /** Class cannot be constructed. */
    private TomcatWebappClassLoaderBaseHandler() {
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
        return "org.apache.catalina.loader.WebappClassLoaderBase".equals(classLoaderClass.getName());
    }

    /**
     * Return true if this classloader delegates to its parent.
     * 
     * @param classLoader
     *            the {@link ClassLoader}.
     * @return true if this classloader delegates to its parent.
     */
    private static boolean isParentFirst(final ClassLoader classLoader) {
        final Object delegateObject = ReflectionUtils.getFieldVal(classLoader, "delegate", false);
        if (delegateObject != null) {
            return (boolean) delegateObject;
        }
        // Assume parent-first delegation order
        return true;
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
        final boolean isParentFirst = isParentFirst(classLoader);
        if (isParentFirst) {
            // Use parent-first delegation order
            classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        }
        classLoaderOrder.add(classLoader, log);
        if (!isParentFirst) {
            // Use parent-last delegation order
            classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
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
        // type StandardRoot (implements WebResourceRoot)
        final Object resources = ReflectionUtils.invokeMethod(classLoader, "getResources", false);
        // type List<URL>
        final Object baseURLs = ReflectionUtils.invokeMethod(resources, "getBaseUrls", false);
        classpathOrder.addClasspathEntryObject(baseURLs, classLoader, scanSpec, log);
        // type List<List<WebResourceSet>>
        // members: preResources, mainResources, classResources, jarResources, postResources
        @SuppressWarnings("unchecked")
        final List<List<?>> allResources = (List<List<?>>) ReflectionUtils.getFieldVal(resources, "allResources",
                false);
        if (allResources != null) {
            // type List<WebResourceSet>
            for (final List<?> webResourceSetList : allResources) {
                // type WebResourceSet
                // {DirResourceSet, FileResourceSet, JarResourceSet, JarWarResourceSet, EmptyResourceSet}
                for (final Object webResourceSet : webResourceSetList) {
                    if (webResourceSet != null) {
                        // For DirResourceSet
                        final File file = (File) ReflectionUtils.invokeMethod(webResourceSet, "getFileBase", false);
                        String base = file == null ? null : file.getPath();
                        if (base == null) {
                            // For FileResourceSet
                            base = (String) ReflectionUtils.invokeMethod(webResourceSet, "getBase", false);
                        }
                        if (base == null) {
                            // For JarResourceSet and JarWarResourceSet
                            // The absolute path to the WAR file on the file system in which the JAR is located
                            base = (String) ReflectionUtils.invokeMethod(webResourceSet, "getBaseUrlString", false);
                        }
                        if (base != null) {
                            // For JarWarResourceSet: the path within the WAR file where the JAR file is located
                            final String archivePath = (String) ReflectionUtils.getFieldVal(webResourceSet,
                                    "archivePath", false);
                            if (archivePath != null && !archivePath.isEmpty()) {
                                // If archivePath is non-null, this is a jar within a war
                                base += "!" + (archivePath.startsWith("/") ? archivePath : "/" + archivePath);
                            }
                            final String className = webResourceSet.getClass().getName();
                            final boolean isJar = className
                                    .equals("java.org.apache.catalina.webresources.JarResourceSet")
                                    || className.equals("java.org.apache.catalina.webresources.JarWarResourceSet");
                            // The path within this WebResourceSet where resources will be served from,
                            // e.g. for a resource JAR, this would be "META-INF/resources"
                            final String internalPath = (String) ReflectionUtils.invokeMethod(webResourceSet,
                                    "getInternalPath", false);
                            if (internalPath != null && !internalPath.isEmpty() && !internalPath.equals("/")) {
                                classpathOrder.addClasspathEntryObject(base + (isJar ? "!" : "")
                                        + (internalPath.startsWith("/") ? internalPath : "/" + internalPath),
                                        classLoader, scanSpec, log);
                            } else {
                                classpathOrder.addClasspathEntryObject(base, classLoader, scanSpec, log);
                            }
                        }
                    }
                }
            }
        }
        // This may or may not duplicate the above
        final Object urls = ReflectionUtils.invokeMethod(classLoader, "getURLs", false);
        classpathOrder.addClasspathEntryObject(urls, classLoader, scanSpec, log);
    }
}
