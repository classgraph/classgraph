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
package io.github.classgraph;

import java.io.IOException;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.classgraph.Scanner.ClassfileScanWorkUnit;
import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import io.github.classgraph.base.internal.concurrency.SingletonMap;
import io.github.classgraph.base.internal.recycler.Recycler;
import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.classpath.internal.classloaderhandler.ClassLoaderHandlerRegistry;
import io.github.classgraph.internal.scanspec.ScanSpec;
import org.jspecify.annotations.Nullable;

/**
 * The modules that are not being scanned, but whose classfiles may still be read in order to complete the class
 * graph above an accepted class.
 *
 * <p>
 * Scanning is extended upwards from an accepted class to its superclasses, interfaces and annotations, so that the
 * part of the class graph above an accepted class is complete. The classfiles of those classes are looked for in
 * the classpath elements that are being scanned, but system modules are not scanned unless
 * {@link ClassGraph#enableSystemJarsAndModules()} is called, so a class hierarchy that passes through a class in a
 * system module (e.g. through {@code java.util.TimerTask}, which implements {@link Runnable}) would otherwise stop
 * at that class.
 *
 * <p>
 * Only rejecting a module prevents its classfiles from being read this way, matching how accept/reject criteria are
 * applied to classes: scanning is extended upwards to a class in a package that was not accepted, but not to a
 * class that was rejected.
 */
class UnscannedModules {
    /** The modules that are not being scanned, and that were not rejected. */
    private final List<ModuleReference> unscannedModules;

    /**
     * A singleton map from a {@link ModuleReference} to a {@link ModuleReader} recycler for the module.
     */
    private final SingletonMap<ModuleReference, Recycler<ModuleReader, IOException>, IOException> //
    moduleReaderRecyclerMap;

    /**
     * The string form of the classloader to record for each module, or null if there is none.
     */
    private final @Nullable String classLoaderStr;

    /** The scan spec. */
    private final ScanSpec scanSpec;

    /**
     * A map from the name of a package to the module that contains the package, or null until the map is built on
     * the first lookup.
     */
    private @Nullable Map<String, ModuleReference> packageNameToModule;

    /**
     * A map from a module to the classpath element that was created for it, for the modules that have been looked
     * in so far.
     */
    private final Map<ModuleReference, ClasspathElementModule> moduleToClasspathElement = new HashMap<>();

    /**
     * Constructor.
     *
     * @param unscannedModules
     *            the modules that are not being scanned, and that were not rejected
     * @param classLoaderStr
     *            the string form of the classloader to record for each module, or null if there is none
     * @param moduleReaderRecyclerMap
     *            the map from a module to its module reader recycler
     * @param scanSpec
     *            the scan spec
     */
    UnscannedModules(final List<ModuleReference> unscannedModules, final @Nullable String classLoaderStr,
            final SingletonMap<ModuleReference, Recycler<ModuleReader, IOException>, IOException> //
            moduleReaderRecyclerMap, final ScanSpec scanSpec) {
        this.unscannedModules = unscannedModules;
        this.classLoaderStr = classLoaderStr;
        this.moduleReaderRecyclerMap = moduleReaderRecyclerMap;
        this.scanSpec = scanSpec;
    }

    /**
     * Look for the classfile of a class in the modules that are not being scanned.
     *
     * @param className
     *            the name of the class
     * @param classfilePath
     *            the path of the class' classfile within a module
     * @param log
     *            the log node, or null to skip logging
     * @return a work unit for scanning the classfile, or null if the class is not in any of the modules that are
     *         not being scanned
     * @throws InterruptedException
     *             if the thread was interrupted while opening a module
     */
    synchronized @Nullable ClassfileScanWorkUnit findClassfile(final String className, final String classfilePath,
            final @Nullable LogNode log) throws InterruptedException {
        if (!scanSpec.classpathSpec.scanModules || unscannedModules.isEmpty()) {
            return null;
        }
        // A class can only be in the module that contains its package (a package is not allowed to be split across
        // two modules in the same module layer), so there is at most one module to look in
        final var packageName = PackageInfo.getParentPackageName(className);
        final var moduleReference = packageName == null ? null : packageNameToModule().get(packageName);
        if (moduleReference == null) {
            return null;
        }
        final var classpathElement = classpathElementForModule(moduleReference, log);
        final var classfileResource = classpathElement.getResource(classfilePath);
        return classfileResource == null ? null
                : new ClassfileScanWorkUnit(classpathElement, classfileResource, /* isExternalClass = */ true);
    }

    /**
     * Get the classpath elements that were created for the modules that were looked in, so that
     * {@link ClasspathElement#setScanResult(ScanResult)} can be called on them.
     *
     * @return the classpath elements
     */
    synchronized Collection<ClasspathElementModule> getClasspathElements() {
        return List.copyOf(moduleToClasspathElement.values());
    }

    /**
     * Get the map from the name of a package to the module that contains the package, building it if this is the
     * first lookup.
     *
     * @return the map from package name to module
     */
    private Map<String, ModuleReference> packageNameToModule() {
        var map = packageNameToModule;
        if (map == null) {
            map = new HashMap<>();
            for (final ModuleReference moduleReference : unscannedModules) {
                for (final String packageName : moduleReference.descriptor().packages()) {
                    // If a package is somehow in more than one module, the module that comes first in the module
                    // order wins, as it would if the modules were being scanned
                    map.putIfAbsent(packageName, moduleReference);
                }
            }
            packageNameToModule = map;
        }
        return map;
    }

    /**
     * Get the classpath element for a module that is not being scanned, opening the module if this is the first
     * time it has been looked in.
     *
     * @param moduleReference
     *            the module
     * @param log
     *            the log node, or null to skip logging
     * @return the classpath element for the module
     * @throws InterruptedException
     *             if the thread was interrupted while opening the module
     */
    private ClasspathElementModule classpathElementForModule(final ModuleReference moduleReference,
            final @Nullable LogNode log) throws InterruptedException {
        var classpathElement = moduleToClasspathElement.get(moduleReference);
        if (classpathElement == null) {
            classpathElement = new ClasspathElementModule(moduleReference, moduleReaderRecyclerMap,
                    new ClasspathEntryWorkUnit(null, classLoaderStr, null, 0, "",
                            ClassLoaderHandlerRegistry.NO_PACKAGE_ROOT_PREFIXES),
                    /* isLookupOnly = */ true, scanSpec);
            classpathElement.open(/* workQueue = */ null, log);
            moduleToClasspathElement.put(moduleReference, classpathElement);
        }
        return classpathElement;
    }
}
