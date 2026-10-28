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

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;

/**
 * Fallback {@link ClassLoaderHandler} for classloaders not matched by any other handler. Probes common classpath
 * method and field names, {@code URLClassPath} fields, and root resources via {@link ClassLoader#getResources}.
 */
class FallbackClassLoaderHandler implements ClassLoaderHandler {
    /** Constructor. */
    FallbackClassLoaderHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
        // This is the fallback handler, it handles anything
        return true;
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable ClassGraphLog log) {
        classLoaderOrder.delegateToParent(classLoader.getParent(), log);
        classLoaderOrder.add(classLoader, log);
    }

    /**
     * A method or field of a {@link ClassLoader} that may hold classpath entries.
     *
     * @param isMethod
     *            {@code true} for a method, {@code false} for a field.
     * @param name
     *            the name of the method or field.
     */
    private record ClasspathSource(boolean isMethod, String name) {
    }

    /**
     * Creates a {@link ClasspathSource} for a method name.
     *
     * @param name
     *            the method name.
     * @return the {@link ClasspathSource}.
     */
    private static ClasspathSource method(final String name) {
        return new ClasspathSource(/* isMethod = */ true, name);
    }

    /**
     * Creates a {@link ClasspathSource} for a field name.
     *
     * @param name
     *            the field name.
     * @return the {@link ClasspathSource}.
     */
    private static ClasspathSource field(final String name) {
        return new ClasspathSource(/* isMethod = */ false, name);
    }

    /** Methods and fields probed for classpath entries, in probe order. */
    private static final List<ClasspathSource> CLASSPATH_SOURCES = List.of(
            // Classpaths
            method("getClassPath"), method("getClasspath"), method("classpath"), method("classPath"), method("cp"),
            field("classpath"), field("classPath"), field("cp"),
            // Paths
            method("getPath"), method("getPaths"), method("path"), method("paths"), field("paths"),
            // Directories
            method("getDir"), method("getDirs"), method("dir"), method("dirs"), field("dir"), field("dirs"),
            // Files
            method("getFile"), method("getFiles"), method("file"), method("files"), field("file"), field("files"),
            // Jars
            method("getJar"), method("getJars"), method("jar"), method("jars"), field("jar"), field("jars"),
            // URLs
            method("getURL"), method("getURLs"), method("getUrl"), method("getUrls"), method("url"), method("urls"),
            field("url"), field("urls"));

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final @Nullable ClassGraphLog log) {
        var valid = false;
        for (final ClasspathSource classpathSource : CLASSPATH_SOURCES) {
            final var classpathEntryObj = classpathSource.isMethod()
                    ? ReflectionUtils.invokeMethod(false, classLoader, classpathSource.name())
                    : ReflectionUtils.getFieldVal(false, classLoader, classpathSource.name());
            valid |= classpathOrder.addClasspathEntryObject(classpathEntryObj, classLoader, log);
        }
        // An unknown classloader may hold a jdk.internal.loader.URLClassPath, which none of the names above find,
        // since it is not itself a classpath entry -- its own fields have to be read to get at the entries
        final var ucp = URLClassPathReader.getUcp(classLoader);
        if (ucp != null) {
            URLClassPathReader.addAllClasspathEntries(ucp, classLoader, classpathOrder, log);
            valid = true;
        }
        if (!valid) {
            // None of the known field or method names yielded a new classpath element, so fall back to asking the
            // classloader for resources that are present in the root of most classpath elements, and strip the
            // resource path from the returned URLs to get the classpath element itself (#892)
            valid = findClasspathOrderByProbingForResources(classLoader, classpathOrder, log);
        }
        if (log != null) {
            log.log("FallbackClassLoaderHandler " + (valid ? "found" : "did not find")
                    + " new classpath entries in unknown ClassLoader " + classLoader);
        }
    }

    /** Root resource paths probed via {@link ClassLoader#getResources(String)} to recover classpath elements. */
    private static final String[] CLASSPATH_ELEMENT_ROOT_RESOURCE_PATHS = { "META-INF/MANIFEST.MF", "META-INF/",
            "module-info.class", "" };

    /**
     * Queries a {@link ClassLoader} for common root resources and strips the resource path from each returned URL
     * to recover the containing classpath element, excluding resources inherited from the parent classloader.
     *
     * @param classLoader
     *            the {@link ClassLoader} to probe.
     * @param classpathOrder
     *            the {@link ClasspathOrder} to update.
     * @param log
     *            the log node, or {@code null} to skip logging.
     * @return {@code true} if any classpath entries were added.
     */
    private static boolean findClasspathOrderByProbingForResources(final ClassLoader classLoader,
            final ClasspathOrder classpathOrder, final @Nullable ClassGraphLog log) {
        final var probeLog = log == null ? null
                : log.log("Probing for classpath elements using " + classLoader.getClass().getName()
                        + "#getResources(String)");
        var valid = false;
        for (final String resourcePath : CLASSPATH_ELEMENT_ROOT_RESOURCE_PATHS) {
            final var parentResourceURLs = getResourceURLs(classLoader.getParent(), resourcePath);
            for (final String resourceURL : getResourceURLs(classLoader, resourcePath)) {
                if (parentResourceURLs.contains(resourceURL)) {
                    // This resource comes from a parent classloader's classpath element
                    continue;
                }
                final var classpathEntry = stripResourcePath(resourceURL, resourcePath);
                if (classpathEntry != null) {
                    valid |= classpathOrder.addClasspathEntry(classpathEntry, classLoader, probeLog);
                }
            }
        }
        return valid;
    }

    /**
     * Returns the URLs of a resource in a {@link ClassLoader} as strings.
     *
     * @param classLoader
     *            the {@link ClassLoader} to query, or {@code null} for the bootstrap classloader.
     * @param resourcePath
     *            the path of the resource.
     * @return the URLs of the resource, or an empty set if none were found.
     */
    private static Set<String> getResourceURLs(final @Nullable ClassLoader classLoader, final String resourcePath) {
        final Set<String> resourceURLs = new LinkedHashSet<>();
        try {
            // The bootstrap classloader cannot be asked for its resources directly, but a classloader that has no
            // resources of its own and no parent delegates to it, and so serves exactly its resources. (This
            // matters because the bootstrap classloader serves module-info.class for every module of the runtime
            // image, and those modules are not classpath elements -- they are found by module scanning.)
            final var classLoaderToQuery = classLoader != null ? classLoader : new ClassLoader(null) {
            };
            for (final var e = classLoaderToQuery.getResources(resourcePath); e.hasMoreElements();) {
                resourceURLs.add(e.nextElement().toString());
            }
        } catch (final IOException | RuntimeException | LinkageError _) {
            // Ignore -- the classloader could not enumerate this resource
        }
        return resourceURLs;
    }

    /**
     * Strips a resource path suffix from a resource URL to recover the containing classpath element.
     *
     * @param resourceURL
     *            the URL of the resource.
     * @param resourcePath
     *            the path of the resource within its classpath element.
     * @return the classpath element, or {@code null} if {@code resourcePath} is not a suffix of {@code resourceURL}
     *         or nothing remains after stripping.
     */
    private static @Nullable String stripResourcePath(final String resourceURL, final String resourcePath) {
        if (!resourceURL.endsWith(resourcePath)) {
            return null;
        }
        var classpathEntry = resourceURL.substring(0, resourceURL.length() - resourcePath.length());
        // Strip the jar entry separator, so that e.g. "jar:file:/x.jar!/" becomes "jar:file:/x.jar"
        // (FastPathResolver strips the "jar:" prefix, and handles the "!/" separators of nested jars)
        if (classpathEntry.endsWith("!/")) {
            classpathEntry = classpathEntry.substring(0, classpathEntry.length() - 2);
        }
        // Reject a URL that consisted of nothing but the resource path and separators
        return classpathEntry.isEmpty() || "/".equals(classpathEntry) ? null : classpathEntry;
    }

}
