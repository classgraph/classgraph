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
 * Works out where a running JVM loads its classes and resources from: the classpath, the module path, and the
 * locations that a container's custom classloaders load from.
 *
 * <p>
 * Start at {@link io.github.classgraph.classpath.ClasspathFinder}, which returns a
 * {@link io.github.classgraph.classpath.Classpath}:
 *
 * <pre>
 * try (Classpath classpath = new ClasspathFinder().find()) {
 *     for (ClasspathEntry entry : classpath) {
 *         System.out.println(entry.location());
 *     }
 * }
 * </pre>
 *
 * <p>
 * This package reports where classes and resources <i>would be</i> loaded from. Finding the classpath does not open
 * or verify the existence of anything it reports -- an entry may name a jar or directory that does not exist, and
 * nested jars are reported in the {@code outer.jar!/inner.jar} form rather than being extracted. To read what is at
 * an element, open it with {@link io.github.classgraph.classpath.ClasspathEntry#open}, which lists its contents; or
 * use <a href="https://github.com/classgraph/classgraph">ClassGraph</a> itself, which is built on this library, to
 * parse the classfiles it finds there.
 *
 * <p>
 * This package is {@link org.jspecify.annotations.NullMarked}: unless a type is annotated
 * {@link org.jspecify.annotations.Nullable}, it is never null. That applies in both directions -- a value returned
 * from this package is null only where the return type says so, and an argument passed to this package may be null
 * only where the parameter type says so.
 *
 * <p>
 * The {@code @NullMarked} contract is checked at compile time, and only for callers that run a null checker of
 * their own. Public methods in this package therefore also check their arguments at runtime, and throw
 * {@link java.lang.NullPointerException} if a null is passed for a parameter that is not annotated
 * {@link org.jspecify.annotations.Nullable}, so that the failure happens at the call that passed the null rather
 * than deeper in the library, or silently, as a "not found" result. Individual methods do not repeat this in their
 * own documentation.
 *
 * @author Luke Hutchison
 */
@NullMarked
package io.github.classgraph.classpath;

import org.jspecify.annotations.NullMarked;
