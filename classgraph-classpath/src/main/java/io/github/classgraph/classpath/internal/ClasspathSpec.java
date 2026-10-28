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
package io.github.classgraph.classpath.internal;

import java.io.File;
import java.io.IOError;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ModulePathInfo;

/**
 * Configuration settings for classpath and module path discovery.
 */
public class ClasspathSpec {
    /** Whether system modules are enabled. */
    private boolean scanSystemModules;

    /** Whether non-system modules are enabled. */
    private boolean scanNonSystemModules;

    /** Custom URL schemes recognized when splitting {@code ':'}-delimited classpath strings. */
    private final Set<String> allowedURLSchemes = new HashSet<>();

    /** Network URL schemes denied by default unless explicitly permitted. */
    public static final List<String> NETWORK_URL_SCHEMES = List.of("http", "https", "ftp", "mailto");

    // -----------------------------------------------------------------------------------------------------------

    // N.B. the places to look for classpath elements and modules are deliberately not held here, but in
    // ScanSourceSpec, since a ScanResult holds its ClasspathSpec, and a scan must not keep a classloader or a
    // module layer alive after it has finished with it

    /** Filters applied to classpath element path strings. */
    private final List<Predicate<String>> classpathElementPathFilters = new ArrayList<>(2);

    /** Filters applied to classpath element {@link URL}s. */
    private final List<Predicate<URL>> classpathElementURLFilters = new ArrayList<>(2);

    /** Whether parent classloaders are ignored. */
    private boolean ignoreParentClassLoaders;

    /** User-registered {@link ClassLoaderHandler} instances, in registration order. */
    private final List<ClassLoaderHandler> classLoaderHandlers = new ArrayList<>();

    /** Whether parent module layers are ignored. */
    private boolean ignoreParentModuleLayers;

    // -----------------------------------------------------------------------------------------------------------

    /** Constructor. */
    public ClasspathSpec() {
        // Intentionally empty
    }

    /**
     * Copy constructor.
     *
     * @param other
     *            the {@link ClasspathSpec} to copy.
     */
    private ClasspathSpec(final ClasspathSpec other) {
        scanSystemModules = other.scanSystemModules;
        scanNonSystemModules = other.scanNonSystemModules;
        allowedURLSchemes.addAll(other.allowedURLSchemes);
        classpathElementPathFilters.addAll(other.classpathElementPathFilters);
        classpathElementURLFilters.addAll(other.classpathElementURLFilters);
        ignoreParentClassLoaders = other.ignoreParentClassLoaders;
        classLoaderHandlers.addAll(other.classLoaderHandlers);
        ignoreParentModuleLayers = other.ignoreParentModuleLayers;
    }

    /**
     * Returns a shallow copy of this {@link ClasspathSpec}.
     *
     * @return a copy of this {@link ClasspathSpec}.
     */
    public ClasspathSpec copy() {
        return new ClasspathSpec(this);
    }

    // -----------------------------------------------------------------------------------------------------------

    /**
     * Adds a filter predicate applied to the path string of discovered classpath elements.
     *
     * @param filter
     *            the path filter predicate.
     */
    public void filterClasspathElements(final Predicate<String> filter) {
        Assert.notNull(filter, "filter");
        classpathElementPathFilters.add(filter);
    }

    /**
     * Adds a filter predicate applied to the {@link URL} of discovered classpath elements.
     *
     * @param filter
     *            the {@link URL} filter predicate.
     */
    public void filterClasspathElementsByURL(final Predicate<URL> filter) {
        Assert.notNull(filter, "filter");
        classpathElementURLFilters.add(filter);
    }

    /**
     * Converts a resolved classpath element path string into a {@link URL} for URL filtering, or returns null if
     * conversion fails.
     *
     * @param classpathElementPath
     *            the resolved classpath element path.
     * @return the {@link URL}, or null if conversion failed.
     */
    private static @Nullable URL toURL(final String classpathElementPath) {
        try {
            if (URLPaths.startsWithURLScheme(classpathElementPath)) {
                // The path is already a URL, such as a manifest Class-Path entry that names a remote jarfile
                return new URL(classpathElementPath);
            }
            // A '!' before the outermost jarfile belongs to a directory name, as in "/work/build!/lib.jar", so the
            // outermost separator is found by testing the filesystem, as it was when the path was resolved
            final var nestedPathIdx = PathSyntax.indexOfNestedJarSeparator(classpathElementPath);
            if (nestedPathIdx < 0) {
                return new File(classpathElementPath).toURI().toURL();
            }
            // A nested path "outer.jar!/inner" becomes the jar URL "jar:file:/outer.jar!/inner". The inner path is
            // percent-encoded with URLPaths#encodePath, so that a '%', ' ', '?' or '#' in it is decoded back to the
            // same character rather than to an escape, a query or a fragment. Every separator is spelled "!/", as a
            // jar URL requires, since a separator may be a bare '!', or a trailing '!' whose '/' was stripped (the
            // path before the outermost separator is unchanged by that). A "!/" in the outer path, as at the end of
            // a directory named "dir!", is percent-encoded, as ArchiveRoot#resolveURI encodes it, since the "jar:"
            // scheme would otherwise end the outer path there
            return new URL("jar:"
                    + new File(classpathElementPath.substring(0, nestedPathIdx)).toURI().toString().replace("!/",
                            "%21/")
                    + URLPaths.encodePath(
                            PathSyntax.toJarUrlSeparators(classpathElementPath).substring(nestedPathIdx)));
        } catch (final MalformedURLException | IllegalArgumentException | IOError | SecurityException e) {
            return null;
        }
    }

