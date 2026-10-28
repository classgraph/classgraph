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
package nonapi.io.github.classgraph.classloaderhandler;

import java.io.File;
import java.util.List;

import nonapi.io.github.classgraph.classpath.ClassLoaderFinder;
import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;

/** Extract classpath entries from the Tomcat/Catalina WebappClassLoaderBase. */
class TomcatWebappClassLoaderBaseHandler implements ClassLoaderHandler {
    /** The path within a webapp's resources from which its classloader loads classes and resources. */
    private static final String CLASSLOADER_RESOURCES_PATH = "/WEB-INF/classes";

    /** The superclass of the {@code WebResourceSet}s that serve resources from a jarfile or a WAR file. */
    private static final String ARCHIVE_RESOURCE_SET = "org.apache.catalina.webresources.AbstractArchiveResourceSet";

    /** Constructor. */
    TomcatWebappClassLoaderBaseHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final LogNode log) {
        return ClassLoaderFinder.classIsOrExtendsOrImplements(classLoaderClass,
                "org.apache.catalina.loader.WebappClassLoaderBase");
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
        final Object delegateObject = reflectionUtils.getFieldVal(false, classLoader, "delegate");
        if (delegateObject instanceof Boolean) {
            return (Boolean) delegateObject;
        }
        // A subclass can declare a field of its own named "delegate" that hides Catalina's boolean field from the
        // lookup by name, so ask Catalina's public getter instead
        final Object getDelegateResult = reflectionUtils.invokeMethod(false, classLoader, "getDelegate");
        if (getDelegateResult instanceof Boolean) {
            return (Boolean) getDelegateResult;
        }
        // Assume parent-first delegation order
        return true;
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final LogNode log) {
        final boolean isParentFirst = isParentFirst(classLoader, classLoaderOrder.reflectionUtils);
        if (isParentFirst) {
            // Use parent-first delegation order
            classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        }
        if ("org.apache.tomee.catalina.TomEEWebappClassLoader".equals(classLoader.getClass().getName())) {
            // TomEEWebappClassLoader has a lot of complex delegation rules, including classname-specific
            // delegation, which is not supported by the current ClassGraph model, so we just try to approximate
            // the delegation order with a fixed order.
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
            final ScanSpec scanSpec, final LogNode log) {
        // type StandardRoot (implements WebResourceRoot)
        Object resources = classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getResources");
        if (resources == null) {
            // WebappClassLoaderBase#getResources() was deprecated in Tomcat 8.5 and 9.0, and removed in Tomcat
            // 10.1, so fall back to reading the "resources" field that it returned, which is still present.
            // Without this, none of the WebResourceSets below were found on Tomcat 10.1 or above. (#925)
            resources = classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "resources");
        }
        // type List<List<WebResourceSet>> members: preResources, mainResources, classResources, jarResources,
        // postResources, in the order that StandardRoot looks a resource up in
        @SuppressWarnings("unchecked")
        final List<List<?>> allResources = (List<List<?>>) classpathOrder.reflectionUtils.getFieldVal(false,
                resources, "allResources");
        if (allResources != null) {
            // type List<WebResourceSet>
            for (final List<?> webResourceSetList : allResources) {
                // type WebResourceSet
                for (final Object webResourceSet : webResourceSetList) {
                    if (webResourceSet != null) {
                        addWebResourceSet(webResourceSet, classLoader, classpathOrder, scanSpec, log);
                    }
                }
            }
        }
        // WebappClassLoaderBase extends URLClassLoader, so add the URLs it holds as a URLClassLoader too. This may
        // or may not duplicate the resources above -- duplicate classpath entries are dropped.
        final Object urls = classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getURLs");
        classpathOrder.addClasspathEntryObject(urls, classLoader, scanSpec, log);
    }

    /**
     * Add the classpath element contributed by a Catalina {@code WebResourceSet}, if mounted at or above
     * {@code "/WEB-INF/classes"} and not static-only.
     *
     * @param webResourceSet
     *            the {@code WebResourceSet}.
     * @param classLoader
     *            the classloader the {@code WebResourceSet} was found in.
     * @param classpathOrder
     *            the classpath order to add the classpath entry to.
     * @param scanSpec
     *            the {@link ScanSpec}.
     * @param log
     *            the log.
     */
    private static void addWebResourceSet(final Object webResourceSet, final ClassLoader classLoader,
            final ClasspathOrder classpathOrder, final ScanSpec scanSpec, final LogNode log) {
        final ReflectionUtils reflectionUtils = classpathOrder.reflectionUtils;
        // Every resource set that serves files extends AbstractResourceSet, which holds these. The path of its
        // directory or archive is a filesystem path, and Catalina normalizes an internal path or a mount of "/"
        // to "", so each of those is either "" or starts with "/"
        final String base = (String) reflectionUtils.invokeMethod(false, webResourceSet, "getBase");
        final String internalPath = (String) reflectionUtils.invokeMethod(false, webResourceSet, "getInternalPath");
        String webAppMount = (String) reflectionUtils.invokeMethod(false, webResourceSet, "getWebAppMount");
        if (base == null || internalPath == null || webAppMount == null
                || Boolean.TRUE.equals(reflectionUtils.invokeMethod(false, webResourceSet, "getStaticOnly"))) {
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
        final String classpathElementPath = internalPath
                + CLASSLOADER_RESOURCES_PATH.substring(webAppMount.length());
        if (ClassLoaderFinder.classIsOrExtendsOrImplements(webResourceSet.getClass(), ARCHIVE_RESOURCE_SET)) {
            // For JarWarResourceSet: the path of the jarfile within the WAR file
            final String archivePath = (String) reflectionUtils.getFieldVal(false, webResourceSet, "archivePath");
            final String archive = archivePath == null ? base
                    : base + "!" + (archivePath.startsWith("/") ? archivePath : "/" + archivePath);
            classpathOrder.addClasspathEntryObject(
                    classpathElementPath.isEmpty() ? archive : archive + "!" + classpathElementPath, classLoader,
                    scanSpec, log);
        } else {
            classpathOrder.addClasspathEntryObject(new File(base, classpathElementPath).getPath(), classLoader,
                    scanSpec, log);
        }
    }

    /**
     * Get the automatic package root prefixes for classpath elements obtained from this classloader.
     *
     * @return the package root prefixes.
     */
    @Override
    public String[] getPackageRootPrefixes() {
        return ClassLoaderHandlerRegistry.NO_PACKAGE_ROOT_PREFIXES;
    }
}
