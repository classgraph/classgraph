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
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.internal.classloaderhandler.ClassLoaderHandlerRegistry;

/**
 * Builds the delegation order of {@link ClassLoader} instances and selects the {@link ClassLoaderHandler} instances
 * for each classloader.
 */
public class ClassLoaderOrderBuilder implements ClassLoaderOrder {
    /** User-registered {@link ClassLoaderHandler} instances, in registration order. */
    private final List<ClassLoaderHandler> userClassLoaderHandlers;

    /** The ordered {@link ClassLoader} instances paired with their selected handlers. */
    private final List<HandledClassLoader> classLoaderOrder = new ArrayList<>();

    /** {@link ClassLoader} instances already added to {@link #classLoaderOrder}. */
    // Need to use IdentityHashMap for maps and sets here, because TomEE weirdly makes instances of
    // CxfContainerClassLoader equal to (via .equals()) the instance of TomEEWebappClassLoader that it delegates to
    // (#515)
    private final Set<ClassLoader> added = Collections.newSetFromMap(new IdentityHashMap<>());

    /** {@link ClassLoader} instances already delegated to. */
    private final Set<ClassLoader> delegatedTo = Collections.newSetFromMap(new IdentityHashMap<>());

    /** {@link ClassLoader} instances reached via {@link #delegateToParent(ClassLoader, ClassGraphLog)}. */
    private final Set<ClassLoader> allParentClassLoaders = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Cached {@link ClassLoaderHandler} lists keyed by {@link ClassLoader}. */
    private final Map<ClassLoader, List<ClassLoaderHandler>> classLoaderHandlers = //
            new IdentityHashMap<>();

    /**
     * Pairs a {@link ClassLoader} with the {@link ClassLoaderHandler} instances selected for it.
     *
     * @param classLoader
     *            the classloader.
     * @param handlers
     *            the {@link ClassLoaderHandler} instances to run for the classloader.
     */
    public record HandledClassLoader(ClassLoader classLoader, List<ClassLoaderHandler> handlers) {
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param userClassLoaderHandlers
     *            user-registered {@link ClassLoaderHandler} instances.
     */
    public ClassLoaderOrderBuilder(final List<ClassLoaderHandler> userClassLoaderHandlers) {
        this.userClassLoaderHandlers = userClassLoaderHandlers;
    }

    /**
     * Returns the ordered list of classloaders and their selected handlers.
     *
     * @return an immutable copy of the {@link ClassLoader} order.
     */
    public List<HandledClassLoader> getClassLoaderOrder() {
        return List.copyOf(classLoaderOrder);
    }

    /**
     * Returns all classloaders reached as parent classloaders.
     *
     * @return the parent classloaders, as an unmodifiable set.
     */
    public Set<ClassLoader> getAllParentClassLoaders() {
        return Collections.unmodifiableSet(allParentClassLoaders);
    }

    /**
     * Returns the cached or newly selected {@link ClassLoaderHandler} list for {@code classLoader}.
     *
     * @param classLoader
     *            the classloader.
     * @param log
     *            the log node, or null to skip logging.
     * @return the handlers for {@code classLoader}.
     */
    private List<ClassLoaderHandler> getClassLoaderHandlers(final ClassLoader classLoader,
            final @Nullable ClassGraphLog log) {
        // The handlers are chosen once per classloader, so this also logs the choice only once
        return classLoaderHandlers.computeIfAbsent(classLoader, cl -> chooseClassLoaderHandlers(cl, log));
    }

    /**
     * Selects the {@link ClassLoaderHandler} instances for {@code classLoader}, falling back to
     * {@link ClassLoaderHandlerRegistry#FALLBACK_HANDLER} if none match.
     *
     * @param classLoader
     *            the classloader.
     * @param log
     *            the log node, or null to skip logging.
     * @return the selected handlers for {@code classLoader}.
     */
    private List<ClassLoaderHandler> chooseClassLoaderHandlers(final ClassLoader classLoader,
            final @Nullable ClassGraphLog log) {
        final var classLoaderClass = classLoader.getClass();
        final List<ClassLoaderHandler> handlers = new ArrayList<>();
        // The user's handlers are offered the classloader before the built-in handlers are, so that a user handler
        // can override a built-in one
        for (final ClassLoaderHandler handler : userClassLoaderHandlers) {
            if (canHandle(handler, classLoaderClass, log)) {
                handlers.add(handler);
            }
        }
        final var numUserHandlers = handlers.size();
        for (final ClassLoaderHandler handler : ClassLoaderHandlerRegistry.CLASS_LOADER_HANDLERS) {
            if (canHandle(handler, classLoaderClass, log)) {
                // This ClassLoaderHandler can handle the ClassLoader class, or one of its superclasses
                handlers.add(handler);
            }
        }
        if (handlers.isEmpty()) {
            handlers.add(ClassLoaderHandlerRegistry.FALLBACK_HANDLER);
        } else if (handlers.size() > 1) {
            return dropMoreGeneralHandlers(handlers, numUserHandlers, classLoaderClass, log);
        }
        return handlers;
    }

    /**
     * Calls {@link ClassLoaderHandler#canHandle(Class, ClassGraphLog)}, logging and returning false if it throws.
     *
     * @param handler
     *            the handler.
     * @param classLoaderClass
     *            the {@link ClassLoader} class or superclass.
     * @param log
     *            the log node, or null to skip logging.
     * @return true if the handler can handle the class.
     */
    private static boolean canHandle(final ClassLoaderHandler handler, final Class<?> classLoaderClass,
            final @Nullable ClassGraphLog log) {
        try {
            return handler.canHandle(classLoaderClass, log);
        } catch (final RuntimeException | LinkageError e) {
            if (log != null) {
                log.log("ClassLoaderHandler " + handler.getClass().getName() + " failed when asked whether it can "
                        + "handle " + classLoaderClass.getName() + ", so it is taken not to handle it", e);
            }
            return false;
        }
    }

