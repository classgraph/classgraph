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
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.PathList;
import io.github.classgraph.base.internal.utils.Assert;
import org.jspecify.annotations.Nullable;

/**
 * Where the classpath elements and modules are looked for. Nothing is looked for until something here is enabled.
 *
 * <p>
 * The classpath sources are kept in the order they were enabled in, and searched in that order. The module sources
 * are searched before all of them, because that is the order in which the JVM resolves a class: a builtin
 * classloader looks the class's package up among the modules before it delegates to its parent or falls back to its
 * classpath.
 *
 * <p>
 * This is deliberately not part of {@link ClasspathSpec}: a {@code ScanResult} holds the specs the scan was run
 * with for as long as the caller holds the {@code ScanResult}, and a scan must not keep a {@link ClassLoader} or a
 * {@link ModuleLayer} alive. This object is held only by the {@code ClassGraph} or
 * {@link io.github.classgraph.classpath.ClasspathFinder} instance that the caller configured, and by the scan
 * itself while it runs; it is unreachable from the {@code ScanResult}.
 */
public class ScanSourceSpec {
    /** A place that classpath elements are looked for. */
    public sealed interface ClasspathSource {
    }

    /**
     * The classpath elements declared by the classloaders found in the environment: the context classloader of the
     * calling thread, the classloader of the caller's own class, the system classloader, the classloaders of the
     * classes on the call stack, and the parents of all of those.
     */
    public record DetectedClassLoaders() implements ClasspathSource {
    }

    /**
     * The classpath elements declared by the given classloaders, and by their parents.
     *
     * @param classLoaders
     *            the classloaders.
     */
    public record NamedClassLoaders(List<ClassLoader> classLoaders) implements ClasspathSource {
    }

    /**
     * Classpath elements given directly, rather than found by asking a classloader.
     *
     * @param classpathEntries
     *            the classpath elements, one entry per element.
     */
    public record NamedClasspathEntries(List<Object> classpathEntries) implements ClasspathSource {
    }

    /**
     * A whole classpath given directly, still to be split at {@link File#pathSeparatorChar}. The split is left
     * until the classpath is found, because which URL schemes' own {@code ':'} must not be read as a separator is
     * not settled until then: the caller may register a scheme after handing over the classpath that names it.
     *
     * @param classpath
     *            the classpath, with the elements separated by {@link File#pathSeparatorChar}.
     */
    public record ClasspathString(String classpath) implements ClasspathSource {
    }

    /** The places classpath elements are looked for, in the order they were enabled in. */
    private final List<ClasspathSource> classpathSources = new ArrayList<>();

    /**
     * If true, the caller asked for the module layers that are visible from it, and the boot layer, to be searched.
     * They are also searched without being asked for when a kind of module is enabled and the caller named no
     * layers -- see {@link #searchesDetectedModuleLayers(ClasspathSpec)}.
     */
    private boolean detectedModuleLayersRequested;

    /** If non-null, the module layers the caller named. */
    private @Nullable List<ModuleLayer> namedModuleLayers;

    // -----------------------------------------------------------------------------------------------------------

    /** Constructor. */
    public ScanSourceSpec() {
        // Intentionally empty
    }

    // -----------------------------------------------------------------------------------------------------------

    /** Search the classloaders found in the environment for classpath elements. */
    public void enableClasspath() {
        classpathSources.add(new DetectedClassLoaders());
    }

    /**
     * Search the given classloaders for classpath elements.
     *
     * @param classLoaders
     *            the classloaders to search.
     * @throws IllegalArgumentException
     *             if no classloader is given.
     */
    public void enableClassLoaders(final ClassLoader... classLoaders) {
        Assert.notNullElements(classLoaders, "classLoaders");
        if (classLoaders.length == 0) {
            throw new IllegalArgumentException("At least one ClassLoader must be provided");
        }
        final List<ClassLoader> classLoaderList = new ArrayList<>(classLoaders.length);
        Collections.addAll(classLoaderList, classLoaders);
        classpathSources.add(new NamedClassLoaders(classLoaderList));
    }

