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
 * A virtual filesystem: reads directories, jarfiles and modules through one interface, however they are named.
 *
 * <p>
 * Start at {@link io.github.classgraph.vfs.Vfs}, which opens a {@link io.github.classgraph.vfs.VfsRoot} of
 * {@link io.github.classgraph.vfs.VfsEntry} instances:
 *
 * <pre>
 * try (Vfs vfs = new Vfs()) {
 *     for (VfsEntry entry : vfs.open("outer.jar!/lib/inner.jar").getEntries()) {
 *         System.out.println(entry.getName() + " (" + entry.getLength() + " bytes)");
 *     }
 * }
 * </pre>
 *
 * <p>
 * The same code reads a directory, a module, a jarfile downloaded from a URL, or a jarfile held in RAM, because
 * {@link io.github.classgraph.vfs.Vfs#open(String)} has an overload for every way that Java names a place to read
 * from -- a path string, a {@link java.io.File}, a {@link java.nio.file.Path} in any filesystem, a
 * {@link java.net.URI}, a {@link java.net.URL}, a {@link java.lang.module.ModuleReference}, an
 * {@link java.io.InputStream} or a byte array -- and all of them give back the same
 * {@link io.github.classgraph.vfs.VfsRoot} interface. Each entry can then be read as an
 * {@link java.io.InputStream}, a {@link java.nio.channels.ReadableByteChannel}, a {@link java.nio.ByteBuffer}, a
 * byte array or a string, whichever the caller wants, whatever the entry is stored in.
 *
 * <p>
 * {@link io.github.classgraph.vfs.VfsRoot#walk(io.github.classgraph.vfs.VfsVisitor)} enumerates a root without
 * building a list of every entry in it, offering each directory before the entries in it so that an unwanted
 * directory can be skipped -- and, for a directory tree, never listed at all.
 *
 * <p>
 * {@link io.github.classgraph.vfs.VfsRoot#asFileSystem()} presents a whole root as a read-only
 * {@link java.nio.file.FileSystem}, and {@link io.github.classgraph.vfs.VfsEntry#asPath()} presents a single entry
 * as a {@link java.nio.file.Path} within it, so that code written against {@link java.nio.file.Files} can read a
 * nested jarfile, a package root, a module, or a jarfile held in RAM -- none of which the zip filesystem provider
 * that ships with the JDK can open.
 *
 * <p>
 * The zipfile reader in this package is a good deal faster than {@link java.util.zip.ZipFile}, and unlike
 * {@link java.util.zip.ZipFile} it can read a jarfile that is nested inside another jarfile, as produced by Spring
 * Boot and other executable-jar formats. A nested jarfile is read in place through a slice of the enclosing
 * jarfile, rather than being extracted to a temporary directory first.
 *
 * <p>
 * A {@link io.github.classgraph.vfs.Vfs} owns the file handles, memory mappings and temporary files that back
 * everything it opened, so it must be closed, and it must stay open for as long as its entries are being read.
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
package io.github.classgraph.vfs;

import org.jspecify.annotations.NullMarked;
