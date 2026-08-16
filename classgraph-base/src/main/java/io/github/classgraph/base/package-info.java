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

/**
 * The types that every ClassGraph library shares with the code that calls it.
 *
 * <p>
 * There are currently two: {@link io.github.classgraph.base.LogNode}, the verbose log that ClassGraph writes what
 * it is doing to, and {@link io.github.classgraph.base.ClassGraphLog}, the narrower view of that log which is
 * handed to code ClassGraph calls out to, such as a {@code io.github.classgraph.classpath.ClassLoaderHandler}.
 *
 * <p>
 * This package is {@link org.jspecify.annotations.NullMarked}: unless a type is annotated
 * {@link org.jspecify.annotations.Nullable}, it is never null.
 *
 * @author Luke Hutchison
 */
@NullMarked
package io.github.classgraph.base;

import org.jspecify.annotations.NullMarked;
