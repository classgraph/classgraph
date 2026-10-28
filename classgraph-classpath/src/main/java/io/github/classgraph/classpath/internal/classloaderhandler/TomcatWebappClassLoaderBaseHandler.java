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

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;

/** Extracts classpath entries from the Apache Tomcat/Catalina {@code WebappClassLoaderBase}. */
class TomcatWebappClassLoaderBaseHandler extends URLClassLoaderHandler {
    /** The webapp resource mount path from which the webapp classloader loads classes and resources. */
    private static final String CLASSLOADER_RESOURCES_PATH = "/WEB-INF/classes";

    /** The TomEE webapp classloader class name. */
    private static final String TOMEE_WEBAPP_CLASS_LOADER = "org.apache.tomee.catalina.TomEEWebappClassLoader";

    /** The superclass of {@code WebResourceSet} implementations backed by a JAR or WAR archive. */
    private static final String ARCHIVE_RESOURCE_SET = "org.apache.catalina.webresources.AbstractArchiveResourceSet";

    /** Constructor. */
    TomcatWebappClassLoaderBaseHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
        return classIsOrExtendsOrImplements(classLoaderClass, "org.apache.catalina.loader.WebappClassLoaderBase");
    }

    /**
     * Checks whether the classloader delegates to its parent before searching its own resources.
     *
     * @param classLoader
     *            the {@link ClassLoader}.
     * @return {@code true} if the classloader uses parent-first delegation.
     */
    private static boolean isParentFirst(final ClassLoader classLoader) {
        final var delegateObject = ReflectionUtils.getFieldVal(false, classLoader, "delegate");
        if (delegateObject instanceof final Boolean delegate) {
            return delegate;
        }
        // A subclass can declare a field of its own named "delegate" that hides Catalina's boolean field from the
        // lookup by name, so ask Catalina's public getter instead
        if (ReflectionUtils.invokeMethod(false, classLoader, "getDelegate") instanceof final Boolean delegate) {
            return delegate;
        }
        // Assume Tomcat's default, delegate == false: look in the webapp before delegating to the parent (apart
        // from the JDK and servlet API classes, which Catalina always delegates)
        return false;
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable ClassGraphLog log) {
        final var isParentFirst = isParentFirst(classLoader);
        if (isParentFirst) {
            // Use parent-first delegation order
            classLoaderOrder.delegateToParent(classLoader.getParent(), log);
        }
        if (classIsOrExtendsOrImplements(classLoader.getClass(), TOMEE_WEBAPP_CLASS_LOADER)) {
            // TomEEWebappClassLoader has a lot of complex delegation rules, including classname-specific
            // delegation, which is not supported by the current ClassGraph model, so we just try to approximate the
            // delegation order with a fixed order.
            // The container's classes are looked up in the classloader being handled, since they are not
            // necessarily visible to ClassGraph's own classloader
            final var openEJB = ReflectionUtils.classForNameOrNull("org.apache.openejb.OpenEJB", classLoader);
            if (openEJB != null) {
                classLoaderOrder.delegateToParent(openEJB.getClassLoader(), log);
            }
        }
        classLoaderOrder.add(classLoader, log);
        if (!isParentFirst) {
            // Use parent-last delegation order
            classLoaderOrder.delegateToParent(classLoader.getParent(), log);
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
        // type List<List<WebResourceSet>> members: preResources, mainResources, classResources, jarResources,
        // postResources, in the order that StandardRoot looks a resource up in
        @SuppressWarnings("unchecked")
        final var allResources = (List<List<?>>) ReflectionUtils.getFieldVal(false, resources, "allResources");
        if (allResources != null) {
            // type List<WebResourceSet>
            for (final List<?> webResourceSetList : allResources) {
                // type WebResourceSet
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
     * Adds the classpath entry corresponding to the portion of a Catalina {@code WebResourceSet} mounted at or
     * above {@code "/WEB-INF/classes"}, if any.
     *
     * @param webResourceSet
     *            the {@code WebResourceSet}.
     * @param classLoader
     *            the classloader.
     * @param classpathOrder
     *            the classpath order to populate.
     * @param log
     *            the log node, or {@code null} to skip logging.
     */
    private void addWebResourceSet(final Object webResourceSet, final ClassLoader classLoader,
            final ClasspathOrder classpathOrder, final @Nullable ClassGraphLog log) {
        // Every resource set that serves files extends AbstractResourceSet, which holds these. The path of its
        // directory or archive is a filesystem path, and Catalina normalizes an internal path or a mount of "/" to
        // "", so each of those is either "" or starts with "/"
        final var base = (String) ReflectionUtils.invokeMethod(false, webResourceSet, "getBase");
        final var internalPath = (String) ReflectionUtils.invokeMethod(false, webResourceSet, "getInternalPath");
        var webAppMount = (String) ReflectionUtils.invokeMethod(false, webResourceSet, "getWebAppMount");
        if (base == null || internalPath == null || webAppMount == null
                || Boolean.TRUE.equals(ReflectionUtils.invokeMethod(false, webResourceSet, "getStaticOnly"))) {
            // This resource set serves nothing from the filesystem (e.g. EmptyResourceSet), or nothing to the
            // classloader
            return;
        }
        if (webAppMount.endsWith("/")) {
            // Catalina matches a mount as a plain prefix of the path it looks up, so it serves the same files at
            // "/WEB-INF/" as at "/WEB-INF"
            webAppMount = webAppMount.substring(0, webAppMount.length() - 1);
        }
        if (!webAppMount.equals(CLASSLOADER_RESOURCES_PATH)
                && !CLASSLOADER_RESOURCES_PATH.startsWith(webAppMount + "/")) {
            // A resource set mounted below "/WEB-INF/classes" is loaded from too, but with the rest of its mount as
            // a prefix, e.g. "sub/x" for "x" mounted at "/WEB-INF/classes/sub", which a classpath element cannot
            // express
            if (log != null) {
                log.log("Skipping " + base + ", which the webapp mounts at " + webAppMount + ", not at or above "
                        + CLASSLOADER_RESOURCES_PATH);
            }
            return;
        }
        // The path within the directory or archive that the classloader loads from
        final var classpathElementPath = internalPath + CLASSLOADER_RESOURCES_PATH.substring(webAppMount.length());
        if (classIsOrExtendsOrImplements(webResourceSet.getClass(), ARCHIVE_RESOURCE_SET)) {
            // For JarWarResourceSet: the path of the jarfile within the WAR file
            final var archivePath = (String) ReflectionUtils.getFieldVal(false, webResourceSet, "archivePath");
            final var archive = archivePath == null ? base
                    : base + "!" + (archivePath.startsWith("/") ? archivePath : "/" + archivePath);
            classpathOrder.addClasspathEntry(
                    classpathElementPath.isEmpty() ? archive : archive + "!" + classpathElementPath, classLoader,
                    log);
        } else {
            classpathOrder.addClasspathEntry(new File(base, classpathElementPath).getPath(), classLoader, log);
        }
    }

}
