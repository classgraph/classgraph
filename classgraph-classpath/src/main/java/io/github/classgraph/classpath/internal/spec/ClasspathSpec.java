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
package io.github.classgraph.classpath.internal.spec;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import io.github.classgraph.base.internal.utils.AcceptReject;
import io.github.classgraph.base.internal.utils.AcceptReject.AcceptRejectWholeString;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.base.internal.utils.JarUtils;
import io.github.classgraph.base.internal.utils.LogNode;
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
     * The accept/reject criteria of this spec. Each of them adds itself to this list as it is created, so that
     * {@link #sortPrefixes()} cannot miss one. N.B. this has to be declared before them, so that it exists by the
     * time the first of them is created.
     */
    private final List<AcceptReject> acceptRejects = new ArrayList<>();

    /** Module accept/reject criteria (with separator '.'). */
    // N.B. this is read here, and not just by the scanner, because the module path is searched if any module is
    // specifically accepted, even when system jars and modules are not otherwise scanned.
    public final AcceptRejectWholeString moduleAcceptReject = register(new AcceptRejectWholeString('.'));

    /**
     * If true, search the module path.
     *
     * <p>
     * There are no corresponding settings for jarfiles and directories: the classpath finder always reports every
     * classpath element it finds, and it is the scanner that decides whether to open a given element. The module
     * path is different, because it has to be enumerated through a separate API, which can be skipped entirely.
     */
    public boolean scanModules = true;

    /** If true, system packages and modules (java.*, jre.*, etc.) should be searched. */
    public boolean enableSystemJarsAndModules;

    /**
     * URL schemes that are allowed in classpath elements (not counting the optional "jar:" prefix and/or "file:",
     * which are automatically allowed).
     */
    public @Nullable Set<String> allowedURLSchemes;

    // -----------------------------------------------------------------------------------------------------------

    // N.B. the classloaders and module layers to search are deliberately not held here, but in
    // ClassLoaderAndModuleLayerSpec, since a ScanResult holds its ClasspathSpec, and a scan must not keep a
    // classloader alive after it has finished with it

    /**
     * If non-null, specifies a list of classpath elements (String, {@link URL} or {@link URI} to use to override
     * the default classpath.
     */
    public @Nullable List<Object> overrideClasspath;

    /** If non-null, a list of filters to apply to classpath element path strings. */
    public @Nullable List<Predicate<String>> classpathElementPathFilters;

    /** If non-null, a list of filters to apply to classpath element {@link URL}s. */
    public @Nullable List<Predicate<URL>> classpathElementURLFilters;

    /** If true, do not fetch paths from parent classloaders. */
    public boolean ignoreParentClassLoaders;

    /**
     * The {@link ClassLoaderHandler} instances the user registered, in registration order. These are offered each
     * classloader before the built-in handlers are.
     */
    public final List<ClassLoaderHandler> classLoaderHandlers = new ArrayList<>();

    /**
     * If true, do not search module layers that are the parent of other module layers.
     */
    public boolean ignoreParentModuleLayers;

    /** Commandline module path parameters. */
    public ModulePathInfo modulePathInfo = new ModulePathInfo();

    // -----------------------------------------------------------------------------------------------------------

    /** Constructor. */
    public ClasspathSpec() {
        // Intentionally empty
    }

    // -----------------------------------------------------------------------------------------------------------

    /**
     * Override the automatically-detected classpath with a custom path. You can specify multiple elements in
     * separate calls, and if this method is called even once, the default classpath will be overridden, such that
     * nothing but the provided classpath will be searched, i.e. causes ClassLoaders to be ignored, as well as the
     * java.class.path system property.
     *
     * @param overrideClasspathElement
     *            The classpath element to add as an override to the default classpath.
     */
    public void addClasspathOverride(final Object overrideClasspathElement) {
        if (this.overrideClasspath == null) {
            this.overrideClasspath = new ArrayList<>();
        }
        if (overrideClasspathElement instanceof ClassLoader) {
            throw new IllegalArgumentException(
                    "Need to pass ClassLoader instances to overrideClassLoaders, not overrideClasspath");
        }
        // A classpath element of a type the classpath order understands is kept as it is, so that the filesystem of
        // a Path and the scheme of a URL or URI are not lost. Anything else is read by its string form, which is
        // taken now rather than at scan time, so that a mutable object cannot change what it names in between
        this.overrideClasspath
                .add(overrideClasspathElement instanceof String || overrideClasspathElement instanceof URL
                        || overrideClasspathElement instanceof URI || overrideClasspathElement instanceof File
                        || overrideClasspathElement instanceof Path ? overrideClasspathElement
                                : overrideClasspathElement.toString());
    }

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
        if (this.classpathElementPathFilters == null) {
            this.classpathElementPathFilters = new ArrayList<>(2);
        }
        this.classpathElementPathFilters.add(filter);
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
        if (this.classpathElementURLFilters == null) {
            this.classpathElementURLFilters = new ArrayList<>(2);
        }
        this.classpathElementURLFilters.add(filter);
    }

    /**
     * Allow a specified URL scheme in classpath elements.
     *
     * @param scheme
     *            the scheme, e.g. "http". The scheme name only, without the trailing {@code ':'}.
     * @throws IllegalArgumentException
     *             if the scheme is shorter than two characters (a one-character scheme cannot be told apart from a
     *             Windows drive letter), or is not a valid URL scheme.
     */
    public void enableURLScheme(final String scheme) {
        Assert.notNull(scheme, "scheme");
        final var normalizedScheme = JarUtils.normalizeURLScheme(scheme);
        if (allowedURLSchemes == null) {
            allowedURLSchemes = new HashSet<>();
        }
        allowedURLSchemes.add(normalizedScheme);
    }

    // -----------------------------------------------------------------------------------------------------------

    /**
     * Record an accept/reject criterion, so that {@link #sortPrefixes()} sorts it. Called from the field
     * initializers, so that a criterion cannot be added without being sorted.
     *
     * @param <T>
     *            the type of the accept/reject criterion.
     * @param acceptReject
     *            the accept/reject criterion.
     * @return the same accept/reject criterion.
     */
    private <T extends AcceptReject> T register(final T acceptReject) {
        acceptRejects.add(acceptReject);
        return acceptReject;
    }

    /** Sort prefixes to ensure correct accept/reject evaluation. */
    // #167
    public void sortPrefixes() {
        for (final AcceptReject acceptReject : acceptRejects) {
            acceptReject.sortPrefixes();
        }
    }

    /**
     * Write to log.
     *
     * @param log
     *            The {@link LogNode} to log to.
     */
    public void log(final @Nullable LogNode log) {
        if (log != null) {
            final var classpathSpecLog = log.log("ClasspathSpec:");
            for (final Field field : ClasspathSpec.class.getDeclaredFields()) {
                try {
                    classpathSpecLog.log(field.getName() + ": " + field.get(this));
                } catch (final ReflectiveOperationException e) {
                    // Ignore
                }
            }
        }
    }
}
