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
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * The call stack of the thread that started a classpath search, read once and then passed to everything that needs
 * it.
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
 * The walk has to happen on the thread that called ClassGraph, since that is the thread whose caller and whose
 * locks are being asked about, and it has to happen before any of the work is handed to another thread.
 */
public final class CallStack {
    /** The name the compiler gives a static initializer block. */
    private static final String STATIC_INITIALIZER = "<clinit>";

    /** The methods of {@link ClassLoader} that hold the loading lock of the class that is being loaded. */
    private static final Set<String> CLASS_LOADING_METHODS = Set.of("loadClass", "findClass", "defineClass");

    /** The classes in the call stack, innermost frame first. */
    private final Class<?>[] classContext;

    /** The innermost frame that is holding a class loading lock, or null if there is no such frame. */
    private final @Nullable String frameHoldingClassLoadingLock;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the classes in the call stack.
     *
     * @return The classes in the call stack, innermost frame first.
     */
    public Class<?>[] getClassContext() {
        return classContext;
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
     *         was not holding a class loading lock.
     */
    public @Nullable String getFrameHoldingClassLoadingLock() {
        return frameHoldingClassLoadingLock;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param classContext
     *            the classes in the call stack, innermost frame first
     * @param frameHoldingClassLoadingLock
     *            the innermost frame that is holding a class loading lock, or null if there is no such frame
     */
    private CallStack(final Class<?>[] classContext, final @Nullable String frameHoldingClassLoadingLock) {
        this.classContext = classContext;
        this.frameHoldingClassLoadingLock = frameHoldingClassLoadingLock;
    }

    /**
     * Read the call stack of the current thread.
     *
     * @return the call stack.
     */
    public static CallStack read() {
        try {
            final var callStack = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                    .walk(CallStack::read);
            if (callStack.classContext.length > 0) {
                return callStack;
            }
        } catch (Exception | LinkageError e) {
            // Fall through
        }
        // The call stack could not be read -- fall back to naming just this class, and to reporting no class
        // loading lock, since the check for one exists to avoid a deadlock that only some classloaders cause, so
        // failing to run it must not stop a scan that would have worked
        return new CallStack(new Class<?>[] { CallStack.class }, /* frameHoldingClassLoadingLock = */ null);
    }

    /**
     * Read everything that is needed from the frames of one {@link StackWalker} walk.
     *
     * @param stackFrames
     *            the stack frames, innermost frame first
     * @return the call stack.
     */
    private static CallStack read(final Stream<StackWalker.StackFrame> stackFrames) {
        final List<Class<?>> classContext = new ArrayList<>();
        var frameHoldingClassLoadingLock = (String) null;
        // The frames are consumed in one pass, rather than with two stream operations, because a StackFrame is
        // valid only while the walk is running, so nothing that a later pass would need can be kept
        for (final var stackFrame : (Iterable<StackWalker.StackFrame>) stackFrames::iterator) {
            classContext.add(stackFrame.getDeclaringClass());
            if (frameHoldingClassLoadingLock == null && holdsClassLoadingLock(stackFrame)) {
                // The innermost such frame is the one that is reported
                frameHoldingClassLoadingLock = stackFrame.toString();
            }
        }
        // The array element type is given explicitly on toArray(), because otherwise it is inferred from the list
        // element type, which captures the wildcard of Class<?> -- and an array constructor reference cannot
        // produce an array of a captured type
        return new CallStack(classContext.toArray(new Class<?>[0]), frameHoldingClassLoadingLock);
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
