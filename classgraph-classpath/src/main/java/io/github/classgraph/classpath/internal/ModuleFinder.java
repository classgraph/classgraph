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

import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.classgraph.base.internal.log.LogNode;
import io.github.classgraph.base.internal.utils.CollectionUtils;
import org.jspecify.annotations.Nullable;

/** A class to find the visible modules. */
public class ModuleFinder {
    /**
     * Sorts modules by name, with the location as a tiebreaker, so that the module order does not depend on hash
     * ordering.
     */
    private static final Comparator<ModuleReference> BY_NAME_THEN_LOCATION = Comparator
            .<ModuleReference, String> comparing(moduleReference -> moduleReference.descriptor().name())
            .thenComparing(moduleReference -> moduleReference.location().map(URI::toString).orElse(""));

    /** The system modules. */
    private @Nullable List<ModuleReference> systemModuleReferences;

    /** The non-system modules. */
    private @Nullable List<ModuleReference> nonSystemModuleReferences;

    /**
     * If true, must forcibly scan {@code java.class.path}, since there was an anonymous module layer.
     */
    private boolean forceScanJavaClassPath;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the system modules. All visible system modules are listed, whether or not they are going to be scanned.
     *
     * @return The system modules, or null if no modules were found.
     */
    public @Nullable List<ModuleReference> getSystemModuleReferences() {
        return systemModuleReferences;
    }

    /**
     * Get the non-system modules.
     *
     * @return The non-system modules, or null if no modules were found.
     */
    public @Nullable List<ModuleReference> getNonSystemModuleReferences() {
        return nonSystemModuleReferences;
    }

    /**
     * Check if a module is a system module, based on its name.
     *
     * @param moduleName
     *            the module name
     * @return true if this is a system module.
     */
    private static boolean isSystemModule(final String moduleName) {
        return moduleName.startsWith("java.") || moduleName.startsWith("jdk.") || moduleName.startsWith("javafx.")
                || moduleName.startsWith("oracle.");
    }

