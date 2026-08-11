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
package nonapi.io.github.classgraph.classpathspec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nonapi.io.github.classgraph.utils.Assert;
import nonapi.io.github.classgraph.utils.LogNode;
import org.jspecify.annotations.Nullable;

/**
 * The {@link ClassLoader} and {@link ModuleLayer} instances that the caller asked to be scanned, either in addition
 * to or instead of the ones found in the environment.
 *
 * <p>
 * These are deliberately not part of {@link ClassPathSpec}: a {@code ScanResult} holds the specs the scan was run
 * with for as long as the caller holds the {@code ScanResult}, and a scan must not keep a classloader alive. This
 * object is held only by the {@code ClassGraph} instance the caller built the scan with, and by the scan itself
 * while it runs; it is unreachable from the {@code ScanResult}.
 */
public class ClassLoaderAndModuleLayerSpec {
    /**
     * If non-null, classloaders that should be searched after the context classloader(s).
     */
    public @Nullable List<ClassLoader> addedClassLoaders;

    /**
     * If non-null, these classloaders are searched instead of the visible/context classloader(s). In particular,
     * this causes ClassGraph to ignore the {@code java.class.path} system property.
     */
    public @Nullable List<ClassLoader> overrideClassLoaders;

    /**
     * If non-null, module layers that should be searched after the visible module layers.
     */
    public @Nullable List<ModuleLayer> addedModuleLayers;

    /**
     * If non-null, these module layers are searched instead of the visible module layers.
     */
    public @Nullable List<ModuleLayer> overrideModuleLayers;

    // -----------------------------------------------------------------------------------------------------------

    /** Constructor. */
    public ClassLoaderAndModuleLayerSpec() {
        // Intentionally empty
    }

    // -----------------------------------------------------------------------------------------------------------

    /**
     * Add a classloader to the list of classloaders to scan. (This only works if {@code overrideClasspath()} is not
     * called.)
     *
     * @param classLoader
     *            The classloader to add.
     */
    public void addClassLoader(final ClassLoader classLoader) {
        Assert.notNull(classLoader, "classLoader");
        if (this.addedClassLoaders == null) {
            this.addedClassLoaders = new ArrayList<>();
        }
        this.addedClassLoaders.add(classLoader);
    }

    /**
     * Completely override the list of classloaders to scan. (This only works if {@code overrideClasspath()} is not
     * called.) Causes the {@code java.class.path} system property to be ignored.
     *
     * @param overrideClassLoaders
     *            The classloaders to override the default context classloaders with.
     */
    public void overrideClassLoaders(final ClassLoader... overrideClassLoaders) {
        Assert.notNullElements(overrideClassLoaders, "overrideClassLoaders");
        if (overrideClassLoaders.length == 0) {
            throw new IllegalArgumentException("At least one override ClassLoader must be provided");
        }
        this.addedClassLoaders = null;
        this.overrideClassLoaders = new ArrayList<>();
        Collections.addAll(this.overrideClassLoaders, overrideClassLoaders);
    }

    /**
     * Add a module layer to the list of module layers to scan. Use this method if you define your own module layer,
     * but the scanning code is not running within that custom module layer.
     *
     * <p>
     * This call is ignored if it is called before {@link #overrideModuleLayers(ModuleLayer...)}.
     *
     * @param moduleLayer
     *            The additional module layer to scan.
     */
    public void addModuleLayer(final ModuleLayer moduleLayer) {
        Assert.notNull(moduleLayer, "moduleLayer");
        if (this.addedModuleLayers == null) {
            this.addedModuleLayers = new ArrayList<>();
        }
        this.addedModuleLayers.add(moduleLayer);
    }

    /**
     * Completely override (and ignore) the visible module layers, and instead scan the requested module layers.
     *
     * <p>
     * This call is ignored if {@code overrideClasspath()} is called.
     *
     * @param overrideModuleLayers
     *            The module layers to scan instead of the automatically-detected module layers.
     */
    public void overrideModuleLayers(final ModuleLayer... overrideModuleLayers) {
        Assert.notNullElements(overrideModuleLayers, "overrideModuleLayers");
        if (overrideModuleLayers.length == 0) {
            throw new IllegalArgumentException("At least one override ModuleLayer must be provided");
        }
        this.addedModuleLayers = null;
        this.overrideModuleLayers = new ArrayList<>();
        Collections.addAll(this.overrideModuleLayers, overrideModuleLayers);
    }

    // -----------------------------------------------------------------------------------------------------------

    /**
     * Log the classloaders and module layers that were named by the caller.
     *
     * @param log
     *            the log node, or null to skip logging
     */
    public void log(final @Nullable LogNode log) {
        if (log != null) {
            final var subLog = log.log("ClassLoaderAndModuleLayerSpec:");
            subLog.log("addedClassLoaders: " + addedClassLoaders);
            subLog.log("overrideClassLoaders: " + overrideClassLoaders);
            subLog.log("addedModuleLayers: " + addedModuleLayers);
            subLog.log("overrideModuleLayers: " + overrideModuleLayers);
        }
    }
}
