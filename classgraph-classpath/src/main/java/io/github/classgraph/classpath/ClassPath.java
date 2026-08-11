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
package io.github.classgraph.classpath;

import java.lang.module.ModuleReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nonapi.io.github.classgraph.classpath.ClasspathFinder;

/**
 * Where a JVM loads its classes and resources from: the classpath elements and the modules that were found by a
 * {@link ClassPathFinder}.
 */
public class ClassPath {
    /** The classpath elements, in the order the classloaders would search them. */
    private final List<ClassPathEntry> entries;

    /** The system modules, in name order. */
    private final List<ModuleReference> systemModules;

    /** The non-system modules, in name order. */
    private final List<ModuleReference> nonSystemModules;

    /** The module path switches the JVM was launched with. */
    private final ModulePathInfo modulePathInfo;

    /**
     * Constructor.
     *
     * @param classpathFinder
     *            the classpath finder that found the classpath.
     * @param modulePathInfo
     *            the module path switches the JVM was launched with.
     */
    ClassPath(final ClasspathFinder classpathFinder, final ModulePathInfo modulePathInfo) {
        final List<ClassPathEntry> entriesTmp = new ArrayList<>();
        for (final var entry : classpathFinder.getClasspathOrder().getOrder()) {
            entriesTmp.add(new ClassPathEntry(entry.classpathEntryObj.toString(), entry.getClassLoaderString(),
                    List.of(entry.packageRootPrefixes)));
        }
        this.entries = Collections.unmodifiableList(entriesTmp);

        final var moduleFinder = classpathFinder.getModuleFinder();
        final var systemModulesTmp = moduleFinder == null ? null : moduleFinder.getSystemModuleReferences();
        final var nonSystemModulesTmp = moduleFinder == null ? null : moduleFinder.getNonSystemModuleReferences();
        this.systemModules = systemModulesTmp == null ? List.of() : List.copyOf(systemModulesTmp);
        this.nonSystemModules = nonSystemModulesTmp == null ? List.of() : List.copyOf(nonSystemModulesTmp);

        this.modulePathInfo = modulePathInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the classpath elements, in the order the classloaders would search them. Elements reached through
     * more than one classloader are listed once, at the position of the first classloader that reaches them.
     *
     * @return the classpath elements, as an unmodifiable list.
     */
    public List<ClassPathEntry> getEntries() {
        return entries;
    }

    /**
     * Returns the {@link ClassPathEntry#location()} of each classpath element, in the same order as
     * {@link #getEntries()}.
     *
     * @return the classpath element locations, as an unmodifiable list.
     */
    public List<String> getLocations() {
        return entries.stream().map(ClassPathEntry::location).toList();
    }

    /**
     * Returns the modules that this JVM can see, system modules first, each group in name order. Modules are listed
     * whether or not they are on the module path -- the system modules and the automatic modules created for jars
     * on the classpath are included. The list is empty if {@link ClassPathFinder#ignoreModules()} was called, or if
     * the classpath was overridden.
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
     * Returns the classpath elements and the modules, one per line.
     *
     * @return the classpath, as a string.
     */
    @Override
    public String toString() {
        final var buf = new StringBuilder(1024);
        for (final ClassPathEntry entry : entries) {
            buf.append(entry).append('\n');
        }
        for (final ModuleReference module : getModules()) {
            buf.append(module.descriptor().name())
                    .append(module.location().map(location -> " [" + location + "]").orElse("")).append('\n');
        }
        return buf.toString();
    }
}
