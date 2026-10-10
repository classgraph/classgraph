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
     * The path within a webapp's resources that its classloader loads from: {@code StandardRoot} looks up every
     * classloader resource {@code "x"} as the webapp resource {@code "/WEB-INF/classes/x"}.
     */
    private static final String CLASSLOADER_RESOURCES_PATH = "/WEB-INF/classes";

    /** The TomEE classloader, which has its own delegation rules. */
    private static final String TOMEE_WEBAPP_CLASS_LOADER = "org.apache.tomee.catalina.TomEEWebappClassLoader";

    /** The superclass of the {@code WebResourceSet}s that serve resources from a jarfile or a WAR file. */
    private static final String ARCHIVE_RESOURCE_SET = "org.apache.catalina.webresources.AbstractArchiveResourceSet";

    /** Constructor. */
    TomcatWebappClassLoaderBaseHandler() {
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
        if (delegateObject instanceof final Boolean delegate) {
            return delegate;
        }
        // A subclass can declare a field of its own named "delegate" that hides Catalina's boolean field from the
        // lookup by name, so ask Catalina's public getter instead
        if (ReflectionUtils.invokeMethod(false, classLoader, "getDelegate") instanceof final Boolean delegate) {
            return delegate;
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
     * Add the part of a single Catalina {@code WebResourceSet} that the webapp's classloader loads from, if any.
     *
     * <p>
     * A resource set serves the files under a path within its directory or archive (its internal path) at a path
     * within the webapp's resources (its mount), and the classloader loads only what is served under
     * {@code "/WEB-INF/classes"}. So:
     * <ul>
     * <li>A resource set mounted at {@code "/WEB-INF/classes"}, as every jarfile in {@code WEB-INF/lib} is, is on
     * the classpath as a whole.
     * <li>A resource set mounted above {@code "/WEB-INF/classes"}, as the webapp's own directory or WAR file is, is
     * on the classpath only as far as its {@code WEB-INF/classes} dir. The rest of the webapp, e.g.
     * {@code index.jsp} or {@code WEB-INF/web.xml}, is not on the classpath.
     * <li>A resource set mounted anywhere else, e.g. static content mounted at {@code "/static"}, is not on the
     * classpath. Neither is a resource set marked as serving static content only, as the {@code META-INF/resources}
     * dir of each resource jar is.
     * </ul>
     *
     * @param webResourceSet
     *            the {@code WebResourceSet}, one of {@code DirResourceSet}, {@code FileResourceSet},
     *            {@code JarResourceSet}, {@code JarWarResourceSet}, {@code WarResourceSet} or
     *            {@code EmptyResourceSet}.
     * @param classLoader
     *            the classloader the {@code WebResourceSet} was found in.
     * @param classpathOrder
     *            the classpath order to add the classpath entry to.
     * @param log
     *            the log.
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
            // A resource set mounted below "/WEB-INF/classes" is loaded from too, but with the rest of its mount as a
            // prefix, e.g. "sub/x" for "x" mounted at "/WEB-INF/classes/sub", which a classpath element cannot
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
            classpathOrder.addClasspathEntryObject(
                    classpathElementPath.isEmpty() ? archive : archive + "!" + classpathElementPath, classLoader,
                    log);
        } else {
            classpathOrder.addClasspathEntryObject(new File(base, classpathElementPath).getPath(), classLoader,
                    log);
        }
    }

}
