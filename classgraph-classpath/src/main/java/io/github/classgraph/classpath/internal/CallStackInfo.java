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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * What a classpath search needs to know about the thread that started it, read once, up front, and then passed to
 * everything that needs it.
 *
 * <p>
 * Three parts of a scan ask about the call stack: the classloader search, which searches the classloader of the
 * code that called ClassGraph, so that a caller loaded by a classloader that is in none of the usual places is
 * still searched; the module search, which searches the module layers that the caller can see; and the check for a
 * class loading lock, which decides whether the scan can safely load classes on a worker thread (#933). Reading the
 * stack is not free -- {@link StackWalker} materializes a frame per stack entry -- and a
 * {@link StackWalker.StackFrame} is valid only while the walk is running, so the stack is walked once, up front,
 * and what all three need is taken from that one walk.
 *
 * <p>
 * Only what they need is kept, rather than a frame or a class per stack entry: the classloaders and the module
 * layers of the classes in the stack, each deduplicated and in innermost-frame-first order, and whether any frame
 * is holding a class loading lock. The thread's context classloader is read here too, since it is a property of the
 * calling thread in the same way. (Anything else a later caller needs can be added here.)
 *
 * <p>
 * The read has to happen on the thread that called ClassGraph, since that is the thread whose caller, whose context
 * classloader and whose locks are being asked about, and it has to happen before any of the work is handed to
 * another thread.
 */
public final class CallStackInfo {
    /** The name the compiler gives a static initializer block. */
    private static final String STATIC_INITIALIZER = "<clinit>";

    /** The methods of {@link ClassLoader} that hold the loading lock of the class that is being loaded. */
    private static final Set<String> CLASS_LOADING_METHODS = Set.of("loadClass", "findClass", "defineClass");

    /** The context classloader of the thread that this info was read on, or null if it has none. */
    private final @Nullable ClassLoader contextClassLoader;

    /** The classloaders of the classes in the call stack, innermost frame first. */
    private final LinkedHashSet<ClassLoader> classLoaders;

    /** The module layers of the classes in the call stack, innermost frame first. */
    private final LinkedHashSet<ModuleLayer> moduleLayers;

    /** Whether any class in the call stack is in an unnamed module, i.e. was loaded from the classpath. */
    private final boolean anyClassIsInAnUnnamedModule;

    /** The innermost frame that is holding a class loading lock, or null if there is no such frame. */
    private final @Nullable String frameHoldingClassLoadingLock;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the context classloader of the thread that started the search.
     *
     * @return the context classloader, or null if the thread has none. This is the first classloader that a search
     *         tries, since a context classloader can be set as an override on a per-thread basis.
     */
    public @Nullable ClassLoader getContextClassLoader() {
        return contextClassLoader;
    }

    /**
     * Get the classloaders of the classes in the call stack.
     *
     * @return the classloaders, in innermost-frame-first order, as an unmodifiable set. A class loaded by the
     *         bootstrap classloader contributes no classloader, since {@link Class#getClassLoader()} returns null
     *         for one.
     */
    public Set<ClassLoader> getClassLoaders() {
        return Collections.unmodifiableSet(classLoaders);
    }

    /**
     * Get the module layers of the classes in the call stack.
     *
     * @return the module layers, in innermost-frame-first order, as an unmodifiable set. A class in an unnamed
     *         module contributes no layer, since {@link Module#getLayer()} returns null for one -- see
     *         {@link #anyClassIsInAnUnnamedModule()}.
     */
    public Set<ModuleLayer> getModuleLayers() {
        return Collections.unmodifiableSet(moduleLayers);
    }

    /**
     * Determine whether any class in the call stack is in an unnamed module.
     *
     * @return true if any class in the call stack is in an unnamed module, which means that the class was loaded
     *         from the classpath rather than from a module, so the classpath has to be searched in order to find
     *         classes like it.
     */
    public boolean anyClassIsInAnUnnamedModule() {
        return anyClassIsInAnUnnamedModule;
    }

    /**
     * Get the innermost stack frame that is holding a lock that the classloader would also need in order to load a
     * class on another thread.
     *
     * <p>
     * There are two such frames. A static initializer holds the initialization lock of the class it is
     * initializing, which the JVM does not release until the initializer returns. A {@link ClassLoader} method that
     * is loading a class holds that classloader's loading lock for the name of the class it is loading, and most
     * classloaders take a lock of their own as well.
     *
     * @return the stack frame that is holding the lock, in the form
     *         {@code com.xyz.Example.<clinit>(Example.java:20)}, or null if the thread that read this call stack
     *         was not holding a class loading lock. Only the innermost such frame is kept, and only so that the
     *         verbose log can say which frame stopped the scan from using worker threads.
     */
    public @Nullable String getFrameHoldingClassLoadingLock() {
        return frameHoldingClassLoadingLock;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param contextClassLoader
     *            the context classloader of the thread that this info was read on, or null if it has none
     * @param classLoaders
     *            the classloaders of the classes in the call stack, innermost frame first
     * @param moduleLayers
     *            the module layers of the classes in the call stack, innermost frame first
     * @param anyClassIsInAnUnnamedModule
     *            whether any class in the call stack is in an unnamed module
     * @param frameHoldingClassLoadingLock
     *            the innermost frame that is holding a class loading lock, or null if there is no such frame
     */
    private CallStackInfo(final @Nullable ClassLoader contextClassLoader,
            final LinkedHashSet<ClassLoader> classLoaders, final LinkedHashSet<ModuleLayer> moduleLayers,
            final boolean anyClassIsInAnUnnamedModule, final @Nullable String frameHoldingClassLoadingLock) {
        this.contextClassLoader = contextClassLoader;
        this.classLoaders = classLoaders;
        this.moduleLayers = moduleLayers;
        this.anyClassIsInAnUnnamedModule = anyClassIsInAnUnnamedModule;
        this.frameHoldingClassLoadingLock = frameHoldingClassLoadingLock;
    }

    /**
     * Read what is needed from the call stack of the current thread.
     *
     * @return the call stack info.
     */
    public static CallStackInfo read() {
        final var contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final var callStackInfo = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                    .walk(stackFrames -> read(contextClassLoader, stackFrames));
            if (!callStackInfo.classLoaders.isEmpty() || !callStackInfo.moduleLayers.isEmpty()) {
                return callStackInfo;
            }
        } catch (Exception | LinkageError e) {
            // Fall through
        }
        // The call stack could not be read -- fall back to naming only this class' own classloader and module
        // layer, and to reporting no class loading lock, since the check for one exists to avoid a deadlock that
        // only some classloaders cause, so failing to run it must not stop a scan that would have worked
        final LinkedHashSet<ClassLoader> classLoaders = new LinkedHashSet<>();
        final var classLoader = CallStackInfo.class.getClassLoader();
        if (classLoader != null) {
            classLoaders.add(classLoader);
        }
        final LinkedHashSet<ModuleLayer> moduleLayers = new LinkedHashSet<>();
        final var moduleLayer = CallStackInfo.class.getModule().getLayer();
        if (moduleLayer != null) {
            moduleLayers.add(moduleLayer);
        }
        return new CallStackInfo(contextClassLoader, classLoaders, moduleLayers,
                /* anyClassIsInAnUnnamedModule = */ moduleLayer == null, /* frameHoldingClassLoadingLock = */ null);
    }

    /**
     * Read everything that is needed from the frames of one {@link StackWalker} walk.
     *
     * @param contextClassLoader
     *            the context classloader of the thread that is being walked, or null if it has none
     * @param stackFrames
     *            the stack frames, innermost frame first
     * @return the call stack info.
     */
    private static CallStackInfo read(final @Nullable ClassLoader contextClassLoader,
            final Stream<StackWalker.StackFrame> stackFrames) {
        final LinkedHashSet<ClassLoader> classLoaders = new LinkedHashSet<>();
        final LinkedHashSet<ModuleLayer> moduleLayers = new LinkedHashSet<>();
        var anyClassIsInAnUnnamedModule = false;
        var frameHoldingClassLoadingLock = (String) null;
        // The frames are consumed in one pass, rather than with two stream operations, because a StackFrame is
        // valid only while the walk is running, so nothing that a later pass would need can be kept
        for (final var stackFrame : (Iterable<StackWalker.StackFrame>) stackFrames::iterator) {
            final var stackFrameClass = stackFrame.getDeclaringClass();
            final var classLoader = stackFrameClass.getClassLoader();
            if (classLoader != null) {
                classLoaders.add(classLoader);
            }
            final var moduleLayer = stackFrameClass.getModule().getLayer();
            if (moduleLayer != null) {
                moduleLayers.add(moduleLayer);
            } else {
                anyClassIsInAnUnnamedModule = true;
            }
            if (frameHoldingClassLoadingLock == null && holdsClassLoadingLock(stackFrame)) {
                // The innermost such frame is the one that is reported
                frameHoldingClassLoadingLock = stackFrame.toString();
            }
        }
        return new CallStackInfo(contextClassLoader, classLoaders, moduleLayers, anyClassIsInAnUnnamedModule,
                frameHoldingClassLoadingLock);
    }

    /**
     * Determine whether a stack frame is holding a lock that the classloader would also need in order to load a
     * class on another thread.
     *
     * @param stackFrame
     *            the stack frame.
     * @return true if the stack frame is holding a class loading lock.
     */
    private static boolean holdsClassLoadingLock(final StackWalker.StackFrame stackFrame) {
        final var methodName = stackFrame.getMethodName();
        return STATIC_INITIALIZER.equals(methodName) || (CLASS_LOADING_METHODS.contains(methodName)
                && ClassLoader.class.isAssignableFrom(stackFrame.getDeclaringClass()));
    }
}
