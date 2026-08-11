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
 * The classes shared by the libraries of
 * <a href="https://github.com/classgraph/classgraph">ClassGraph</a>: path and
 * URL handling, logging, reflection, and accept/reject matching.
 *
 * <p>
 * This module has no public API of its own, and is not useful on its own. It
 * exports its packages only to the other ClassGraph modules.
 *
 * <p>
 * This module is {@link org.jspecify.annotations.NullMarked}: unless a type is
 * annotated {@link org.jspecify.annotations.Nullable}, it is never null.
 *
 * @author Luke Hutchison
 */
@NullMarked
module io.github.classgraph.base {
    // The internal packages are the internals of ClassGraph. They are only exported to the modules that are built on
    // top of this one, and they are not covered by the project's API compatibility guarantees.
    exports io.github.classgraph.base.internal.concurrency to io.github.classgraph, io.github.classgraph.classpath,
            io.github.classgraph.vfs;
    exports io.github.classgraph.base.internal.recycler to io.github.classgraph, io.github.classgraph.vfs;
    exports io.github.classgraph.base.internal.reflection to io.github.classgraph, io.github.classgraph.classpath,
            io.github.classgraph.vfs;
    exports io.github.classgraph.base.internal.utils to io.github.classgraph, io.github.classgraph.classpath,
            io.github.classgraph.vfs;

    // N.B. make sure the "Import-Package" entries in the manifest (in pom.xml) match these "requires" statements.

    // VersionFinder requires java.xml
    requires java.xml;
    // FileUtils requires jdk.unsupported (for usage of Unsafe)
    requires jdk.unsupported;
    // LogNode requires java.logging
    requires java.logging;

    // ReflectionUtils may use Narcissus, if it is available
    requires static io.github.toolfactory.narcissus;

    // JSpecify nullability annotations are only needed at compile time. This deliberately is not "requires
    // transitive", even though the annotations appear in exported signatures: that would force every modular
    // downstream project to put JSpecify on its own module path, which is what broke Log4j 2.24.0 for its users.
    // A downstream module that depends on JSpecify itself still sees the annotations.
    requires static org.jspecify;
}
