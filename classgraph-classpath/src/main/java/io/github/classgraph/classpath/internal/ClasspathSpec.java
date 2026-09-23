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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ModulePathInfo;
import org.jspecify.annotations.Nullable;

/**
 * The settings that determine which classpath and module path elements are found.
 *
 * <p>
 * Everything here is read while the classpath is being built. Settings that only affect what happens to a classpath
 * element after it has been found (whether jarfiles or directories are scanned, which packages and classes are
 * accepted, and so on) belong in the specs of the libraries layered on top of this one.
 */
public class ClasspathSpec {
    /**
     * If true, scan the modules supplied by the running JVM, as identified by
     * {@link java.lang.module.ModuleFinder#ofSystem()}, found in the module layers that are searched.
     *
     * <p>
     * {@link ModuleFinder} lists the system modules of the module layers it searches whether or not this is true,
     * since the classfile of a class in a system module that is not being scanned may still have to be read in
     * order to complete the class graph above an accepted class (#902).
     */
    private boolean scanSystemModules;

    /**
     * If true, scan the non-system modules found in the module layers that are searched.
     *
     * <p>
     * There are no corresponding settings for jarfiles and directories: the classpath finder always reports every
     * classpath element it finds, and it is the scanner that decides whether to open a given element. The module
     * path is different, because it has to be enumerated through a separate API, which can be skipped entirely.
     */
    private boolean scanNonSystemModules;

    /**
     * URL schemes that may start a classpath element, so that a {@code ':'}-separated classpath string is not split
     * at a scheme's own colon. This is a parsing aid, not a permission check -- whether a jarfile may be fetched
     * over a scheme is {@link io.github.classgraph.vfs.VfsSpec#denyURLScheme(String)}'s business. {@code "jar:"}
     * and {@code "file:"} are recognized without being listed here.
     */
    private final Set<String> allowedURLSchemes = new HashSet<>();

    /**
     * The URL schemes that a scan or a classpath walk does not fetch a jarfile from unless asked to. These are the
     * schemes that every JVM can fetch over a network, so a classpath element naming one would otherwise be read
     * from the network, which is not something to do with a path that was merely handed over.
     */
    public static final List<String> NETWORK_URL_SCHEMES = List.of("http", "https", "ftp", "mailto");

    // -----------------------------------------------------------------------------------------------------------

    // N.B. the places to look for classpath elements and modules are deliberately not held here, but in
    // ScanSourceSpec, since a ScanResult holds its ClasspathSpec, and a scan must not keep a classloader or a
    // module layer alive after it has finished with it

    /** The filters to apply to classpath element path strings. */
    private final List<Predicate<String>> classpathElementPathFilters = new ArrayList<>(2);

    /** The filters to apply to classpath element {@link URL}s. */
    private final List<Predicate<URL>> classpathElementURLFilters = new ArrayList<>(2);

    /** If true, do not fetch paths from parent classloaders. */
    private boolean ignoreParentClassLoaders;

    /**
     * The {@link ClassLoaderHandler} instances the user registered, in registration order. These are offered each
     * classloader before the built-in handlers are.
     */
    private final List<ClassLoaderHandler> classLoaderHandlers = new ArrayList<>();

    /**
     * If true, do not search module layers that are the parent of other module layers.
     */
    private boolean ignoreParentModuleLayers;

    /** The module path switches the JVM was launched with. */
    private final ModulePathInfo modulePathInfo = new ModulePathInfo();

    // -----------------------------------------------------------------------------------------------------------

    /** Constructor. */
    public ClasspathSpec() {
        // Intentionally empty
    }

    // -----------------------------------------------------------------------------------------------------------

    /**
     * Add a classpath element path filter. The provided filter should return true if the path string passed to it
     * is a path that should be scanned.
     *
     * @param filter
     *            The filter to apply to the path string of all discovered classpath elements, to decide which
     *            should be scanned.
     */
    public void filterClasspathElements(final Predicate<String> filter) {
        Assert.notNull(filter, "filter");
        classpathElementPathFilters.add(filter);
    }

    /**
     * Get the classpath element path filters.
     *
     * @return the filters added by {@link #filterClasspathElements(Predicate)}, in the order they were added.
     */
    public List<Predicate<String>> getClasspathElementPathFilters() {
        return Collections.unmodifiableList(classpathElementPathFilters);
    }

