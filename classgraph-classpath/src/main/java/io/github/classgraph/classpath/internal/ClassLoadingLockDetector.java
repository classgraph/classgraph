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

import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * A class to detect whether the current thread is holding a lock that the classloader would also need in order to
 * load a class on another thread. If it is, loading a class on a worker thread can deadlock (#933), so the work has
 * to be done on this thread instead.
 */
public final class ClassLoadingLockDetector {
    /** The name the compiler gives a static initializer block. */
    private static final String STATIC_INITIALIZER = "<clinit>";

    /** The methods of {@link ClassLoader} that hold the loading lock of the class that is being loaded. */
    private static final Set<String> CLASS_LOADING_METHODS = Set.of("loadClass", "findClass", "defineClass");

    /**
     * Constructor.
     */
    private ClassLoadingLockDetector() {
        // Cannot be constructed
    }

    /**
     * Find the innermost stack frame of the current thread that is holding a lock that the classloader would also
     * need in order to load a class on another thread.
     *
     * <p>
     * There are two such frames. A static initializer holds the initialization lock of the class it is
     * initializing, which the JVM does not release until the initializer returns. A {@link ClassLoader} method that
     * is loading a class holds that classloader's loading lock for the name of the class it is loading, and most
     * classloaders take a lock of their own as well.
     *
     * @return the stack frame that is holding the lock, in the form
     *         {@code com.xyz.Example.<clinit>(Example.java:20)}, or null if the current thread is not holding a
     *         class loading lock.
     */
    public static @Nullable String findFrameHoldingClassLoadingLock() {
        try {
            return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                    .walk(stackFrames -> stackFrames.filter(ClassLoadingLockDetector::holdsClassLoadingLock)
                            .findFirst().map(StackWalker.StackFrame::toString).orElse(null));
        } catch (Exception | LinkageError e) {
            // The call stack could not be read. Report no lock: this check exists to avoid a deadlock that only
            // some classloaders cause, so failing to run it must not stop a scan that would have worked.
            return null;
        }
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