    /**
     * Force scan java class path.
     *
     * @return If true, must forcibly scan {@code java.class.path}, since there was an anonymous module layer.
     */
    public boolean forceScanJavaClassPath() {
        return forceScanJavaClassPath;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Recursively find the topological sort order of ancestral layers.
     *
     * <p>
     * (The JDK (as of 10.0.0.1) uses a broken (non-topological) DFS ordering for layer resolution in
     * ModuleLayer#layers() and Configuration#configurations() but when I reported this bug on the Jigsaw mailing
     * list, Alan didn't see what the problem was.)
     *
     * @param layer
     *            the layer
     * @param layerVisited
     *            layer visited
     * @param parentLayers
     *            the parent layers
     * @param layerOrderOut
     *            the layer order
     */
    private static void findLayerOrder(final ModuleLayer layer, final Set<ModuleLayer> layerVisited,
            final Set<ModuleLayer> parentLayers, final Deque<ModuleLayer> layerOrderOut) {
        if (layerVisited.add(layer)) {
            final var parents = layer.parents();
            parentLayers.addAll(parents);
            for (final ModuleLayer parent : parents) {
                findLayerOrder(parent, layerVisited, parentLayers, layerOrderOut);
            }
            layerOrderOut.push(layer);
        }
    }

    /**
     * Get all visible ModuleReferences in a list of layers.
     *
     * @param layers
     *            the layers
     * @param classpathSpec
     *            the scan spec
     * @param classLoaderAndModuleLayerSpec
     *            the classloaders and module layers the caller asked to be scanned
     * @return the list
     */
    private static List<ModuleReference> findModuleReferences(final LinkedHashSet<ModuleLayer> layers,
            final ClasspathSpec classpathSpec, final ClassLoaderAndModuleLayerSpec classLoaderAndModuleLayerSpec) {
        if (layers.isEmpty()) {
            return List.of();
        }

        // Traverse the layer DAG to find the layer resolution order
        final Deque<ModuleLayer> layerOrder = new ArrayDeque<>();
        final Set<ModuleLayer> parentLayers = new HashSet<>();
        for (final ModuleLayer layer : layers) {
            findLayerOrder(layer, /* layerVisited = */ new HashSet<>(), parentLayers, layerOrder);
        }
        if (classLoaderAndModuleLayerSpec.addedModuleLayers != null) {
            for (final ModuleLayer layer : classLoaderAndModuleLayerSpec.addedModuleLayers) {
                findLayerOrder(layer, /* layerVisited = */ new HashSet<>(), parentLayers, layerOrder);
            }
        }

        // Remove parent layers from layer order if classpathSpec.ignoreParentModuleLayers is true
        final List<ModuleLayer> layerOrderFinal;
        if (classpathSpec.ignoreParentModuleLayers) {
            layerOrderFinal = new ArrayList<>();
            for (final ModuleLayer layer : layerOrder) {
                if (!parentLayers.contains(layer)) {
                    layerOrderFinal.add(layer);
                }
            }
        } else {
            layerOrderFinal = new ArrayList<>(layerOrder);
        }

        // Find modules in the ordered layers
        final Set<ModuleReference> addedModules = new HashSet<>();
        final List<ModuleReference> moduleOrder = new ArrayList<>();
        for (final ModuleLayer layer : layerOrderFinal) {
            // Get ModuleReferences from layer configuration
            final List<ModuleReference> modulesInLayer = new ArrayList<>();
            for (final ResolvedModule module : layer.configuration().modules()) {
                final var moduleReference = module.reference();
                // A module that is resolved in more than one layer is only listed once, in the first layer
                // that resolves it
                if (addedModules.add(moduleReference)) {
                    modulesInLayer.add(moduleReference);
                }
            }
            // Sort modules in layer by name
            CollectionUtils.sortIfNotEmpty(modulesInLayer, BY_NAME_THEN_LOCATION);
            moduleOrder.addAll(modulesInLayer);
        }
        return moduleOrder;
    }

    /**
     * Get all visible ModuleReferences in all layers, given an array of stack frame {@code Class<?>} references.
     *
     * @param callStack
     *            the call stack
     * @param classpathSpec
     *            the scan spec
     * @param classLoaderAndModuleLayerSpec
     *            the classloaders and module layers the caller asked to be scanned
     * @param scanNonSystemModules
     *            whether to include unnamed and non-system modules
     * @return the list
     */
    private List<ModuleReference> findModuleReferencesFromCallstack(final Class<?>[] callStack,
            final ClasspathSpec classpathSpec, final ClassLoaderAndModuleLayerSpec classLoaderAndModuleLayerSpec,
            final boolean scanNonSystemModules) {
        final LinkedHashSet<ModuleLayer> layers = new LinkedHashSet<>();
        for (final Class<?> stackFrameClass : callStack) {
            final var layer = stackFrameClass.getModule().getLayer();
            if (layer != null) {
                layers.add(layer);
            } else if (scanNonSystemModules) {
                // getLayer() returns null for unnamed modules -- in that case the classes are on
                // java.class.path, so java.class.path has to be scanned to find them
                forceScanJavaClassPath = true;
            }
        }
        // Add system modules from boot layer, if they weren't already found in stacktrace
        layers.add(ModuleLayer.boot());
        return findModuleReferences(layers, classpathSpec, classLoaderAndModuleLayerSpec);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A class to find the visible modules.
     *
     * @param callStack
     *            the callstack.
     * @param classpathSpec
     *            The scan spec.
     * @param classLoaderAndModuleLayerSpec
     *            The classloaders and module layers the caller asked to be scanned.
     * @param scanNonSystemModules
     *            whether to scan unnamed and non-system modules
     * @param scanSystemModules
     *            whether system modules are going to be scanned (system modules are listed either way, see
     *            {@link #getSystemModuleReferences()})
     * @param log
     *            The log.
     */
    public ModuleFinder(final Class<?>[] callStack, final ClasspathSpec classpathSpec,
            final ClassLoaderAndModuleLayerSpec classLoaderAndModuleLayerSpec, final boolean scanNonSystemModules,
            final boolean scanSystemModules, final @Nullable LogNode log) {
        // Get the module resolution order
        List<ModuleReference> allModuleReferences = null;
        final var overrideModuleLayers = classLoaderAndModuleLayerSpec.overrideModuleLayers;
        if (overrideModuleLayers == null) {
            // Find module references for classes on the callstack, and from the boot layer
            if (callStack.length > 0) {
                allModuleReferences = findModuleReferencesFromCallstack(callStack, classpathSpec,
                        classLoaderAndModuleLayerSpec, scanNonSystemModules);
            }
        } else {
            if (log != null) {
                final var subLog = log.log("Overriding module layers");
                for (final ModuleLayer moduleLayer : overrideModuleLayers) {
                    subLog.log(moduleLayer.toString());
                }
            }
            allModuleReferences = findModuleReferences(new LinkedHashSet<>(overrideModuleLayers), classpathSpec,
                    classLoaderAndModuleLayerSpec);
        }
        if (allModuleReferences != null) {
            // Split modules into system modules and non-system modules
            systemModuleReferences = new ArrayList<>();
            nonSystemModuleReferences = new ArrayList<>();
            for (final ModuleReference moduleReference : allModuleReferences) {
                if (isSystemModule(moduleReference.descriptor().name())) {
                    // System modules are listed whether or not they are going to be scanned, since the classfile of
                    // a class in a system module that is not being scanned may still be read, in order to complete
                    // the class graph above an accepted class (#902)
                    systemModuleReferences.add(moduleReference);
                } else if (scanNonSystemModules) {
                    nonSystemModuleReferences.add(moduleReference);
                }
            }
        }
        // Log any identified modules
        if (log != null) {
            if (scanSystemModules) {
                final var sysSubLog = log.log("System modules found:");
                if (systemModuleReferences != null && !systemModuleReferences.isEmpty()) {
                    for (final ModuleReference moduleReference : systemModuleReferences) {
                        sysSubLog.log(moduleReference.toString());
                    }
                } else {
                    sysSubLog.log("[None]");
                }
            } else {
                log.log("Scanning of system modules is not enabled");
            }
            if (scanNonSystemModules) {
                final var nonSysSubLog = log.log("Non-system modules found:");
                if (nonSystemModuleReferences != null && !nonSystemModuleReferences.isEmpty()) {
                    for (final ModuleReference moduleReference : nonSystemModuleReferences) {
                        nonSysSubLog.log(moduleReference.toString());
                    }
                } else {
                    nonSysSubLog.log("[None]");
                }
            } else {
                log.log("Scanning of non-system modules is not enabled");
            }
        }
    }
}
