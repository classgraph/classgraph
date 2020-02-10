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

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import io.github.classgraph.ClassGraph;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandler;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry.ClassLoaderHandlerRegistryEntry;
import nonapi.io.github.classgraph.utils.LogNode;

/** A class to find all unique classloaders. */
public class ClassLoaderOrder {
    /** The {@link ClassLoader} order. */
    private final List<Entry<ClassLoader, ClassLoaderHandlerRegistryEntry>> classLoaderOrder = new ArrayList<>();

    /**
     * The set of all {@link ClassLoader} instances that have been added to the order so far, so that classloaders
     * don't get added twice.
     */
    private final Set<ClassLoader> added = new HashSet<>();

    /**
     * The set of all {@link ClassLoader} instances that have been delegated to so far, to prevent an infinite loop
     * in delegation.
     */
    private final Set<ClassLoader> delegatedTo = new HashSet<>();

    /**
     * The set of all parent {@link ClassLoader} instances that have been delegated to so far, to enable
     * {@link ClassGraph#ignoreParentClassLoaders()}.
     */
    private final Set<ClassLoader> allParentClassLoaders = new HashSet<>();

    /** A map from {@link ClassLoader} to {@link ClassLoaderHandlerRegistryEntry}. */
    private final Map<ClassLoader, ClassLoaderHandlerRegistryEntry> classLoaderToClassLoaderHandlerRegistryEntry = //
            new HashMap<>();

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the {@link ClassLoader} order.
     *
     * @return the {@link ClassLoader} order, as a pair: {@link ClassLoader},
     *         {@link ClassLoaderHandlerRegistryEntry}.
     */
    public List<Entry<ClassLoader, ClassLoaderHandlerRegistryEntry>> getClassLoaderOrder() {
        return classLoaderOrder;
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
     * Find the {@link ClassLoaderHandler} that can handle a given {@link ClassLoader} instance.
     *
     * @param classLoader
     *            the {@link ClassLoader}.
     * @param log
     *            the log
     * @return the {@link ClassLoaderHandlerRegistryEntry} for the {@link ClassLoader}.
     */
    private ClassLoaderHandlerRegistryEntry getRegistryEntry(final ClassLoader classLoader, final LogNode log) {
        ClassLoaderHandlerRegistryEntry entry = classLoaderToClassLoaderHandlerRegistryEntry.get(classLoader);
        if (entry == null) {
            // Try all superclasses of classloader in turn
            for (Class<?> currClassLoaderClass = classLoader.getClass(); // 
                    currClassLoaderClass != Object.class && currClassLoaderClass != null; //
                    currClassLoaderClass = currClassLoaderClass.getSuperclass()) {
                // Find a ClassLoaderHandler that can handle the ClassLoader
                for (final ClassLoaderHandlerRegistryEntry ent : ClassLoaderHandlerRegistry.CLASS_LOADER_HANDLERS) {
                    if (ent.canHandle(currClassLoaderClass, log)) {
                        // This ClassLoaderHandler can handle the ClassLoader class, or one of its superclasses
                        entry = ent;
                        break;
                    }
                }
                if (entry != null) {
                    // Don't iterate to next superclass if a matching ClassLoaderHandler was found
                    break;
                }
            }
            if (entry == null) {
                // Use fallback handler
                entry = ClassLoaderHandlerRegistry.FALLBACK_HANDLER;
            }
            classLoaderToClassLoaderHandlerRegistryEntry.put(classLoader, entry);
        }
        return entry;
    }

    /**
     * Add a {@link ClassLoader} to the {@link ClassLoader} order at the current position.
     *
     * @param classLoader
     *            the class loader
     * @param log
     *            the log
     */
    public void add(final ClassLoader classLoader, final LogNode log) {
        if (classLoader == null) {
            return;
        }
        if (added.add(classLoader)) {
            final ClassLoaderHandlerRegistryEntry entry = getRegistryEntry(classLoader, log);
            if (entry != null) {
                classLoaderOrder.add(new SimpleEntry<>(classLoader, entry));
            }
        }
    }

    /**
     * Recursively delegate to another {@link ClassLoader}.
     *
     * @param classLoader
     *            the class loader
     * @param isParent
     *            true if this is a parent of another classloader
     * @param log
     *            the log
     */
    public void delegateTo(final ClassLoader classLoader, final boolean isParent, final LogNode log) {
        if (classLoader == null) {
            return;
        }
        // Check if this is a parent before checking if the classloader is already in the delegatedTo set,
        // so that if the classloader is a context classloader but also a parent, it still gets marked as
        // a parent classloader.
        if (isParent) {
            allParentClassLoaders.add(classLoader);
        }
        // Don't delegate to a classloader twice
        if (delegatedTo.add(classLoader)) {
            // Find ClassLoaderHandlerRegistryEntry for this classloader
            final ClassLoaderHandlerRegistryEntry entry = getRegistryEntry(classLoader, log);
            // Delegate to this classloader, by recursing to that classloader to get its classloader order
            entry.findClassLoaderOrder(classLoader, this, log);
        }
    }
}
