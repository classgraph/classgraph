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

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;

/**
 * Fallback ClassLoaderHandler. Tries to get classpath from a range of possible method and field names.
 */
class FallbackClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final LogNode log) {
        // This is the fallback handler, it handles anything
        return true;
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final LogNode log) {
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        classLoaderOrder.add(classLoader, log);
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final ScanSpec scanSpec, final LogNode log) {
        boolean valid = false;
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getClassPath"), classLoader,
                scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getClasspath"), classLoader,
                scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "classpath"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "classPath"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "cp"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "classpath"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "classPath"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "cp"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getPath"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getPaths"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "path"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "paths"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "paths"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getDir"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getDirs"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "dir"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "dirs"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "dir"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "dirs"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getFile"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getFiles"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "file"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "files"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "file"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "files"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getJar"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getJars"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "jar"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "jars"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "jar"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "jars"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getURL"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getURLs"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getUrl"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getUrls"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "url"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "urls"), classLoader, scanSpec,
                log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "url"), classLoader, scanSpec, log);
        valid |= classpathOrder.addClasspathEntryObject(
                classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "urls"), classLoader, scanSpec, log);
        if (!valid) {
            // None of the known field or method names worked, so fall back to asking the classloader for
            // resources that are present in the root of most classpath elements, and strip the resource path
            // from the returned URLs to get the classpath element itself (#892)
            valid = findClasspathOrderByProbingForResources(classLoader, classpathOrder, scanSpec, log);
        }
        if (log != null) {
            log.log("FallbackClassLoaderHandler " + (valid ? "found" : "did not find")
                    + " classpath entries in unknown ClassLoader " + classLoader);
        }
    }

    /**
     * Resources that are present in the root of many classpath elements, so that the classpath element can be
     * recovered from the URL of the resource. The empty path is the package root itself, which is only returned by
     * some classloaders, and only for classpath elements that are directories.
     */
    private static final String[] CLASSPATH_ELEMENT_ROOT_RESOURCE_PATHS = { "META-INF/MANIFEST.MF", "META-INF/",
            "module-info.class", "" };

    /**
     * Ask a {@link ClassLoader} for resources that are present in the root of most classpath elements, and strip
     * the resource path from each returned URL to recover the classpath element that contains it.
     *
     * <p>
     * {@link ClassLoader#getResources(String)} delegates to the parent classloader, so any resource that is also
     * visible to the parent is skipped -- the parent's classpath elements are found by handling the parent
     * classloader itself, and adding them here would ignore
     * {@link io.github.classgraph.ClassGraph#ignoreParentClassLoaders()}.
     *
     * @param classLoader
     *            the {@link ClassLoader} to probe.
     * @param classpathOrder
     *            a {@link ClasspathOrder} object to update.
     * @param scanSpec
     *            the {@link ScanSpec}.
     * @param log
     *            the log.
     * @return true if any classpath entries were found.
     */
    private static boolean findClasspathOrderByProbingForResources(final ClassLoader classLoader,
            final ClasspathOrder classpathOrder, final ScanSpec scanSpec, final LogNode log) {
        final LogNode probeLog = log == null ? null
                : log.log("Probing for classpath elements using " + classLoader.getClass().getName()
                        + "#getResources(String)");
        boolean valid = false;
        for (final String resourcePath : CLASSPATH_ELEMENT_ROOT_RESOURCE_PATHS) {
            final Set<String> parentResourceURLs = getResourceURLs(classLoader.getParent(), resourcePath);
            for (final String resourceURL : getResourceURLs(classLoader, resourcePath)) {
                if (parentResourceURLs.contains(resourceURL)) {
                    // This resource comes from a parent classloader's classpath element
                    continue;
                }
                final String classpathEntry = stripResourcePath(resourceURL, resourcePath);
                if (classpathEntry != null) {
                    valid |= classpathOrder.addClasspathEntry(classpathEntry, classLoader, scanSpec, probeLog);
                }
            }
        }
        return valid;
    }

    /**
     * Get the URLs of a resource in a {@link ClassLoader}, as strings.
     *
     * @param classLoader
     *            the {@link ClassLoader} to query, or null (in which case no URLs are returned -- the bootstrap
     *            classloader's classpath elements are handled by system jar and module scanning).
     * @param resourcePath
     *            the path of the resource.
     * @return the URLs of the resource, or the empty set if the resource could not be found.
     */
    private static Set<String> getResourceURLs(final ClassLoader classLoader, final String resourcePath) {
        if (classLoader == null) {
            return Collections.emptySet();
        }
        final Set<String> resourceURLs = new LinkedHashSet<>();
        try {
            for (final Enumeration<URL> e = classLoader.getResources(resourcePath); e.hasMoreElements();) {
                resourceURLs.add(e.nextElement().toString());
            }
        } catch (final IOException | RuntimeException | LinkageError e) {
            // Ignore -- the classloader could not enumerate this resource
        }
        return resourceURLs;
    }

    /**
     * Strip a resource path from the end of the URL of the resource, to recover the classpath element that contains
     * the resource.
     *
     * @param resourceURL
     *            the URL of the resource.
     * @param resourcePath
     *            the path of the resource within its classpath element.
     * @return the classpath element, or null if the resource path is not a suffix of the URL (which happens for
     *         URLs that do not name a resource within a classpath element, e.g. {@code "jrt:/java.base"}).
     */
    private static String stripResourcePath(final String resourceURL, final String resourcePath) {
        if (!resourceURL.endsWith(resourcePath)) {
            return null;
        }
        String classpathEntry = resourceURL.substring(0, resourceURL.length() - resourcePath.length());
        // Strip the jar entry separator, so that e.g. "jar:file:/x.jar!/" becomes "jar:file:/x.jar"
        // (FastPathResolver strips the "jar:" prefix, and handles the "!/" separators of nested jars)
        if (classpathEntry.endsWith("!/")) {
            classpathEntry = classpathEntry.substring(0, classpathEntry.length() - 2);
        }
        // Reject a URL that consisted of nothing but the resource path and separators
        return classpathEntry.isEmpty() || classpathEntry.equals("/") ? null : classpathEntry;
    }

    /**
     * Get the automatic package root prefixes for classpath elements obtained from this classloader.
     *
     * <p>
     * Nothing is known about an unrecognized classloader, so look for all the common package roots. Note that this
     * includes {@code "classes/"} and {@code "test-classes/"}, which are legal package names, so this is a
     * heuristic -- see {@link ClassLoaderHandlerRegistry#DEFAULT_PACKAGE_ROOT_PREFIXES}.
     *
     * @return the package root prefixes.
     */
    @Override
    public String[] getPackageRootPrefixes() {
        return ClassLoaderHandlerRegistry.DEFAULT_PACKAGE_ROOT_PREFIXES;
    }
}
