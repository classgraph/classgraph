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
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.filter.AcceptReject.AcceptRejectWholeString;
import io.github.classgraph.base.internal.filter.AcceptReject;
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
     * The accept/reject criteria of this spec. Each of them adds itself to this list as it is created, so that
     * {@link #sortPrefixes()} cannot miss one. N.B. this has to be declared before them, so that it exists by the
     * time the first of them is created.
     */
    private final List<AcceptReject> acceptRejects = new ArrayList<>();

    /** Module accept/reject criteria (with separator '.'). */
    public final AcceptRejectWholeString moduleAcceptReject = register(new AcceptRejectWholeString('.'));

    /**
     * If true, scan the modules supplied by the running JVM, as identified by
     * {@link java.lang.module.ModuleFinder#ofSystem()}, found in the module layers that are searched.
     *
     * <p>
     * System modules are always <i>listed</i> when a module layer is searched, whether or not this is true, since
     * the classfile of a class in a system module that is not being scanned may still have to be read in order to
     * complete the class graph above an accepted class (#902).
     */
    public boolean scanSystemModules;

    /**
     * If true, scan the non-system modules found in the module layers that are searched.
     *
     * <p>
     * There are no corresponding settings for jarfiles and directories: the classpath finder always reports every
     * classpath element it finds, and it is the scanner that decides whether to open a given element. The module
     * path is different, because it has to be enumerated through a separate API, which can be skipped entirely.
     */
    public boolean scanNonSystemModules;

    /** If true, scan the JRE's own {@code lib} and {@code ext} jars when they are found on the classpath. */
    public boolean enableSystemJars;

    /**
     * URL schemes that may start a classpath element, so that a {@code ':'}-separated classpath string is not split
     * at a scheme's own colon. This is a parsing aid, not a permission gate -- whether a jarfile may be fetched
     * over a scheme is {@link io.github.classgraph.vfs.VfsSpec#disableURLScheme(String)}'s business. {@code "jar:"}
     * and {@code "file:"} are recognized without being listed here.
     */
    public @Nullable Set<String> allowedURLSchemes;

    // -----------------------------------------------------------------------------------------------------------

    // N.B. the places to look for classpath elements and modules are deliberately not held here, but in
    // ScanSourceSpec, since a ScanResult holds its ClasspathSpec, and a scan must not keep a classloader or a
    // module layer alive after it has finished with it

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
     * Recognize a URL scheme at the start of a classpath element, so that a {@code ':'}-separated classpath string
     * is not split at that scheme's own colon.
     *
     * @param scheme
     *            the scheme, e.g. "http". The scheme name only, without the trailing {@code ':'}.
     * @throws IllegalArgumentException
     *             if the scheme is shorter than two characters (a one-character scheme cannot be told apart from a
     *             Windows drive letter), or is not a valid URL scheme.
     */
    public void enableURLScheme(final String scheme) {
        Assert.notNull(scheme, "scheme");
        final var normalizedScheme = URLPaths.normalizeURLScheme(scheme);
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
                    final var value = field.get(this);
                    // Skip the bookkeeping list, which duplicates every criterion logged by name below, and
                    // skip a criterion that nothing was accepted or rejected with
                    if (value == acceptRejects || value instanceof AcceptReject && value.toString().isEmpty()) {
                        continue;
                    }
                    classpathSpecLog.log(field.getName() + ": " + value);
                } catch (final ReflectiveOperationException e) {
                    // A criterion that cannot be read is named in the log rather than dropped from it: a log
                    // that silently omits a criterion reads as if the criterion was never set
                    classpathSpecLog.log(field.getName() + ": could not be read: " + e);
                }
            }
        }
    }
}
