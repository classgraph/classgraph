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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.classgraph.base.LogNode;
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
    private final List<ModuleReference> systemModuleReferences;

    /** The non-system modules. */
    private final List<ModuleReference> nonSystemModuleReferences;

    /**
     * If true, must forcibly scan {@code java.class.path}, since there was an anonymous module layer.
     */
    private boolean forceScanJavaClassPath;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the system modules. All visible system modules are listed, whether or not they are going to be scanned.
     *
     * @return The system modules, or an empty list if none were found.
     */
    public List<ModuleReference> getSystemModuleReferences() {
        return systemModuleReferences;
    }

    /**
     * Get the non-system modules.
     *
     * @return The non-system modules, or an empty list if none were found.
     */
    public List<ModuleReference> getNonSystemModuleReferences() {
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
     * Recursively append a layer and then its ancestors to the layer order, skipping any layer that is already in
     * the order.
     *
     * <p>
     * A layer's own modules are searched before its parent layers' modules, which is the reverse of the classloader
     * axis, where a parent classloader is searched before its children. The classloader that
     * {@link ModuleLayer#defineModulesWithOneLoader} creates is a {@code jdk.internal.loader.Loader}, and its
     * {@code loadClass} looks the package up in {@code localPackageToModule} -- the modules defined to that loader,
     * i.e. this layer's own -- and only if that misses does it consult {@code remotePackageToLoader} (the parent
     * layers' modules) and then the parent classloader. This is observable: a child layer may define a module with
     * the same name as one in a parent layer, and the child layer's loader then resolves the shared package to the
     * child's copy. So the layer is appended before its parents are.
     *
     * <p>
     * The shared {@code layerVisited} set keeps a layer from being appended twice when the caller names both a
     * layer and one of its ancestors. A layer named directly still takes the position its own name gives it, which
     * is what naming it asks for; it is only reached indirectly, through {@link ModuleLayer#parents()}, if the
     * caller did not name it.
     *
     * <p>
     * (The JDK (as of 10.0.0.1) uses a broken (non-topological) DFS ordering for layer resolution in
     * ModuleLayer#layers() and Configuration#configurations() but when I reported this bug on the Jigsaw mailing
     * list, Alan didn't see what the problem was.)
     *
     * @param layer
     *            the layer
     * @param layerVisited
     *            the layers already appended to {@code layerOrderOut}, shared across all the top-level layers
     * @param parentLayers
     *            the parent layers
     * @param layerOrderOut
     *            the layer order
     */
    private static void findLayerOrder(final ModuleLayer layer, final Set<ModuleLayer> layerVisited,
            final Set<ModuleLayer> parentLayers, final List<ModuleLayer> layerOrderOut) {
        if (layerVisited.add(layer)) {
            layerOrderOut.add(layer);
            final var parents = layer.parents();
            parentLayers.addAll(parents);
            for (final ModuleLayer parent : parents) {
                findLayerOrder(parent, layerVisited, parentLayers, layerOrderOut);
            }
        }
    }

    /**
     * Get all visible ModuleReferences in a list of layers.
     *
     * @param layers
     *            the layers
     * @param classpathSpec
     *            the scan spec
     * @return the list
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
     * Find the module layers of the classes on the callstack, and the boot layer.
     *
     * @param callStack
     *            the call stack
     * @param scanNonSystemModules
     *            whether the non-system modules are going to be scanned
     * @param layersOut
     *            the set to add the layers to
     */
    private void findDetectedModuleLayers(final Class<?>[] callStack, final boolean scanNonSystemModules,
            final LinkedHashSet<ModuleLayer> layersOut) {
        for (final Class<?> stackFrameClass : callStack) {
            final var layer = stackFrameClass.getModule().getLayer();
            if (layer != null) {
                layersOut.add(layer);
            } else if (scanNonSystemModules) {
                // getLayer() returns null for unnamed modules -- in that case the classes are on
                // java.class.path, so java.class.path has to be scanned to find them
                forceScanJavaClassPath = true;
            }
        }
        // Add system modules from boot layer, if they weren't already found in stacktrace
        layersOut.add(ModuleLayer.boot());
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the modules of the module layers that the caller enabled.
     *
     * @param callStack
     *            the callstack.
     * @param classpathSpec
     *            The scan spec, which says which kinds of module are going to be scanned.
     * @param scanSourceSpec
     *            The places to look for classpath elements and modules.
     * @param log
     *            The log.
     */
    public ModuleFinder(final Class<?>[] callStack, final ClasspathSpec classpathSpec,
            final ScanSourceSpec scanSourceSpec, final @Nullable LogNode log) {
        final var scanSystemModules = classpathSpec.scanSystemModules;
        final var scanNonSystemModules = classpathSpec.scanNonSystemModules;

        // Find the layers to search: the ones detected in the environment, if they were enabled, followed by the
        // ones the caller named. A layer that both of them reach is searched at the first position it is reached at.
        final LinkedHashSet<ModuleLayer> layers = new LinkedHashSet<>();
        if (scanSourceSpec.searchDetectedModuleLayers) {
            findDetectedModuleLayers(callStack, scanNonSystemModules, layers);
        }
        final var namedModuleLayers = scanSourceSpec.namedModuleLayers;
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
