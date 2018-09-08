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
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.classgraph.utils;

/** A class to find the unique ordered classpath elements. */
class CallStackReader {
    /** Used for resolving the call stack. Requires RuntimePermission("createSecurityManager"). */
    private static CallerResolver CALLER_RESOLVER;

    private static Throwable throwableOnCreate;

    static {
        try {
            // This can fail if the current SecurityManager does not allow RuntimePermission
            // ("createSecurityManager"):
            CALLER_RESOLVER = new CallerResolver();
        } catch (final Throwable e) {
            throwableOnCreate = e;
        }
    }

    /**
     * Using a SecurityManager gets around the fact that Oracle removed sun.reflect.Reflection.getCallerClass, see:
     * 
     * https://www.infoq.com/news/2013/07/Oracle-Removes-getCallerClass
     *
     * http://www.javaworld.com/article/2077344/core-java/find-a-way-out-of-the-classloader-maze.html
     */
    private static final class CallerResolver extends SecurityManager {
        @Override
        protected Class<?>[] getClassContext() {
            return super.getClassContext();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find all classes on the call stack.
     * 
     * @throws IllegalArgumentException
     *             if the call stack could not be read.
     * @return the classes on the call stack.
     */
    static Class<?>[] getClassContext() {
        if (CALLER_RESOLVER == null) {
            throw new IllegalArgumentException(CallStackReader.class.getSimpleName() + " could not create "
                    + CallerResolver.class.getSimpleName() + ", current SecurityManager does not grant "
                    + "RuntimePermission(\"createSecurityManager\")", throwableOnCreate);
        } else {
            final Class<?>[] callStack = CALLER_RESOLVER.getClassContext();
            if (callStack == null) {
                throw new IllegalArgumentException(CallStackReader.class.getSimpleName() + ": "
                        + CallerResolver.class.getSimpleName() + "#getClassContext() returned null");
            } else {
                return callStack;
            }
        }
    }
}