    /**
     * Add a classpath element {@link URL} filter. The provided filter should return true if the {@link URL} passed
     * to it is a classpath element that should be scanned.
     *
     * @param filter
     *            The filter to apply to the {@link URL} of all discovered classpath elements, to decide which
     *            should be scanned.
     */
    public void filterClasspathElementsByURL(final Predicate<URL> filter) {
        Assert.notNull(filter, "filter");
        classpathElementURLFilters.add(filter);
    }

    /**
     * Get the classpath element {@link URL} filters.
     *
     * @return the filters added by {@link #filterClasspathElementsByURL(Predicate)}, in the order they were added.
     */
    public List<Predicate<URL>> getClasspathElementURLFilters() {
        return Collections.unmodifiableList(classpathElementURLFilters);
    }

    /**
     * Recognize a URL scheme at the start of a classpath element, so that a {@code ':'}-separated classpath string
     * is not split at that scheme's own colon.
     *
     * @param scheme
     *            the scheme, e.g. "http". The scheme name only, without the trailing {@code ':'}.
     * @throws IllegalArgumentException
     *             if the scheme is shorter than two characters (a one-character scheme cannot be told apart from a
     *             Windows drive letter), or is not a valid URL scheme.
     */
    public void allowURLScheme(final String scheme) {
        Assert.notNull(scheme, "scheme");
        allowedURLSchemes.add(URLPaths.normalizeURLScheme(scheme));
    }

    /**
     * Get the URL schemes that may start a classpath element.
     *
     * @return the schemes added by {@link #allowURLScheme(String)}, in lowercase.
     */
    public Set<String> getAllowedURLSchemes() {
        return Collections.unmodifiableSet(allowedURLSchemes);
    }

    /** Scan the modules supplied by the running JVM. */
    public void enableSystemModules() {
        scanSystemModules = true;
    }

    /**
     * Check whether the modules supplied by the running JVM are scanned.
     *
     * @return true if {@link #enableSystemModules()} was called.
     */
    public boolean isSystemModulesEnabled() {
        return scanSystemModules;
    }

    /** Scan the non-system modules. */
    public void enableNonSystemModules() {
        scanNonSystemModules = true;
    }

    /**
     * Check whether the non-system modules are scanned.
     *
     * @return true if {@link #enableNonSystemModules()} was called.
     */
    public boolean isNonSystemModulesEnabled() {
        return scanNonSystemModules;
    }

    /** Do not fetch paths from parent classloaders. */
    public void ignoreParentClassLoaders() {
        ignoreParentClassLoaders = true;
    }

    /**
     * Check whether parent classloaders are ignored.
     *
     * @return true if {@link #ignoreParentClassLoaders()} was called.
     */
    public boolean isParentClassLoadersIgnored() {
        return ignoreParentClassLoaders;
    }

    /** Do not search module layers that are the parent of other module layers. */
    public void ignoreParentModuleLayers() {
        ignoreParentModuleLayers = true;
    }

    /**
     * Check whether parent module layers are ignored.
     *
     * @return true if {@link #ignoreParentModuleLayers()} was called.
     */
    public boolean isParentModuleLayersIgnored() {
        return ignoreParentModuleLayers;
    }

    /**
     * Register a {@link ClassLoaderHandler}.
     *
     * @param classLoaderHandler
     *            the handler, which is offered each classloader before the built-in handlers are.
     */
    public void addClassLoaderHandler(final ClassLoaderHandler classLoaderHandler) {
        Assert.notNull(classLoaderHandler, "classLoaderHandler");
        classLoaderHandlers.add(classLoaderHandler);
    }

    /**
     * Get the registered {@link ClassLoaderHandler} instances.
     *
     * @return the handlers, in registration order.
     */
    public List<ClassLoaderHandler> getClassLoaderHandlers() {
        return Collections.unmodifiableList(classLoaderHandlers);
    }

    /**
     * Get the module path switches the JVM was launched with.
     *
     * @return the {@link ModulePathInfo}.
     */
    public ModulePathInfo getModulePathInfo() {
        return modulePathInfo;
    }

    // -----------------------------------------------------------------------------------------------------------

    /**
     * Log the settings.
     *
     * @param log
     *            The {@link LogNode} to log to.
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
        }
    }
}
