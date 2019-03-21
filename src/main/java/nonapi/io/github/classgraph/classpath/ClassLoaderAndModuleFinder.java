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
import nonapi.io.github.classgraph.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.ReflectionUtils;

/** A class to find the unique ordered classpath elements. */
public class ClassLoaderAndModuleFinder {
    /** The context class loaders. */
    private final ClassLoader[] contextClassLoaders;

    /** The system module refs. */
    private List<ModuleRef> systemModuleRefs;

    /** The non system module refs. */
    private List<ModuleRef> nonSystemModuleRefs;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the context class loaders.
     *
     * @return The context classloader, and any other classloader that is not an ancestor of context classloader.
     */
    public ClassLoader[] getContextClassLoaders() {
        return contextClassLoaders;
    }

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
    private static void findLayerOrder(final Object /* ModuleLayer */ layer,
            final Set<Object> /* Set<ModuleLayer> */ layerVisited,
            final Set<Object> /* Set<ModuleLayer> */ parentLayers,
            final Deque<Object> /* Deque<ModuleLayer> */ layerOrderOut) {
        if (layerVisited.add(layer)) {
            @SuppressWarnings("unchecked")
            final List<Object> /* List<ModuleLayer> */ parents = (List<Object>) ReflectionUtils.invokeMethod(layer,
                    "parents", /* throwException = */ true);
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
    private static List<ModuleRef> findModuleRefs(final LinkedHashSet<Object> layers, final ScanSpec scanSpec,
            final LogNode log) {
        if (layers.isEmpty()) {
            return Collections.emptyList();
        }

        // Traverse the layer DAG to find the layer resolution order
        final Deque<Object> /* Deque<ModuleLayer> */ layerOrder = new ArrayDeque<>();
        final Set<Object> /* Set<ModuleLayer */ parentLayers = new HashSet<>();
        for (final Object layer : layers) {
            findLayerOrder(layer, /* layerVisited = */ new HashSet<>(), parentLayers, layerOrder);
        }
        if (scanSpec.addedModuleLayers != null) {
            for (final Object layer : scanSpec.addedModuleLayers) {
                findLayerOrder(layer, /* layerVisited = */ new HashSet<>(), parentLayers, layerOrder);
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
            final Object /* Configuration */ configuration = ReflectionUtils.invokeMethod(layer, "configuration",
                    /* throwException = */ true);
            if (configuration != null) {
                // Get ModuleReferences from layer configuration
                @SuppressWarnings("unchecked")
                final Set<Object> /* Set<ResolvedModule> */ modules = (Set<Object>) ReflectionUtils
                        .invokeMethod(configuration, "modules", /* throwException = */ true);
                if (modules != null) {
                    final List<ModuleRef> modulesInLayer = new ArrayList<>();
                    for (final Object /* ResolvedModule */ module : modules) {
                        final Object /* ModuleReference */ moduleReference = ReflectionUtils.invokeMethod(module,
                                "reference", /* throwException = */ true);
                        if (moduleReference != null && addedModules.add(moduleReference)) {
                            try {
                                modulesInLayer.add(new ModuleRef(moduleReference, layer));
                            } catch (final IllegalArgumentException e) {
                                if (log != null) {
                                    log.log("Exception while creating ModuleRef for module " + moduleReference, e);
                                }
                            }
                        }
                    }
                    // Sort modules in layer by name
                    Collections.sort(modulesInLayer);
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
     * @param log
     *            the log
     * @return the list
     */
    private static List<ModuleRef> findModuleRefsFromCallstack(final Class<?>[] callStack, final ScanSpec scanSpec,
            final LogNode log) {
        final LinkedHashSet<Object> layers = new LinkedHashSet<>();
        if (callStack != null) {
            for (final Class<?> stackFrameClass : callStack) {
                final Object /* Module */ module = ReflectionUtils.invokeMethod(stackFrameClass, "getModule",
                        /* throwException = */ false);
                if (module != null) {
                    final Object /* ModuleLayer */ layer = ReflectionUtils.invokeMethod(module, "getLayer",
                            /* throwException = */ true);
                    // getLayer() returns null for unnamed modules -- have to get their classes from java.class.path 
                    if (layer != null) {
                        layers.add(layer);
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
            final Object /* ModuleLayer */ bootLayer = ReflectionUtils.invokeStaticMethod(moduleLayerClass, "boot",
                    /* throwException = */ false);
            if (bootLayer != null) {
                layers.add(bootLayer);
            }
        }
        return findModuleRefs(layers, scanSpec, log);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A class to find the unique ordered classpath elements.
     * 
     * @param scanSpec
     *            The scan spec, or null if none available.
     * @param log
     *            The log.
     */
    ClassLoaderAndModuleFinder(final ScanSpec scanSpec, final LogNode log) {
        LinkedHashSet<ClassLoader> classLoadersUnique;
        LogNode classLoadersFoundLog;
        if (scanSpec.overrideClassLoaders == null) {
            // ClassLoaders were not overridden

            // There's some advice here about choosing the best or the right classloader, but it is not complete
            // (e.g. it doesn't cover parent delegation modes):
            // http://www.javaworld.com/article/2077344/core-java/find-a-way-out-of-the-classloader-maze.html?page=2

            // Get thread context classloader (this is the first classloader to try, since a context classloader
            // can be set as an override on a per-thread basis)
            classLoadersUnique = new LinkedHashSet<>();
            final ClassLoader threadClassLoader = Thread.currentThread().getContextClassLoader();
            if (threadClassLoader != null) {
                classLoadersUnique.add(threadClassLoader);
            }

            // Get classloader for this class, which will generally be the classloader of the class that
            // called ClassGraph (the classloader of the caller is used by Class.forName(className), when
            // no classloader is provided)
            final ClassLoader currClassClassLoader = getClass().getClassLoader();
            if (currClassClassLoader != null) {
                classLoadersUnique.add(currClassClassLoader);
            }

            // Get system classloader (this is a fallback if one of the above do not work)
            final ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
            if (systemClassLoader != null) {
                classLoadersUnique.add(systemClassLoader);
            }

            // There is one more classloader in JDK9+, the platform classloader (used for handling extensions),
            // see: http://openjdk.java.net/jeps/261#Class-loaders
            // The method call to get it is ClassLoader.getPlatformClassLoader()
            // However, since it's not possible to get URLs from this classloader, and it is the parent of
            // the application classloader returned by ClassLoader.getSystemClassLoader() (so is delegated to
            // by the application classloader), there is no point adding it here. Modules are scanned
            // directly anyway, so we don't need to get module path entries from the platform classloader. 

            // Find classloaders for classes on callstack, in case any were missed
            Class<?>[] callStack = null;
            try {
                callStack = CallStackReader.getClassContext(log);
                for (int i = callStack.length - 1; i >= 0; --i) {
                    final ClassLoader callerClassLoader = callStack[i].getClassLoader();
                    if (callerClassLoader != null) {
                        classLoadersUnique.add(callerClassLoader);
                    }
                }
            } catch (final IllegalArgumentException e) {
                if (log != null) {
                    log.log("Could not get call stack", e);
                }
            }

            // Get the module resolution order
            List<ModuleRef> allModuleRefsList = null;
            if (scanSpec.overrideModuleLayers == null) {
                // Find module references for classes on callstack, and from system (for JDK9+)
                allModuleRefsList = findModuleRefsFromCallstack(callStack, scanSpec, log);
            } else {
                if (log != null) {
                    final LogNode subLog = log.log("Overriding module layers");
                    for (final Object moduleLayer : scanSpec.overrideModuleLayers) {
                        subLog.log(moduleLayer.toString());
                    }
                }
                allModuleRefsList = findModuleRefs(new LinkedHashSet<>(scanSpec.overrideModuleLayers), scanSpec,
                        log);
            }
            if (allModuleRefsList != null) {
                // Split modules into system modules and non-system modules
                systemModuleRefs = new ArrayList<>();
                nonSystemModuleRefs = new ArrayList<>();
                for (final ModuleRef moduleRef : allModuleRefsList) {
                    if (moduleRef.isSystemModule()) {
                        systemModuleRefs.add(moduleRef);
                    } else {
                        nonSystemModuleRefs.add(moduleRef);
                    }
                }
            }

            // Add any custom-added classloaders after system/context/module classloaders
            if (scanSpec.addedClassLoaders != null) {
                classLoadersUnique.addAll(scanSpec.addedClassLoaders);
            }
            classLoadersFoundLog = log == null ? null : log.log("Found ClassLoaders:");

        } else {
            // ClassLoaders were overridden
            classLoadersUnique = new LinkedHashSet<>(scanSpec.overrideClassLoaders);
            classLoadersFoundLog = log == null ? null : log.log("Override ClassLoaders:");
        }

        // Log all identified ClassLoaders
        if (classLoadersFoundLog != null) {
            for (final ClassLoader classLoader : classLoadersUnique) {
                classLoadersFoundLog.log(classLoader.getClass().getName());
            }
        }

        // Log any identified modules
        if (log != null) {
            final LogNode sysSubLog = log.log("Found system modules:");
            if (systemModuleRefs != null && !systemModuleRefs.isEmpty()) {
                for (final ModuleRef moduleRef : systemModuleRefs) {
                    sysSubLog.log(moduleRef.toString());
                }
            } else {
                sysSubLog.log("[None]");
            }
            final LogNode nonSysSubLog = log.log("Found non-system modules:");
            if (nonSystemModuleRefs != null && !nonSystemModuleRefs.isEmpty()) {
                for (final ModuleRef moduleRef : nonSystemModuleRefs) {
                    nonSysSubLog.log(moduleRef.toString());
                }
            } else {
                nonSysSubLog.log("[None]");
            }
        }

        this.contextClassLoaders = classLoadersUnique.toArray(new ClassLoader[0]);
    }
}
