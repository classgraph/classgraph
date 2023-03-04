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
package nonapi.io.github.classgraph.classpath;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.classgraph.ModuleRef;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.CollectionUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** A class to find the visible modules. */
public class ModuleFinder {
    /** The system module refs. */
    private List<ModuleRef> systemModuleRefs;

    /** The non system module refs. */
    private List<ModuleRef> nonSystemModuleRefs;

    /** If true, must forcibly scan {@code java.class.path}, since there was an anonymous module layer. */
    private boolean forceScanJavaClassPath;

    private final ReflectionUtils reflectionUtils;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the system modules as {@link ModuleRef} wrappers.
     *
     * @return The system modules as {@link ModuleRef} wrappers, or null if no modules were found (e.g. on JDK 7 or
     *         8).
     */
    public List<ModuleRef> getSystemModuleRefs() {
        return systemModuleRefs;
    }

    /**
     * Get the non-system modules as {@link ModuleRef} wrappers.
     *
     * @return The non-system modules as {@link ModuleRef} wrappers, or null if no modules were found (e.g. on JDK 7
     *         or 8).
     */
    public List<ModuleRef> getNonSystemModuleRefs() {
        return nonSystemModuleRefs;
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
    private void findLayerOrder(final Object /* ModuleLayer */ layer,
            final Set<Object> /* Set<ModuleLayer> */ layerVisited,
            final Set<Object> /* Set<ModuleLayer> */ parentLayers,
            final Deque<Object> /* Deque<ModuleLayer> */ layerOrderOut) {
        if (layerVisited.add(layer)) {
            @SuppressWarnings("unchecked")
            final List<Object> /* List<ModuleLayer> */ parents = (List<Object>) reflectionUtils
                    .invokeMethod(/* throwException = */ true, layer, "parents");
            if (parents != null) {
                parentLayers.addAll(parents);
                for (final Object parent : parents) {
                    findLayerOrder(parent, layerVisited, parentLayers, layerOrderOut);
                }
            }
            layerOrderOut.push(layer);
        }
    }

    /**
     * Get all visible ModuleReferences in a list of layers.
     *
     * @param layers
     *            the layers
     * @param scanSpec
     *            the scan spec
     * @param log
     *            the log
     * @return the list
     */
    private List<ModuleRef> findModuleRefs(final LinkedHashSet<Object> layers, final ScanSpec scanSpec,
            final LogNode log) {
        if (layers.isEmpty()) {
            return Collections.emptyList();
        }

        // Traverse the layer DAG to find the layer resolution order
        final Deque<Object> /* Deque<ModuleLayer> */ layerOrder = new ArrayDeque<>();
        final Set<Object> /* Set<ModuleLayer */ parentLayers = new HashSet<>();
        for (final Object layer : layers) {
            if (layer != null) {
                findLayerOrder(layer, /* layerVisited = */ new HashSet<>(), parentLayers, layerOrder);
            }
        }
        if (scanSpec.addedModuleLayers != null) {
            for (final Object layer : scanSpec.addedModuleLayers) {
                if (layer != null) {
                    findLayerOrder(layer, /* layerVisited = */ new HashSet<>(), parentLayers, layerOrder);
                }
            }
        }

        // Remove parent layers from layer order if scanSpec.ignoreParentModuleLayers is true
        List<Object> /* List<ModuleLayer> */ layerOrderFinal;
        if (scanSpec.ignoreParentModuleLayers) {
            layerOrderFinal = new ArrayList<>();
            for (final Object layer : layerOrder) {
                if (!parentLayers.contains(layer)) {
                    layerOrderFinal.add(layer);
                }
            }
        } else {
            layerOrderFinal = new ArrayList<>(layerOrder);
        }

        // Find modules in the ordered layers
        final Set<Object> /* Set<ModuleReference> */ addedModules = new HashSet<>();
        final LinkedHashSet<ModuleRef> moduleRefOrder = new LinkedHashSet<>();
        for (final Object /* ModuleLayer */ layer : layerOrderFinal) {
            final Object /* Configuration */ configuration = reflectionUtils
                    .invokeMethod(/* throwException = */ true, layer, "configuration");
            if (configuration != null) {
                // Get ModuleReferences from layer configuration
                @SuppressWarnings("unchecked")
                final Set<Object> /* Set<ResolvedModule> */ modules = (Set<Object>) reflectionUtils
                        .invokeMethod(/* throwException = */ true, configuration, "modules");
                if (modules != null) {
                    final List<ModuleRef> modulesInLayer = new ArrayList<>();
                    for (final Object /* ResolvedModule */ module : modules) {
                        final Object /* ModuleReference */ moduleReference = reflectionUtils
                                .invokeMethod(/* throwException = */ true, module, "reference");
                        if (moduleReference != null && addedModules.add(moduleReference)) {
                            try {
                                modulesInLayer.add(new ModuleRef(moduleReference, layer, reflectionUtils));
                            } catch (final IllegalArgumentException e) {
                                if (log != null) {
                                    log.log("Exception while creating ModuleRef for module " + moduleReference, e);
                                }
                            }
                        }
                    }
                    // Sort modules in layer by name
                    CollectionUtils.sortIfNotEmpty(modulesInLayer);
                    moduleRefOrder.addAll(modulesInLayer);
                }
            }
        }
        return new ArrayList<>(moduleRefOrder);
    }

    /**
     * Get all visible ModuleReferences in all layers, given an array of stack frame {@code Class<?>} references.
     *
     * @param callStack
     *            the call stack
     * @param scanSpec
     *            the scan spec
     * @param scanNonSystemModules
     *            whether to include unnamed and non-system modules
     * @param log
     *            the log
     * @return the list
     */
    private List<ModuleRef> findModuleRefsFromCallstack(final Class<?>[] callStack, final ScanSpec scanSpec,
            final boolean scanNonSystemModules, final LogNode log) {
        final LinkedHashSet<Object> layers = new LinkedHashSet<>();
        if (callStack != null) {
            for (final Class<?> stackFrameClass : callStack) {
                final Object /* Module */ module = reflectionUtils.invokeMethod(/* throwException = */ false,
                        stackFrameClass, "getModule");
                if (module != null) {
                    final Object /* ModuleLayer */ layer = reflectionUtils.invokeMethod(/* throwException = */ true,
                            module, "getLayer");
                    if (layer != null) {
                        layers.add(layer);
                    } else if (scanNonSystemModules) {
                        // getLayer() returns null for unnamed modules -- still add null to list if it is returned,
                        // so we can get classes from java.class.path 
                        forceScanJavaClassPath = true;
                    }
                }
            }
        }
        // Add system modules from boot layer, if they weren't already found in stacktrace
        Class<?> moduleLayerClass = null;
        try {
            moduleLayerClass = Class.forName("java.lang.ModuleLayer");
        } catch (ClassNotFoundException | LinkageError e) {
            // Ignored
        }
        if (moduleLayerClass != null) {
            final Object /* ModuleLayer */ bootLayer = reflectionUtils
                    .invokeStaticMethod(/* throwException = */ false, moduleLayerClass, "boot");
            if (bootLayer != null) {
                layers.add(bootLayer);
            } else if (scanNonSystemModules) {
                // getLayer() returns null for unnamed modules -- still add null to list if it is returned,
                // so we can get classes from java.class.path. (I'm not sure if the boot layer can ever
                // actually be null, but this is here for completeness.)
                forceScanJavaClassPath = true;
            }
        }
        return findModuleRefs(layers, scanSpec, log);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A class to find the visible modules.
     *
     * @param callStack
     *            the callstack.
     * @param scanSpec
     *            The scan spec.
     * @param scanNonSystemModules
     *            whether to scan unnamed and non-system modules
     * @param scanSystemModules
     *            whether to scan system modules
     * @param log
     *            The log.
     */
    public ModuleFinder(final Class<?>[] callStack, final ScanSpec scanSpec, final boolean scanNonSystemModules,
            final boolean scanSystemModules, final ReflectionUtils reflectionUtils, final LogNode log) {
        this.reflectionUtils = reflectionUtils;

        // Get the module resolution order
        List<ModuleRef> allModuleRefsList = null;
        if (scanSpec.overrideModuleLayers == null) {
            // Find module references for classes on callstack, and from system (for JDK9+)
            if (callStack != null && callStack.length > 0) {
                allModuleRefsList = findModuleRefsFromCallstack(callStack, scanSpec, scanNonSystemModules, log);
            }
        } else {
            if (log != null) {
                final LogNode subLog = log.log("Overriding module layers");
                for (final Object moduleLayer : scanSpec.overrideModuleLayers) {
                    subLog.log(moduleLayer.toString());
                }
            }
            allModuleRefsList = findModuleRefs(new LinkedHashSet<>(scanSpec.overrideModuleLayers), scanSpec, log);
        }
        if (allModuleRefsList != null) {
            // Split modules into system modules and non-system modules
            systemModuleRefs = new ArrayList<>();
            nonSystemModuleRefs = new ArrayList<>();
            for (final ModuleRef moduleRef : allModuleRefsList) {
                if (moduleRef != null) {
                    final boolean isSystemModule = moduleRef.isSystemModule();
                    if (isSystemModule && scanSystemModules) {
                        systemModuleRefs.add(moduleRef);
                    } else if (!isSystemModule && scanNonSystemModules) {
                        nonSystemModuleRefs.add(moduleRef);
                    }
                }
            }
        }
        // Log any identified modules
        if (log != null) {
            if (scanSystemModules) {
                final LogNode sysSubLog = log.log("System modules found:");
                if (systemModuleRefs != null && !systemModuleRefs.isEmpty()) {
                    for (final ModuleRef moduleRef : systemModuleRefs) {
                        sysSubLog.log(moduleRef.toString());
                    }
                } else {
                    sysSubLog.log("[None]");
                }
            } else {
                log.log("Scanning of system modules is not enabled");
            }
            if (scanNonSystemModules) {
                final LogNode nonSysSubLog = log.log("Non-system modules found:");
                if (nonSystemModuleRefs != null && !nonSystemModuleRefs.isEmpty()) {
                    for (final ModuleRef moduleRef : nonSystemModuleRefs) {
                        nonSysSubLog.log(moduleRef.toString());
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
