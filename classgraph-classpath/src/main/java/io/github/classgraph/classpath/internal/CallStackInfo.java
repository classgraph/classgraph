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
 * Captures call stack information from the calling thread: context classloader, classloaders and module layers of
 * classes on the call stack, and any frame holding a class loading lock.
 */
public final class CallStackInfo {
    /** Method name for a class static initializer block. */
    private static final String STATIC_INITIALIZER = "<clinit>";

    /** {@link ClassLoader} method names that hold a class loading lock. */
    private static final Set<String> CLASS_LOADING_METHODS = Set.of("loadClass", "findClass", "defineClass");

    /** The context classloader of the calling thread, or null if none. */
    private final @Nullable ClassLoader contextClassLoader;

    /** The classloaders of the classes in the call stack, innermost frame first, deduplicated by reference. */
    private final LinkedIdentitySet<ClassLoader> classLoaders;

    /** The module layers of the classes in the call stack, innermost frame first. */
    private final LinkedHashSet<ModuleLayer> moduleLayers;

    /** The innermost frame holding a class loading lock, or null if none. */
    private final @Nullable String frameHoldingClassLoadingLock;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the context classloader of the thread that started the search.
     *
     * @return the context classloader, or null if none.
     */
    public @Nullable ClassLoader getContextClassLoader() {
        return contextClassLoader;
    }

    /**
     * Returns the classloaders of the classes in the call stack, innermost frame first.
     *
     * @return the classloaders, as an unmodifiable set.
     */
    public Set<ClassLoader> getClassLoaders() {
        return Collections.unmodifiableSet(classLoaders);
    }

    /**
     * Returns the module layers of the classes in the call stack, innermost frame first.
     *
     * @return the module layers, as an unmodifiable set.
     */
    public Set<ModuleLayer> getModuleLayers() {
        return Collections.unmodifiableSet(moduleLayers);
    }

    /**
     * Returns the innermost stack frame holding a class initialization lock ({@code <clinit>}) or a
     * {@link ClassLoader} loading lock, or null if none.
     *
     * @return the stack frame string, or null if no frame holds a class loading lock.
     */
    public @Nullable String getFrameHoldingClassLoadingLock() {
        return frameHoldingClassLoadingLock;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param contextClassLoader
     *            the context classloader of the calling thread, or null if none.
     * @param classLoaders
     *            the classloaders of the classes in the call stack, innermost frame first.
     * @param moduleLayers
     *            the module layers of the classes in the call stack, innermost frame first.
     * @param frameHoldingClassLoadingLock
     *            the innermost frame holding a class loading lock, or null if none.
     */
    private CallStackInfo(final @Nullable ClassLoader contextClassLoader,
            final LinkedIdentitySet<ClassLoader> classLoaders, final LinkedHashSet<ModuleLayer> moduleLayers,
            final @Nullable String frameHoldingClassLoadingLock) {
        this.contextClassLoader = contextClassLoader;
        this.classLoaders = classLoaders;
        this.moduleLayers = moduleLayers;
        this.frameHoldingClassLoadingLock = frameHoldingClassLoadingLock;
    }

    /**
     * Reads call stack information from the current thread.
     *
     * @return the {@link CallStackInfo} for the current thread.
     */
    public static CallStackInfo read() {
        final var contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            // The walk includes the frames of this class, so this class' own classloader and module layer are
            // always in the result. When they are both absent, because ClassGraph was loaded by the bootstrap
            // classloader from the boot classpath, the walk still finds any frame that is holding a class loading
            // lock, so the result of a walk that succeeds is always returned.
            return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                    .walk(stackFrames -> read(contextClassLoader, stackFrames));
        } catch (Exception | LinkageError e) {
            // Fall through
        }
        // The walk threw, e.g. because a SecurityManager denied it, so the call stack could not be read -- fall
        // back to naming only this class' own classloader and module layer, and to reporting no class loading lock,
        // since the check for one exists to avoid a deadlock that only some classloaders cause, so failing to run
        // it must not stop a scan that would have worked
        final LinkedIdentitySet<ClassLoader> classLoaders = new LinkedIdentitySet<>();
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
                /* frameHoldingClassLoadingLock = */ null);
    }

    /**
     * Populates a {@link CallStackInfo} from a stream of {@link StackWalker.StackFrame} instances.
     *
     * @param contextClassLoader
     *            the context classloader of the thread being walked, or null if none.
     * @param stackFrames
     *            the stack frames, innermost frame first.
     * @return the {@link CallStackInfo}.
     */
    private static CallStackInfo read(final @Nullable ClassLoader contextClassLoader,
            final Stream<StackWalker.StackFrame> stackFrames) {
        final LinkedIdentitySet<ClassLoader> classLoaders = new LinkedIdentitySet<>();
        final LinkedHashSet<ModuleLayer> moduleLayers = new LinkedHashSet<>();
        String frameHoldingClassLoadingLock = null;
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
            }
            if (frameHoldingClassLoadingLock == null && holdsClassLoadingLock(stackFrame)) {
                // The innermost such frame is the one that is reported
                frameHoldingClassLoadingLock = stackFrame.toString();
            }
        }
        return new CallStackInfo(contextClassLoader, classLoaders, moduleLayers, frameHoldingClassLoadingLock);
    }

    /**
     * Checks whether {@code stackFrame} is a class static initializer or a {@link ClassLoader} class-loading
     * method.
     *
     * @param stackFrame
     *            the stack frame.
     * @return true if the stack frame holds a class loading lock.
     */
    private static boolean holdsClassLoadingLock(final StackWalker.StackFrame stackFrame) {
        final var methodName = stackFrame.getMethodName();
        return STATIC_INITIALIZER.equals(methodName) || (CLASS_LOADING_METHODS.contains(methodName)
                && ClassLoader.class.isAssignableFrom(stackFrame.getDeclaringClass()));
    }
}
