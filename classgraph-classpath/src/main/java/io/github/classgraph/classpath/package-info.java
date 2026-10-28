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
 * Resolves the locations from which a running JVM loads classes and resources, including the classpath, the module
 * path, and custom classloaders.
 *
 * <p>
 * Configure and run {@link io.github.classgraph.classpath.ClasspathFinder} to obtain a
 * {@link io.github.classgraph.classpath.Classpath}:
 *
 * <pre>
 * try (Classpath classpath = new ClasspathFinder().enableSystemModules().enableNonSystemModules().enableClasspath()
 *         .find()) {
 *     for (ClasspathEntry entry : classpath) {
 *         System.out.println(entry.getLocation());
 *     }
 * }
 * </pre>
 *
 * <p>
 * At least one {@code enable*} method must be called on {@link io.github.classgraph.classpath.ClasspathFinder} to
 * specify what to search.
 *
 * <p>
 * This package resolves classpath entries and modules without scanning their contents. Jarfiles are opened only to
 * read classpath entries declared in their manifests and automatic lib directories, nonexistent local paths are
 * omitted, and nested jars are reported in {@code outer.jar!/inner.jar} form. Call
 * {@link io.github.classgraph.classpath.ClasspathEntry#open} to read the contents of a classpath entry.
 *
 * <p>
 * This package is {@link org.jspecify.annotations.NullMarked}: parameters and return values are non-null unless
 * annotated with {@link org.jspecify.annotations.Nullable}, and passing {@code null} for a non-null parameter
 * throws {@link java.lang.NullPointerException}.
 *
 * @author Luke Hutchison
 */
@NullMarked
package io.github.classgraph.classpath;

import org.jspecify.annotations.NullMarked;
