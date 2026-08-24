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

import io.github.classgraph.classpath.internal.ClassLoaderProbe;
import io.github.classgraph.vfs.Vfs;

/**
 * Where a JVM loads its classes and resources from: the classpath elements and the modules that were found by a
 * {@link ClasspathFinder}.
 *
 * <p>
 * Finding the classpath opens the jarfiles on it, in order to read their manifests. {@link #close()} closes them
 * again, and deletes any temporary files that were needed to open a jarfile nested inside another jarfile, so a
 * {@link Classpath} is best obtained in a try-with-resources statement. The classpath elements and the modules can
 * still be read after {@link #close()} has been called.
 *
 * <p>
 * Iterating a {@link Classpath} iterates its classpath elements, in the same order as {@link #getEntries()}. The
 * modules are listed separately, by {@link #getModules()}.
 */
public final class Classpath implements AutoCloseable, Iterable<ClasspathEntry> {
    /** The classpath elements, in the order the classloaders would search them. */
    private final List<ClasspathEntry> entries;

    /** The system modules, in name order. */
    private final List<ModuleReference> systemModules;

    /** The non-system modules, in name order. */
    private final List<ModuleReference> nonSystemModules;

    /** The module path switches the JVM was launched with. */
    private final ModulePathInfo modulePathInfo;

    /** The virtual filesystem that the jarfiles were read through, in order to read their manifests. */
    private final Vfs vfs;

    /**
     * Constructor.
     *
     * @param entries
     *            the classpath elements, in the order the classloaders would search them.
     * @param classLoaderProbe
     *            the classpath finder that found the classpath.
     * @param modulePathInfo
     *            the module path switches the JVM was launched with.
     * @param vfs
     *            the virtual filesystem that the jarfiles were read through, in order to read their manifests.
     */
    Classpath(final List<ClasspathEntry> entries, final ClassLoaderProbe classLoaderProbe,
            final ModulePathInfo modulePathInfo, final Vfs vfs) {
        this.entries = List.copyOf(entries);
        this.vfs = vfs;

        final var moduleFinder = classLoaderProbe.getModuleFinder();
        this.systemModules = moduleFinder == null ? List.of()
                : List.copyOf(moduleFinder.getSystemModuleReferences());
        this.nonSystemModules = moduleFinder == null ? List.of()
                : List.copyOf(moduleFinder.getNonSystemModuleReferences());

        this.modulePathInfo = modulePathInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Closes the jarfiles that were opened to read their manifests, and deletes any temporary files that were
     * needed to open a jarfile nested inside another jarfile. The classpath elements and the modules can still be
     * read afterwards. Calling this more than once has no further effect.
     */
    @Override
    public void close() {
        vfs.close();
    }

    /**
     * Returns the classpath elements, in the order the classloaders would search them, including the elements that
     * a jarfile on the classpath declares: the jarfiles in its automatic lib dirs, and the entries of its
     * manifest's {@code Class-Path} and {@code Bundle-ClassPath} attributes. An element that is reached more than
     * once is listed only at the first position it is reached at, which is the position that decides which copy of
     * a duplicated class is loaded.
     *
     * @return the classpath elements, as an unmodifiable list.
     */
    public List<ClasspathEntry> getEntries() {
        return entries;
    }

    /**
     * Returns an iterator over the classpath elements, in the same order as {@link #getEntries()}, so that a
     * {@link Classpath} can be iterated directly.
     *
     * @return an iterator over the classpath elements.
     */
    @Override
    public Iterator<ClasspathEntry> iterator() {
        return entries.iterator();
    }

    /**
     * Returns the {@link ClasspathEntry#getLocation()} of each classpath element, in the same order as
     * {@link #getEntries()}.
     *
     * @return the classpath element locations, as an unmodifiable list.
     */
    public List<String> getLocations() {
        return entries.stream().map(ClasspathEntry::getLocation).toList();
    }

    /**
     * Returns the modules that this JVM can see, system modules first, each group in name order. Modules are listed
     * whether or not they are on the module path -- the system modules and the automatic modules created for jars
     * on the classpath are included. The list is empty unless a module source was enabled, using
     * {@link ClasspathFinder#enableModules()}, {@link ClasspathFinder#enableSystemModules()},
     * {@link ClasspathFinder#enableNonSystemModules()} or
     * {@link ClasspathFinder#enableModuleLayers(ModuleLayer...)}.
     *
     * @return the modules, as an unmodifiable list.
     */
    public List<ModuleReference> getModules() {
        final List<ModuleReference> modules = new ArrayList<>(systemModules.size() + nonSystemModules.size());
        modules.addAll(systemModules);
        modules.addAll(nonSystemModules);
        return Collections.unmodifiableList(modules);
    }

    /**
     * Returns the system modules, in name order. These are the modules whose name starts with {@code java.},
     * {@code jdk.}, {@code javafx.} or {@code oracle.}, i.e. the modules that ship with the JDK rather than the
     * ones the application brought with it.
     *
     * @return the system modules, as an unmodifiable list.
     */
    public List<ModuleReference> getSystemModules() {
        return systemModules;
    }

    /**
     * Returns the modules other than the system modules, in name order.
     *
     * @return the non-system modules, as an unmodifiable list.
     */
    public List<ModuleReference> getNonSystemModules() {
        return nonSystemModules;
    }

    /**
     * Returns the module path switches this JVM was launched with: {@code --module-path}, {@code --add-modules},
     * {@code --patch-module}, {@code --add-exports}, {@code --add-opens} and {@code --add-reads}.
     *
     * @return the module path info.
     */
    public ModulePathInfo getModulePathInfo() {
        return modulePathInfo;
    }

    /**
     * Returns the {@link Vfs} that the jarfiles on the classpath were read through, so that they can be read again
     * without being opened a second time. It opens a directory, a jarfile, or a jarfile nested inside another
     * jarfile all the same way, and it has the same settings that the {@link ClasspathFinder} was configured with,
     * so it can be passed straight to {@link ClasspathEntry#open(Vfs)} to read a classpath element, or to
     * {@link Vfs#open(java.lang.module.ModuleReference)} to read a module.
     *
     * <p>
     * The {@link Vfs} is closed by {@link #close()}, along with everything opened through it, so do not close it
     * yourself, and do not let anything it hands out escape the block that the {@link Classpath} is closed at the
     * end of.
     *
     * @return the {@link Vfs} that the jarfiles on the classpath were read through.
     */
    public Vfs getVfs() {
        return vfs;
    }

    /**
     * Returns the classpath elements and the modules, one per line.
     *
     * @return the classpath, as a string.
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