    /**
     * Retains all user-registered handlers and only those built-in handlers that match the most specific
     * classloader class in the superclass hierarchy of {@code classLoaderClass}.
     *
     * @param handlers
     *            all matching handlers (user-registered handlers first).
     * @param numUserHandlers
     *            the number of user-registered handlers at the start of {@code handlers}.
     * @param classLoaderClass
     *            the class of the classloader being handled.
     * @param log
     *            the log node, or null to skip logging.
     * @return the filtered list of handlers.
     */
    private static List<ClassLoaderHandler> dropMoreGeneralHandlers(final List<ClassLoaderHandler> handlers,
            final int numUserHandlers, final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
        // The classloader class, then each of its superclasses in turn, so that a larger index is more general
        final List<Class<?>> classHierarchy = new ArrayList<>();
        for (var cls = classLoaderClass; cls != null; cls = cls.getSuperclass()) {
            classHierarchy.add(cls);
        }
        // Find the index in that hierarchy of the most distant ancestor each handler still handles. Only the
        // superclasses are walked, so a handler that recognizes the classloader by an interface it implements, and
        // that therefore handles no superclass of it, is left at index 0 and counts as the most specific of all --
        // which is what a handler written against an interface that only this classloader implements deserves.
        final var generality = new int[handlers.size()];
        var leastGeneral = Integer.MAX_VALUE;
        for (var i = 0; i < handlers.size(); i++) {
            // Index 0 does not need testing -- every handler already handles the classloader class itself
            for (var j = classHierarchy.size() - 1; j > 0; j--) {
                // Don't log the probing calls, since they say nothing about the classloader being handled
                if (canHandle(handlers.get(i), classHierarchy.get(j), /* log = */ null)) {
                    generality[i] = j;
                    break;
                }
            }
            leastGeneral = Math.min(leastGeneral, generality[i]);
        }
        final List<ClassLoaderHandler> leastGeneralHandlers = new ArrayList<>(handlers.size());
        for (var i = 0; i < handlers.size(); i++) {
            if (i < numUserHandlers || generality[i] == leastGeneral) {
                leastGeneralHandlers.add(handlers.get(i));
            } else if (log != null) {
                log.log("Not using ClassLoaderHandler " + handlers.get(i).getClass().getName()
                        + ", since it handles " + classHierarchy.get(generality[i]).getName()
                        + ", and another handler handles the more specific class "
                        + classHierarchy.get(leastGeneral).getName());
            }
        }
        return leastGeneralHandlers;
    }

    /**
     * Adds a {@link ClassLoader} to the classloader order at the current position if not already added.
     *
     * @param classLoader
     *            the classloader, or null (ignored).
     * @param log
     *            the log node, or null to skip logging.
     */
    @Override
    public void add(final @Nullable ClassLoader classLoader, final @Nullable ClassGraphLog log) {
        if (classLoader == null) {
            return;
        }
        if (added.add(classLoader)) {
            classLoaderOrder.add(new HandledClassLoader(classLoader, getClassLoaderHandlers(classLoader, log)));
        }
    }

    /**
     * Recursively delegates to a parent {@link ClassLoader} and records it as a parent classloader.
     *
     * @param parent
     *            the parent classloader, or null (ignored).
     * @param log
     *            the log node, or null to skip logging.
     */
    @Override
    public void delegateToParent(final @Nullable ClassLoader parent, final @Nullable ClassGraphLog log) {
        delegateTo(parent, /* isParent = */ true, log);
    }

    /**
     * Recursively delegates to a peer {@link ClassLoader} without recording it as a parent classloader.
     *
     * @param classLoader
     *            the peer classloader, or null (ignored).
     * @param log
     *            the log node, or null to skip logging.
     */
    @Override
    public void delegateToPeer(final @Nullable ClassLoader classLoader, final @Nullable ClassGraphLog log) {
        delegateTo(classLoader, /* isParent = */ false, log);
    }

    /**
     * Recursively traverses {@code classLoader} using its selected handlers, appending it after its delegates if no
     * handler added it directly.
     *
     * @param classLoader
     *            the classloader, or null (ignored).
     * @param isParent
     *            true to record {@code classLoader} as a parent classloader.
     * @param log
     *            the log node, or null to skip logging.
     */
    private void delegateTo(final @Nullable ClassLoader classLoader, final boolean isParent,
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
            for (final ClassLoaderHandler handler : getClassLoaderHandlers(classLoader, log)) {
                try {
                    handler.findClassLoaderOrder(classLoader, this, log);
                } catch (final RuntimeException | LinkageError e) {
                    // One failing handler, for example one that meets a container version whose internals have
                    // changed, must not stop every other classloader from being read
                    if (log != null) {
                        log.log("ClassLoaderHandler " + handler.getClass().getName() + " failed on classloader "
                                + classLoader.getClass().getName() + ", so it is skipped", e);
                    }
                }
            }
            // A classloader that no handler placed, including one whose handler failed before placing it, would
            // never have its classpath read. Placing it after everything its handlers delegated to cannot move a
            // classloader that a handler did place.
            if (!added.contains(classLoader)) {
                if (log != null) {
                    log.log("No ClassLoaderHandler placed classloader " + classLoader.getClass().getName()
                            + " in the classloader order, so it is placed after the classloaders it delegates to");
                }
                add(classLoader, log);
            }
        }
    }
}
