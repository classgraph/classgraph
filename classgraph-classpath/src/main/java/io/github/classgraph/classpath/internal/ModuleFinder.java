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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.utils.CollectionUtils;

/**
 * Discovers modules in the searched {@link ModuleLayer}s and classifies them into system and non-system modules.
 */
public class ModuleFinder {
    /** Comparator ordering modules by descriptor name and then location URI string. */
    private static final Comparator<ModuleReference> BY_NAME_THEN_LOCATION = Comparator
            .<ModuleReference, String> comparing(moduleReference -> moduleReference.descriptor().name())
            .thenComparing(moduleReference -> moduleReference.location().map(URI::toString).orElse(""));

    /** Discovered system modules. */
    private final List<ModuleReference> systemModuleReferences;

    /** Discovered non-system modules. */
    private final List<ModuleReference> nonSystemModuleReferences;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns all discovered system modules in the searched module layers.
     *
     * @return the system modules, as an unmodifiable list.
     */
    public List<ModuleReference> getSystemModuleReferences() {
        return systemModuleReferences;
    }

    /**
     * Returns all discovered non-system modules in the searched module layers.
     *
     * @return the non-system modules, as an unmodifiable list.
     */
    public List<ModuleReference> getNonSystemModuleReferences() {
        return nonSystemModuleReferences;
    }

