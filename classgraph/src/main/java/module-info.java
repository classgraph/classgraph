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

import org.jspecify.annotations.NullMarked;

/**
 * <a href="https://github.com/classgraph/classgraph">ClassGraph</a>: the
 * uber-fast, ultra-lightweight classpath and module scanner for JVM languages.
 *
 * <p>
 * This module is {@link org.jspecify.annotations.NullMarked}: unless a type is
 * annotated {@link org.jspecify.annotations.Nullable}, it is never null.
 *
 * @author Luke Hutchison
 */
@NullMarked
module io.github.classgraph {
    exports io.github.classgraph;

    // N.B. make sure the "Import-Package" entries in the manifest (in pom.xml) match these "requires" statements.

    // Finding the classpath and the module path, and reading jarfiles, are the jobs of separate modules. They are
    // required transitively, so that a single dependency on this module is all a project needs. (The classpath
    // finder in turn requires the archive reader transitively, which requires the shared helper classes.)
    requires transitive io.github.classgraph.classpath;

    // JSpecify nullability annotations are only needed at compile time. This deliberately is not "requires
    // transitive", even though the annotations appear in exported signatures: that would force every modular
    // downstream project to put JSpecify on its own module path, which is what broke Log4j 2.24.0 for its users.
    // A downstream module that depends on JSpecify itself still sees the annotations.
    requires static org.jspecify;
}
