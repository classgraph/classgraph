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
 * A virtual filesystem providing unified read access to directories, jarfiles, and modules.
 *
 * <p>
 * Entry point: {@link io.github.classgraph.vfs.Vfs}, which opens a {@link io.github.classgraph.vfs.VfsRoot}
 * containing {@link io.github.classgraph.vfs.VfsEntry} instances:
 *
 * <pre>
 * try (Vfs vfs = new Vfs()) {
 *     for (VfsEntry entry : vfs.open("outer.jar!/lib/inner.jar").getEntries()) {
 *         System.out.println(entry.getPathFromRoot() + " (" + entry.getLength() + " bytes)");
 *     }
 * }
 * </pre>
 *
 * <p>
 * {@link io.github.classgraph.vfs.Vfs#open(String)} and its overloads accept a path string, {@link java.io.File},
 * {@link java.nio.file.Path}, {@link java.net.URI}, {@link java.net.URL}, {@link java.lang.module.ModuleReference},
 * {@link java.io.InputStream}, or byte array, returning a {@link io.github.classgraph.vfs.VfsRoot}. Each
 * {@link io.github.classgraph.vfs.VfsEntry} can be read as an {@link java.io.InputStream},
 * {@link java.nio.channels.ReadableByteChannel}, {@link java.nio.ByteBuffer}, byte array, or string. Stored nested
 * jarfiles (such as in Spring Boot executable jars) are read in place via a slice of the enclosing jarfile without
 * extracting to disk.
 *
 * <p>
 * {@link io.github.classgraph.vfs.VfsRoot#walk(io.github.classgraph.vfs.VfsVisitor)} traverses a root without
 * materializing a list of all entries, visiting each directory before its contents so that unwanted subdirectories
 * can be skipped.
 *
 * <p>
 * {@link io.github.classgraph.vfs.VfsRoot#asFileSystem()} exposes a root as a read-only
 * {@link java.nio.file.FileSystem}, and {@link io.github.classgraph.vfs.VfsEntry#asPath()} exposes an entry as a
 * {@link java.nio.file.Path}, allowing {@link java.nio.file.Files} APIs to read nested jarfiles, package roots,
 * modules, and in-memory jarfiles.
 *
 * <p>
 * A {@link io.github.classgraph.vfs.Vfs} instance owns all roots it opens and must remain open while its entries
 * are read. Closing the {@link io.github.classgraph.vfs.Vfs} closes all of its roots and releases their underlying
 * file handles, memory mappings, and temporary files. Individual {@link io.github.classgraph.vfs.VfsRoot} instances
 * also implement {@link java.lang.AutoCloseable} so their resources can be released early.
 *
 * <p>
 * This package is {@link org.jspecify.annotations.NullMarked}: parameters and return values are non-null unless
 * annotated with {@link org.jspecify.annotations.Nullable}, and passing {@code null} for a non-null parameter
 * throws {@link java.lang.NullPointerException}.
 *
 * @author Luke Hutchison
 */
@NullMarked
package io.github.classgraph.vfs;

import org.jspecify.annotations.NullMarked;
