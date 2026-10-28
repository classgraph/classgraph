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
 * Non-rejected modules that are not being scanned in full, but from which individual classfiles may be read when
 * extending the class graph upwards from an accepted class.
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
     * Get the classpath elements created for modules that were accessed, in module order.
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
     * Get or lazily build the map from package name to the modules containing that package.
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
     * Get or open the classpath element for an unscanned module.
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
