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
package io.github.classgraph.classpath;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * One element of the classpath: a directory or a jarfile that classes and resources are loaded from.
 *
 * @param location
 *            the location of the classpath element. This is an absolute path for a local directory or jarfile, with
 *            {@code '/'} as the separator on every platform. A jarfile nested inside another jarfile is written in
 *            the Java {@code outer.jar!/inner.jar} form. Anything that is not a local file, for example a classpath
 *            element served over HTTP by a container, is the URL or URI it was found as. The location is not
 *            checked, so it may name a directory or jarfile that does not exist.
 * @param classLoaderName
 *            the {@link Object#toString()} of the classloader this classpath element was obtained from, or null if
 *            it did not come from a classloader (for example, an entry from the {@code java.class.path} system
 *            property, or from an overridden classpath). Only the string is kept, so that finding the classpath
 *            does not keep a classloader alive.
 * @param packageRootPrefixes
 *            the directory prefixes that should be looked for within this classpath element and stripped if
 *            present, because a classloader of this type can place the root of the package hierarchy below them,
 *            for example {@code "BOOT-INF/classes/"} for a Spring Boot jar. These are the layouts that the
 *            classloader could have used, not the ones this classpath element actually uses, so a prefix is listed
 *            whether or not the element contains a directory with that name. The list is empty for a classloader
 *            whose classpath elements always have their classes at the root, and never contains the empty string.
 */
public record ClasspathEntry(String location, @Nullable String classLoaderName, List<String> packageRootPrefixes) {
    @Override
    public String toString() {
        return classLoaderName == null ? location : location + " [" + classLoaderName + "]";
    }
}
