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
import java.util.List;

import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import org.jspecify.annotations.Nullable;

/** A class to read the classes in the current call stack. */
class CallStackReader {
    /** The reflection utils instance. */
    private final ReflectionUtils reflectionUtils;

    /**
     * Constructor.
     *
     * @param reflectionUtils
     *            the reflection utils instance.
     */
    public CallStackReader(final ReflectionUtils reflectionUtils) {
        this.reflectionUtils = reflectionUtils;
    }

    /**
     * Get the call stack via the {@link StackWalker} API.
     *
     * @return the call stack, or null if it could not be obtained.
     */
    private static Class<?> @Nullable [] getCallStackViaStackWalker() {
        try {
            return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                    .walk(stackFrames -> stackFrames.map(StackWalker.StackFrame::getDeclaringClass)
                            .toArray(Class<?>[]::new));
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the class context.
     *
     * @return The classes in the call stack.
     */
    Class<?>[] getClassContext() {
        Class<?>[] callStack = null;

        // Get the stack via StackWalker. Invoke with doPrivileged -- see:
        // http://mail.openjdk.java.net/pipermail/jigsaw-dev/2018-October/013974.html
        try {
            callStack = reflectionUtils.doPrivileged(CallStackReader::getCallStackViaStackWalker);
        } catch (final Throwable e) {
            // Fall through
        }

        // As a fallback, use getStackTrace() to try to get the call stack
        if (callStack == null || callStack.length == 0) {
            StackTraceElement[] stackTrace = null;
            try {
                stackTrace = Thread.currentThread().getStackTrace();
            } catch (final SecurityException e) {
                // Fall through
            }
            if (stackTrace == null || stackTrace.length == 0) {
                try {
                    // Try getting stacktrace by throwing an exception
                    throw new Exception();
                } catch (final Exception e) {
                    stackTrace = e.getStackTrace();
                }
            }
            final List<Class<?>> stackClassesList = new ArrayList<>();
            for (final StackTraceElement elt : stackTrace) {
                try {
                    stackClassesList.add(Class.forName(elt.getClassName()));
                } catch (final ClassNotFoundException | LinkageError ignored) {
                    // Ignored
                }
            }
            if (!stackClassesList.isEmpty()) {
                callStack = stackClassesList.toArray(new Class<?>[0]);
            }
        }

        // Last-ditch effort -- include just this class in the call stack
        if (callStack == null || callStack.length == 0) {
            callStack = new Class<?>[] { CallStackReader.class };
        }

        return callStack;
    }
}