    /**
     * Checks whether {@code moduleReference} is a JDK system module ({@code java.*}, {@code jdk.*},
     * {@code javafx.*}, or {@code oracle.*} located at the same URI in {@code systemModuleFinder}).
     *
     * @param moduleReference
     *            the module reference to classify.
     * @param systemModuleFinder
     *            the system module finder ({@link java.lang.module.ModuleFinder#ofSystem()}).
     * @return true if {@code moduleReference} is a system module.
     */
    static boolean isSystemModule(final ModuleReference moduleReference,
            final java.lang.module.ModuleFinder systemModuleFinder) {
        final var name = moduleReference.descriptor().name();
        if (!(name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("javafx.")
                || name.startsWith("oracle."))) {
            return false;
        }
        final var systemReference = systemModuleFinder.find(name).orElse(null);
        return systemReference != null && systemReference.location().equals(moduleReference.location());
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Traverses {@code layer} and its parent layers in depth-first preorder (child layers before parent layers) and
     * appends unvisited layers to {@code layerOrderOut}.
     *
     * @param layer
     *            the starting module layer.
     * @param layerVisited
     *            set of already-visited layers.
     * @param parentLayers
     *            set collecting all parent layers encountered.
     * @param layerOrderOut
     *            list receiving layers in resolution order.
     */
    private static void findLayerOrder(final ModuleLayer layer, final Set<ModuleLayer> layerVisited,
            final Set<ModuleLayer> parentLayers, final List<ModuleLayer> layerOrderOut) {
        final var pending = new ArrayDeque<ModuleLayer>();
        pending.push(layer);
        while (!pending.isEmpty()) {
            final var current = pending.pop();
            if (!layerVisited.add(current)) {
                continue;
            }
            layerOrderOut.add(current);
            final var parents = current.parents();
            parentLayers.addAll(parents);
            // Reverse the push order to preserve the caller's parent order in depth-first preorder.
            for (var i = parents.size() - 1; i >= 0; i--) {
                pending.push(parents.get(i));
            }
        }
    }

    /**
     * Collects all unique {@link ModuleReference}s from {@code layers} in layer resolution order, sorted by name
     * within each layer.
     *
     * @param layers
     *            the starting module layers.
     * @param classpathSpec
     *            the classpath configuration.
     * @return the ordered list of module references.
     */
    private static List<ModuleReference> findModuleReferences(final LinkedHashSet<ModuleLayer> layers,
            final ClasspathSpec classpathSpec) {
        if (layers.isEmpty()) {
            return List.of();
        }

        // Traverse the layer DAG to find the layer resolution order
        final List<ModuleLayer> layerOrder = new ArrayList<>();
        final Set<ModuleLayer> parentLayers = new HashSet<>();
        final Set<ModuleLayer> layerVisited = new HashSet<>();
        for (final ModuleLayer layer : layers) {
            findLayerOrder(layer, layerVisited, parentLayers, layerOrder);
        }

        // Remove parent layers from layer order if ignoreParentModuleLayers() was called
        final List<ModuleLayer> layerOrderFinal;
        if (classpathSpec.isParentModuleLayersIgnored()) {
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
                // A module that is resolved in more than one layer is only listed once, in the first layer that
                // resolves it, which is the layer whose loader would reach it first
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
     * Adds module layers from {@code callStackInfo} and {@link ModuleLayer#boot()} to {@code layersOut}.
     *
     * @param callStackInfo
     *            the call stack info.
     * @param layersOut
     *            the set receiving the detected module layers.
     */
    private static void findDetectedModuleLayers(final CallStackInfo callStackInfo,
            final LinkedHashSet<ModuleLayer> layersOut) {
        layersOut.addAll(callStackInfo.getModuleLayers());
        // Add the boot layer, unless a class on the call stack was already in it
        layersOut.add(ModuleLayer.boot());
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Discovers modules in the configured module layers.
     *
     * @param callStackInfo
     *            the call stack info.
     * @param classpathSpec
     *            the classpath configuration.
     * @param scanSourceSpec
     *            the configured module layer sources.
     * @param log
     *            the log node, or null to skip logging.
     */
    public ModuleFinder(final CallStackInfo callStackInfo, final ClasspathSpec classpathSpec,
            final ScanSourceSpec scanSourceSpec, final @Nullable LogNode log) {
        final var scanSystemModules = classpathSpec.isSystemModulesEnabled();
        final var scanNonSystemModules = classpathSpec.isNonSystemModulesEnabled();

        // Find the layers to search: the ones the caller named, or, if the caller named none, the ones detected in
        // the environment
        final LinkedHashSet<ModuleLayer> layers = new LinkedHashSet<>();
        if (scanSourceSpec.searchesDetectedModuleLayers(classpathSpec)) {
            findDetectedModuleLayers(callStackInfo, layers);
        }
        final var namedModuleLayers = scanSourceSpec.getNamedModuleLayers();
        if (namedModuleLayers != null) {
            if (log != null) {
                final var subLog = log.log("Module layers given by the caller:");
                for (final ModuleLayer moduleLayer : namedModuleLayers) {
                    subLog.log(moduleLayer.toString());
                }
            }
            layers.addAll(namedModuleLayers);
        }

        // Get the module resolution order
        final var allModuleReferences = findModuleReferences(layers, classpathSpec);

        // Split modules into system modules and non-system modules
        final List<ModuleReference> systemModules = new ArrayList<>();
        final List<ModuleReference> nonSystemModules = new ArrayList<>();
        final var systemModuleFinder = java.lang.module.ModuleFinder.ofSystem();
        for (final ModuleReference moduleReference : allModuleReferences) {
            // Both kinds of module are listed whether or not they are going to be scanned, since the classfile of a
            // class in a module that is not being scanned may still be read, in order to complete the class graph
            // above an accepted class (#902). Classpath and Scanner drop or skip the kinds that were not enabled.
            if (isSystemModule(moduleReference, systemModuleFinder)) {
                systemModules.add(moduleReference);
            } else {
                nonSystemModules.add(moduleReference);
            }
        }
        systemModuleReferences = List.copyOf(systemModules);
        nonSystemModuleReferences = List.copyOf(nonSystemModules);

        // Log any identified modules
        if (log != null) {
            if (scanSystemModules) {
                final var sysSubLog = log.log("System modules found:");
                if (!systemModuleReferences.isEmpty()) {
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
                if (!nonSystemModuleReferences.isEmpty()) {
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