    /**
     * Tests a classpath element against all registered path and URL filters.
     *
     * @param classpathElementURL
     *            the {@link URL} of the classpath element, or null to derive it from {@code classpathElementPath}.
     * @param classpathElementPath
     *            the resolved path of the classpath element, or null.
     * @return true if the classpath element passes all filters.
     */
    public boolean acceptsClasspathElement(final @Nullable URL classpathElementURL,
            final @Nullable String classpathElementPath) {
        if (!classpathElementURLFilters.isEmpty()) {
            // FastPathResolver strips the scheme from "file:" and "jar:file:" classpath elements, so for those the
            // URL has to be reconstituted from the resolved path before the URL filters can be applied to it
            final var url = classpathElementURL != null ? classpathElementURL
                    : classpathElementPath == null ? null : toURL(classpathElementPath);
            if (url != null) {
                for (final Predicate<URL> urlFilter : classpathElementURLFilters) {
                    if (!urlFilter.test(url)) {
                        return false;
                    }
                }
            }
        }
        if (classpathElementPath != null) {
            for (final Predicate<String> pathFilter : classpathElementPathFilters) {
                if (!pathFilter.test(classpathElementPath)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Registers a URL scheme to recognize when splitting {@code ':'}-delimited classpath strings.
     *
     * @param scheme
     *            the URL scheme without {@code ':'} (e.g. {@code "http"}).
     * @throws IllegalArgumentException
     *             if the scheme is shorter than two characters or is not a valid URL scheme.
     */
    public void allowURLScheme(final String scheme) {
        Assert.notNull(scheme, "scheme");
        allowedURLSchemes.add(URLPaths.normalizeURLScheme(scheme));
    }

    /**
     * Returns the registered URL schemes in lowercase.
     *
     * @return the allowed URL schemes, as an unmodifiable set.
     */
    public Set<String> getAllowedURLSchemes() {
        return Collections.unmodifiableSet(allowedURLSchemes);
    }

    /** Enables system modules. */
    public void enableSystemModules() {
        scanSystemModules = true;
    }

    /**
     * Returns whether system modules are enabled.
     *
     * @return true if {@link #enableSystemModules()} was called.
     */
    public boolean isSystemModulesEnabled() {
        return scanSystemModules;
    }

    /** Enables non-system modules. */
    public void enableNonSystemModules() {
        scanNonSystemModules = true;
    }

    /**
     * Returns whether non-system modules are enabled.
     *
     * @return true if {@link #enableNonSystemModules()} was called.
     */
    public boolean isNonSystemModulesEnabled() {
        return scanNonSystemModules;
    }

    /**
     * Returns whether either system or non-system modules are enabled.
     *
     * @return true if {@link #enableSystemModules()} or {@link #enableNonSystemModules()} was called.
     */
    public boolean isModuleScanningEnabled() {
        return scanSystemModules || scanNonSystemModules;
    }

    /** Excludes parent classloaders. */
    public void ignoreParentClassLoaders() {
        ignoreParentClassLoaders = true;
    }

    /**
     * Returns whether parent classloaders are ignored.
     *
     * @return true if {@link #ignoreParentClassLoaders()} was called.
     */
    public boolean isParentClassLoadersIgnored() {
        return ignoreParentClassLoaders;
    }

    /** Excludes parent module layers. */
    public void ignoreParentModuleLayers() {
        ignoreParentModuleLayers = true;
    }

    /**
     * Returns whether parent module layers are ignored.
     *
     * @return true if {@link #ignoreParentModuleLayers()} was called.
     */
    public boolean isParentModuleLayersIgnored() {
        return ignoreParentModuleLayers;
    }

    /**
     * Registers a custom {@link ClassLoaderHandler}.
     *
     * @param classLoaderHandler
     *            the handler to register.
     */
    public void addClassLoaderHandler(final ClassLoaderHandler classLoaderHandler) {
        Assert.notNull(classLoaderHandler, "classLoaderHandler");
        classLoaderHandlers.add(classLoaderHandler);
    }

    /**
     * Returns the user-registered {@link ClassLoaderHandler} instances in registration order.
     *
     * @return the handlers, as an unmodifiable list.
     */
    public List<ClassLoaderHandler> getClassLoaderHandlers() {
        return Collections.unmodifiableList(classLoaderHandlers);
    }

    // -----------------------------------------------------------------------------------------------------------

    /**
     * Logs the fields of this {@link ClasspathSpec} and {@link ModulePathInfo#current()}.
     *
     * @param log
     *            the {@link LogNode} to log to, or null.
     */
    public void log(final @Nullable LogNode log) {
        if (log != null) {
            final var classpathSpecLog = log.log("ClasspathSpec:");
            for (final Field field : ClasspathSpec.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    // A constant, not a setting
                    continue;
                }
                try {
                    classpathSpecLog.log(field.getName() + ": " + field.get(this));
                } catch (final ReflectiveOperationException e) {
                    // A setting that cannot be read is named in the log rather than dropped from it: a log that
                    // silently omits a setting reads as if the setting was never changed
                    classpathSpecLog.log(field.getName() + ": could not be read: " + e);
                }
            }
            // Not a setting, but logged with them, since it affects which modules the JVM can see
            classpathSpecLog.log("modulePathInfo: " + ModulePathInfo.current());
        }
    }
}
