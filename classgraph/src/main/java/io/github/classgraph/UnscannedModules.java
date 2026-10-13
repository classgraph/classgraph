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
package io.github.classgraph;

import java.lang.module.ModuleReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.Scanner.ClassfileScanWorkUnit;
import io.github.classgraph.base.LogNode;
import io.github.classgraph.vfs.Vfs;

/**
 * The modules that are not being scanned, but whose classfiles may still be read in order to complete the class
 * graph above an accepted class.
 *
 * <p>
 * Scanning is extended upwards from an accepted class to its superclasses, interfaces and annotations, so that the
 * part of the class graph above an accepted class is complete. The classfiles of those classes are looked for in
 * the classpath elements that are being scanned, but system modules are not scanned unless
 * {@link ClassGraph#enableSystemModules()} is called, and non-system modules are not scanned unless
 * {@link ClassGraph#enableNonSystemModules()} is called, so a class hierarchy that passes through a class in a
 * module (e.g. through {@code java.util.TimerTask}, which implements {@link Runnable}) would otherwise stop at that
 * class.
 *
 * <p>
 * Only rejecting a module prevents its classfiles from being read this way. The accept and reject criteria for
 * classes do not: scanning is extended upwards to a class in a package that was not accepted, and to a class that
 * was rejected by name or by package, which the queries then leave out of their results.
 */
class UnscannedModules {
    /** The modules that are not being scanned, and that were not rejected. */
    private final List<ModuleReference> unscannedModules;

    /** The virtual filesystem that the modules are read through. */
    private final Vfs vfs;

    /**
     * The string form of the classloader to record for each module, or null if there is none.
     */
    private final @Nullable String classLoaderStr;

    /** The scan spec. */
    private final ScanSpec scanSpec;

    /**
     * A map from the name of a package to the modules that contain the package, or null until the map is built on
     * the first lookup.
     */
    private @Nullable Map<String, List<ModuleReference>> packageNameToModule;

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
     * @param vfs
     *            the virtual filesystem to read the modules through
     * @param scanSpec
     *            the scan spec
     */
    UnscannedModules(final List<ModuleReference> unscannedModules, final @Nullable String classLoaderStr,
            final Vfs vfs, final ScanSpec scanSpec) {
        this.unscannedModules = unscannedModules;
        this.classLoaderStr = classLoaderStr;
        this.vfs = vfs;
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
     */
    synchronized @Nullable ClassfileScanWorkUnit findClassfile(final String className, final String classfilePath,
            final @Nullable LogNode log) {
        if (unscannedModules.isEmpty()) {
            return null;
        }
        // Separate layers may contain modules with the same package. Search each candidate in module order:
        // first-wins applies to class names, not to every class in a package.
        final var packageName = PackageInfo.getParentPackageName(className);
        final var moduleReferences = packageName == null ? null : packageNameToModule().get(packageName);
        if (moduleReferences == null) {
            return null;
        }
        for (final var moduleReference : moduleReferences) {
            final var classpathElement = classpathElementForModule(moduleReference, log);
            final var classfileResource = classpathElement.getResource(classfilePath);
            if (classfileResource != null) {
                return new ClassfileScanWorkUnit(classpathElement, classfileResource, /* isExternalClass = */ true);
            }
        }
        return null;
    }

    /**
     * Get the classpath elements that were created for the modules that were looked in, in module order (not in the
     * order the modules were first looked in, which depends on thread timing).
     *
     * @return the classpath elements
     */
    synchronized List<ClasspathElementModule> getClasspathElements() {
        final List<ClasspathElementModule> classpathElements = new ArrayList<>(moduleToClasspathElement.size());
        for (final ModuleReference moduleReference : unscannedModules) {
            final var classpathElement = moduleToClasspathElement.get(moduleReference);
            if (classpathElement != null) {
                classpathElements.add(classpathElement);
            }
        }
        return classpathElements;
    }

    /**
     * Get the map from the name of a package to the modules that contain the package, building it if this is the
     * first lookup.
     *
     * @return the map from package name to modules, in module order
     */
    private Map<String, List<ModuleReference>> packageNameToModule() {
        var map = packageNameToModule;
        if (map == null) {
            map = new HashMap<>();
            for (final ModuleReference moduleReference : unscannedModules) {
                for (final String packageName : moduleReference.descriptor().packages()) {
                    var modules = map.get(packageName);
                    if (modules == null) {
                        modules = new ArrayList<>();
                        map.put(packageName, modules);
                    }
                    modules.add(moduleReference);
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
     */
    private ClasspathElementModule classpathElementForModule(final ModuleReference moduleReference,
            final @Nullable LogNode log) {
        var classpathElement = moduleToClasspathElement.get(moduleReference);
        if (classpathElement == null) {
            classpathElement = new ClasspathElementModule(moduleReference, vfs, classLoaderStr,
                    /* isLookupOnly = */ true, scanSpec);
            classpathElement.open(/* workQueue = */ null, log);
            moduleToClasspathElement.put(moduleReference, classpathElement);
        }
        return classpathElement;
    }
}