    /**
     * Search the given classpath elements. Each {@link String}, {@link File}, {@link Path}, {@link URL} or
     * {@link URI} is one classpath entry, and a {@link String} is not split on {@link File#pathSeparatorChar}. An
     * element that is an {@link Iterable} (other than a {@link Path}) or an array of objects is replaced by its own
     * elements, to any depth, except that an {@link Iterable} that is not a {@link Collection} and yields itself,
     * as an Ant {@code Resource} does, is one classpath entry. That, and any other element, is read by its
     * {@link Object#toString()}.
     *
     * @param classpathEntries
     *            the classpath elements.
     * @throws IllegalArgumentException
     *             if the elements hold no classpath element, or hold a {@link ClassLoader}.
     */
    public void enableClasspathEntries(final List<?> classpathEntries) {
        final List<Object> normalized = new ArrayList<>(classpathEntries.size());
        // Compared by identity, since the hashCode() of a collection that contains itself overflows the stack
        final Set<Object> expanding = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final Object classpathEntry : classpathEntries) {
            addClasspathEntry(classpathEntry, normalized, expanding);
        }
        // Tested after expanding, so that collections and arrays that hold no classpath element are rejected too
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("At least one classpath entry must be provided");
        }
        classpathSources.add(new NamedClasspathEntries(normalized));
    }

    /**
     * Add a classpath element to a list, replacing an {@link Iterable} or array by its own elements.
     *
     * @param classpathEntry
     *            the classpath element.
     * @param normalized
     *            the list to add the classpath entries to.
     * @param expanding
     *            the {@link Iterable}s and arrays that are being expanded by the callers of this call, compared by
     *            identity.
     * @throws NullPointerException
     *             if the classpath element is, or contains, null.
     * @throws IllegalArgumentException
     *             if the classpath element is, or contains, a {@link ClassLoader}.
     */
    private static void addClasspathEntry(final Object classpathEntry, final List<Object> normalized,
            final Set<Object> expanding) {
        // A caller that runs no null checker of its own can still pass a null element, at any depth
        Assert.notNull(classpathEntry, "classpathEntries element");
        if (classpathEntry instanceof ClassLoader) {
            throw new IllegalArgumentException(
                    "Need to pass ClassLoader instances to enableClassLoaders, not enableClasspathEntries");
        }
        if (classpathEntry instanceof String || classpathEntry instanceof URL || classpathEntry instanceof URI
                || classpathEntry instanceof File || classpathEntry instanceof Path) {
            // A classpath element of a type the classpath order understands is kept as it is, so that the
            // filesystem of a Path and the scheme of a URL or URI are not lost. A Path is an Iterable of its own
            // name elements, so it has to be tested for before Iterable is
            normalized.add(classpathEntry);
        } else if (classpathEntry instanceof Iterable<?> || classpathEntry instanceof Object[]) {
            if (expanding.add(classpathEntry)) {
                // The elements are read now rather than at scan time, so that a mutable collection cannot change
                // what it names in between
                final Iterable<?> elements = classpathEntry instanceof final Object[] array ? Arrays.asList(array)
                        : (Iterable<?>) classpathEntry;
                for (final Object element : elements) {
                    addClasspathEntry(element, normalized, expanding);
                }
                expanding.remove(classpathEntry);
            } else if (!(classpathEntry instanceof Collection<?> || classpathEntry instanceof Object[])) {
                // An Iterable that is not a collection, and that yields itself, stands for one classpath element,
                // which its string form names. An Ant Resource is such an Iterable
                normalized.add(classpathEntry.toString());
            }
            // A collection or array that contains itself, directly or through another one, is not expanded again:
            // every entry it holds is already being added by the call that is expanding it
        } else {
            // Anything else is read by its string form, which is taken now rather than at scan time, so that a
            // mutable object cannot change what it names in between
            normalized.add(classpathEntry.toString());
        }
    }

    /**
     * Search the classpath elements of the given classpath, which is split at {@link File#pathSeparatorChar} when
     * the classpath is found rather than now.
     *
     * @param classpath
     *            the classpath, with the elements separated by {@link File#pathSeparatorChar}.
     * @throws IllegalArgumentException
     *             if the classpath holds no classpath element.
     */
    public void enableClasspathString(final String classpath) {
        // Only the emptiness of the classpath is tested here, which no set of registered URL schemes can change:
        // splitting for real is left until the classpath is found
        if (PathList.split(classpath, /* allowedURLSchemes = */ null).length == 0) {
            throw new IllegalArgumentException("At least one classpath entry must be provided");
        }
        classpathSources.add(new ClasspathString(classpath));
    }

    /** Search the module layers that are visible from the caller, and the boot layer. */
    public void enableDetectedModuleLayers() {
        detectedModuleLayersRequested = true;
    }

    /**
     * Whether the module layers that are visible from the caller are searched: they are, if the caller asked for
     * them, and also if a kind of module was enabled and the caller named no layers of its own. Layers the caller
     * named otherwise replace the detected ones rather than adding to them, since naming layers is how a caller
     * says which layers to search.
     *
     * @param classpathSpec
     *            the classpath settings, which say which kinds of module are scanned.
     * @return true if the module layers detected in the environment are searched.
     */
    public boolean searchesDetectedModuleLayers(final ClasspathSpec classpathSpec) {
        return detectedModuleLayersRequested
                || classpathSpec.isModuleScanningEnabled() && namedModuleLayers == null;
    }

    /**
     * Whether any classloader is asked for classpath elements, as opposed to the classpath elements all being given
     * directly.
     *
     * @return true if the classloaders detected in the environment, or classloaders the caller named, are searched.
     */
    public boolean searchesClassLoaders() {
        return classpathSources.stream().anyMatch(ScanSourceSpec::isClassLoaderSource);
    }

    /**
     * Whether a classpath source asks classloaders for classpath elements. Each kind of source is named, so a new
     * kind of source fails until it is added here.
     *
     * @param source
     *            the classpath source.
     * @return true if the source is a set of classloaders, false if it is a set of classpath entries.
     * @throws AssertionError
     *             if the source is of a kind that is not named here.
     */
    private static boolean isClassLoaderSource(final ClasspathSource source) {
        if (source instanceof DetectedClassLoaders || source instanceof NamedClassLoaders) {
            return true;
        } else if (source instanceof NamedClasspathEntries || source instanceof ClasspathString) {
            return false;
        } else {
            throw new AssertionError(source);
        }
    }

    /**
     * Search the given module layers instead of the ones that are visible from the caller, unless
     * {@link #enableDetectedModuleLayers()} is called as well, in which case both are searched.
     *
     * @param moduleLayers
     *            the module layers to search.
     * @throws IllegalArgumentException
     *             if no module layer is given.
     */
    public void enableModuleLayers(final ModuleLayer... moduleLayers) {
        Assert.notNullElements(moduleLayers, "moduleLayers");
        if (moduleLayers.length == 0) {
            throw new IllegalArgumentException("At least one ModuleLayer must be provided");
        }
        if (this.namedModuleLayers == null) {
            this.namedModuleLayers = new ArrayList<>();
        }
        Collections.addAll(this.namedModuleLayers, moduleLayers);
    }

    // -----------------------------------------------------------------------------------------------------------

    /**
     * Get the places classpath elements are looked for.
     *
     * @return the classpath sources, in the order they were enabled in.
     */
    public List<ClasspathSource> getClasspathSources() {
        return Collections.unmodifiableList(classpathSources);
    }

    /**
     * Get the module layers the caller named.
     *
     * @return the module layers named by {@link #enableModuleLayers(ModuleLayer...)}, in the order they were named
     *         in, or null if none were named.
     */
    public @Nullable List<ModuleLayer> getNamedModuleLayers() {
        final var moduleLayers = namedModuleLayers;
        return moduleLayers == null ? null : Collections.unmodifiableList(moduleLayers);
    }

    /**
     * Check that every option that says how the sources are searched was asked for alongside a source that it
     * applies to. No config method turns on another config method for you, so an option with no source to apply to
     * would otherwise be silently ignored. {@code ClassGraph} and
     * {@link io.github.classgraph.classpath.ClasspathFinder} both check their sources with this, so that the two
     * refuse the same configurations.
     *
     * @param classpathSpec
     *            the classpath settings.
     * @param className
     *            the simple name of the class whose config methods were called, e.g. {@code "ClassGraph"}, which
     *            the exception message names the methods by.
     * @throws IllegalArgumentException
     *             if an option was asked for without a source that it applies to.
     */
    public void checkSourcesAreExplicit(final ClasspathSpec classpathSpec, final String className) {
        // The module layer options say which layers to look in, so they need a kind of module to look for
        final var moduleScanningEnabled = classpathSpec.isModuleScanningEnabled();
        Assert.companionIsCalled(namedModuleLayers != null, className, "enableModuleLayers", moduleScanningEnabled,
                "enableSystemModules", "enableNonSystemModules");
        Assert.companionIsCalled(detectedModuleLayersRequested, className, "enableDetectedModuleLayers",
                moduleScanningEnabled, "enableSystemModules", "enableNonSystemModules");
        Assert.companionIsCalled(classpathSpec.isParentModuleLayersIgnored(), className, "ignoreParentModuleLayers",
                moduleScanningEnabled, "enableSystemModules", "enableNonSystemModules");

        // The classloader options apply only to the classloaders that are searched, so a classloader has to be
        // searched. Named classpath entries and modules are found without asking any classloader.
        final var classLoadersSearched = searchesClassLoaders();
        Assert.companionIsCalled(classpathSpec.isParentClassLoadersIgnored(), className, "ignoreParentClassLoaders",
                classLoadersSearched, "enableClasspath", "enableClassLoaders");
        Assert.companionIsCalled(!classpathSpec.getClassLoaderHandlers().isEmpty(), className,
                "registerClassLoaderHandler", classLoadersSearched, "enableClasspath", "enableClassLoaders");
    }

    // -----------------------------------------------------------------------------------------------------------

    /**
     * Log the sources that the caller enabled.
     *
     * @param classpathSpec
     *            the classpath settings, which say whether the module layers detected in the environment are
     *            searched.
     * @param log
     *            the log node, or null to skip logging
     */
    public void log(final ClasspathSpec classpathSpec, final @Nullable LogNode log) {
        if (log != null) {
            final var subLog = log.log("ScanSourceSpec:");
            subLog.log("classpathSources: " + classpathSources);
            subLog.log("searchDetectedModuleLayers: " + searchesDetectedModuleLayers(classpathSpec));
            subLog.log("namedModuleLayers: " + namedModuleLayers);
        }
    }
}
