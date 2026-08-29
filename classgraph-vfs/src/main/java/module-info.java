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
 * The virtual filesystem of
 * <a href="https://github.com/classgraph/classgraph">ClassGraph</a>: reads
 * directories, jarfiles and modules through one interface, however they are
 * named -- by path string, {@link java.io.File}, {@link java.nio.file.Path},
 * {@link java.net.URI}, {@link java.net.URL},
 * {@link java.lang.module.ModuleReference}, {@link java.io.InputStream} or byte
 * array -- including jarfiles nested inside other jarfiles to any depth, which
 * are read without being extracted to disk.
 *
 * <p>
 * This module is {@link org.jspecify.annotations.NullMarked}: unless a type is
 * annotated {@link org.jspecify.annotations.Nullable}, it is never null.
 *
 * @author Luke Hutchison
 */
@NullMarked
module io.github.classgraph.vfs {
    exports io.github.classgraph.vfs;

    // The internal packages are the internals of ClassGraph. They are only exported to the modules that are built on
    // top of this one, and they are not covered by the project's API compatibility guarantees.
    exports io.github.classgraph.vfs.internal.slice.reader to io.github.classgraph;

    // N.B. make sure the "Import-Package" entries in the manifest (in pom.xml) match these "requires" statements.

    // The shared helper classes. This is "requires transitive" because they appear in signatures that the modules
    // above this one use, e.g. the LogNode passed to the virtual filesystem
    requires transitive io.github.classgraph.base;

    // OffHeapMemory requires jdk.unsupported (for usage of Unsafe on JDK 17-21)
    requires jdk.unsupported;

    // JSpecify nullability annotations are only needed at compile time. This deliberately is not "requires
    // transitive", even though the annotations appear in exported signatures: that would force every modular
    // downstream project to put JSpecify on its own module path, which is what broke Log4j 2.24.0 for its users.
    // A downstream module that depends on JSpecify itself still sees the annotations.
    requires static org.jspecify;

    // Register the "cgvfs:" URL scheme, so that a "cgvfs:" URI can be opened through java.nio.file.FileSystems
    // without the caller having to install anything. The equivalent registration for the classpath is the file
    // src/main/resources/META-INF/services/java.nio.file.spi.FileSystemProvider -- change both together.
    provides java.nio.file.spi.FileSystemProvider with io.github.classgraph.vfs.VfsFileSystemProvider;
}
