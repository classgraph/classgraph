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
 * The classpath and module path finder of
 * <a href="https://github.com/classgraph/classgraph">ClassGraph</a>: works out
 * where a running JVM loads its classes and resources from, including from the
 * custom classloaders of application servers, build tools and frameworks.
 *
 * <p>
 * This module is {@link org.jspecify.annotations.NullMarked}: unless a type is
 * annotated {@link org.jspecify.annotations.Nullable}, it is never null.
 *
 * @author Luke Hutchison
 */
@NullMarked
module io.github.classgraph.classpath {
    exports io.github.classgraph.classpath;

    // The internal packages are the internals of ClassGraph. They are only exported to the modules that are built on
    // top of this one, and they are not covered by the project's API compatibility guarantees.
    exports io.github.classgraph.classpath.internal.classloaderhandler to io.github.classgraph;
    exports io.github.classgraph.classpath.internal to io.github.classgraph;

    // N.B. make sure the "Import-Package" entries in the manifest (in pom.xml) match these "requires" statements.

    // The virtual filesystem, used to read the Class-Path: manifest entry of each jarfile found on the
    // classpath, so that the classpath can be expanded with the jarfiles it references. This is "requires
    // transitive" because the shared helper classes and the virtual filesystem both appear in signatures that the
    // module above this one uses
    requires transitive io.github.classgraph.vfs;
    // ModulePathInfo requires java.management
    requires java.management;

    // JSpecify nullability annotations are only needed at compile time. This deliberately is not "requires
    // transitive", even though the annotations appear in exported signatures: that would force every modular
    // downstream project to put JSpecify on its own module path, which is what broke Log4j 2.24.0 for its users.
    // A downstream module that depends on JSpecify itself still sees the annotations.
    requires static org.jspecify;
}
