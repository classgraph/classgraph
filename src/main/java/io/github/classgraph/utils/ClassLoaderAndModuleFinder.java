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
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.classgraph.utils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.classgraph.ModuleRef;
import io.github.classgraph.ScanSpec;

/** A class to find the unique ordered classpath elements. */
public class ClassLoaderAndModuleFinder {
    private final ClassLoader[] classLoaders;
    private final List<ModuleRef> systemModuleRefs;
    private final List<ModuleRef> nonSystemModuleRefs;

    /**
     * @return The context classloader, and any other classloader that is not an ancestor of context classloader.
     */
    public ClassLoader[] getClassLoaders() {
        return classLoaders;
    }

    /** @return The system modules as ModuleRef wrappers, or null if no modules were found (e.g. on JDK 7 or 8). */
    public List<ModuleRef> getSystemModuleRefs() {
        return systemModuleRefs;
    }

    /**
     * @return The non-system modules as ModuleRef wrappers, or null if no modules were found (e.g. on JDK 7 or 8).
     */
    public List<ModuleRef> getNonSystemModuleRefs() {
        return nonSystemModuleRefs;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Recursively find the topological sort order of ancestral layers. The JDK (as of 10.0.0.1) uses a broken
     * (non-topological) DFS ordering for layer resolution in ModuleLayer#layers() and
     * Configuration#configurations() but when I reported this bug on the Jigsaw mailing list, Alan didn't see what
     * the problem was...
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
                for (int i = 0; i < parents.size(); i++) {
                    findLayerOrder(parents.get(i), layerVisited, parentLayers, layerOrderOut);
                }
            }
            layerOrderOut.push(layer);
        }
    }

    /** Get all visible ModuleReferences in a list of layers. */
    private static List<ModuleRef> findModuleRefs(final List<Object> layers, final ScanSpec scanSpec,
            final LogNode log) {
        if (layers.isEmpty()) {
            return Collections.<ModuleRef> emptyList();
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
                        if (moduleReference != null) {
                            if (addedModules.add(moduleReference)) {
                                try {
                                    modulesInLayer.add(new ModuleRef(moduleReference, layer));
                                } catch (final Exception e) {
                                    if (log != null) {
                                        log.log("Exception while creating ModuleRef for module " + moduleReference,
                                                e);
                                    }
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
     */
    private static List<ModuleRef> findModuleRefs(final Class<?>[] callStack, final ScanSpec scanSpec,
            final LogNode log) {
        final List<Object> layers = new ArrayList<>();
        for (int i = 0; i < callStack.length; i++) {
            final Object /* Module */ module = ReflectionUtils.invokeMethod(callStack[i], "getModule",
                    /* throwException = */ false);
            if (module != null) {
                final Object /* ModuleLayer */ layer = ReflectionUtils.invokeMethod(module, "getLayer",
                        /* throwException = */ true);
                // getLayer() returns null for unnamed modules -- we have to get their classes from java.class.path 
                if (layer != null) {
                    layers.add(layer);
                }
            }
        }
        // Add system modules from boot layer, if they weren't already found in stacktrace
        Class<?> moduleLayerClass = null;
        try {
            moduleLayerClass = Class.forName("java.lang.ModuleLayer");
        } catch (final Throwable e) {
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
    public ClassLoaderAndModuleFinder(final ScanSpec scanSpec, final LogNode log) {
        LinkedHashSet<ClassLoader> classLoadersUnique;
        LogNode classLoadersFoundLog = null;
        List<ModuleRef> systemModuleRefs = null;
        List<ModuleRef> nonSystemModuleRefs = null;
        if (scanSpec.overrideClassLoaders == null) {
            // ClassLoaders were not overridden

            // Add the ClassLoaders in the order system, caller, context; then remove any of them that are
            // parents/ancestors of one or more other classloaders (performed below). There will generally only be
            // one class left after this. In rare cases, you may have a separate callerLoader and contextLoader, but
            // those cases are ill-defined -- see:
            // http://www.javaworld.com/article/2077344/core-java/find-a-way-out-of-the-classloader-maze.html?page=2

            // Get system classloader
            classLoadersUnique = new LinkedHashSet<>();
            final ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
            if (systemClassLoader != null) {
                classLoadersUnique.add(systemClassLoader);
            }

            // There is one more classloader in JDK9+, the platform classloader (used for handling extensions),
            // see: http://openjdk.java.net/jeps/261#Class-loaders
            // The method call to get it is ClassLoader.getPlatformClassLoader()
            // However, since it's not possible to get URLs from this classloader, and it is the parent of
            // the application classloader returned by ClassLoader.getSystemClassLoader() (so is delegated to
            // by the application classloader), there is no point adding it here.

            List<ModuleRef> allModuleRefsList = null;
            if (scanSpec.overrideModuleLayers == null) {
                try {
                    // Find classloaders for classes on callstack
                    final Class<?>[] callStack = CallStackReader.getClassContext(log);
                    for (int i = callStack.length - 1; i >= 0; --i) {
                        final ClassLoader callerClassLoader = callStack[i].getClassLoader();
                        if (callerClassLoader != null) {
                            classLoadersUnique.add(callerClassLoader);
                        }
                    }
                    // Find module references for classes on callstack (for JDK9+)
                    allModuleRefsList = findModuleRefs(callStack, scanSpec, log);
                } catch (final IllegalArgumentException e) {
                    if (log != null) {
                        log.log("Could not get call stack", e);
                    }
                }
            } else {
                if (log != null) {
                    final LogNode subLog = log.log("Overriding module layers");
                    for (final Object moduleLayer : scanSpec.overrideModuleLayers) {
                        subLog.log(moduleLayer.toString());
                    }
                }
                allModuleRefsList = findModuleRefs(scanSpec.overrideModuleLayers, scanSpec, log);
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

            // Get context classloader
            final ClassLoader threadClassLoader = Thread.currentThread().getContextClassLoader();
            if (threadClassLoader != null) {
                classLoadersUnique.add(threadClassLoader);
            }

            // Add any custom-added classloaders after system/context classloaders
            if (scanSpec.addedClassLoaders != null) {
                classLoadersUnique.addAll(scanSpec.addedClassLoaders);
            }
            classLoadersFoundLog = log == null ? null : log.log("Found ClassLoaders:");

        } else {
            // ClassLoaders were overridden
            classLoadersUnique = new LinkedHashSet<>(scanSpec.overrideClassLoaders);
            classLoadersFoundLog = log == null ? null : log.log("Override ClassLoaders:");
        }

        // Remove all ancestral classloaders (they are called automatically during class load)
        final Set<ClassLoader> ancestralClassLoaders = new HashSet<>(classLoadersUnique.size());
        for (final ClassLoader classLoader : classLoadersUnique) {
            for (ClassLoader cl = classLoader.getParent(); cl != null; cl = cl.getParent()) {
                ancestralClassLoaders.add(cl);
            }
        }
        final List<ClassLoader> classLoaderFinalOrder = new ArrayList<>(classLoadersUnique.size());
        for (final ClassLoader classLoader : classLoadersUnique) {
            // Build final ClassLoader order, with ancestral classloaders removed
            if (!ancestralClassLoaders.contains(classLoader)) {
                classLoaderFinalOrder.add(classLoader);
            }
        }

        // Define a fallback classloader when classpath is overridden
        if (scanSpec.overrideClasspath != null) {
            final ClassLoader fallbackURLClassLoader = JarUtils
                    .createURLClassLoaderFromPathString(scanSpec.overrideClasspath);
            classLoaderFinalOrder.add(fallbackURLClassLoader);
            if (log != null) {
                log.log("Adding fallback URLClassLoader for overriden classpath: " + fallbackURLClassLoader);
            }
        }

        // Log all identified ClassLoaders
        if (classLoadersFoundLog != null) {
            for (final ClassLoader cl : classLoaderFinalOrder) {
                classLoadersFoundLog.log("" + cl);
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

        this.classLoaders = classLoaderFinalOrder.toArray(new ClassLoader[0]);
        this.systemModuleRefs = systemModuleRefs;
        this.nonSystemModuleRefs = nonSystemModuleRefs;
    }
}
