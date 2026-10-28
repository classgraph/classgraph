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
package io.github.classgraph.classpath;

import java.lang.module.ModuleReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.jetbrains.annotations.Unmodifiable;

import io.github.classgraph.classpath.internal.ClassLoaderProbe;
import io.github.classgraph.classpath.internal.ClasspathSpec;
import io.github.classgraph.vfs.Vfs;

/**
 * The classpath entries and modules found by a {@link ClasspathFinder}.
 *
 * <p>
 * Finding the classpath opens jarfiles on the classpath to read their manifests. Calling {@link #close()} closes
 * these jarfiles and deletes any temporary files extracted from nested jarfiles, so a {@link Classpath} should be
 * used in a try-with-resources statement. Entry and module metadata remain accessible after {@link #close()}.
 *
 * <p>
 * Iterating over a {@link Classpath} yields its {@link ClasspathEntry} elements in {@link #getEntries()} order.
 * Modules are returned separately by {@link #getModules()}.
 */
public final class Classpath implements AutoCloseable, Iterable<ClasspathEntry> {
    /** The classpath elements in classloader search order. */
    private final List<ClasspathEntry> entries;

    /** The system modules, ordered by module layer and then by module name. */
    private final List<ModuleReference> systemModules;

    /** The non-system modules, ordered by module layer and then by module name. */
    private final List<ModuleReference> nonSystemModules;

    /** The virtual filesystem used to read jarfiles on the classpath. */
    private final Vfs vfs;

    /**
     * Constructor.
     *
     * @param entries
     *            the classpath elements in classloader search order.
     * @param classLoaderProbe
     *            the classloader probe containing discovered modules.
     * @param classpathSpec
     *            the classpath configuration.
     * @param vfs
     *            the virtual filesystem used to read jarfiles.
     */
    Classpath(final List<ClasspathEntry> entries, final ClassLoaderProbe classLoaderProbe,
            final ClasspathSpec classpathSpec, final Vfs vfs) {
        this.entries = List.copyOf(entries);
        this.vfs = vfs;

        final var moduleFinder = classLoaderProbe.getModuleFinder();
        // The ModuleFinder lists both kinds of module even when they were not enabled, since the scanner may need
        // to read a class from one of them (#902), so each kind is dropped here unless it was enabled
        this.systemModules = moduleFinder == null || !classpathSpec.isSystemModulesEnabled() ? List.of()
                : moduleFinder.getSystemModuleReferences();
        this.nonSystemModules = moduleFinder == null || !classpathSpec.isNonSystemModulesEnabled() ? List.of()
                : moduleFinder.getNonSystemModuleReferences();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Closes the underlying {@link Vfs}, releasing any open jarfiles and deleting any temporary files created for
     * nested jarfiles. Entry and module metadata remain accessible after closing.
     */
    @Override
    public void close() {
        vfs.close();
    }

    /**
     * Returns the deduplicated classpath entries in classloader resolution order, including entries expanded from
     * automatic lib directories and manifest {@code Class-Path} and {@code Bundle-ClassPath} attributes.
     *
     * @return the classpath entries, as an unmodifiable list.
     */
    public @Unmodifiable List<ClasspathEntry> getEntries() {
        return entries;
    }

    /**
     * Returns an iterator over the classpath entries in {@link #getEntries()} order.
     *
     * @return an iterator over the classpath entries.
     */
    @Override
    public @Unmodifiable Iterator<ClasspathEntry> iterator() {
        return entries.iterator();
    }

    /**
     * Returns the {@link ClasspathEntry#getLocation()} string for each entry in {@link #getEntries()}.
     *
     * @return the classpath entry locations, as an unmodifiable list.
     */
    public @Unmodifiable List<String> getLocations() {
        return entries.stream().map(ClasspathEntry::getLocation).toList();
    }

    /**
     * Returns all discovered modules ({@link #getSystemModules()} followed by {@link #getNonSystemModules()}).
     * Empty unless {@link ClasspathFinder#enableSystemModules()} or
     * {@link ClasspathFinder#enableNonSystemModules()} was called.
     *
     * @return the modules, as an unmodifiable list.
     */
    public @Unmodifiable List<ModuleReference> getModules() {
        final List<ModuleReference> modules = new ArrayList<>(systemModules.size() + nonSystemModules.size());
        modules.addAll(systemModules);
        modules.addAll(nonSystemModules);
        return Collections.unmodifiableList(modules);
    }

    /**
     * Returns the discovered system modules ({@code java.*}, {@code jdk.*}, {@code javafx.*}, and {@code oracle.*}
     * modules present at the same location in {@link java.lang.module.ModuleFinder#ofSystem()}), ordered by module
     * layer and module name. Empty unless {@link ClasspathFinder#enableSystemModules()} was called.
     *
     * @return the system modules, as an unmodifiable list.
     */
    public @Unmodifiable List<ModuleReference> getSystemModules() {
        return systemModules;
    }

    /**
     * Returns the discovered non-system modules, ordered by module layer and module name. Empty unless
     * {@link ClasspathFinder#enableNonSystemModules()} was called.
     *
     * @return the non-system modules, as an unmodifiable list.
     */
    public @Unmodifiable List<ModuleReference> getNonSystemModules() {
        return nonSystemModules;
    }

    /**
     * Returns the module path command-line options for the running JVM (equivalent to
     * {@link ModulePathInfo#current()}).
     *
     * @return the module path info.
     */
    // An instance method, so that the module path info is reached the same way as the rest of the result
    @SuppressWarnings("static-method")
    public ModulePathInfo getModulePathInfo() {
        return ModulePathInfo.current();
    }

    /**
     * Returns the {@link Vfs} instance owned by this {@link Classpath}, which can be passed to
     * {@link ClasspathEntry#open(Vfs)} or {@link Vfs#open(java.lang.module.ModuleReference)} to read classpath
     * entries and modules while reusing already-opened jarfiles.
     *
     * <p>
     * This {@link Vfs} is closed when {@link #close()} is called on this {@link Classpath}.
     *
     * @return the {@link Vfs} instance.
     */
    public Vfs getVfs() {
        return vfs;
    }

    /**
     * Returns a string representation listing all classpath entries and modules, one per line.
     *
     * @return the classpath and modules as a newline-separated string.
     */
    @Override
    public String toString() {
        final var buf = new StringBuilder(1024);
        for (final ClasspathEntry entry : entries) {
            buf.append(entry).append('\n');
        }
        for (final ModuleReference module : getModules()) {
            buf.append(module.descriptor().name())
                    .append(module.location().map(location -> " [" + location + "]").orElse("")).append('\n');
        }
        return buf.toString();
    }
}
