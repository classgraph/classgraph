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

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import io.github.classgraph.ClassGraph;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry.ClassLoaderHandlerRegistryEntry;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import org.jspecify.annotations.Nullable;

/** A class to find all unique classloaders. */
public class ClassLoaderOrder {
    /** The {@link ClassLoader} order. */
    private final Map<ClassLoader, List<ClassLoaderHandlerRegistryEntry>> classLoaderOrder = new LinkedHashMap<>();

    /** The reflection utils instance. */
    public final ReflectionUtils reflectionUtils;

    /**
     * The set of all {@link ClassLoader} instances that have been added to the order so far, so that classloaders
     * don't get added twice.
     */
    // Need to use IdentityHashMap for maps and sets here, because TomEE weirdly
    // makes instances of
    // CxfContainerClassLoader equal to (via .equals()) the instance of
    // TomEEWebappClassLoader that it
    // delegates to (#515)
    private final Set<ClassLoader> added = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * The set of all {@link ClassLoader} instances that have been delegated to so far, to prevent an infinite loop
     * in delegation.
     */
    private final Set<ClassLoader> delegatedTo = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * The set of all parent {@link ClassLoader} instances that have been delegated to so far, to enable
     * {@link ClassGraph#ignoreParentClassLoaders()}.
     */
    private final Set<ClassLoader> allParentClassLoaders = Collections.newSetFromMap(new IdentityHashMap<>());

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param reflectionUtils
     *            the reflection utils instance.
     */
    public ClassLoaderOrder(final ReflectionUtils reflectionUtils) {
        this.reflectionUtils = reflectionUtils;
    }

    /**
     * Get the {@link ClassLoader} order.
     *
     * @return the {@link ClassLoader} order, as a pair: {@link ClassLoader},
     *         {@link ClassLoaderHandlerRegistryEntry}.
     */
    public List<Entry<ClassLoader, List<ClassLoaderHandlerRegistryEntry>>> getClassLoaderOrder() {
        return new ArrayList<>(classLoaderOrder.entrySet());
    }

    /**
     * Get the all parent classloaders.
     *
     * @return all parent classloaders
     */
    public Set<ClassLoader> getAllParentClassLoaders() {
        return allParentClassLoaders;
    }

    /**
     * Get the ClassLoaderHandler(s) that can handle a given ClassLoader.
     *
     * @param classLoader
     *            the class loader
     * @param log
     *            the log node, or null to skip logging
     * @return the registry entries that can handle the classloader, or a singleton list containing the fallback
     *         handler if none can.
     */
    private static List<ClassLoaderHandlerRegistryEntry> getClassLoaderHandlerRegistryEntries(
            final ClassLoader classLoader, final @Nullable LogNode log) {
        final List<ClassLoaderHandlerRegistryEntry> ents = new ArrayList<>();
        var matched = false;
        for (final ClassLoaderHandlerRegistryEntry ent : ClassLoaderHandlerRegistry.CLASS_LOADER_HANDLERS) {
            if (ent.canHandle(classLoader.getClass(), log)) {
                // This ClassLoaderHandler can handle the ClassLoader class, or one of its
                // superclasses
                ents.add(ent);
                matched = true;
            }
        }
        if (!matched) {
            ents.add(ClassLoaderHandlerRegistry.FALLBACK_HANDLER);
        }
        return ents;
    }

    /**
     * Add a {@link ClassLoader} to the ClassLoader order at the current position.
     *
     * @param classLoader
     *            the class loader, or null (ignored)
     * @param log
     *            the log node, or null to skip logging
     */
    public void add(final @Nullable ClassLoader classLoader, final @Nullable LogNode log) {
        if (classLoader == null) {
            return;
        }
        if (added.add(classLoader)) {
            classLoaderOrder.put(classLoader, getClassLoaderHandlerRegistryEntries(classLoader, log));
        }
    }

    /**
     * Recursively delegate to another {@link ClassLoader}.
     *
     * @param classLoader
     *            the class loader, or null (ignored)
     * @param isParent
     *            true if this is a parent of another classloader
     * @param log
     *            the log node, or null to skip logging
     */
    public void delegateTo(final @Nullable ClassLoader classLoader, final boolean isParent,
            final @Nullable LogNode log) {
        if (classLoader == null) {
            return;
        }
        // Check if this is a parent before checking if the classloader is already in
        // the delegatedTo set,
        // so that if the classloader is a context classloader but also a parent, it
        // still gets marked as
        // a parent classloader.
        if (isParent) {
            allParentClassLoaders.add(classLoader);
        }
        // Don't delegate to a classloader twice
        if (delegatedTo.add(classLoader)) {
            add(classLoader, log);
            // Recurse to get delegation order
            // (note: may be wrong if multiple ClassLoaderHandlers can handle this
            // classloader)
            for (final ClassLoaderHandlerRegistryEntry entry : getClassLoaderHandlerRegistryEntries(classLoader,
                    /* Don't log twice -- also logged by add method above */ null)) {
                entry.findClassLoaderOrder(classLoader, this, log);
            }
        }
    }
}
