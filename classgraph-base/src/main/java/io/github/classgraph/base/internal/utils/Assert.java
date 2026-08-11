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
package io.github.classgraph.base.internal.utils;

import org.jspecify.annotations.Nullable;

/** Assertions. */
public final class Assert {
    /** Class can't be constructed. */
    private Assert() {
        // Empty
    }

    /**
     * Throw {@link NullPointerException} if the argument is null.
     *
     * <p>
     * ClassGraph's public API is {@code @NullMarked}, but that is a compile-time contract, which only protects
     * callers that run a null checker of their own. Public API methods therefore check their arguments at runtime
     * too, so that a null fails immediately, at the call that passed it, rather than deeper in ClassGraph or
     * (worse) silently, as a "not found" result.
     *
     * @param obj
     *            the argument.
     * @param paramName
     *            the name of the parameter, for the exception message.
     * @throws NullPointerException
     *             if the argument is null.
     */
    public static void notNull(final @Nullable Object obj, final String paramName) {
        if (obj == null) {
            throw new NullPointerException(paramName + " must not be null");
        }
    }

    /**
     * Throw {@link NullPointerException} if a varargs array, or any of its elements, is null.
     *
     * @param array
     *            the varargs array.
     * @param paramName
     *            the name of the parameter, for the exception message.
     * @throws NullPointerException
     *             if the array or any of its elements is null.
     */
    public static void notNullElements(final @Nullable Object @Nullable [] array, final String paramName) {
        // Not a call to notNull(): a null checker cannot see that that would have thrown, so it would flag the
        // dereference of array below.
        if (array == null) {
            throw new NullPointerException(paramName + " must not be null");
        }
        for (int i = 0; i < array.length; i++) {
            if (array[i] == null) {
                throw new NullPointerException(paramName + "[" + i + "] must not be null");
            }
        }
    }

    /**
     * Throw {@link IllegalArgumentException} if the class is not an annotation.
     *
     * @param clazz
     *            the class.
     * @throws IllegalArgumentException
     *             if the class is not an annotation.
     */
    public static void isAnnotation(final Class<?> clazz) {
        if (!clazz.isAnnotation()) {
            throw new IllegalArgumentException(clazz + " is not an annotation");
        }
    }

    /**
     * Throw {@link IllegalArgumentException} if the class is not an interface.
     *
     * @param clazz
     *            the class.
     * @throws IllegalArgumentException
     *             if the class is not an interface.
     */
    public static void isInterface(final Class<?> clazz) {
        if (!clazz.isInterface()) {
            throw new IllegalArgumentException(clazz + " is not an interface");
        }
    }
}
