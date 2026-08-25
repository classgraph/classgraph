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

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.internal.classloaderhandler.ClassLoaderHandlerRegistry;
import io.github.classgraph.classpath.internal.classloaderhandler.ClassLoaderHandlerRegistry.ClassLoaderHandlerRegistryEntry;
import org.jspecify.annotations.Nullable;

/** A class to find all unique classloaders. */
public class ClassLoaderOrderBuilder implements ClassLoaderOrder {
    /**
     * The registry entries for the {@link ClassLoaderHandler} instances the user registered, in registration order.
     * These are offered each classloader before the built-in handlers are, so that a user handler can override a
     * built-in one.
     */
    private final List<ClassLoaderHandlerRegistryEntry> userClassLoaderHandlers;

    /** The {@link ClassLoader} order. */
    private final Map<ClassLoader, List<ClassLoaderHandlerRegistryEntry>> classLoaderOrder = new LinkedHashMap<>();

    /**
     * The set of all {@link ClassLoader} instances that have been added to the order so far, so that classloaders
     * don't get added twice.
     */
    // Need to use IdentityHashMap for maps and sets here, because TomEE weirdly makes instances of
    // CxfContainerClassLoader equal to (via .equals()) the instance of TomEEWebappClassLoader that it delegates to
    // (#515)
    private final Set<ClassLoader> added = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * The set of all {@link ClassLoader} instances that have been delegated to so far, to prevent an infinite loop
     * in delegation.
     */
    private final Set<ClassLoader> delegatedTo = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * The set of all parent {@link ClassLoader} instances that have been delegated to so far, to enable
     * {@code ClassGraph#ignoreParentClassLoaders()}.
     */
    private final Set<ClassLoader> allParentClassLoaders = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * The handlers found for each {@link ClassLoader}, so that the handlers are chosen only once per classloader.
     * Every classloader is looked up twice, once by {@link #delegateTo(ClassLoader, boolean, ClassGraphLog)} and
     * once by {@link #add(ClassLoader, ClassGraphLog)}, and choosing the handlers walks the classloader's class
     * hierarchy once per registered handler.
     */
    private final Map<ClassLoader, List<ClassLoaderHandlerRegistryEntry>> classLoaderHandlers = //
            new IdentityHashMap<>();

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param userClassLoaderHandlers
     *            the registry entries for the {@link ClassLoaderHandler} instances the user registered.
     */
    public ClassLoaderOrderBuilder(final List<ClassLoaderHandlerRegistryEntry> userClassLoaderHandlers) {
        this.userClassLoaderHandlers = userClassLoaderHandlers;
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
    private List<ClassLoaderHandlerRegistryEntry> getClassLoaderHandlerRegistryEntries(
            final ClassLoader classLoader, final @Nullable ClassGraphLog log) {
        // The handlers are chosen once per classloader, so this also logs the choice only once
        return classLoaderHandlers.computeIfAbsent(classLoader, cl -> chooseClassLoaderHandlers(cl, log));
    }

    /**
     * Choose the {@link ClassLoaderHandler}(s) to handle a given {@link ClassLoader}.
     *
     * @param classLoader
     *            the class loader
     * @param log
     *            the log node, or null to skip logging
     * @return the registry entries that can handle the classloader, or a singleton list containing the fallback
     *         handler if none can.
     */
    private List<ClassLoaderHandlerRegistryEntry> chooseClassLoaderHandlers(final ClassLoader classLoader,
            final @Nullable ClassGraphLog log) {
        final var classLoaderClass = classLoader.getClass();
        final List<ClassLoaderHandlerRegistryEntry> ents = new ArrayList<>();
        // The user's handlers are offered the classloader before the built-in handlers are, so that a user handler
        // can override a built-in one
        for (final ClassLoaderHandlerRegistryEntry ent : userClassLoaderHandlers) {
            if (ent.canHandle(classLoaderClass, log)) {
                ents.add(ent);
            }
        }
        final var numUserHandlers = ents.size();
        for (final ClassLoaderHandlerRegistryEntry ent : ClassLoaderHandlerRegistry.CLASS_LOADER_HANDLERS) {
            if (ent.canHandle(classLoaderClass, log)) {
                // This ClassLoaderHandler can handle the ClassLoader class, or one of its superclasses
                ents.add(ent);
            }
        }
        if (ents.isEmpty()) {
            ents.add(ClassLoaderHandlerRegistry.FALLBACK_HANDLER);
        } else if (ents.size() > 1) {
            return dropMoreGeneralHandlers(ents, numUserHandlers, classLoaderClass, log);
        }
        return ents;
    }

    /**
     * Drop each built-in handler that only handles the classloader because it handles a superclass of it, when
     * another handler handles a more specific class in the same hierarchy.
     *
     * <p>
     * A handler declares the classloader class it handles by returning true from
     * {@code ClassLoaderHandler#canHandle(Class, ClassGraphLog)} for that class and for every subclass of it, so
     * the most distant ancestor of the classloader that a handler still returns true for is the class the handler
     * was written against, and a handler whose class is further up the hierarchy is the more general of the two.
     * Only the least general handlers are kept: a handler written for a subclass of {@link java.net.URLClassLoader}
     * knows how that subclass really delegates and where it really keeps its classpath entries, so it must not have
     * a general {@code URLClassLoader} handler running alongside it and placing the same classloaders in a
     * different order. This is what makes the order the built-in handlers are registered in irrelevant.
     *
     * <p>
     * Handlers the user registered are never dropped, since the user registered them for this exact purpose;
     * dropping one would silently turn off something the caller explicitly asked for.
     *
     * @param ents
     *            the registry entries that can handle the classloader, user-registered ones first, in the order
     *            they should run in. Must contain at least two entries.
     * @param numUserHandlers
     *            the number of user-registered entries at the head of {@code ents}.
     * @param classLoaderClass
     *            the class of the classloader being handled.
     * @param log
     *            the log node, or null to skip logging
     * @return the entries to run, in the order they were given in.
     */
    private static List<ClassLoaderHandlerRegistryEntry> dropMoreGeneralHandlers(
            final List<ClassLoaderHandlerRegistryEntry> ents, final int numUserHandlers,
            final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
        // The classloader class, then each of its superclasses in turn, so that a larger index is more general
        final List<Class<?>> classHierarchy = new ArrayList<>();
        for (var cls = classLoaderClass; cls != null; cls = cls.getSuperclass()) {
            classHierarchy.add(cls);
        }
        // Find the index in that hierarchy of the most distant ancestor each handler still handles
        final var generality = new int[ents.size()];
        var leastGeneral = Integer.MAX_VALUE;
        for (var i = 0; i < ents.size(); i++) {
            // Index 0 does not need testing -- every handler in ents already handles the classloader class itself
            for (var j = classHierarchy.size() - 1; j > 0; j--) {
                // Don't log the probing calls, since they say nothing about the classloader being handled
                if (ents.get(i).canHandle(classHierarchy.get(j), /* log = */ null)) {
                    generality[i] = j;
                    break;
                }
            }
            leastGeneral = Math.min(leastGeneral, generality[i]);
        }
        final List<ClassLoaderHandlerRegistryEntry> leastGeneralEnts = new ArrayList<>(ents.size());
        for (var i = 0; i < ents.size(); i++) {
            if (i < numUserHandlers || generality[i] == leastGeneral) {
                leastGeneralEnts.add(ents.get(i));
            } else if (log != null) {
                log.log("Not using ClassLoaderHandler " + ents.get(i).getHandlerName() + ", since it handles "
                        + classHierarchy.get(generality[i]).getName() + ", and another handler handles the more "
                        + "specific class " + classHierarchy.get(leastGeneral).getName());
            }
        }
        return leastGeneralEnts;
    }

    /**
     * Add a {@link ClassLoader} to the ClassLoader order at the current position.
     *
     * @param classLoader
     *            the class loader, or null (ignored)
     * @param log
     *            the log node, or null to skip logging
     */
    @Override
    public void add(final @Nullable ClassLoader classLoader, final @Nullable ClassGraphLog log) {
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
     * <p>
     * The classloader is not placed in the order here: its handler places it, by calling
     * {@link #add(ClassLoader, ClassGraphLog)} either before or after it delegates to the classloader's parent,
     * according to whether the classloader resolves classes parent-first or parent-last. That is what puts the
     * classpath elements in the order that classes are resolved in, which is the order that class masking depends
     * on.
     *
     * @param classLoader
     *            the class loader, or null (ignored)
     * @param isParent
     *            true if this is a parent of another classloader
     * @param log
     *            the log node, or null to skip logging
     */
    @Override
    public void delegateTo(final @Nullable ClassLoader classLoader, final boolean isParent,
            final @Nullable ClassGraphLog log) {
        if (classLoader == null) {
            return;
        }
        // Check if this is a parent before checking if the classloader is already in the delegatedTo set, so that
        // if the classloader is a context classloader but also a parent, it still gets marked as a parent
        // classloader.
        if (isParent) {
            allParentClassLoaders.add(classLoader);
        }
        // Don't delegate to a classloader twice
        if (delegatedTo.add(classLoader)) {
            // Recurse to get delegation order. Only the handlers that handle the most specific classloader class
            // are called, so a handler that knows this classloader's real delegation order is not competing with a
            // general handler that would place the parent and child classloaders in a different order. When
            // several equally specific handlers remain, they are called in the order they were registered in, and
            // the ones that run later can only add classloaders that an earlier one did not already place.
            for (final ClassLoaderHandlerRegistryEntry entry : getClassLoaderHandlerRegistryEntries(classLoader,
                    log)) {
                entry.findClassLoaderOrder(classLoader, this, log);
            }
        }
    }
}
